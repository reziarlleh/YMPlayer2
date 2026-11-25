@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
package dev.petrov.ymplayer2

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.petrov.ymplayer2.core.*
import dev.petrov.ymplayer2.designsystem.PrismTheme
import dev.petrov.ymplayer2.shell.LocalMusicBrowser
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalMusicBrowserTest {
    @get:Rule val ui = createComposeRule()

    @Test fun selectThreeFoldersExceptThreeSongsAndPlayOrSaveTheSame150() = selectAcrossFolders(remote = false)
    @Test fun selectTheSame150WithDpadCenter() = selectAcrossFolders(remote = true)

    private fun selectAcrossFolders(remote: Boolean) {
        val counts = listOf(72, 51, 30)
        val roots = counts.indices.map { index ->
            MediaFileEntry("root$index", "root$index", null, "Folder $index", "Folder $index",
                if (index == 2) Source.LOCAL else Source.USB, true, true, false, false, false, null, 0, 0)
        }
        val folders = roots.associate { root ->
            val count = counts[roots.indexOf(root)]
            root.uri to (1..count).map { index -> root.copy(
                uri = "${root.uri}/$index", name = "$index.mp3", path = "${root.path} / $index.mp3",
                parentUri = root.uri, directory = false, trackId = "${root.uri}:$index", fileKey = "${root.uri}/$index",
            ) }
        }
        val files = object : LocalMediaFiles {
            override suspend fun roots() = roots
            override suspend fun children(folder: MediaFileEntry) = folders.getValue(folder.uri)
            override suspend fun tracks(entries: List<MediaFileEntry>) = entries.map { file ->
                Track(requireNotNull(file.trackId), file.name, "", "", file.source, 30, true)
            }
            override suspend fun createFolder(parent: MediaFileEntry, name: String): MediaFileEntry = error("read only")
            override suspend fun rename(entry: MediaFileEntry, name: String): MediaFileEntry = error("read only")
            override suspend fun transfer(entries: List<MediaFileEntry>, destination: MediaFileEntry, move: Boolean): FileBatchResult = error("read only")
            override suspend fun delete(entries: List<MediaFileEntry>): FileBatchResult = error("read only")
        }
        val library = object : LocalLibrary {
            override val state = MutableStateFlow(LibrarySnapshot(roots = roots.mapIndexed { i, r ->
                LibraryRoot(r.uri, r.name, r.source, trackCount = counts[i])
            }, ready = true))
            override val profiles = emptyList<Profile>()
            override fun tracks(profileId: String) = emptyList<Track>()
            override suspend fun addFolder(uri: String, source: Source) = Unit
            override suspend fun forgetFolder(uri: String) = Unit
            override suspend fun refresh() = Unit
        }
        var saved: List<String>? = null
        var playing: List<String>? = null
        ui.setContent { PrismTheme(systemBars = false) {
            LocalMusicBrowser(files, library, "browser-test", manageFolders = {},
                play = { ids, _, _ -> playing = ids }, enqueue = { _, _ -> error("unexpected enqueue") },
                onAddToPlaylist = { saved = it })
        } }
        val list = ui.onNodeWithTag("local_file_list")
        if (remote) androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
            .sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_DOWN)
        fun activate(tag: String) {
            ui.waitUntil(8000) { ui.onAllNodes(hasTestTag(tag) and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            val node = ui.onNodeWithTag(tag)
            if (remote) {
                node.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus) { it() }
                node.assertIsFocused().performKeyInput { pressKey(androidx.compose.ui.input.key.Key.DirectionCenter) }
            } else node.performClick()
        }
        fun clickRow(tag: String) {
            list.performScrollToNode(hasTestTag(tag))
            activate(tag)
        }
        activate("bulk_start")
        roots.forEach { clickRow("check_${it.uri}") }
        clickRow("file_root2")
        for (i in 1..3) clickRow("file_root2/$i")
        activate("files_up")
        if (remote) ui.waitUntil(8000) { ui.onAllNodes(hasTestTag("file_root2") and isFocused()).fetchSemanticsNodes().isNotEmpty() }
        list.performScrollToNode(hasTestTag("check_root2"))
        ui.onNodeWithTag("check_root2").assertIsToggleable().assert(
            SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.ToggleableState,
                androidx.compose.ui.state.ToggleableState.Indeterminate))
        assertNull(playing)
        activate("bulk_playlist")
        activate("files_play_selected")
        val expected = folders.values.flatten().mapNotNull(MediaFileEntry::trackId).filterNot { it in setOf("root2:1", "root2:2", "root2:3") }
        assertEquals(150, expected.size)
        ui.waitForIdle()
        assertEquals(expected, saved)
        assertEquals(expected, playing)
    }
}
