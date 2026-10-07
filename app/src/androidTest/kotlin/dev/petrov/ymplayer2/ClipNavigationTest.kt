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

/** Real Shell -> ClipActivity -> Shell navigation; no OAuth or remote video required. */
@RunWith(AndroidJUnit4::class)
class ClipNavigationTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrument get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrument.targetContext
    private val app get() = context.applicationContext as PlayerApplication
    private var main: MainActivity? = null
    private fun waitFor(label: String, test: () -> Boolean) {
        val end = System.currentTimeMillis() + 15000
        while (!test() && System.currentTimeMillis() < end) Thread.sleep(40)
        assertTrue(label, test())
    }
    private fun clip(): ClipActivity? {
        var result: ClipActivity? = null
        instrument.runOnMainSync {
            result = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<ClipActivity>().firstOrNull()
        }
        return result
    }
    private fun launchMain() {
        main = instrument.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
    }
    @Before fun prepare() {
        instrument.runOnMainSync { app.radio.release(); app.playback.stop() }
        app.navigation.edit().clear().putString("route", "player").commit()
        app.launchState.write(app.playback.state.value.profileId, PlaybackOutput.MUSIC, false)
        launchMain()
    }
    @After fun cleanup() {
        val video = clip()
        instrument.runOnMainSync { video?.finish(); main?.finish(); app.radio.release(); app.playback.stop() }
    }
    private fun open(): ClipActivity {
        compose.onNodeWithTag("nav_clips").performClick()
        waitFor("ClipActivity resumed") { clip() != null }
        return clip()!!
    }
    private fun assertReturned(video: ClipActivity, route: String) {
        waitFor("Video closed") { video.isDestroyed }
        waitFor("Underlying Activity resumed") { main?.lifecycle?.currentState?.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) == true }
        compose.onNodeWithTag("nav_$route").assertIsSelected()
        assertEquals(route, app.navigation.getString("route", null))
        assertEquals(0, app.clipActivities)
    }
    @Test fun systemBackReturnsToEverySourceSectionAndPreservesRadioSearch() {
        for (route in listOf("player", "library", "search", "radio")) {
            compose.onNodeWithTag("nav_$route").performClick()
            if (route == "radio") {
                compose.runOnIdle { app.radioCatalog.search("rock") }
                compose.onNodeWithTag("radio_search").assertTextEquals("rock")
            }
            val video = open()
            assertEquals(route, app.navigation.getString("clips_return_route", null))
            instrument.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            assertReturned(video, route)
            if (route == "radio") compose.onNodeWithTag("radio_search").assertTextEquals("rock")
        }
    }
    @Test fun videoBackButtonReturnsToSettingsInsteadOfPlaceholder() {
        compose.onNodeWithTag("settings").performClick()
        val video = open()
        instrument.runOnMainSync {
            val views = ArrayList<View>()
            video.window.decorView.findViewsWithText(views, trMessage("← Назад"), View.FIND_VIEWS_WITH_TEXT)
            assertTrue("Native video Back button", views.isNotEmpty())
            views.first().performClick()
        }
        waitFor("Video closed") { video.isDestroyed }
        compose.onNodeWithTag("settings_list").assertIsDisplayed()
        assertEquals("settings", app.navigation.getString("route", null))
    }
    @Test fun restoredClipSessionReturnsToStoredSectionAndDoesNotReopenAfterClose() {
        instrument.runOnMainSync { main!!.finish() }
        waitFor("First Main destroyed") { main!!.isDestroyed }
        app.navigation.edit().putString("route", "clips").putString("clips_return_route", "library").commit()
        app.launchState.write(app.playback.state.value.profileId, PlaybackOutput.CLIPS, false)
        launchMain()
        waitFor("Restored ClipActivity resumed") { clip() != null }
        val video = clip()!!
        instrument.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        assertReturned(video, "library")
        instrument.runOnMainSync { main!!.finish() }
        waitFor("Main destroyed after close") { main!!.isDestroyed }
        launchMain()
        compose.onNodeWithTag("nav_library").assertIsSelected()
        assertNull(clip())
        // Older beta checkpoints did not record a return section; use a real screen as fallback.
        app.navigation.edit().putString("route", "clips").remove("clips_return_route").commit()
        assertEquals("player", app.shellRoute())
        app.navigation.edit().putString("route", "library").commit()
    }
}
