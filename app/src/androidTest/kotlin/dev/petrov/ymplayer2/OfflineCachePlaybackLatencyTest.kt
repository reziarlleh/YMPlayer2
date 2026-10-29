@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
package dev.petrov.ymplayer2

import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.petrov.ymplayer2.core.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

/** A real slow SAF stream, real WAV decoder and closed online gates; no timing-only fake player. */
@RunWith(AndroidJUnit4::class)
class OfflineCachePlaybackLatencyTest {
    @get:Rule val compose = createAndroidComposeRule<OnlineTestActivity>()
    private val h get() = compose.activity.harness
    private val player get() = h.player
    private val owner = OfflineOwner("owner", "1")
    private val resolver get() = compose.activity.contentResolver
    private val onlineGate = CompletableDeferred<Unit>()
    private val audits = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var requestsBeforePlayback = 0
    private fun control(method: String, argument: String? = null): Bundle = resolver.call(
        Uri.parse("content://dev.petrov.ymplayer2.test.control"), method, argument, null) ?: Bundle.EMPTY
    private fun stats() = control("storage-audio-stats")
    private fun waitFor(timeout: Long = 30_000, condition: () -> Boolean) = compose.waitUntil(timeout, condition)

    @Before fun prepare() {
        waitFor { h.library.state.value.ready && !h.library.state.value.scanning && player.state.value.connected }
        control("storage-reset")
        compose.runOnIdle {
            player.stop(); player.switchProfile("owner"); player.clearQueue(); h.offline.setEnabled(true)
            // Reset only fixture source slots; no production cache-clear semantics are assumed.
            h.context.getSharedPreferences("playback", 0).edit().apply {
                for (key in listOf("owner:1", "road:2")) { remove("offlineState:$key"); remove("offlineOrder:$key") }
            }.commit()
            h.cacheLocation.move(null)
        }
        waitFor { !h.cacheLocation.state.value.moving && h.offline.state.value.owner == owner && h.offline.state.value.ready }
        compose.runOnIdle { h.offline.clear() }
        waitFor { h.offline.state.value.ready && h.offline.state.value.tracks.isEmpty() }
        runBlocking { h.library.state.value.roots.forEach { h.library.forgetFolder(it.uri) } }
        control("fixtures")
        runBlocking { h.library.addFolder(TestMusicProvider.tree.toString(), Source.LOCAL) }
        waitFor { h.library.testTracks.size == 2 }
        compose.runOnIdle { h.tasteLists["owner" to TasteKind.TRACK] = TasteList(setOf("1", "2")); h.taste.refresh(TasteKind.TRACK) }
        waitFor { h.taste.state.value.shelf(TasteKind.TRACK).list.liked == setOf("1", "2") && !h.taste.state.value.shelf(TasteKind.TRACK).busy }
        compose.runOnIdle { h.offline.sync() }
        waitFor { !h.offline.state.value.running && h.offline.state.value.tracks.size == 2 }
        compose.runOnIdle { h.cacheLocation.move(TestStorageProvider.tree.toString()) }
        waitFor { !h.cacheLocation.state.value.moving && h.cacheLocation.state.value.external && h.offline.state.value.ready }
        assertEquals(2, h.offline.state.value.tracks.size)
        // Establish a real integrity proof before simulating a slow, unchanged storage device.
        // A changed file or checksum is deliberately tested separately below.
        runBlocking { h.offline.state.value.tracks.forEach { assertNotNull(h.offlineStore.audio(owner, it.id)) } }
        requestsBeforePlayback = h.resolved.size
        compose.runOnIdle {
            h.internet.available.value = false; h.offlineNetwork = false
            h.streamFailure = MusicFailure.NETWORK; h.failure = MusicFailure.NETWORK
            h.streamGates = mapOf("yandex:1:7" to onlineGate, "yandex:2:7" to onlineGate)
        }
    }

    @After fun cleanup() {
        control("storage-audio-tail", "open")
        onlineGate.complete(Unit); audits.cancel()
        compose.runOnIdle {
            player.stop(); h.offline.cancel(); h.streamGates = emptyMap()
            h.streamFailure = null; h.failure = null; h.offlineNetwork = true; h.internet.available.value = true
        }
        waitFor { stats().getInt("activePipes") == 0 }
        compose.runOnIdle { h.cacheLocation.move(null) }
        waitFor { !h.cacheLocation.state.value.moving }
    }

    @Test fun readyUnchangedCacheStartsBeforeSlowStorageReachesEndOfFile() {
        control("storage-audio-tail", "closed")
        compose.runOnIdle { player.playOffline() }
        assertPlayingBeforeTail("yandex:1:7")
    }

    @Test fun nextCachedTrackStartsBeforeItsSlowStorageTailIsAvailable() {
        compose.runOnIdle { player.playOffline() }
        waitFor { player.state.value.playing && player.state.value.current?.id == "yandex:1:7" }
        // Discard the outgoing decoder's buffered next period, preserving the real metadata queue.
        compose.runOnIdle { h.systemPlayer.pause(); player.saveForExit(); h.restartEngine() }
        waitFor { player.state.value.connected && player.state.value.current?.id == "yandex:1:7" && !player.state.value.playing }
        control("storage-audio-tail", "closed")
        compose.runOnIdle { player.toggle(); player.skip(1) }
        assertPlayingBeforeTail("yandex:2:7")
    }

    @Test fun anUnrelatedIntegrityAuditCannotBlockAReadySelectedTrack() {
        val unrelated = h.offline.state.value.tracks.single { it.id == "yandex:2:7" }
        val audio = Uri.parse(unrelated.uri)
        val checksum = DocumentsContract.buildDocumentUriUsingTree(audio, DocumentsContract.getDocumentId(audio) + ".sha256")
        // Invalidate only the other file's proof. Its whole-file check must still reject corruption.
        resolver.openOutputStream(checksum, "wt")!!.use { it.write("0".repeat(64).toByteArray()) }
        control("storage-audio-tail", "closed")
        val audit = audits.async { h.offlineStore.audio(owner, unrelated.id) }
        waitFor(5_000) { stats().getInt("tailWaiters") > 0 }
        compose.runOnIdle { player.playOffline() }
        assertPlayingBeforeTail("yandex:1:7")
        control("storage-audio-tail", "open")
        assertNull("Changed checksum must invalidate the proof and reject the other file", runBlocking { withTimeout(10_000) { audit.await() } })
    }

    private fun assertPlayingBeforeTail(id: String) {
        try {
            waitFor(6_000) {
                var decoded = false
                InstrumentationRegistry.getInstrumentation().runOnMainSync { decoded = h.systemPlayer.isPlaying }
                player.state.value.current?.id == id && decoded && !player.state.value.buffering &&
                    player.state.value.positionSeconds >= 1 && stats().getInt("tailWaiters") > 0
            }
        } catch (failure: Throwable) {
            throw AssertionError("Ready cached audio must start while EOF is gated: current=${player.state.value.current?.id}, " +
                "playing=${player.state.value.playing}, buffering=${player.state.value.buffering}, position=${player.state.value.positionSeconds}, " +
                "error=${player.state.value.error}, opens=${stats().getInt("opens")}, " +
                "tailWaiters=${stats().getInt("tailWaiters")}, onlineRequests=${h.resolved.size - requestsBeforePlayback}", failure)
        }
        assertEquals(PlaybackSource.OFFLINE, player.state.value.origin.source)
        assertEquals("Cached Play/Next must not attempt an online resolver", requestsBeforePlayback, h.resolved.size)
        assertFalse("The online stream gate remains closed", onlineGate.isCompleted)
    }
}
