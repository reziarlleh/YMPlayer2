@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
package dev.petrov.ymplayer2

import android.net.Uri
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.petrov.ymplayer2.core.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

/** Real ExoPlayer and complete local fixture audio; no Yandex account or network. */
@RunWith(AndroidJUnit4::class)
class WaveQueuePrefetchTest {
    @get:Rule val compose = createAndroidComposeRule<OnlineTestActivity>()
    private val h get() = compose.activity.harness
    private val player get() = h.player
    private fun waitFor(condition: () -> Boolean) {
        try { compose.waitUntil(25_000, condition) }
        catch (e: Throwable) { throw AssertionError("state=${player.state.value}; buffered=${player.bufferedWaveAudioIds()}; requests=${h.waveRequests}; streams=${h.resolvedQualities}", e) }
    }
    @Before fun prepare() {
        waitFor { h.library.state.value.ready && player.state.value.connected }
        compose.runOnIdle { player.stop(); player.switchProfile("owner"); player.clearQueue() }
        runBlocking { h.library.state.value.roots.forEach { h.library.forgetFolder(it.uri) } }
        compose.activity.contentResolver.call(Uri.parse("content://dev.petrov.ymplayer2.test.control"), "fixtures", null, null)
        runBlocking { h.library.addFolder(TestMusicProvider.tree.toString(), Source.LOCAL) }
        waitFor { h.library.testTracks.size == 2 && h.taste.state.value.shelf(TasteKind.ARTIST).ready }
    }
    @After fun stop() { compose.runOnIdle { player.stop() } }

    private fun fullWindow() = player.state.value.queue.size == 10 && !player.state.value.waveLoading &&
        player.bufferedWaveAudioIds() == player.state.value.queue.mapTo(hashSetOf(), Track::id)

    @Test fun allTenEntriesContainRealAudioAndAdvancingReplenishesWithoutGrowingStorage() {
        compose.runOnIdle { player.playMyWave() }
        waitFor { player.state.value.playing && fullWindow() }
        assertEquals((1..10).map { "yandex:$it:7" }, player.state.value.queue.map(Track::id))
        val original = player.state.value.queue.associate { track ->
            val uri = Uri.parse(player.resolveStream("owner", track.id))
            assertEquals("A URL or ID is not a complete audio cache", "file", uri.scheme)
            val file = File(uri.path!!)
            val source = h.library.testTracks[(track.id.removePrefix("yandex:").substringBefore(':').toInt() - 1) % 2]
            val expected = compose.activity.contentResolver.openInputStream(Uri.parse(source.uri))!!.use { it.readBytes() }
            assertArrayEquals("The whole fixture audio, not a preview, must be cached", expected, file.readBytes())
            track.id to file
        }
        assertTrue(h.tasteWrites.isEmpty())
        assertTrue(h.offline.state.value.tracks.isEmpty())
        for (id in 2..7) {
            compose.runOnIdle { player.skip(1) }
            waitFor { player.state.value.current?.id == "yandex:$id:7" && player.state.value.playing && fullWindow() }
            assertTrue(player.state.value.queue.size <= WaveQueueWindow.MAX_ITEMS)
        }
        assertFalse(original.getValue("yandex:1:7").exists())
        val root = original.values.first().parentFile!!
        assertEquals(10, root.listFiles()!!.count { it.extension == "audio" })
        assertEquals(0, root.listFiles()!!.count { it.extension == "part" })
        compose.runOnIdle { player.stop() }
        waitFor { player.bufferedWaveAudioIds().isEmpty() }
        assertTrue(root.listFiles()!!.isEmpty())
    }

    @Test fun slowTailCannotDelayStartOrPreparedNextAndEarlyNextWaitsForOneExistingDownload() {
        val gate = CompletableDeferred<Unit>()
        compose.runOnIdle { h.streamGates = mapOf("yandex:3:7" to gate); player.playMyWave() }
        waitFor { player.state.value.current?.id == "yandex:1:7" && player.state.value.positionSeconds >= 1 &&
            h.resolved.any { it.second == "yandex:3:7" } }
        assertEquals(setOf("yandex:1:7", "yandex:2:7"), player.bufferedWaveAudioIds())
        assertFalse(gate.isCompleted)
        compose.runOnIdle { player.skip(1) }
        waitFor { player.state.value.current?.id == "yandex:2:7" && player.state.value.positionSeconds >= 1 }
        compose.runOnIdle { player.skip(1) }
        assertEquals("yandex:2:7", player.state.value.current?.id)
        compose.runOnIdle { gate.complete(Unit) }
        waitFor { player.state.value.current?.id == "yandex:3:7" && player.state.value.positionSeconds >= 1 }
        assertEquals(1, h.resolved.count { it.second == "yandex:3:7" })
        assertTrue(player.state.value.wave)
    }

    @Test fun sourceChangeCancelsTailAndCannotPublishItsLateFile() {
        val gate = CompletableDeferred<Unit>()
        compose.runOnIdle { h.streamGates = mapOf("yandex:3:7" to gate); player.playMyWave() }
        waitFor { player.state.value.playing && h.resolved.any { it.second == "yandex:3:7" } }
        val cached = File(Uri.parse(player.resolveStream("owner", "yandex:2:7")).path!!)
        compose.runOnIdle { player.playQueue(listOf(h.library.testTracks.first().id)); gate.complete(Unit) }
        waitFor { !player.state.value.wave && player.state.value.playing && player.state.value.positionSeconds >= 1 }
        assertTrue(player.bufferedWaveAudioIds().isEmpty())
        assertFalse(cached.exists())
        assertTrue(cached.parentFile!!.listFiles()!!.isEmpty())
    }

