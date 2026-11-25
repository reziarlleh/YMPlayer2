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
    private val owner = UserProfiles.LOCAL_PROFILE_ID
    private lateinit var secondary: String
    private fun edit(change: CollectionEdit, profile: String = owner) { assertTrue(runBlocking { store.edit(profile, change) }) }
    private fun data(profile: String = owner) = store.state.value.profile(profile)
    private fun id(title: String) = library.testTracks.single { it.title == title }.id
    private fun goCollections(favorites: Boolean = false) {
        compose.onNodeWithTag("nav_library").performClick()
        compose.onNodeWithTag("source_local").performClick()
        compose.onNodeWithTag("category_PLAYLISTS").performClick()
        if (favorites) compose.onNodeWithTag("category_FAVORITES").performClick()
    }
    private fun exists(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    @Before fun fixtures() {
        compose.onNodeWithTag("nav_player").performClick()
        waitFor { store.state.value.ready && library.state.value.ready && !library.state.value.scanning && player.state.value.connected }
        secondary = runBlocking { library.profiles.firstOrNull { it.name == "Playlist QA" } ?: graph.users.create("Playlist QA") }.id
        compose.runOnIdle { player.stop(); player.switchProfile(owner) }
        waitFor { player.state.value.profileId == owner }
        compose.runOnIdle { player.chooseSource(null); player.setRepeatMode(RepeatMode.OFF); player.setShuffle(false) }
        runBlocking { library.state.value.roots.forEach { library.forgetFolder(it.uri) } }
        provider("fixtures")
        runBlocking { library.addFolder(TestMusicProvider.tree.toString(), Source.LOCAL) }
        waitFor { library.testTracks.size == 2 }
        listOf(owner, secondary).forEach { profile ->
            data(profile).playlists.forEach { edit(CollectionEdit.Delete(it.id), profile) }
            data(profile).favorites.forEach { edit(CollectionEdit.Favorite(it.id, false), profile) }
        }
    }
    @After fun stop() { compose.runOnIdle { player.stop() }; provider("unavailable", "false") }

    @Test fun backClosesPickerThenAscendsPlaylistAndFolderManagement() {
        edit(CollectionEdit.Create("Навигация", id("one")))
        goCollections()
        compose.onNodeWithTag("playlist_${data().playlists.single().id}").performClick()
        compose.onNodeWithTag("playlist_add_tracks").performClick()
        compose.onNodeWithTag("local_file_list").assertExists()
        compose.onNodeWithTag("navigate_up").performClick()
        compose.onNodeWithTag("collection_play").assertExists()
        compose.onNodeWithTag("navigate_up").performClick()
        compose.onNodeWithTag("playlist_create").assertExists()
        compose.onNodeWithTag("library_folders").performClick()
        compose.onNodeWithTag("local_file_list").performScrollToNode(hasTestTag("manage_folders"))
        compose.onNodeWithTag("manage_folders").performClick()
        compose.onNodeWithTag("navigate_up").performClick()
        compose.onNodeWithTag("category_PLAYLISTS").assertExists()
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
        val rootUri = runBlocking { graph.mediaFiles.roots().single().uri }
        waitFor { exists("check_$rootUri") }
        compose.onNodeWithTag("check_$rootUri").performClick()
        compose.waitUntil(5000) { compose.onAllNodes(hasTestTag("bulk_playlist") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("bulk_playlist").performClick()
        waitFor { data().playlists.single().tracks.size == 2 }
        compose.onNodeWithTag("collection_play").performClick()
        waitFor { player.state.value.playing && player.state.value.positionSeconds >= 1 }
        compose.onNodeWithTag("playlist_edit").performClick()
        compose.onNodeWithTag("playlist_handle_$two").performScrollTo().performClick()
        compose.onNodeWithTag("playlist_move_start").performClick()
        waitFor { data().playlists.single().tracks.first().id == two }
        compose.onNodeWithTag("playlist_undo_order").performScrollTo().performClick()
        waitFor { data().playlists.single().tracks.first().id == one }
        assertEquals(one, player.state.value.current?.id)
        assertTrue(player.state.value.playing)
        compose.onNodeWithTag("track_$one").performScrollTo().performClick()
        compose.onNodeWithTag("playlist_selection_menu").performClick()
        compose.onNodeWithTag("playlist_remove_selection").performClick()
        waitFor { data().playlists.single().tracks.size == 1 }
        compose.onNodeWithTag("collection_play").performClick()
        waitFor { player.state.value.current?.id == two }
        assertEquals(listOf(two), player.state.value.queue.map { it.id })
        compose.onNodeWithTag("navigate_up").performClick()
        compose.onNodeWithTag("navigate_up").performClick()
        compose.onNodeWithTag("playlist_rename_$list").performClick()
        compose.onNodeWithTag("playlist_name").performTextReplacement("Ночная дорога")
        compose.onNodeWithTag("playlist_save").performClick()
        waitFor { data().playlists.single().name == "Ночная дорога" }
        compose.onNodeWithTag("playlist_delete_$list").performClick()
        compose.onNodeWithTag("playlist_delete_confirm").performClick()
        waitFor { data().playlists.isEmpty() }
        assertEquals(two, player.state.value.current?.id); assertTrue(player.state.value.playing)
        assertEquals(2, library.testTracks.size)
    }

    @Test fun favoriteMenuPersistsAndProfilesRemainSeparate() {
        val one = id("one")
        compose.onNodeWithTag("nav_library").performClick()
        compose.onNodeWithTag("source_local").performClick()
        compose.onNodeWithTag("library_folders").performClick()
        val root = runBlocking { graph.mediaFiles.roots().single() }
        compose.onNodeWithTag("file_${root.uri}").performClick()
        val file = runBlocking { graph.mediaFiles.children(root).single { it.trackId == one } }
        compose.onNodeWithTag("bulk_start").performClick()
        compose.onNodeWithTag("check_${file.uri}").performClick()
        compose.onNodeWithTag("bulk_playlist").performClick()
        compose.onNodeWithTag("bulk_favorites").performClick()
        waitFor { data().favorites.size == 1 }
        assertFalse(player.state.value.playing)
        goCollections(true)
        compose.onNodeWithTag("track_$one").assertIsDisplayed()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("track_$one").assertIsDisplayed()
        edit(CollectionEdit.Create("Владелец", one))
        edit(CollectionEdit.Create("Второй профиль", id("two")), secondary)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        try {
            val restored = LocalCollections(graph, library, scope)
            waitFor { restored.state.value.ready }
            assertEquals(data(), restored.state.value.profile(owner))
            assertEquals(data(secondary), restored.state.value.profile(secondary))
        } finally { scope.cancel() }
        compose.runOnIdle { player.switchProfile(secondary) }
        waitFor { player.state.value.profileId == secondary }
        goCollections(true)
        compose.onNodeWithTag("track_$one").assertDoesNotExist()
        assertTrue(data(secondary).favorites.isEmpty())
    }
    @Test fun moveElevenOf150BeforeFifthTrackWithRemoteAndUndoWhilePlaying() {
        provider("bulk", "150")
        runBlocking { library.refresh() }
        val music = library.testTracks.filter { it.title.startsWith("bulk-") }.sortedBy(Track::title)
        assertEquals(150, music.size)
        val ids = music.map(Track::id)
        val playing = id("one")
        edit(CollectionEdit.CreateMany("150", ids))
        compose.runOnIdle { player.playList(listOf(playing), origin = PlaybackOrigin(PlaybackSource.LIST)); player.setRepeatMode(RepeatMode.ONE) }
        waitFor { player.state.value.current?.id == playing && player.state.value.positionSeconds >= 1 }
        goCollections()
        compose.onNodeWithTag("playlist_${data().playlists.single().id}").performClick()
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_DOWN)
        fun activate(tag: String) {
            val node = compose.onNodeWithTag(tag)
            node.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus) { it() }
            node.assertIsFocused().performKeyInput { pressKey(androidx.compose.ui.input.key.Key.DirectionCenter) }
        }
        activate("playlist_edit")
        activate("playlist_selection_menu")
        activate("playlist_range")
        for (i in listOf(119, 129)) {
            compose.onNodeWithTag("collection_tracks").performScrollToIndex(i)
            activate("select_${ids[i]}")
        }
        activate("playlist_move")
        activate("playlist_move_before")
        compose.onNodeWithTag("playlist_find_destination").performTextInput(music[4].title)
        activate("playlist_before_${ids[4]}")
        val group = ids.subList(119, 130)
        val expected = ids.take(4) + group + ids.drop(4).filterNot { it in group }
        waitFor { data().playlists.single().tracks.map(SavedTrack::id) == expected }
        assertEquals(playing, player.state.value.current?.id)
        assertTrue(player.state.value.playing)
        activate("playlist_undo_order")
        waitFor { data().playlists.single().tracks.map(SavedTrack::id) == ids }
        assertEquals(listOf(playing), runBlocking { player.queuePage(0).items.map(Track::id) })
    }

    @Test fun editsResolveOnlyRequestedIdsWithoutEnumeratingLibrary() {
        val isolated = File(graph.cacheDir, "playlist-edit-test").apply { mkdirs() }
        val context = object : android.content.ContextWrapper(graph) { override fun getFilesDir(): File = isolated }
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
            assertTrue(runBlocking { collections.edit(owner, CollectionEdit.Create("Адресно", one)) })
            val list = collections.state.value.profile(owner).playlists.single().id
            assertTrue(runBlocking { collections.edit(owner, CollectionEdit.Add(list, two)) })
            assertTrue(runBlocking { collections.edit(owner, CollectionEdit.Favorite(one, true)) })
            assertEquals(listOf(one, two), collections.state.value.profile(owner).playlists.single().tracks.map(SavedTrack::id))
            assertEquals(one, collections.state.value.profile(owner).favorites.single().id)
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
        goCollections()
        compose.onNodeWithTag("playlist_$list").performClick()
        compose.onNodeWithTag("collection_play").assertIsEnabled()
        provider("unavailable", "true"); runBlocking { library.refresh() }
        waitFor { compose.onAllNodes(hasTestTag("collection_play") and isNotEnabled()).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(2, data().playlists.single().tracks.size)
        assertFalse(data().favorites.single().resolve(library.testTracks.associateBy(Track::id)).available)
        runBlocking { library.forgetFolder(TestMusicProvider.tree.toString()) }
        assertEquals("one", data().favorites.single().resolve(emptyMap()).title)
        provider("unavailable", "false"); provider("grant")
        runBlocking { library.addFolder(TestMusicProvider.tree.toString(), Source.LOCAL) }
        assertTrue(data().favorites.single().resolve(library.testTracks.associateBy(Track::id)).available)
        assertEquals(2, library.testTracks.size)
        waitFor { compose.onAllNodes(hasTestTag("collection_play") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
    }
    @Test fun failedWriteAndUnknownSchemaDoNotOverwriteSavedCollections() {
        edit(CollectionEdit.Create("Сохранённый", id("one")))
        val file = File(graph.filesDir, "user-collections.json")
        val original = file.readBytes(); val before = data()
        // API 29 writes via .bak, newer AtomicFile uses .new; deny writes to both paths.
        assertTrue(file.setWritable(false, true))
        assertTrue(graph.filesDir.setWritable(false, true))
        try {
            assertFalse(runBlocking { store.edit(owner, CollectionEdit.Rename(before.playlists.single().id, "Потерянный")) })
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
