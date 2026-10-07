package dev.petrov.ymplayer2

import androidx.compose.ui.test.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.petrov.ymplayer2.yandex.YandexRadioApi
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assert.*
import org.junit.Rule
import org.junit.runner.RunWith
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import androidx.test.platform.app.InstrumentationRegistry

/** Public station, actual HTTPS/HLS and decoder. No personal credentials or favourite writes. */
@RunWith(AndroidJUnit4::class)
class RadioLivePlaybackTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    private val initialScreen = object : ExternalResource() {
        override fun before() {
            val instrument = InstrumentationRegistry.getInstrumentation()
            val graph = instrument.targetContext.applicationContext as PlayerApplication
            instrument.runOnMainSync { graph.playback.stop() }
            graph.navigation.edit().clear().putString("route", "player").commit()
            graph.launchState.write(graph.playback.state.value.profileId, dev.petrov.ymplayer2.playback.PlaybackOutput.MUSIC, false)
        }
    }
    @get:Rule val rules: RuleChain = RuleChain.outerRule(initialScreen).around(compose)
    @Test fun publicHlsStationPlaysWithSharedAudioServiceAndStopsFromScreen() {
        val graph = compose.activity.application as PlayerApplication
        compose.waitUntil(15000) { graph.playback.state.value.connected }
        compose.onNodeWithTag("nav_radio").performClick()
        val station = runBlocking { YandexRadioApi(graph.accounts).station("europa-plus", "moscow") }
        compose.runOnIdle { graph.radio.play(station) }
        try {
            compose.waitUntil(45000) { graph.radio.state.value.playing }
            assertFalse(graph.playback.state.value.playing)
            assertEquals("europa-plus", graph.radio.state.value.station?.slug)
            compose.onNodeWithTag("radio_stop").assertIsDisplayed()
            val png = java.io.File(compose.activity.getExternalFilesDir(null), "radio-live.png")
            png.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            compose.onNodeWithTag("radio_stop").performClick()
            compose.waitUntil(5000) { !graph.radio.state.value.playing && !graph.radio.state.value.buffering }
            compose.onNodeWithTag("radio_play").assertIsEnabled()
        } finally { compose.runOnIdle { graph.radio.release() } }
    }
}
