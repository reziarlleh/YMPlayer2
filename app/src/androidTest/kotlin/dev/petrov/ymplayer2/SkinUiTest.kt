package dev.petrov.ymplayer2

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SkinUiTest {
    @get:Rule val compose = createAndroidComposeRule<SkinTestActivity>()
    private fun node(tag: String): SemanticsNodeInteraction {
        compose.onNodeWithTag("skins_list").performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(tag)
    }
    private fun dpad(tag: String) {
        if (compose.activity.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_TYPE_MASK != android.content.res.Configuration.UI_MODE_TYPE_TELEVISION) {
            node(tag).performClick()
            return
        }
        node(tag).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus) { it() }
        compose.onNodeWithTag(tag).assertIsFocused().performKeyInput { pressKey(androidx.compose.ui.input.key.Key.DirectionCenter) }
    }
    @Test fun previewLightCancelApplyAndRestoreUseSeparateActions() {
        val repo = compose.activity.skins
        compose.waitUntil(10000) { repo.state.value.ready }
        runBlocking { repo.restore().join() }
        compose.onNodeWithTag("settings").performClick()
        compose.onNodeWithTag("settings_skins").performScrollTo().performClick()
        dpad("skin_choose_harbor")
        node("skin_preview_name").assertTextEquals("Предпросмотр: Гавань")
        assertEquals("prism", repo.state.value.active.id)
        node("skin_preview_light").performClick()
        compose.onNodeWithTag("skin_preview").assertExists()
        node("skin_cancel").performClick()
        compose.waitUntil(10000) { repo.state.value.preview == null }
        assertEquals("prism", repo.state.value.active.id)
        node("skin_choose_olive").performClick()
        dpad("skin_apply")
        compose.waitUntil(10000) { repo.state.value.active.id == "olive" }
        node("skin_active").assertTextEquals("Сейчас: Олива")
        node("skin_restore").performClick()
        compose.waitUntil(10000) { repo.state.value.active.id == "prism" }
        compose.onNodeWithTag("navigate_up").performClick()
        compose.onNodeWithTag("settings_skins").assertExists()
    }
}
