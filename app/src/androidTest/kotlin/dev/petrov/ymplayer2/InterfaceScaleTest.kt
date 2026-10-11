package dev.petrov.ymplayer2

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.input.key.Key
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.petrov.ymplayer2.designsystem.InterfaceScalePreferences
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InterfaceScaleTest {
    @get:Rule val compose = createAndroidComposeRule<DemoActivity>()
    private val preferences get() = InterfaceScalePreferences(compose.activity)
    private var original = 100
    @Before fun setup() { original = preferences.read(); compose.runOnIdle { preferences.save(100) } }
    @After fun restore() { compose.runOnIdle { preferences.save(original) } }
    private fun editor() {
        compose.onNodeWithTag("settings").performClick()
        compose.onNodeWithTag("settings_scale").performScrollTo().performClick()
        compose.onNodeWithTag("scale_value").assertTextEquals("100%")
    }
    @Test fun liveChangeUsesOneActivityAndResetRestoresPhysicalSize() {
        val activity = compose.activity
        val originalWidth = compose.onNodeWithTag("profiles").fetchSemanticsNode().boundsInRoot.width
        editor()
        val previewWidth = compose.onNodeWithTag("scale_preview_play").performScrollTo().fetchSemanticsNode().boundsInRoot.width
        val editorWidth = compose.onNodeWithTag("scale_reset").performScrollTo().fetchSemanticsNode().boundsInRoot.width
        compose.onNodeWithTag("scale_slider").performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(150f) }
        compose.onNodeWithTag("scale_value").assertTextEquals("150%")
        assertEquals(150, preferences.read())
        assertSame(activity, compose.activity)
        val largeWidth = compose.onNodeWithTag("scale_preview_play").performScrollTo().fetchSemanticsNode().boundsInRoot.width
        assertEquals(previewWidth * 1.5f, largeWidth, 2f)
        assertEquals("Editor navigation stays usable at every scale", originalWidth,
            compose.onNodeWithTag("profiles").fetchSemanticsNode().boundsInRoot.width, 2f)
        assertEquals("Scale editor must not move under a dragging finger", editorWidth,
            compose.onNodeWithTag("scale_reset").performScrollTo().fetchSemanticsNode().boundsInRoot.width, 2f)
        compose.onNodeWithTag("scale_reset").performScrollTo().performClick()
        compose.onNodeWithTag("scale_value").assertTextEquals("100%")
        assertEquals(originalWidth, compose.onNodeWithTag("profiles").fetchSemanticsNode().boundsInRoot.width, 2f)
    }
    @Test fun persistedScaleSurvivesRecreationAndBackReturnsToSettings() {
        editor()
        repeat(3) { compose.onNodeWithTag("scale_increase").performClick() }
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("scale_value").assertTextEquals("130%")
        assertEquals(130, preferences.read())
        compose.onNodeWithTag("navigate_up").performClick()
        compose.onNodeWithTag("settings_scale").assertExists()
    }
    @Test fun remoteHorizontalAdjustsScaleAndVerticalLeavesSlider() {
        editor()
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_DOWN)
        val slider = compose.onNodeWithTag("scale_slider")
        slider.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        slider.assertIsFocused().performKeyInput { pressKey(Key.DirectionRight) }
        compose.onNodeWithTag("scale_value").assertTextEquals("110%")
        slider.performKeyInput { pressKey(Key.DirectionDown) }
        slider.assertIsNotFocused()
        assertEquals(110, preferences.read())
    }
    @Test fun controlsRemainInsideViewportAcrossScaleRange() {
        compose.onNodeWithTag("player_play").performClick()
        for (percent in listOf(50, 100, 150, 200)) {
            compose.runOnIdle { preferences.save(percent) }
            val viewport = compose.onNodeWithTag("player_viewport").fetchSemanticsNode().boundsInRoot
            for (tag in listOf("player_play", "player_previous", "player_next", "player_stop", "progress", "current_title", "player_queue", "player_equalizer")) {
                val bounds = compose.onNodeWithTag(tag).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                assertTrue("$percent%: $tag $bounds outside $viewport", bounds.left >= viewport.left - 1 && bounds.right <= viewport.right + 1 && bounds.top >= viewport.top - 1 && bounds.bottom <= viewport.bottom + 1)
            }
            for (route in listOf("player", "library", "search", "radio", "clips")) compose.onNodeWithTag("nav_$route").assertIsDisplayed()
            compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollBy)).assertCountEquals(0)
        }
    }
    @Test fun invalidValuesAreBoundedAndNativeControlsKeepSystemFontSetting() {
        assertEquals(50, InterfaceScalePreferences.normalize(Int.MIN_VALUE))
        assertEquals(200, InterfaceScalePreferences.normalize(Int.MAX_VALUE))
        compose.runOnIdle { preferences.save(130) }
        val context = preferences.controlsContext(compose.activity)
        assertEquals(compose.activity.resources.displayMetrics.density * 1.3f, context.resources.displayMetrics.density, .015f)
        assertEquals(compose.activity.resources.configuration.fontScale, context.resources.configuration.fontScale, .001f)
        assertEquals(compose.activity.resources.configuration.densityDpi, compose.activity.resources.displayMetrics.densityDpi)
    }
}
