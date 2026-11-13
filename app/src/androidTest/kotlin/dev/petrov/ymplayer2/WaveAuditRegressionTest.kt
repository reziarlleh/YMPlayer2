@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
package dev.petrov.ymplayer2

import android.net.Uri
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.petrov.ymplayer2.core.*
import dev.petrov.ymplayer2.playback.LaunchStateStore
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

/** Real Media3, account-owned fixture files and fault boundaries from WA-01..WA-10. */
@RunWith(AndroidJUnit4::class)
class WaveAuditRegressionTest {
    @get:Rule val compose = createAndroidComposeRule<OnlineTestActivity>()
    private val h get() = compose.activity.harness
    private val player get() = h.player
    private val raw get() = h.javaClass.getDeclaredField("engine").apply { isAccessible = true }.get(h) as ExoPlayer
    private fun waitFor(label: String, condition: () -> Boolean) {
        try { compose.waitUntil(25_000, condition) }
        catch (e: Throwable) { throw AssertionError("$label; state=${player.state.value}; requests=${h.waveRequests}; buffered=${player.bufferedWaveAudioIds()}", e) }
    }
    @Before fun prepare() {
        waitFor("Fixture") { h.library.state.value.ready && player.state.value.connected && h.online.catalog.value.enabled }
        compose.runOnIdle { player.stop(); player.switchProfile("owner"); player.clearQueue() }
        runBlocking { h.library.state.value.roots.forEach { h.library.forgetFolder(it.uri) } }
        compose.activity.contentResolver.call(Uri.parse("content://dev.petrov.ymplayer2.test.control"), "fixtures", null, null)
        runBlocking { h.library.addFolder(TestMusicProvider.tree.toString(), Source.LOCAL) }
        waitFor("Audio and taste") { h.library.testTracks.size == 2 && h.taste.state.value.shelf(TasteKind.ARTIST).ready }
    }
    @After fun stop() { compose.runOnIdle { player.stop() } }
    private fun full() = player.state.value.queue.size == 10 && !player.state.value.waveLoading && player.bufferedWaveAudioIds().size == 10
    private fun startFull() {
        compose.runOnIdle { player.playMyWave() }
        waitFor("Ten completed files") { full() && player.state.value.playing }
    }
    private fun file(id: String) = File(Uri.parse(player.resolveStream("owner", id)).path!!)

    @Test fun delayedConnectionRetryCannotOverrideNewPause() = lateRetry(false)
    @Test fun delayedConnectionRetryCannotOverrideNewStop() = lateRetry(true)
    private fun lateRetry(stop: Boolean) {
        val next = CompletableDeferred<Unit>()
        compose.runOnIdle { h.waveRequestGates = mapOf(2 to next); player.playMyWave() }
        waitFor("Playing A before tail failure") { player.state.value.playing && 2 in h.waveRequests }
        compose.runOnIdle { h.waveFailure = MusicFailure.NETWORK; next.complete(Unit) }
        waitFor("Tail issue") { player.state.value.waveIssue != null && !player.state.value.waveLoading }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        var check: InternetCheck? = null
        var callbacks = 0
        try {
            compose.runOnIdle {
                h.internet.available.value = false
                check = InternetCheck(h.internet, scope, onReconnect = { manual ->
                    if (manual) { callbacks++; player.retryPlayback() }
                }, graceMillis = 1)
                check!!.retry()
                player.seek(7)
                if (stop) player.stop() else h.systemPlayer.pause()
            }
            val selected = player.state.value.current?.id
            compose.runOnIdle { h.internet.available.value = true }
            waitFor("Delayed manual callback") { callbacks == 1 }
            Thread.sleep(700)
            assertFalse(player.state.value.playing)
            assertEquals(selected, player.state.value.current?.id)
            assertEquals(if (stop) 0 else 7, player.state.value.positionSeconds)
            val saved = LaunchStateStore(h.context).read("owner")
            assertFalse(saved.playing); assertEquals(stop, saved.stopped)
        } finally { compose.runOnIdle { check?.close(); scope.cancel() } }
    }

