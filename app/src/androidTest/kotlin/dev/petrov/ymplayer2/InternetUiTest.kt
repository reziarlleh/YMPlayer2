package dev.petrov.ymplayer2

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InternetUiTest {
    @get:Rule val compose = createAndroidComposeRule<OnlineTestActivity>()
    private val f get() = compose.activity.harness
    @Before fun prepare() {
        compose.waitUntil(15000) { f.player.state.value.connected && f.library.state.value.ready }
        compose.runOnIdle { f.player.switchProfile("owner"); f.player.stop(); f.player.clearQueue() }
        compose.waitUntil(10000) { f.radioCatalog.state.value.profileId == "owner" }
    }
    @After fun stop() { compose.runOnIdle { f.radio.release(); f.player.stop() } }
    @Test fun radioWaitsFiveSecondsAndRetryRestartsGrace() {
        compose.runOnIdle { f.internet.available.value = false }
        compose.onNodeWithTag("nav_radio").performClick()
        compose.onNodeWithTag("internet_waiting").assertIsDisplayed()
        compose.mainClock.advanceTimeBy(3500)
        compose.onNodeWithTag("internet_retry").assertDoesNotExist()
        compose.mainClock.advanceTimeBy(2000)
        compose.onNodeWithTag("internet_retry").assertIsDisplayed()
        val refreshes = f.internet.refreshes
        compose.onNodeWithTag("internet_retry").performClick()
        compose.onNodeWithTag("internet_waiting").assertIsDisplayed()
        assertEquals(refreshes + 1, f.internet.refreshes)
        compose.runOnIdle { f.internet.available.value = true }
        compose.waitUntil(3000) { compose.onAllNodesWithTag("internet_notice").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("radio_screen").assertIsDisplayed()
    }
    @Test fun onlineLibraryAndSearchUseNoticeButLocalAndOfflineDoNot() {
        compose.runOnIdle { f.internet.available.value = false }
        compose.onNodeWithTag("nav_library").performClick()
        compose.onNodeWithTag("internet_notice").assertDoesNotExist()
        compose.onNodeWithTag("source_yandex").performClick()
        compose.onNodeWithTag("internet_waiting").assertIsDisplayed()
        compose.waitUntil(8000) { compose.onAllNodesWithTag("internet_retry").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("source_local").performClick()
        compose.onNodeWithTag("internet_notice").assertDoesNotExist()
        compose.onNodeWithTag("nav_search").performClick()
        compose.onNodeWithTag("source_yandex").performClick()
        compose.onNodeWithTag("internet_waiting").assertIsDisplayed()
        compose.onNodeWithTag("open_offline").performClick()
        compose.onNodeWithTag("internet_notice").assertDoesNotExist()
        compose.onNodeWithTag("source_local").performClick()
        compose.onNodeWithTag("internet_notice").assertDoesNotExist()
    }
    @Test fun quickConnectionSuppressesErrorAndStoppedRadioRemainsStopped() {
        compose.runOnIdle { f.internet.available.value = false }
        compose.onNodeWithTag("nav_radio").performClick()
        compose.onNodeWithTag("internet_waiting").assertExists()
        compose.mainClock.advanceTimeBy(1000)
        compose.runOnIdle { f.internet.available.value = true }
        compose.waitUntil(3000) { compose.onAllNodesWithTag("internet_notice").fetchSemanticsNodes().isEmpty() }
        compose.mainClock.advanceTimeBy(4500)
        compose.onNodeWithTag("internet_retry").assertDoesNotExist()
        assertFalse(f.radio.state.value.playing)
    }
}