    @Test fun rapidNextRetainsPreviousAudioDownloadAndPreviousUsesItsCompleteFile() {
        val gate = CompletableDeferred<Unit>()
        compose.runOnIdle { h.streamGates = mapOf("yandex:1:7" to gate); player.playMyWave() }
        waitFor { "yandex:2:7" in player.bufferedWaveAudioIds() }
        compose.runOnIdle { player.skip(1) }
        waitFor { player.state.value.current?.id == "yandex:2:7" && player.state.value.positionSeconds >= 1 }
        compose.runOnIdle { gate.complete(Unit) }
        waitFor { fullWindow() }
        val previous = File(Uri.parse(player.resolveStream("owner", "yandex:1:7")).path!!)
        assertTrue(previous.isFile)
        val requests = h.resolved.count { it.second == "yandex:1:7" }
        compose.runOnIdle { player.skip(-1) }
        waitFor { player.state.value.current?.id == "yandex:1:7" && player.state.value.positionSeconds >= 1 }
        assertEquals("Previous must use its retained complete audio", requests, h.resolved.count { it.second == "yandex:1:7" })
        assertTrue(previous.isFile)
    }

    @Test fun changedQualityDiscardsInflightTailButKeepsAlreadyPreparedAudio() {
        val gate = CompletableDeferred<Unit>()
        compose.runOnIdle {
            h.audioQuality.setStream(AudioQuality.STANDARD)
            h.streamGates = mapOf("yandex:3:7" to gate)
            player.playMyWave()
        }
        waitFor { player.state.value.playing && h.resolvedQualities.any { it.first == "yandex:3:7" } }
        compose.runOnIdle { h.audioQuality.setStream(AudioQuality.HIGH); gate.complete(Unit) }
        waitFor { fullWindow() }
        assertEquals(listOf(AudioQuality.STANDARD, AudioQuality.HIGH), h.resolvedQualities.filter { it.first == "yandex:3:7" }.map { it.second })
        assertEquals(listOf(AudioQuality.STANDARD), h.resolvedQualities.filter { it.first == "yandex:2:7" }.map { it.second })
        assertTrue(h.resolvedQualities.filter { it.first.removePrefix("yandex:").substringBefore(':').toInt() >= 4 }.all { it.second == AudioQuality.HIGH })
    }

    @Test fun ordinaryLongPlaylistRetainsItsTracksWithoutDownloadingTheWholeList() {
        compose.runOnIdle { h.onlineTrackCount = 20; h.online.search("long list", MusicKind.TRACKS, false) }
        waitFor { h.online.state.value.loaded && !h.online.state.value.loading }
        compose.runOnIdle { h.online.more() }
        waitFor { h.online.state.value.entries.size == 20 && !h.online.state.value.loading }
        val ids = h.online.state.value.entries.map { it.id }
        compose.runOnIdle { player.playList(ids, origin = PlaybackOrigin(PlaybackSource.LIST, "Twenty tracks")) }
        waitFor { player.state.value.playing && player.state.value.positionSeconds >= 2 }
        assertFalse(player.state.value.wave)
        assertEquals(20, player.state.value.queueCount)
        assertEquals(ids, runBlocking { player.queuePage(0, 50).items.map(Track::id) })
        assertTrue(player.bufferedWaveAudioIds().isEmpty())
        assertTrue("Ordinary lists must not resolve and download every track", h.resolved.map { it.second }.distinct().size < 20)
        assertTrue(h.offline.state.value.tracks.isEmpty())
    }

    @Test fun restartRetainsAllTenCandidatesAndRebuildsTheirTemporaryFiles() {
        compose.runOnIdle { player.playMyWave() }
        waitFor { fullWindow() && player.state.value.positionSeconds >= 1 }
        val ids = player.state.value.queue.map(Track::id)
        val requests = h.waveRequests.toList()
        compose.runOnIdle { player.sessionPause(); h.restartEngine() }
        waitFor { player.state.value.connected && player.state.value.current?.id == ids.first() }
        assertFalse(player.state.value.playing)
        assertEquals(ids, player.state.value.queue.map(Track::id))
        assertTrue(player.bufferedWaveAudioIds().isEmpty())
        compose.runOnIdle { player.sessionPlay() }
        waitFor { fullWindow() && player.state.value.playing }
        assertEquals(ids, player.state.value.queue.map(Track::id))
        assertEquals("Restored queued candidates must not be skipped as already heard", requests, h.waveRequests.toList())
    }

    @Test fun restartingWaveDoesNotTreatItsUnplayedCachedTailAsHeard() {
        compose.runOnIdle { player.playMyWave() }
        waitFor { fullWindow() && player.state.value.current?.id == "yandex:1:7" && player.state.value.positionSeconds >= 1 }
        compose.runOnIdle { player.saveForExit() }
        val saved = org.json.JSONArray(h.context.getSharedPreferences("playback", android.content.Context.MODE_PRIVATE)
            .getString("myWaveSeen:owner:1", "[]"))
        val heard = (0 until saved.length()).map { saved.getString(it) }.toSet()
        assertTrue("1" in heard)
        assertTrue("Downloading the next nine songs must not mark them as heard", (2..10).none { it.toString() in heard })
        compose.runOnIdle { player.playMyWave() }
        waitFor { fullWindow() && player.state.value.current?.id == "yandex:2:7" && player.state.value.playing }
        assertTrue(player.state.value.queue.any { it.id == "yandex:3:7" })
    }
}
