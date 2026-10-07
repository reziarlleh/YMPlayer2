package dev.petrov.ymplayer2

import android.content.Intent
import android.view.View
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import dev.petrov.ymplayer2.localization.trMessage
import dev.petrov.ymplayer2.playback.PlaybackOutput
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

/** Host runs this only on explicitly named emulators; restores Wi-Fi/data in finally. */
@RunWith(AndroidJUnit4::class)
class InternetDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrument get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrument.targetContext.applicationContext as PlayerApplication
    private var main: MainActivity? = null
    private var emulatorOnly = false
    private fun command(value: String) {
        instrument.uiAutomation.executeShellCommand(value).use { descriptor ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
        }
    }
    private fun radios(enabled: Boolean) {
        command("svc wifi ${if (enabled) "enable" else "disable"}")
        command("svc data ${if (enabled) "enable" else "disable"}")
    }
    private fun waitFor(label: String, test: () -> Boolean) {
        val end = System.currentTimeMillis() + 25000
        while (!test() && System.currentTimeMillis() < end) Thread.sleep(50)
        assertTrue(label, test())
    }
    private fun clip(): ClipActivity? {
        var result: ClipActivity? = null
        instrument.runOnMainSync { result = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<ClipActivity>().firstOrNull() }
        return result
    }
    private fun visibleText(activity: ClipActivity, text: String): List<View> {
        val views = ArrayList<View>()
        instrument.runOnMainSync { activity.window.decorView.findViewsWithText(views, text, View.FIND_VIEWS_WITH_TEXT) }
        return views.filter { it.visibility == View.VISIBLE }
    }
    @After fun cleanup() {
        if (!emulatorOnly) return
        radios(true)
        val video = clip()
        instrument.runOnMainSync { video?.finish(); main?.finish(); app.radio.release(); app.playback.stop() }
    }
    @Test fun actualNetworkLossShowsRadioAndClipRetryAndRestoresWithoutPlaceholder() {
        org.junit.Assume.assumeTrue(android.os.Build.FINGERPRINT.contains("generic") || android.os.Build.MODEL.contains("sdk"))
        emulatorOnly = true
        instrument.runOnMainSync { app.radio.release(); app.playback.stop() }
        app.navigation.edit().clear().putString("route", "player").commit()
        app.launchState.write(app.playback.state.value.profileId, PlaybackOutput.MUSIC, false)
        radios(true); waitFor("Initial internet") { app.internet.available.value }
        radios(false); waitFor("Real network loss") { !app.internet.available.value }
        main = instrument.startActivitySync(Intent(instrument.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        compose.onNodeWithTag("nav_library").performClick()
        compose.onNodeWithTag("internet_notice").assertDoesNotExist()
        compose.onNodeWithTag("nav_radio").performClick()
        compose.onNodeWithTag("internet_waiting").assertIsDisplayed()
        compose.waitUntil(9000) { compose.onAllNodesWithTag("internet_retry").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("nav_clips").performClick(); waitFor("Video opened") { clip() != null }
        val video = clip()!!
        waitFor("Video no internet") { visibleText(video, trMessage("Отсутствует интернет")).isNotEmpty() }
        val retry = visibleText(video, trMessage("Повторить подключение")).first()
        instrument.runOnMainSync { retry.performClick() }
        Thread.sleep(100)
        assertTrue(visibleText(video, trMessage("Отсутствует интернет")).isEmpty())
        radios(true); waitFor("Actual connection restored") { app.internet.available.value }
        waitFor("Video notice cleared") { visibleText(video, trMessage("Отсутствует интернет")).isEmpty() }
        val back = visibleText(video, trMessage("← Назад")).first()
        instrument.runOnMainSync { back.performClick() }
        waitFor("Video closed") { video.isDestroyed }
        compose.onNodeWithTag("nav_radio").assertIsSelected()
        compose.onNodeWithTag("internet_notice").assertDoesNotExist()
    }
}
