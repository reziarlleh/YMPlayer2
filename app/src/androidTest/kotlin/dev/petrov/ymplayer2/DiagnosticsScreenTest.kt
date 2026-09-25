package dev.petrov.ymplayer2

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DiagnosticsScreenTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun journalOpensFromSettingsAndBackReturnsOneLevel() {
        compose.onNodeWithTag("settings").performClick()
        compose.onNodeWithTag("settings_list").performScrollToNode(hasTestTag("settings_diagnostics"))
        compose.onNodeWithTag("settings_diagnostics").performClick()
        compose.onNodeWithTag("diagnostics_list").assertExists()
        compose.onNodeWithTag("diagnostics_refresh").performClick()
        compose.onNodeWithTag("diagnostics_clear").performClick()
        compose.waitUntil(10000) {
            compose.onAllNodesWithText("Журнал очищен.").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("navigate_up").performClick()
        compose.onNodeWithTag("settings_list").assertExists()
    }
}
