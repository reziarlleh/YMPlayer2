@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
package dev.petrov.ymplayer2

import android.media.session.MediaController
import android.media.session.PlaybackState
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.media3.session.MediaSession
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.petrov.ymplayer2.core.Source
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

/** Android TV's background-audio Stop uses framework TransportControls.pause(). */
@RunWith(AndroidJUnit4::class)
class TvFrameworkPauseTest {
    @get:Rule val compose = createAndroidComposeRule<OnlineTestActivity>()
    private val f get() = compose.activity.harness
    private lateinit var session: MediaSession
    private lateinit var controller: MediaController
    private fun waitFor(condition: () -> Boolean) = compose.waitUntil(25_000, condition)
    @Before fun setup() {
        waitFor { f.library.state.value.ready && f.player.state.value.connected }
        compose.runOnIdle { f.radio.release(); f.player.stop(); f.player.switchProfile("owner"); f.player.clearQueue() }
        runBlocking { f.library.state.value.roots.forEach { f.library.forgetFolder(it.uri) } }
        compose.activity.contentResolver.call(android.net.Uri.parse("content://dev.petrov.ymplayer2.test.control"), "fixtures", null, null)
        runBlocking { f.library.addFolder(TestMusicProvider.tree.toString(), Source.LOCAL) }
        waitFor { f.library.testTracks.size == 2 }
        compose.runOnIdle {
            session = MediaSession.Builder(compose.activity, f.systemPlayer).setId("tv-framework-pause").build()
            controller = MediaController(compose.activity, session.platformToken)
        }
    }
    @After fun cleanup() = compose.runOnIdle {
        if (::session.isInitialized) session.release()
        f.radio.release(); f.player.stop()
    }
    @Test fun frameworkPauseDuringWavePrefetchWinsOverLateAudioReply() {
        val gate = CompletableDeferred<Unit>()
        compose.runOnIdle { f.streamGates = mapOf("yandex:2:7" to gate); f.player.playMyWave() }
        waitFor { f.player.state.value.positionSeconds >= 1 && f.resolved.any { it.second == "yandex:2:7" } }
        // Pending audio is not yet a playable Next item. Reach the natural end
        // instead of sending a disabled framework skip command.
        compose.runOnIdle { f.player.seek(f.player.state.value.current!!.durationSeconds - 1) }
        waitFor { f.player.state.value.positionSeconds >= 30 }
        controller.transportControls.pause()
        waitFor { !f.player.state.value.playing && controller.playbackState?.state != PlaybackState.STATE_PLAYING }
        // Wait for the framework command to reach the service before releasing network IO.
        compose.waitForIdle(); Thread.sleep(300)
        compose.runOnIdle { gate.complete(Unit) }
        waitFor { !f.player.state.value.waveLoading && f.player.state.value.current?.id == "yandex:2:7" }
        Thread.sleep(2500)
        assertFalse(f.player.state.value.playing)
        assertEquals(PlaybackState.STATE_PAUSED, controller.playbackState?.state)
        assertFalse(dev.petrov.ymplayer2.playback.LaunchStateStore(compose.activity).read("owner").playing)
        controller.transportControls.play()
        waitFor { f.player.state.value.playing && f.player.state.value.positionSeconds >= 1 }
    }
    @Test fun frameworkPauseStopsRadioAndDoesNotReconnect() {
        compose.runOnIdle { f.radio.play(f.radioApi.one) }
        waitFor { f.radio.state.value.playing }
        controller.transportControls.pause()
        waitFor { !f.radio.state.value.playing && !f.radio.state.value.reconnecting }
        val polls = f.radioApi.onAirCalls
        Thread.sleep(6100)
        assertEquals(polls, f.radioApi.onAirCalls)
        assertFalse(f.radio.state.value.playing)
        controller.transportControls.play()
        waitFor { f.radio.state.value.playing }
    }
}
