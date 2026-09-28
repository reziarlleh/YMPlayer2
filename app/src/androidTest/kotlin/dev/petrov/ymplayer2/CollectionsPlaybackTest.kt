package dev.petrov.ymplayer2

import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.petrov.ymplayer2.core.*
import dev.petrov.ymplayer2.library.LocalCollections
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class CollectionsPlaybackTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val graph get() = compose.activity.application as PlayerApplication
    private val store get() = graph.collections
    private val library get() = graph.library
    private val player get() = graph.playback
    private fun waitFor(condition: () -> Boolean) = compose.waitUntil(15000, condition)
    private fun provider(method: String, arg: String? = null) = graph.contentResolver.call(Uri.parse("content://dev.petrov.ymplayer2.test.control"), method, arg, null)
    private fun edit(change: CollectionEdit, profile: String = "owner") { assertTrue(runBlocking { store.edit(profile, change) }) }
    private fun data(profile: String = "owner") = store.state.value.profile(profile)
    private fun id(title: String) = library.state.value.tracks.single { it.title == title }.id
    private fun goCollections(favorites: Boolean = false) {
        compose.onNodeWithTag("nav_library").performClick()
        val tag = if (favorites) "category_FAVORITES" else "category_PLAYLISTS"
        // The category row may be recycled while a large-font catalog is scrolled to a track.
        compose.onNodeWithTag("catalog_list").performScrollToNode(hasTestTag(tag))
        compose.onNodeWithTag(tag).performScrollTo().performClick()
    }
    @Before fun fixtures() {
        waitFor { store.state.value.ready && library.state.value.ready && !library.state.value.scanning && player.state.value.connected }
        compose.runOnIdle { player.stop(); player.switchProfile("owner"); player.chooseSource(null); player.setRepeatMode(RepeatMode.OFF); player.setShuffle(false) }
        runBlocking { library.state.value.roots.forEach { library.forgetFolder(it.uri) } }
        provider("fixtures")
        runBlocking { library.addFolder(TestMusicProvider.tree.toString(), Source.LOCAL) }
        waitFor { player.state.value.queue.size == 2 }
        library.profiles.forEach { profile ->
            data(profile.id).playlists.forEach { edit(CollectionEdit.Delete(it.id), profile.id) }
            data(profile.id).favorites.forEach { edit(CollectionEdit.Favorite(it.id, false), profile.id) }
        }
    }
    @After fun stop() { compose.runOnIdle { player.stop() }; provider("unavailable", "false") }

    @Test fun backClosesDialogThenAscendsPlaylistAndResetsUnrelatedCatalogDetail() {
        edit(CollectionEdit.Create("Навигация", id("one")))
        val list = data().playlists.single().id
        goCollections()
        compose.onNodeWithTag("playlist_$list").performClick()
        compose.onNodeWithTag("playlist_add_tracks").performClick()
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(200, 3000)
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        compose.waitUntil(5000) { compose.onAllNodesWithTag("playlist_picker_done").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("playlist_picker_done").assertDoesNotExist()
        compose.onNodeWithTag("collection_play").assertExists()
        compose.onNodeWithTag("navigate_up").performClick()
        compose.onNodeWithTag("playlist_create").assertExists()
        compose.onNodeWithTag("navigate_up").performClick()
        compose.onNodeWithTag("category_ALBUMS").performScrollTo().performClick()
        compose.onNodeWithText("Без альбома").performScrollTo().performClick()
        compose.onNodeWithTag("settings").performClick()
        compose.onNodeWithTag("settings_list").performScrollToNode(hasTestTag("manage_folders"))
        compose.onNodeWithTag("manage_folders").performScrollTo().performClick()
        compose.onNodeWithTag("navigate_up").performClick()
        compose.onNodeWithTag("category_ALBUMS").assertExists()
        compose.onNodeWithTag("navigate_up").performClick()
        compose.onNodeWithTag("player_play").assertExists()
    }

    @Test fun nativeUiCreatesEditsAndPlaysOnlyTheChosenPlaylist() {
        val one = id("one"); val two = id("two")
        goCollections()
        compose.onNodeWithTag("playlist_create").performClick()
        compose.onNodeWithTag("playlist_save").assertIsNotEnabled()
        compose.onNodeWithTag("playlist_name").performTextInput("В дорогу")
        compose.onNodeWithTag("playlist_save").performClick()
        waitFor { data().playlists.size == 1 }
        val list = data().playlists.single().id
        compose.onNodeWithTag("playlist_$list").performClick()
        compose.onNodeWithTag("collection_play").assertIsNotEnabled()
        compose.onNodeWithTag("playlist_add_tracks").performClick()
        compose.onNodeWithTag("playlist_pick_$one").performClick()
        waitFor { data().playlists.single().tracks.size == 1 }
        compose.onNodeWithTag("playlist_pick_$one").assertIsNotEnabled()
        compose.onNodeWithTag("playlist_pick_$two").performClick()
        waitFor { data().playlists.single().tracks.size == 2 }
        compose.onNodeWithTag("playlist_picker_done").performClick()
        compose.onNodeWithTag("playlist_edit").performClick()
        compose.onNodeWithTag("playlist_down_$one").performScrollTo().performClick()
        waitFor { data().playlists.single().tracks.first().id == two }
        compose.onNodeWithTag("playlist_remove_$one").performScrollTo().performClick()
        waitFor { data().playlists.single().tracks.size == 1 }
        compose.onNodeWithTag("collection_play").performClick()
        waitFor { player.state.value.playing && player.state.value.positionSeconds >= 1 }
        assertEquals(listOf(two), player.state.value.queue.map { it.id })
        compose.onNodeWithContentDescription("К плейлистам").performClick()
        compose.onNodeWithTag("playlist_rename_$list").performClick()
        compose.onNodeWithTag("playlist_name").performTextReplacement("Ночная дорога")
        compose.onNodeWithTag("playlist_save").performClick()
        waitFor { data().playlists.single().name == "Ночная дорога" }
        compose.onNodeWithTag("playlist_delete_$list").performClick()
        compose.onNodeWithTag("playlist_delete_confirm").performClick()
        waitFor { data().playlists.isEmpty() }
        assertEquals(two, player.state.value.current?.id); assertTrue(player.state.value.playing)
        assertEquals(2, library.state.value.tracks.size)
    }
    @Test fun favoriteMenuPersistsAndProfilesRemainSeparate() {
        val one = id("one")
        compose.onNodeWithTag("nav_library").performClick()
        compose.onNodeWithTag("catalog_list").performScrollToNode(hasTestTag("track_more_$one"))
        compose.onNodeWithTag("track_more_$one").performClick()
        compose.onNodeWithTag("favorite_toggle").performClick()
        waitFor { data().favorites.size == 1 }
        assertFalse(player.state.value.playing)
        goCollections(true)
        compose.onNodeWithTag("track_$one").assertIsDisplayed()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("track_$one").assertIsDisplayed()
        edit(CollectionEdit.Create("Владелец", one))
        edit(CollectionEdit.Create("Гость", id("two")), "guest")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        try {
            val restored = LocalCollections(graph, library, scope)
            waitFor { restored.state.value.ready }
            assertEquals(data(), restored.state.value.profile("owner"))
            assertEquals(data("guest"), restored.state.value.profile("guest"))
        } finally { scope.cancel() }
        compose.onNodeWithTag("profiles").performClick()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("profile_guest"))
        compose.onNodeWithTag("profile_guest").performScrollTo().performClick()
        goCollections(true)
        compose.onNodeWithTag("track_$one").assertDoesNotExist()
        assertTrue(data("guest").favorites.isEmpty())
    }
    @Test fun editsResolveOnlyRequestedIdsWithoutEnumeratingLibrary() {
        val context = graph.createDeviceProtectedStorageContext()
        val file = File(context.filesDir, "user-collections.json")
        file.delete()
        val indexed = object : IndexedLocalLibrary by library {
            override fun tracks(profileId: String): List<Track> = error("Full catalog enumeration is forbidden for collection edits")
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val collections = LocalCollections(context, indexed, scope)
            waitFor { collections.state.value.ready }
            val one = id("one"); val two = id("two")
            assertTrue(runBlocking { collections.edit("owner", CollectionEdit.Create("Адресно", one)) })
            val list = collections.state.value.profile("owner").playlists.single().id
            assertTrue(runBlocking { collections.edit("owner", CollectionEdit.Add(list, two)) })
            assertTrue(runBlocking { collections.edit("owner", CollectionEdit.Favorite(one, true)) })
            assertEquals(listOf(one, two), collections.state.value.profile("owner").playlists.single().tracks.map(SavedTrack::id))
            assertEquals(one, collections.state.value.profile("owner").favorites.single().id)
        } finally { scope.cancel(); file.delete() }
    }
    @Test fun metadataEditsDoNotInterruptAudioAndUnavailableReferencesRecover() {
        val one = id("one")
        compose.runOnIdle { player.select(one); player.seek(5) }
        waitFor { player.state.value.positionSeconds >= 6 }
        val queue = player.state.value.queue
        edit(CollectionEdit.Create("Проверка", one)); edit(CollectionEdit.Favorite(one, true))
        val list = data().playlists.single().id
        edit(CollectionEdit.Rename(list, "Новое имя")); edit(CollectionEdit.Add(list, id("two")))
        waitFor { player.state.value.positionSeconds >= 7 }
        assertTrue(player.state.value.playing); assertEquals(queue, player.state.value.queue)
        provider("unavailable", "true"); runBlocking { library.refresh() }
        assertEquals(2, data().playlists.single().tracks.size)
        assertFalse(data().favorites.single().resolve(library.state.value.tracks.associateBy(Track::id)).available)
        runBlocking { library.forgetFolder(TestMusicProvider.tree.toString()) }
        assertEquals("one", data().favorites.single().resolve(emptyMap()).title)
        provider("unavailable", "false"); provider("grant")
        runBlocking { library.addFolder(TestMusicProvider.tree.toString(), Source.LOCAL) }
        assertTrue(data().favorites.single().resolve(library.state.value.tracks.associateBy(Track::id)).available)
        assertEquals(2, library.state.value.tracks.size)
    }
    @Test fun failedWriteAndUnknownSchemaDoNotOverwriteSavedCollections() {
        edit(CollectionEdit.Create("Сохранённый", id("one")))
        val file = File(graph.filesDir, "user-collections.json")
        val original = file.readBytes(); val before = data()
        // API 29 writes via .bak, newer AtomicFile uses .new; deny writes to both paths.
        assertTrue(file.setWritable(false, true))
        assertTrue(graph.filesDir.setWritable(false, true))
        try {
            assertFalse(runBlocking { store.edit("owner", CollectionEdit.Rename(before.playlists.single().id, "Потерянный")) })
            assertEquals(before, data()); assertArrayEquals(original, file.readBytes())
        } finally { assertTrue(graph.filesDir.setWritable(true, true)); assertTrue(file.setWritable(true, true)) }
        try {
            file.writeText("{\"schema\":99,\"profiles\":{}}")
            runBlocking { store.reload() }
            assertFalse(store.state.value.writable)
            assertFalse(runBlocking { store.edit("owner", CollectionEdit.Create("Не записывать")) })
            assertEquals("{\"schema\":99,\"profiles\":{}}", file.readText())
        } finally { file.writeBytes(original); runBlocking { store.reload() } }
        assertEquals(before, data()); assertTrue(store.state.value.writable)
    }
}
