@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
package dev.petrov.ymplayer2

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.petrov.ymplayer2.core.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WaveSettingsPlaybackTest {
    @get:Rule val compose = createAndroidComposeRule<OnlineTestActivity>()
    private val fixture get() = compose.activity.harness
    private val player get() = fixture.player
    private fun waitFor(condition: () -> Boolean) = compose.waitUntil(25000, condition)
    @Before fun prepare() {
        waitFor { fixture.library.state.value.ready && player.state.value.connected }
        compose.runOnIdle { player.stop(); player.switchProfile("owner"); player.clearQueue(); player.waveSettings!!.reset() }
        runBlocking { fixture.library.state.value.roots.forEach { fixture.library.forgetFolder(it.uri) } }
        compose.activity.contentResolver.call(android.net.Uri.parse("content://dev.petrov.ymplayer2.test.control"), "fixtures", null, null)
        runBlocking { fixture.library.addFolder(TestMusicProvider.tree.toString(), Source.LOCAL) }
        waitFor { fixture.library.testTracks.size == 2 && fixture.taste.state.value.signedIn }
    }
    @After fun stop() { compose.runOnIdle { player.stop() } }
    private fun startMyWave() {
        if (!player.state.value.wave || player.state.value.origin.source != PlaybackSource.MY_WAVE) {
            compose.onNodeWithTag("my_wave").performClick()
            compose.onNodeWithTag("player_source_wave").performClick()
            waitFor { player.state.value.wave && player.state.value.origin.source == PlaybackSource.MY_WAVE && player.state.value.positionSeconds >= 1 }
        }
    }
    private fun open() {
        startMyWave()
        compose.onNodeWithTag("my_wave_settings").performClick()
        waitFor { player.waveSettings!!.state.value.options != null && !player.waveSettings!!.state.value.loading }
        compose.onNodeWithTag("wave_settings_dialog").assertIsDisplayed()
    }
    private fun choose() {
        compose.onNodeWithTag("wave_setting_contexts_activity:road-trip").performScrollTo().performClick()
        compose.onNodeWithTag("wave_setting_moodEnergy_settingMoodEnergy:calm").performScrollTo().performClick()
        compose.onNodeWithTag("wave_setting_language_settingLanguage:russian").performScrollTo().performClick()
    }
    @Test fun settingsDoNotInterruptMyWaveAndSelectedSeedsSurviveContinuationAndPausedRestore() {
        startMyWave()
        val current = player.state.value.current!!.id
        open(); choose(); assertEquals(current, player.state.value.current!!.id); assertTrue(player.state.value.playing)
        compose.onNodeWithTag("wave_settings_play").performClick()
        waitFor { player.state.value.current?.source == Source.YANDEX && player.state.value.positionSeconds >= 1 && player.state.value.queue.size == 2 }
        assertEquals("activity:road-trip", fixture.waveSelections.last().station)
        assertEquals(setOf("settingMoodEnergy:calm", "settingLanguage:russian"), fixture.waveSelections.last().settings.toSet())
        compose.runOnIdle { player.seek(player.state.value.current!!.durationSeconds - 1) }
        waitFor { player.state.value.current?.id == "yandex:2:7" && player.state.value.positionSeconds >= 1 }
        assertTrue(fixture.waveFeedback.any { it.second.station == "activity:road-trip" && it.third == WaveFeedback.STARTED })
        compose.runOnIdle { player.toggle(); fixture.restartEngine() }
        waitFor { player.state.value.connected && player.state.value.current?.id == "yandex:2:7" }
        assertFalse(player.state.value.playing)
        assertTrue(fixture.checkpointText().contains("activity:road-trip"))
        assertFalse(fixture.checkpointText().contains("fixture-token"))
        compose.runOnIdle { player.toggle() }; waitFor { player.state.value.positionSeconds >= 2 }
        compose.onNodeWithTag("repeat_mode").assertDoesNotExist(); compose.onNodeWithTag("shuffle_mode").assertDoesNotExist()
    }
    @Test fun closeResetRecreationAndProfileIsolationKeepChoicesAndQueueSeparate() {
        open(); choose(); compose.onNodeWithTag("wave_settings_close").performClick()
        assertTrue(player.state.value.wave)
        compose.activityRule.scenario.recreate(); open()
        compose.onNodeWithTag("wave_setting_language_settingLanguage:russian").assertIsSelected()
        compose.onNodeWithTag("wave_settings_close").performClick()
        compose.runOnIdle { player.switchProfile("road") }
        waitFor { player.state.value.profileId == "road" && fixture.taste.state.value.profileId == "road" && fixture.taste.state.value.signedIn }
        assertTrue(player.waveSettings!!.state.value.selected.isEmpty())
        compose.runOnIdle { player.switchProfile("owner") }
        waitFor { fixture.taste.state.value.profileId == "owner" && fixture.taste.state.value.signedIn }
        open(); compose.onNodeWithTag("wave_setting_language_settingLanguage:russian").assertIsSelected()
        compose.onNodeWithTag("wave_settings_reset").performClick()
        assertEquals(WaveRequest(), player.waveSettings!!.request())
    }
    @Test fun failedSettingsReadCanRetryWithoutRestartingMyWave() {
        startMyWave()
        val sessions = fixture.waveSelections.size
        compose.runOnIdle { fixture.waveOptionsFailure = MusicFailure.NETWORK }
        compose.onNodeWithTag("my_wave_settings").performClick()
        waitFor { player.waveSettings!!.state.value.issue != null }
        compose.onNodeWithTag("wave_settings_play").assertIsNotEnabled()
        assertTrue(player.state.value.wave)
        compose.runOnIdle { fixture.waveOptionsFailure = null }
        compose.onNodeWithTag("wave_settings_retry").performScrollTo().performClick()
        waitFor { player.waveSettings!!.state.value.options != null && player.waveSettings!!.state.value.issue == null }
        compose.onNodeWithTag("wave_settings_play").assertIsEnabled()
        assertEquals(sessions, fixture.waveSelections.size)
    }
    @Test fun remoteCanSelectAnAlreadySelectedChipAndNavigateBetweenGroups() {
        open()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_DOWN)
        val context = compose.onNodeWithTag("wave_setting_contexts_user:onyourwave")
        context.performScrollTo().performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        context.assertIsFocused().performKeyInput { pressKey(Key.DirectionRight) }
        val road = compose.onNodeWithTag("wave_setting_contexts_activity:road-trip")
        road.assertIsFocused().performKeyInput { pressKey(Key.DirectionCenter) }
        road.assertIsSelected().performKeyInput { pressKey(Key.DirectionDown) }
        road.assertIsNotFocused()
        compose.onNodeWithTag("wave_setting_contexts_activity:road-trip").performScrollTo()
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val file = java.io.File(compose.activity.getExternalFilesDir(null), "wave-settings.png")
        file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
