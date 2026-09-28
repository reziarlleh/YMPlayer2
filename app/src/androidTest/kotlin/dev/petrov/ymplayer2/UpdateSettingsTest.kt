package dev.petrov.ymplayer2

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UpdateSettingsTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun updateSettingsKeepAutoCheckChoiceAndNavigateUp() {
        compose.onNodeWithTag("settings").performClick()
        compose.onNodeWithTag("settings_list").performScrollToNode(hasTestTag("settings_updates"))
        compose.onNodeWithTag("settings_updates").performClick()
        compose.onNodeWithTag("updates_auto").assertIsOn().performClick()
        compose.onNodeWithTag("updates_auto").assertIsOff()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("updates_auto").assertIsOff().performClick()
        compose.onNodeWithTag("updates_auto").assertIsOn()
        compose.onNodeWithTag("navigate_up").performClick()
        compose.onNodeWithTag("settings_updates").assertExists()
    }
}
