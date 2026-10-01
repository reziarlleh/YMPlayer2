package dev.petrov.ymplayer2

import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.petrov.ymplayer2.core.DemoCatalog
import dev.petrov.ymplayer2.core.DemoPlaybackController
import dev.petrov.ymplayer2.shell.ShellApp
import dev.petrov.ymplayer2.shell.ShellModel
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AboutTest {
    @get:Rule val compose = createAndroidComposeRule<DemoActivity>()
    private val opened = mutableListOf<String>()
    private var noBrowser = false
    @Before fun prepare() {
        compose.runOnUiThread {
            compose.activity.setContent {
                val model = remember {
                    val catalog = DemoCatalog()
                    ShellModel(catalog, DemoPlaybackController(catalog), SavedStateHandle())
                }
                CompositionLocalProvider(LocalUriHandler provides object : UriHandler {
                    override fun openUri(uri: String) {
                        if (noBrowser) error("No browser fixture")
                        opened += uri
                    }
                }) { ShellApp(model, "2.1.3-about-fixture") }
            }
        }
    }
    private fun assertAbout() {
        compose.onNodeWithTag("about_dialog").assertIsDisplayed()
        compose.onNodeWithTag("about_version").assertTextEquals("2.1.3-about-fixture")
        compose.onNodeWithTag("about_qr").assertIsDisplayed()
    }
    private fun settingsAbout() {
        compose.onNodeWithTag("settings").performClick()
        compose.onNodeWithTag("settings_list").performScrollToNode(hasTestTag("settings_about"))
    }
    private fun screenshot(label: String) {
        if (InstrumentationRegistry.getArguments().getString("aboutScreenshots") != "true") return
        compose.waitForIdle()
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        java.io.File(compose.activity.getExternalFilesDir(null), "about-$label.png").outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }
    @Test fun headerAndSettingsUseSameWindowInDarkAndLightThemes() {
        compose.onNodeWithTag("about_logo").assertIsDisplayed().performClick()
        assertAbout(); screenshot("dark")
        compose.onNodeWithTag("about_close").performScrollTo().performClick()
        compose.onNodeWithTag("player_viewport").assertIsDisplayed()
        settingsAbout()
        compose.onNodeWithTag("settings_about").performClick()
        assertAbout()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        compose.onNodeWithTag("about_dialog").assertDoesNotExist()
        compose.onNodeWithTag("settings_list").performScrollToNode(hasTestTag("theme_light"))
        compose.onNodeWithTag("theme_light").performClick()
        compose.onNodeWithTag("about_logo").performClick()
        assertAbout(); screenshot("light")
        compose.onNodeWithTag("about_close").performScrollTo().performClick()
        compose.onNodeWithTag("navigate_up").performClick()
        compose.onNodeWithTag("player_viewport").assertIsDisplayed()
        screenshot("header-light")
    }
    @Test fun publishedLinksAndMissingBrowserRemainUsable() {
        compose.onNodeWithTag("about_logo").performClick()
        compose.onNodeWithTag("about_donate").performScrollTo().performClick()
        compose.onNodeWithTag("about_github").performScrollTo().performClick()
        assertEquals(listOf("https://donate.stream/donate_6a60559cd9e35", "https://github.com/reziarlleh/YMPlayer2"), opened)
        compose.runOnIdle { noBrowser = true }
        compose.onNodeWithTag("about_donate").performScrollTo().performClick()
        compose.onNodeWithTag("about_browser_issue").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("about_dialog").assertIsDisplayed()
    }
    @Test fun remoteOpensBothEntrancesAndClosesTheWindow() {
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_DOWN)
        fun focus(tag: String) {
            compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            compose.onNodeWithTag(tag).assertIsFocused()
        }
        focus("about_logo")
        compose.onNodeWithTag("about_logo").performKeyInput { pressKey(Key.DirectionCenter) }
        assertAbout()
        compose.onNodeWithTag("about_close").assertIsFocused().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.onNodeWithTag("about_dialog").assertDoesNotExist()
        settingsAbout()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_DOWN)
        focus("settings_about")
        compose.onNodeWithTag("settings_about").performKeyInput { pressKey(Key.DirectionCenter) }
        assertAbout()
        compose.onNodeWithTag("about_close").assertIsFocused().performKeyInput { pressKey(Key.DirectionLeft) }
        compose.onNodeWithTag("about_github").assertIsFocused().performKeyInput { pressKey(Key.DirectionUp) }
        compose.onNodeWithTag("about_donate").assertIsFocused()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        compose.onNodeWithTag("about_dialog").assertDoesNotExist()
    }
}

