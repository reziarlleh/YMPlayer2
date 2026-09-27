package dev.petrov.ymplayer2

import android.content.res.Configuration
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.input.key.Key
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.petrov.ymplayer2.shell.ShellModel
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ShellInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<DemoActivity>()
    private fun model() = ViewModelProvider(compose.activity)[ShellModel::class.java]
    private fun nav(route: String) { compose.onNodeWithTag("nav_$route").performClick() }
    private fun state() = model().player.state.value
    private fun scrollTag(tag: String, list: String): SemanticsNodeInteraction {
        compose.onNodeWithTag(list).performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(tag)
    }

    @Test fun firstPlayAndMiniPlayerShareStateAcrossNavigationAndRecreation() {
        compose.onNodeWithTag("player_play").performClick()
        compose.runOnIdle { model().player.seek(73); assertTrue(state().playing) }
        nav("library")
        compose.onNodeWithTag("mini_play").assertContentDescriptionEquals("Пауза")
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("mini_play").assertContentDescriptionEquals("Пауза")
        compose.runOnIdle { assertEquals(73, state().positionSeconds) }
        compose.onNodeWithTag("mini_open").performClick()
        compose.onNodeWithTag("position").assertTextEquals("1:13")
        compose.onNodeWithTag("player_play").performClick()
        compose.runOnIdle { assertFalse(state().playing) }
    }

    @Test fun searchAndFiltersDoNotChangePlaybackOrLeakIntoLibrary() {
        compose.runOnIdle { model().player.toggle(); model().player.seek(91) }
        nav("search")
        compose.onNodeWithTag("search_input").performTextInput("невозможный запрос")
        compose.onNodeWithTag("search_input").performImeAction()
        compose.onNodeWithTag("catalog_list").performScrollToNode(hasText("Ничего не найдено"))
        compose.onNodeWithText("Ничего не найдено").assertIsDisplayed()
        nav("library")
        compose.onNodeWithTag("filter_USB").performScrollTo().performClick()
        scrollTag("track_usb:3", "catalog_list").assertIsDisplayed()
        compose.runOnIdle { assertEquals("yandex:1", state().current?.id); assertEquals(91, state().positionSeconds); assertTrue(state().playing) }
        nav("search")
        compose.onNodeWithTag("search_input").performScrollTo().assertTextContains("невозможный запрос")
    }

    @Test fun availableFilterHidesDisconnectedUsbWithoutChangingQueue() {
        nav("library")
        compose.onNodeWithTag("filter_USB").performScrollTo().performClick()
        scrollTag("track_usb:6", "catalog_list").assertIsDisplayed()
        compose.onNodeWithTag("filter_available").performScrollTo().performClick()
        scrollTag("track_usb:3", "catalog_list").assertIsDisplayed()
        compose.onNodeWithTag("track_usb:6").assertDoesNotExist()
        compose.runOnIdle { assertEquals("yandex:1", state().current?.id) }
    }

    @Test fun profilesStopAndRetainSeparatePositions() {
        compose.runOnIdle { model().player.toggle(); model().player.seek(54) }
        compose.onNodeWithTag("profiles").performClick()
        compose.onNodeWithTag("profile_guest").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("guest", state().profileId); assertFalse(state().playing) }
        compose.onNodeWithTag("profiles").performClick()
        compose.onNodeWithTag("profile_owner").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(54, state().positionSeconds); assertFalse(state().playing) }
    }

    @Test fun categoryDetailsBackAndQueueSelectionWork() {
        nav("library")
        compose.onNodeWithTag("category_ALBUMS").performScrollTo().performClick()
        compose.onNodeWithText("Город после заката").performScrollTo().performClick()
        compose.onNodeWithTag("track_yandex:2").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("yandex:2", state().current?.id); assertTrue(state().playing) }
        compose.onNodeWithContentDescription("К списку").performScrollTo().performClick()
        compose.onNodeWithTag("category_ALBUMS").assertIsDisplayed()
        compose.onNodeWithContentDescription("Очередь").performClick()
        compose.onNodeWithTag("track_usb:3").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("usb:3", state().current?.id) }
        scrollTag("track_usb:6", "queue_list").assertIsNotEnabled()
    }

    @Test fun errorCanBeRetriedAndThemeSurvivesRecreation() {
        compose.onNodeWithTag("settings").performClick()
        compose.onNodeWithTag("theme_light").performScrollTo().performClick()
        scrollTag("state_ERROR", "settings_list").performClick()
        nav("library")
        compose.onNodeWithText("Не удалось загрузить медиатеку").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Повторить").performScrollTo().performClick()
        scrollTag("track_yandex:1", "catalog_list").assertIsDisplayed()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("track_yandex:1").assertIsDisplayed()
        compose.onNodeWithTag("settings").performClick()
        scrollTag("theme_light", "settings_list").assertIsSelected()
    }

    @Test fun tvDpadFirstPressPlaysAndRightMovesToNext() {
        val tv = compose.activity.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK == Configuration.UI_MODE_TYPE_TELEVISION
        if (!tv) return
        compose.onNodeWithTag("player_play").assertIsFocused()
        compose.onNodeWithTag("player_play").performKeyInput { pressKey(Key.DirectionCenter) }
        compose.runOnIdle { assertTrue(state().playing) }
        compose.onNodeWithTag("player_play").performKeyInput { pressKey(Key.DirectionRight); pressKey(Key.DirectionCenter) }
        compose.runOnIdle { assertEquals("yandex:2", state().current?.id) }
    }
}
