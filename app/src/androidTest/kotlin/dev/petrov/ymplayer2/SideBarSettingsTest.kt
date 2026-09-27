package dev.petrov.ymplayer2

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.petrov.ymplayer2.sidebar.SideBarButton
import dev.petrov.ymplayer2.sidebar.SideBarSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
            compose.onNodeWithTag("sidebar_settings").performScrollToNode(hasText("«Спрятать сайдбар» — всегда последняя кнопка и не отключается. Если все остальные кнопки выключены, SideBar отключается."))
            compose.onNodeWithText("«Спрятать сайдбар» — всегда последняя кнопка и не отключается. Если все остальные кнопки выключены, SideBar отключается.").assertExists()
            compose.onNodeWithTag("sidebar_settings").performScrollToNode(hasTestTag("sidebar_button_BACK"))
            compose.onNodeWithTag("sidebar_button_BACK").assertExists()
            compose.onNodeWithTag("sidebar_settings").performScrollToNode(hasTestTag("sidebar_button_MENU"))
            compose.onNodeWithTag("sidebar_button_MENU").assertExists()
            compose.onNodeWithTag("sidebar_settings").performScrollToNode(hasTestTag("sidebar_button_PLAY_PAUSE"))
            compose.onNodeWithTag("sidebar_button_PLAY_PAUSE").assertExists()
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

    @Test fun emptySelectionDisablesTheSidebar() {
        val settings = SideBarSettings(InstrumentationRegistry.getInstrumentation().targetContext)
        val before = settings.read()
        try {
            settings.setButtons(emptySet())
            settings.setEnabled(true)
            assertFalse(settings.read().enabled)
            assertEquals(emptySet<SideBarButton>(), settings.read().buttons)
        } finally {
            settings.setButtons(before.buttons)
            settings.setEnabled(before.enabled)
        }
    }
}
