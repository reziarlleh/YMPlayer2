@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
package dev.petrov.ymplayer2

import android.graphics.Rect
import android.view.View
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.AndroidComposeView
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.petrov.ymplayer2.designsystem.ChoiceRow
import dev.petrov.ymplayer2.designsystem.PrismTheme
import dev.petrov.ymplayer2.designsystem.prismFocus
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChoiceRowFocusTest {
    @get:Rule val compose = createComposeRule()

    @Test fun platformFocusBoundsAfterFocusedItemRemovalDoNotReenterChoiceRow() {
        val showChoices = mutableStateOf(false)
        lateinit var host: View
        compose.setContent {
            val view = LocalView.current
            SideEffect { host = view }
            PrismTheme(systemBars = false) {
                Column {
                    if (!showChoices.value) Button({}, Modifier.testTag("old_focus").prismFocus()) { Text("Before") }
                    else ChoiceRow("selected") { choice ->
                        Button({}, choice("selected").testTag("new_choice").prismFocus()) { Text("Selected") }
                    }
                }
            }
        }
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_DOWN)
        compose.onNodeWithTag("old_focus").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.onNodeWithTag("old_focus").assertIsFocused()
        compose.runOnIdle { showChoices.value = true }
        compose.runOnIdle {
            // Reproduce the platform transition directly. Android retains its host focus
            // while Compose has no active child. The owner overload is test-only.
            val owner = (host as AndroidComposeView).focusOwner
            assertTrue(owner.clearFocus(force = true, refreshFocusEvents = true,
                clearOwnerFocus = false, focusDirection = FocusDirection.Exit))
            assertTrue("The Android host retains focus after the Compose item is removed", host.isFocused)
            assertNull(owner.getFocusRect())
            host.getFocusedRect(Rect())
        }
        compose.onNodeWithTag("new_choice").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.onNodeWithTag("new_choice").assertIsFocused()
    }
}