    @Test fun visiblePlayAfterFailedEndStartsOnFirstPress() {
        val next = CompletableDeferred<Unit>()
        compose.runOnIdle { h.waveRequestGates = mapOf(2 to next); player.playMyWave() }
        waitFor("Playing only A") { player.state.value.playing && 2 in h.waveRequests }
        compose.runOnIdle { h.waveFailure = MusicFailure.NETWORK; next.complete(Unit) }
        waitFor("Failed tail") { player.state.value.waveIssue != null && !player.state.value.waveLoading }
        compose.runOnIdle { player.seek(player.state.value.current!!.durationSeconds - 1) }
        waitFor("EOF error shows Play") {
            var ended = false
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync { ended = raw.playbackState == Player.STATE_ENDED }
            ended && !player.state.value.playing && player.state.value.waveIssue != null && !player.state.value.waveLoading
        }
        compose.runOnIdle { h.waveFailure = null; player.toggle() }
        assertTrue("First press must persist Play", LaunchStateStore(h.context).read("owner").playing)
        waitFor("Next song on first press") { player.state.value.current?.id == "yandex:2:7" && player.state.value.positionSeconds >= 1 }
    }

    @Test fun transientNetworkErrorKeepsCurrentTrackAndPosition() = transientError(MusicFailure.NETWORK, null)
    @Test fun transientServerErrorKeepsCurrentTrackAndPosition() = transientError(MusicFailure.RESPONSE, 503)
    private fun transientError(failure: MusicFailure, status: Int?) {
        startFull()
        val selected = player.state.value.current!!.id
        val ids = player.state.value.queue.map(Track::id)
        compose.runOnIdle {
            assertTrue(file(selected).delete())
            h.streamFailure = failure; h.streamHttpStatus = status
            raw.stop(); raw.seekTo(7_000); raw.prepare(); raw.play()
        }
        waitFor("Actual failed stream") { player.state.value.error != null }
        Thread.sleep(1200)
        assertEquals(selected, player.state.value.current?.id)
        assertEquals(7, player.state.value.positionSeconds)
        assertEquals(ids, player.state.value.queue.map(Track::id))
        val saved = org.json.JSONObject(h.checkpointText())
        assertEquals(selected, saved.getString("current")); assertEquals(7, saved.getInt("position"))
        compose.runOnIdle { h.streamFailure = null; h.streamHttpStatus = null; player.retryPlayback() }
        waitFor("Retry same A at saved position") { player.state.value.current?.id == selected && player.state.value.positionSeconds >= 8 && player.state.value.playing }
    }

    @Test fun backgroundBlockedListsKeepPausedWindowButNewLocalDislikeCanSkip() {
        startFull()
        compose.runOnIdle { player.seek(7); h.systemPlayer.pause(); player.saveForExit() }
        val selected = player.state.value.current!!
        val ids = player.state.value.queue.map(Track::id)
        compose.runOnIdle {
            h.tasteLists["owner" to TasteKind.TRACK] = TasteList(blocked = setOf(selected.tasteTarget().key))
            h.taste.refresh(TasteKind.TRACK)
        }
        waitFor("Background blocked list") { selected.tasteTarget().key in h.taste.state.value.shelf(TasteKind.TRACK).list.blocked && !h.taste.state.value.shelf(TasteKind.TRACK).busy }
        Thread.sleep(700)
        assertEquals(selected.id, player.state.value.current?.id); assertEquals(7, player.state.value.positionSeconds)
        assertEquals(ids, player.state.value.queue.map(Track::id))
        compose.runOnIdle { h.restartEngine() }
        waitFor("Same paused checkpoint") { player.state.value.current?.id == selected.id && player.state.value.positionSeconds == 7 }
        assertFalse(player.state.value.playing)
        compose.runOnIdle { h.taste.react(selected.tasteTarget(), TasteAction.BLOCK) }
        waitFor("New explicit dislike moves selection") { player.state.value.current?.id == ids[1] }
        assertEquals(0, player.state.value.positionSeconds); assertFalse(player.state.value.playing)
        assertFalse(player.state.value.queue.any { it.id == selected.id })
    }

