package dev.petrov.ymplayer2

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.petrov.ymplayer2.sidebar.SideBarButton
import dev.petrov.ymplayer2.sidebar.SideBarSettings
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SideBarSettingsTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun settingsKeepTheChosenButtonsAndBackGoesUpOneLevel() {
        val settings = SideBarSettings(InstrumentationRegistry.getInstrumentation().targetContext)
        val before = settings.read()
        try {
            compose.onNodeWithTag("settings").performClick()
            compose.onNodeWithTag("settings_list").performScrollToNode(hasTestTag("settings_sidebar"))
            compose.onNodeWithTag("settings_sidebar").performClick()
            compose.onNodeWithTag("sidebar_settings").assertExists()
            compose.onNodeWithTag("sidebar_settings").performScrollToNode(hasText("Свернуть — всегда последняя кнопка и не отключается."))
            compose.onNodeWithText("Свернуть — всегда последняя кнопка и не отключается.").assertExists()
            compose.onNodeWithTag("sidebar_settings").performScrollToNode(hasTestTag("sidebar_button_VOLUME_UP"))
            compose.onNodeWithTag("sidebar_button_VOLUME_UP").performClick()
            compose.waitForIdle()
            val expected = if (SideBarButton.VOLUME_UP in before.buttons) before.buttons - SideBarButton.VOLUME_UP
                else before.buttons + SideBarButton.VOLUME_UP
            assertEquals(expected, settings.read().buttons)
            compose.onNodeWithTag("navigate_up").performClick()
            compose.onNodeWithTag("settings_list").assertExists()
        } finally {
            settings.setButtons(before.buttons)
            settings.setAutoHide(before.autoHide)
        }
    }
}
