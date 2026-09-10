package dev.petrov.ymplayer2

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NavigationTest {
    @get:Rule val compose = createAndroidComposeRule<DemoActivity>()
    private fun nav(route: String) = compose.onNodeWithTag("nav_$route").performClick()
    private fun back() = compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }

    @Test fun upUsesHierarchyAndToolbarHonorsCatalogDetail() {
        nav("library"); nav("search")
        compose.onNodeWithTag("settings").performClick()
        compose.onNodeWithTag("navigate_up").performClick()
        compose.onNodeWithTag("player_play").assertExists()
        compose.onNodeWithTag("navigate_up").assertDoesNotExist()
        nav("library")
        compose.onNodeWithTag("category_ALBUMS").performScrollTo().performClick()
        compose.onNodeWithText("Город после заката").performScrollTo().performClick()
        compose.onNodeWithTag("navigate_up").performClick()
        compose.onNodeWithTag("category_ALBUMS").assertExists()
        back()
        compose.onNodeWithTag("player_play").assertExists()
        nav("search")
        compose.onNodeWithContentDescription("Очередь").performClick()
        back()
        compose.onNodeWithTag("player_play").assertExists()
    }
    @Test fun exitPromptExpiresAndNavigationRecreationAndBackgroundResetIt() {
        back(); compose.onNodeWithTag("exit_hint").assertExists()
        nav("library"); back()
        compose.onNodeWithTag("exit_hint").assertDoesNotExist()
        back(); compose.onNodeWithTag("exit_hint").assertExists()
        compose.mainClock.advanceTimeBy(2100)
        compose.onNodeWithTag("exit_hint").assertDoesNotExist()
        back(); compose.onNodeWithTag("exit_hint").assertExists()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("exit_hint").assertDoesNotExist()
        back()
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.onNodeWithTag("exit_hint").assertDoesNotExist()
        back(); compose.onNodeWithTag("exit_hint").assertExists()
        assertFalse(compose.activity.isFinishing)
    }
    @Test fun twoRootBackPressesFinishTheActivity() {
        val activity = compose.activity
        back(); compose.onNodeWithTag("exit_hint").assertExists()
        back()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        assertTrue(activity.isFinishing)
    }
}
