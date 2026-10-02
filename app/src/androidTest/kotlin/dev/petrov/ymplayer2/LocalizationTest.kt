@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
package dev.petrov.ymplayer2

import android.os.LocaleList
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.petrov.ymplayer2.core.*
import dev.petrov.ymplayer2.localization.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import android.graphics.Bitmap

@RunWith(AndroidJUnit4::class)
class LocalizationTest {
    @get:Rule val compose = createAndroidComposeRule<OnlineTestActivity>()
    private val h get() = compose.activity.harness
    private fun waitFor(block: () -> Boolean) = compose.waitUntil(30000, block)
    @Before fun setup() {
        compose.runOnIdle { AppLanguages.select("ru") }
        waitFor { h.library.state.value.ready && h.player.state.value.connected }
    }
    @After fun finish() { compose.runOnIdle { h.player.stop(); AppLanguages.select("ru") } }
    private fun openLanguages() {
        compose.onNodeWithTag("settings").performClick()
        compose.onNodeWithTag("settings_list").performScrollToNode(hasTestTag("settings_language"))
        compose.onNodeWithTag("settings_language").performClick()
    }
    private fun capture(label: String) {
        if (InstrumentationRegistry.getArguments().getString("localizationScreenshots") != "true") return
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { bitmap ->
            File(compose.activity.getExternalFilesDir(null), "localization-$label.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }; bitmap.recycle()
        }
    }
    @Test fun switchUpdatesCurrentScreenAndKeepsOfflineSeparation() {
        openLanguages()
        val auto = compose.onNodeWithTag("language_system").fetchSemanticsNode().boundsInRoot
        val en = compose.onNodeWithTag("language_en").fetchSemanticsNode().boundsInRoot
        val ru = compose.onNodeWithTag("language_ru").fetchSemanticsNode().boundsInRoot
        assertTrue(auto.top < en.top && en.top < ru.top)
        compose.onNodeWithTag("language_en").performClick()
        compose.onNodeWithText("App language").assertIsDisplayed()
        compose.onNodeWithTag("language_en").assertIsSelected()
        compose.onNodeWithText("English (English)").assertIsDisplayed()
        compose.onNodeWithText("Russian (Русский)").assertIsDisplayed()
        capture("language-en")
        compose.onNodeWithTag("navigate_up").performClick()
        compose.onNodeWithTag("settings_list").assertIsDisplayed()
        compose.onNodeWithTag("manage_folders").assertDoesNotExist()
        compose.onNodeWithTag("settings_list").performScrollToNode(hasTestTag("settings_offline"))
        compose.onNodeWithTag("settings_offline").assertTextEquals("Offline cache settings").performClick()
        compose.onNodeWithTag("offline_settings_list").assertIsDisplayed()
        compose.onNodeWithTag("offline_list").assertDoesNotExist()
        compose.onNodeWithTag("offline_enabled").assertIsDisplayed()
        capture("cache-en")
        compose.onNodeWithTag("navigate_up").performClick()
        compose.onNodeWithTag("settings_list").assertIsDisplayed()
        compose.onNodeWithTag("nav_library").performClick()
        compose.onNodeWithTag("open_offline").performClick()
        compose.onNodeWithTag("offline_list").assertIsDisplayed()
        compose.onNodeWithTag("offline_enabled").assertDoesNotExist()
        compose.onNodeWithTag("offline_sync").assertDoesNotExist()
        capture("offline-en")
    }
    @Test fun choicePersistsAndAutoFollowsSupportedSystemLanguagesWithEnglishFallback() {
        assertEquals("en", AppLanguages.resolve("system", LocaleList.forLanguageTags("de-DE")))
        assertEquals("ru", AppLanguages.resolve("system", LocaleList.forLanguageTags("ru-RU,en-US")))
        assertEquals("en", AppLanguages.resolve("en", LocaleList.forLanguageTags("ru-RU")))
        assertEquals("ru", AppLanguages.resolve("ru", LocaleList.forLanguageTags("en-US")))
        openLanguages()
        compose.onNodeWithTag("language_en").performClick()
        compose.runOnIdle { AppLanguages.initialize(compose.activity, "fixture-language", "ru") }
        assertEquals("en", AppLanguages.state.value.selected)
        compose.onNodeWithText("App language").assertIsDisplayed()
        compose.onNodeWithTag("language_system").performClick()
        assertEquals("system", AppLanguages.state.value.selected)
        compose.onNodeWithTag("language_system").assertIsSelected()
        assertEquals(listOf("English", "Russian"), AppLanguages.available.map { it.englishName })
    }
    @Test fun liveAudioProfileAndSettingsSurviveLanguageSwitch() {
        compose.runOnIdle { h.player.stop(); h.player.switchProfile("road"); h.player.clearQueue() }
        runBlocking { h.library.state.value.roots.forEach { h.library.forgetFolder(it.uri) } }
        compose.activity.contentResolver.call(android.net.Uri.parse("content://dev.petrov.ymplayer2.test.control"), "fixtures", null, null)
        runBlocking { h.library.addFolder(TestMusicProvider.tree.toString(), Source.LOCAL) }
        waitFor { h.library.testTracks.size == 2 }
        compose.runOnIdle { h.player.playQueue(h.library.testTracks.map(Track::id)) }
        waitFor { h.player.state.value.playing && h.player.state.value.positionSeconds >= 1 }
        val before = h.player.state.value
        val quality = h.audioQuality.state.value
        val cacheEnabled = h.offline.state.value.enabled
        val activity = compose.activity
        openLanguages()
        compose.onNodeWithTag("language_en").performClick()
        waitFor { AppLanguages.state.value.effective == "en" }
        assertSame(activity, compose.activity)
        assertEquals(before.profileId, h.player.state.value.profileId)
        assertEquals(before.current?.id, h.player.state.value.current?.id)
        assertTrue(h.player.state.value.playing)
        assertTrue(h.player.state.value.positionSeconds >= before.positionSeconds)
        assertEquals(quality, h.audioQuality.state.value)
        assertEquals(cacheEnabled, h.offline.state.value.enabled)
        compose.onNodeWithTag("language_ru").performClick()
        compose.onNodeWithText("Язык приложения").assertIsDisplayed()
        assertTrue(h.player.state.value.playing)
    }
    @Test fun remoteSelectsLanguageAndAppMessagesKeepUserArgumentsIntact() {
        openLanguages()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_DOWN)
        compose.onNodeWithTag("language_en").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus) { it() }
        compose.onNodeWithTag("language_en").assertIsFocused().performKeyInput { pressKey(androidx.compose.ui.input.key.Key.DirectionCenter) }
        compose.onNodeWithText("App language").assertIsDisplayed()
        val title = "Повторить @1@ \$title"
        val key = Msg.entries.first { it.source == "Трек добавлен в «@0@»." }
        assertTrue(tr(key, title).contains(title))
        assertEquals("Retry", trMessage("Повторить"))
        assertEquals("Brand-new user title", trMessage("Brand-new user title"))
        assertEquals("Settings", trMessage("Настройки"))
        val issue = trIssue("Моя волна: Нет связи с Яндексом. Проверьте сеть и повторите вход.")
        assertTrue(issue.startsWith("My Vibe:"))
        assertFalse(issue.any { it in 'А'..'я' })
        compose.runOnIdle {
            AppLanguages.select("ru")
            assertEquals("Повторить", trMessage("Retry"))
            assertTrue(trMessage(tr(key, title)).contains(title))
        }
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        compose.onNodeWithTag("settings_list").assertIsDisplayed()
    }
    @Test fun nativeClipLabelsSwitchWithoutChangingTitlesOrController() {
        compose.runOnIdle {
            val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main)
            val player = androidx.media3.exoplayer.ExoPlayer.Builder(compose.activity).build()
            val controller = dev.petrov.ymplayer2.clips.ClipWaveController(
                dev.petrov.ymplayer2.yandex.YandexClipApi(h.auth), "owner", player, scope)
            try {
                val controls = dev.petrov.ymplayer2.clips.ClipControlsView(compose.activity, controller) { }
                fun labels(view: android.view.View): List<String> = when (view) {
                    is android.widget.TextView -> listOf(view.text.toString())
                    is android.view.ViewGroup -> (0 until view.childCount).flatMap { labels(view.getChildAt(it)) }
                    else -> emptyList()
                }
                val clip = dev.petrov.ymplayer2.yandex.YandexClip("1", "Повторить", "Основной", "", null, null, 0, emptyList(), "fixture")
                val state = dev.petrov.ymplayer2.clips.ClipWaveState(clip = clip, nextClip = clip.copy(id = "2"), loading = false)
                val before = controller.state.value
                controls.render(state)
                assertTrue(labels(controls).contains("ДАЛЕЕ"))
                AppLanguages.select("en"); controls.refreshLanguage()
                assertTrue(labels(controls).contains("UP NEXT"))
                assertTrue(labels(controls).contains("Повторить"))
                assertTrue(labels(controls).contains("Основной"))
                assertEquals(before, controller.state.value)
            } finally { controller.close(); scope.coroutineContext[kotlinx.coroutines.Job]?.cancel() }
        }
    }
}
