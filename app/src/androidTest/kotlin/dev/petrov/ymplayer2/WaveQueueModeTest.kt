@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
package dev.petrov.ymplayer2

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.media3.common.Player
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.petrov.ymplayer2.core.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WaveQueueModeTest {
    @get:Rule val compose = createAndroidComposeRule<OnlineTestActivity>()
    private val fixture get() = compose.activity.harness
    private val player get() = fixture.player
    private fun waitFor(condition: () -> Boolean) {
        try { compose.waitUntil(25000, condition) }
        catch (failure: Throwable) {
            val state = player.state.value
            throw AssertionError("Wave state: current=${state.current?.id}, count=${state.queueCount}, position=${state.positionSeconds}, " +
                "playing=${state.playing}, wave=${state.wave}, loading=${state.waveLoading}, issue=${state.waveIssue}, error=${state.error}", failure)
        }
    }
    @Before fun prepare() {
        waitFor { fixture.library.state.value.ready && player.state.value.connected }
        compose.runOnIdle { player.stop(); player.switchProfile("owner"); player.clearQueue() }
        compose.onNodeWithTag("repeat_mode").assertDoesNotExist()
        compose.onNodeWithTag("shuffle_mode").assertDoesNotExist()
        runBlocking { fixture.library.state.value.roots.forEach { fixture.library.forgetFolder(it.uri) } }
        compose.activity.contentResolver.call(android.net.Uri.parse("content://dev.petrov.ymplayer2.test.control"), "fixtures", null, null)
        runBlocking { fixture.library.addFolder(TestMusicProvider.tree.toString(), Source.LOCAL) }
        waitFor { fixture.library.testTracks.size == 2 && fixture.taste.state.value.shelf(TasteKind.ARTIST).ready }
    }
    @After fun stop() { compose.runOnIdle { player.stop() } }
    private fun start() {
        compose.onNodeWithTag("my_wave").performClick()
        compose.onNodeWithTag("player_source_wave").performClick()
        waitFor { player.state.value.positionSeconds >= 1 && player.state.value.queue.size >= 2 }
    }
    private fun waveControls() {
        assertTrue("Stopped/paused wave must remain a wave", player.state.value.wave)
        compose.onNodeWithTag("repeat_mode").assertDoesNotExist()
        compose.onNodeWithTag("shuffle_mode").assertDoesNotExist()
        assertEquals(RepeatMode.OFF, player.state.value.repeatMode)
        assertFalse(player.state.value.shuffle)
    }
    @Test fun stopKeepsWaveAcrossResumeAndServiceRestoreButOrdinaryListRestoresControls() {
        val local = fixture.library.testTracks.first().id
        compose.runOnIdle { player.playQueue(listOf(local)); player.setRepeatMode(RepeatMode.ALL); player.setShuffle(true) }
        start(); waveControls()
        compose.onNodeWithTag("player_stop").performClick()
        waveControls()
        assertFalse(player.state.value.playing); assertEquals(0, player.state.value.positionSeconds)
        assertTrue(org.json.JSONObject(fixture.checkpointText()).getBoolean("wave"))
        compose.runOnIdle { fixture.restartEngine() }
        waitFor { player.state.value.connected && player.state.value.current?.id == "yandex:1:7" }
        waveControls(); assertFalse(player.state.value.playing)
        compose.onNodeWithTag("player_play").performClick()
        waitFor { player.state.value.positionSeconds >= 1 && "yandex:2:7" in player.bufferedWaveAudioIds() }
        compose.runOnIdle { player.skip(1) }
        waitFor { player.state.value.current?.id == "yandex:2:7" && player.state.value.positionSeconds >= 1 }
        waveControls()
        compose.runOnIdle { player.playQueue(listOf(local)); player.toggle() }
        waitFor { !player.state.value.wave && player.state.value.current?.id == local }
        compose.onNodeWithTag("repeat_mode").assertIsDisplayed().performClick()
        compose.onNodeWithTag("shuffle_mode").assertIsDisplayed().performClick()
        assertEquals(RepeatMode.OFF, player.state.value.repeatMode); assertTrue(player.state.value.shuffle)
    }
    @Test fun externalControllerCannotEnableOrderingForPlayingPausedOrStoppedWave() {
        start()
        for (phase in 0..2) {
            compose.runOnIdle {
                if (phase == 1) fixture.systemPlayer.pause()
                if (phase == 2) fixture.systemPlayer.stop()
                fixture.systemPlayer.repeatMode = Player.REPEAT_MODE_ALL
                fixture.systemPlayer.shuffleModeEnabled = true
            }
            compose.runOnIdle {
                assertEquals(Player.REPEAT_MODE_OFF, fixture.systemPlayer.repeatMode)
                assertFalse(fixture.systemPlayer.shuffleModeEnabled)
                assertFalse(fixture.systemPlayer.isCommandAvailable(Player.COMMAND_SET_REPEAT_MODE))
                assertFalse(fixture.systemPlayer.isCommandAvailable(Player.COMMAND_SET_SHUFFLE_MODE))
            }
            waveControls()
        }
        compose.runOnIdle {
            player.playQueue(listOf(fixture.library.testTracks.first().id))
            fixture.systemPlayer.repeatMode = Player.REPEAT_MODE_ONE
            fixture.systemPlayer.shuffleModeEnabled = true
        }
        waitFor { !player.state.value.wave && player.state.value.repeatMode == RepeatMode.OFF && player.state.value.shuffle }
        assertEquals(RepeatMode.OFF, player.state.value.repeatMode); assertTrue(player.state.value.shuffle)
    }
    @Test fun stoppedInitialRequestStaysWaveAndCanResumeWithoutLateAutoplay() {
        compose.runOnIdle { fixture.waveDelayMillis = 1200; player.playMyWave() }
        waitFor { player.state.value.waveLoading }
        compose.onNodeWithTag("player_stop").performClick()
        waveControls(); assertFalse(player.state.value.playing)
        compose.waitUntil(3000) { !player.state.value.waveLoading }
        compose.runOnIdle { fixture.restartEngine() }
        waitFor { player.state.value.connected && player.state.value.wave }
        waveControls(); assertFalse(player.state.value.playing)
        compose.onNodeWithTag("player_play").assertIsEnabled().performClick()
        waitFor { player.state.value.positionSeconds >= 1 && player.state.value.queue.size >= 2 }
        waveControls()
    }
}
