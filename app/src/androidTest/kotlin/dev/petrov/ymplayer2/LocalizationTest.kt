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
    private fun selectLanguage(tag: String) {
        compose.onNodeWithTag("language_list").performScrollToNode(hasTestTag("language_$tag"))
        compose.onNodeWithTag("language_$tag").performClick()
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
        val be = compose.onNodeWithTag("language_be").fetchSemanticsNode().boundsInRoot
        val en = compose.onNodeWithTag("language_en").fetchSemanticsNode().boundsInRoot
        assertTrue(auto.top < be.top && be.top < en.top)
        selectLanguage("en")
        compose.onNodeWithText("App language").assertIsDisplayed()
        compose.onNodeWithTag("language_en").assertIsSelected()
        compose.onNodeWithText("English (English)").assertIsDisplayed()
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
        assertEquals("en", AppLanguages.resolve("system", LocaleList.forLanguageTags("ja-JP,it-IT")))
        assertEquals("de", AppLanguages.resolve("system", LocaleList.forLanguageTags("de-DE")))
        assertEquals("ru", AppLanguages.resolve("system", LocaleList.forLanguageTags("ru-RU,en-US")))
        assertEquals("en", AppLanguages.resolve("en", LocaleList.forLanguageTags("ru-RU")))
        assertEquals("ru", AppLanguages.resolve("ru", LocaleList.forLanguageTags("en-US")))
        openLanguages()
        for (language in AppLanguages.available) {
            assertEquals(language.tag, AppLanguages.resolve("system", LocaleList.forLanguageTags("ja-JP,${language.tag}-ZZ")))
            selectLanguage(language.tag)
            compose.runOnIdle { AppLanguages.initialize(compose.activity, "fixture-language", "ru") }
            assertEquals(language.tag, AppLanguages.state.value.selected)
            compose.onNodeWithTag("language_${language.tag}").assertIsSelected()
            compose.onNodeWithText(language.label).assertIsDisplayed()
            capture("language-${language.tag}")
        }
        selectLanguage("system")
        assertEquals("system", AppLanguages.state.value.selected)
        compose.onNodeWithTag("language_system").assertIsSelected()
        assertEquals(listOf("Belarusian", "English", "French", "German", "Kazakh", "Russian", "Spanish", "Ukrainian"), AppLanguages.available.map { it.englishName })
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
        for (language in AppLanguages.available) {
            selectLanguage(language.tag)
            waitFor { AppLanguages.state.value.effective == language.tag }
            assertSame(activity, compose.activity)
            assertEquals(before.profileId, h.player.state.value.profileId)
            assertEquals(before.current?.id, h.player.state.value.current?.id)
            assertTrue(h.player.state.value.playing)
            assertTrue(h.player.state.value.positionSeconds >= before.positionSeconds)
            assertEquals(quality, h.audioQuality.state.value)
            assertEquals(cacheEnabled, h.offline.state.value.enabled)
        }
        selectLanguage("ru")
        compose.onNodeWithTag("language_list").performScrollToNode(hasText("Язык приложения"))
        compose.onNodeWithText("Язык приложения").assertIsDisplayed()
        assertTrue(h.player.state.value.playing)
    }
    @Test fun remoteSelectsLanguageAndAppMessagesKeepUserArgumentsIntact() {
        openLanguages()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_DOWN)
        compose.onNodeWithTag("language_en").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus) { it() }
        compose.onNodeWithTag("language_en").assertIsFocused().performKeyInput { pressKey(androidx.compose.ui.input.key.Key.DirectionCenter) }
        compose.onNodeWithTag("language_list").performScrollToNode(hasText("App language"))
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
                val next = mapOf("be" to "ДАЛЕЙ", "en" to "UP NEXT", "fr" to "À SUIVRE", "de" to "ALS NÄCHSTES", "kk" to "КЕЛЕСІ", "ru" to "ДАЛЕЕ", "es" to "A CONTINUACIÓN", "uk" to "ДАЛІ")
                for (language in AppLanguages.available) {
                    AppLanguages.select(language.tag); controls.refreshLanguage()
                    assertTrue("${language.tag}: next clip", labels(controls).contains(next.getValue(language.tag)))
                    assertTrue(labels(controls).contains("Повторить"))
                    assertTrue(labels(controls).contains("Основной"))
                    assertEquals(before, controller.state.value)
                }
            } finally { controller.close(); scope.coroutineContext[kotlinx.coroutines.Job]?.cancel() }
        }
    }

    @Test fun allPacksHandlePluralsAndCachedMessagesWithoutTranslatingArguments() {
        val counts = mapOf(
            "be" to listOf("0 трэкаў", "1 трэк", "2 трэкі", "5 трэкаў", "11 трэкаў", "21 трэк"),
            "en" to listOf("0 tracks", "1 track", "2 tracks", "5 tracks", "11 tracks", "21 tracks"),
            "fr" to listOf("0 titre", "1 titre", "2 titres", "5 titres", "11 titres", "21 titres"),
            "de" to listOf("0 Titel", "1 Titel", "2 Titel", "5 Titel", "11 Titel", "21 Titel"),
            "kk" to listOf("0 трек", "1 трек", "2 трек", "5 трек", "11 трек", "21 трек"),
            "ru" to listOf("0 треков", "1 трек", "2 трека", "5 треков", "11 треков", "21 трек"),
            "es" to listOf("0 canciones", "1 canción", "2 canciones", "5 canciones", "11 canciones", "21 canciones"),
            "uk" to listOf("0 треків", "1 трек", "2 треки", "5 треків", "11 треків", "21 трек")
        )
        val countKey = Msg.entries.first { it.source == "@0@ треков" }
        val addedKey = Msg.entries.first { it.source == "Трек добавлен в «@0@»." }
        val title = "Настройки · Retry @1@ \$title"
        val originalIssue = "Моя волна: Нет связи с Яндексом. Проверьте сеть и повторите вход. Повторим автоматически (2/3)."
        val originalChange = "Трек убран из плейлиста. Результат изменения не подтверждён. Проверьте плейлисты перед повтором."
        for (from in AppLanguages.available) {
            lateinit var cachedCount: String
            lateinit var cachedAdded: String
            lateinit var cachedIssue: String
            lateinit var cachedChange: String
            compose.runOnIdle {
                AppLanguages.select(from.tag)
                listOf(0, 1, 2, 5, 11, 21).forEachIndexed { index, count ->
                    assertEquals("${from.tag}/$count", counts.getValue(from.tag)[index], tr(countKey, count))
                }
                Msg.entries.forEach { key ->
                    val rendered = tr(key, 21, 2, 0)
                    assertTrue("${from.tag}/${key.name}", rendered.isNotBlank())
                    assertFalse(rendered.contains('\uFFFD'))
                    assertFalse("${from.tag}/${key.name}: unresolved argument", Regex("@\\d+(?:\\|track)?@").containsMatchIn(rendered))
                }
                cachedCount = tr(countKey, 21)
                cachedAdded = tr(addedKey, title)
                cachedIssue = trIssue(originalIssue)
                cachedChange = trIssue(originalChange)
            }
            for (to in AppLanguages.available) compose.runOnIdle {
                AppLanguages.select(to.tag)
                assertEquals("${from.tag}->${to.tag}: count", tr(countKey, 21), trMessage(cachedCount))
                assertEquals("${from.tag}->${to.tag}: metadata", tr(addedKey, title), trMessage(cachedAdded))
                assertEquals("${from.tag}->${to.tag}: wave retry", trIssue(originalIssue), trIssue(cachedIssue))
                assertEquals("${from.tag}->${to.tag}: uncertain edit", trIssue(originalChange), trIssue(cachedChange))
            }
        }
    }

    @Test fun remoteReachesLastLanguageAndLeavesSelectedRowVertically() {
        openLanguages()
        compose.onNodeWithTag("language_list").performScrollToNode(hasTestTag("language_uk"))
        compose.onNodeWithTag("language_uk").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus) { it() }
        compose.onNodeWithTag("language_uk").assertIsFocused().performKeyInput { pressKey(androidx.compose.ui.input.key.Key.DirectionCenter) }
        compose.onNodeWithTag("language_uk").assertIsSelected()
        assertEquals("Мова застосунку", trMessage("Язык приложения"))
        compose.onNodeWithTag("language_uk").performKeyInput { pressKey(androidx.compose.ui.input.key.Key.DirectionUp) }
        compose.onNodeWithTag("language_es").assertIsFocused()
        compose.onNodeWithTag("language_es").performKeyInput { pressKey(androidx.compose.ui.input.key.Key.DirectionDown) }
        compose.onNodeWithTag("language_uk").assertIsFocused()
    }

    @Test fun longTranslatedCacheSettingsRemainReachable() {
        compose.onNodeWithTag("settings").performClick()
        for (language in AppLanguages.available) {
            compose.runOnIdle { AppLanguages.select(language.tag) }
            compose.onNodeWithTag("settings_list").performScrollToNode(hasTestTag("settings_offline"))
            compose.onNodeWithTag("settings_offline").assertTextEquals(trMessage("Настройки офлайн-кэша")).performClick()
            compose.onNodeWithTag("offline_settings_list").performScrollToNode(hasTestTag("offline_enabled"))
            compose.onNodeWithTag("offline_enabled").assertIsDisplayed()
            compose.onNodeWithTag("offline_list").assertDoesNotExist()
            capture("cache-${language.tag}-large")
            compose.onNodeWithTag("offline_settings_list").performScrollToNode(hasTestTag("offline_sync"))
            compose.onNodeWithTag("offline_sync").assertIsDisplayed()
            compose.onNodeWithTag("offline_sync").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus) { it() }
            compose.onNodeWithTag("offline_sync").assertIsFocused()
            compose.onNodeWithTag("navigate_up").performClick()
            compose.onNodeWithTag("settings_list").assertIsDisplayed()
        }
    }
}
