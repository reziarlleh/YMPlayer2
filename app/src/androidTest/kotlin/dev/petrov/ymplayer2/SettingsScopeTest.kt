@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
package dev.petrov.ymplayer2

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.petrov.ymplayer2.core.Profile
import dev.petrov.ymplayer2.designsystem.PrismTheme
import dev.petrov.ymplayer2.shell.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.compose.runtime.*
import dev.petrov.ymplayer2.core.PlaybackStartMode

@RunWith(AndroidJUnit4::class)
class SettingsScopeTest {
    @get:Rule val compose = createComposeRule()
    @Test fun generalSettingsHaveAboutFirstUpdatesAndDiagnosticsLastAndNoPersonalControls() {
        val actions = mutableListOf<String>()
        compose.setContent { PrismTheme {
            SettingsScreen("fixture", CatalogState.READY, {}, demo = false, offline = { actions += "offline" }, quality = { actions += "quality" },
                diagnostics = { actions += "diagnostics" }, sideBar = { actions += "sidebar" }, updates = { actions += "updates" }, about = { actions += "about" })
        } }
        val about = compose.onNodeWithTag("settings_about").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val quality = compose.onNodeWithTag("settings_quality").fetchSemanticsNode().boundsInRoot
        assertTrue(about.top < quality.top)
        for (tag in listOf("settings_language", "settings_scale", "settings_skins", "theme_dark", "profile_cache")) compose.onNodeWithTag(tag).assertDoesNotExist()
        compose.onNodeWithTag("settings_list").performScrollToNode(hasTestTag("settings_diagnostics"))
        val updates = compose.onNodeWithTag("settings_updates").fetchSemanticsNode().boundsInRoot
        val diagnostics = compose.onNodeWithTag("settings_diagnostics").fetchSemanticsNode().boundsInRoot
        assertTrue(updates.top < diagnostics.top)
        compose.onNodeWithTag("settings_diagnostics").performClick()
        assertEquals(listOf("diagnostics"), actions)
    }
    @Test fun personalSettingsUseTheCurrentProfileAndHaveNoGlobalCacheSwitch() {
        val actions = mutableListOf<String>()
        compose.setContent { PrismTheme {
            ProfileSettingsScreen(Profile("fixture-user", "User-owned name", ""), "system", { actions += it }, { actions += "skins" },
                { actions += "language" }, { actions += "scale" }, { actions += "cache" })
        } }
        compose.onNodeWithText("User-owned name").assertIsDisplayed()
        compose.onNodeWithTag("settings_language").performClick()
        assertEquals(listOf("language"), actions)
        compose.onNodeWithTag("profile_settings_list").performScrollToNode(hasTestTag("profile_cache"))
        compose.onNodeWithTag("profile_cache").performClick()
        assertEquals(listOf("language", "cache"), actions)
        for (tag in listOf("offline_enabled", "offline_wifi", "settings_updates", "settings_diagnostics", "settings_about")) compose.onNodeWithTag(tag).assertDoesNotExist()
    }
    @Test fun playbackStartChoicesAreInProfileSettingsAndSelectionIsApplied() {
        var selected by mutableStateOf(PlaybackStartMode.AUTO)
        compose.setContent { PrismTheme {
            ProfileSettingsScreen(Profile("fixture-user", "User", ""), "system", {}, null, {}, {}, null,
                startMode = selected, setStartMode = { selected = it })
        } }
        compose.onNodeWithTag("profile_playback_start").performClick()
        compose.onNodeWithTag("startup_mode_AUTO").assertIsSelected()
        compose.onNodeWithTag("startup_mode_OFF").assertIsDisplayed()
        compose.onNodeWithTag("startup_mode_ON").performClick()
        compose.runOnIdle { assertEquals(PlaybackStartMode.ON, selected) }
        compose.onNodeWithTag("profile_playback_start").performClick()
        compose.onNodeWithTag("startup_mode_ON").assertIsSelected()
    }
}