    @Test fun fullRestoredWindowContinuesFromSavedTailCursor() {
        startFull()
        val count = h.waveRequests.size
        compose.runOnIdle { player.seek(7); h.systemPlayer.pause(); player.saveForExit(); h.restartEngine() }
        waitFor("Restored A") { player.state.value.current?.id == "yandex:1:7" && player.state.value.positionSeconds == 7 }
        compose.runOnIdle { h.systemPlayer.play(); player.skip(1) }
        waitFor("B") { player.state.value.current?.id == "yandex:2:7" }
        compose.runOnIdle { player.skip(1) }
        waitFor("Populated after C") { player.state.value.current?.id == "yandex:3:7" && h.waveRequests.size > count && full() }
        assertEquals("No refetch of B..J", listOf(11), h.waveRequests.drop(count))
        assertEquals("yandex:2:7", player.state.value.queue.first().id)
    }

    @Test fun missingMiddleFileCannotJumpToLaterCachedTrackOrLoseBack() {
        startFull()
        val first = player.state.value.current!!.id
        val gate = CompletableDeferred<Unit>()
        compose.runOnIdle {
            h.systemPlayer.pause(); player.saveForExit()
            assertTrue(file("yandex:2:7").delete())
            h.streamGates = mapOf("yandex:2:7" to gate)
            h.restartEngine(); h.systemPlayer.play()
        }
        waitFor("Missing B repair starts") { player.state.value.playing && h.resolved.count { it.second == "yandex:2:7" } >= 2 }
        compose.runOnIdle {
            assertEquals(1, raw.mediaItemCount)
            player.seek(player.state.value.current!!.durationSeconds - 1)
        }
        Thread.sleep(1800)
        assertEquals("A must not jump over B to C", first, player.state.value.current?.id)
        assertTrue(file(first).isFile)
        compose.runOnIdle { gate.complete(Unit) }
        waitFor("B after repair") { player.state.value.current?.id == "yandex:2:7" && player.state.value.positionSeconds >= 1 }
        assertEquals(first, player.state.value.queue.first().id)
        assertTrue(file(first).isFile)
        compose.runOnIdle { player.skip(-1) }
        waitFor("Back to actual previous A") { player.state.value.current?.id == first && player.state.value.playing }
    }

    @Test fun systemStopRetainsPlayingWavePositionAndCompleteAudio() {
        startFull()
        val selected = player.state.value.current!!.id
        val ids = player.state.value.queue.map(Track::id)
        compose.runOnIdle { player.seek(7); h.systemPlayer.stop() }
        compose.runOnIdle { assertEquals(Player.STATE_IDLE, raw.playbackState) }
        assertEquals(selected, player.state.value.current?.id)
        assertEquals(7, player.state.value.positionSeconds)
        assertEquals(ids, player.state.value.queue.map(Track::id))
        assertEquals(ids.toSet(), player.bufferedWaveAudioIds())
        assertFalse(player.state.value.playing)
        assertFalse(LaunchStateStore(h.context).read("owner").stopped)
    }

    @Test fun systemStopAfterExplicitStopCannotUndoItsMarker() {
        startFull()
        compose.runOnIdle { player.stop(); h.systemPlayer.stop() }
        assertTrue(LaunchStateStore(h.context).read("owner").stopped)
        assertTrue(player.state.value.queue.isEmpty())
        assertTrue(player.bufferedWaveAudioIds().isEmpty())
        assertEquals(0, player.state.value.positionSeconds)
    }

    @Test fun actualMedia3NoisyReasonAfterStopPreservesStopped() {
        startFull()
        compose.runOnIdle {
            h.enableNoisyForTest()
            player.stop()
            val listener = raw.javaClass.getDeclaredField("componentListener").apply { isAccessible = true }.get(raw)
            listener.javaClass.getDeclaredMethod("onAudioBecomingNoisy").apply { isAccessible = true }.invoke(listener)
        }
        Thread.sleep(700)
        assertTrue(LaunchStateStore(h.context).read("owner").stopped)
        compose.runOnIdle { player.saveForExit(); h.restartEngine() }
        waitFor("Stopped wave restored") { player.state.value.connected && player.state.value.wave }
        assertTrue(player.state.value.queue.isEmpty()); assertEquals(0, player.state.value.positionSeconds)
        assertTrue(LaunchStateStore(h.context).read("owner").stopped); assertFalse(player.state.value.playing)
    }
}
