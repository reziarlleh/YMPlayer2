@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
package dev.petrov.ymplayer2

import android.net.Uri
import android.os.SystemClock
import android.view.KeyEvent
import android.view.ViewConfiguration
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.petrov.ymplayer2.core.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalListHistoryTest {
    @get:Rule val compose = createAndroidComposeRule<OnlineTestActivity>()
    private val h get() = compose.activity.harness
    private val player get() = h.player
    private fun waitFor(condition: () -> Boolean) = compose.waitUntil(20000, condition)
    private fun press(tag: String) = compose.onNodeWithTag(tag).performClick()
    private val ids get() = h.library.testTracks.map(Track::id)

    @Before fun localMusic() {
        waitFor { h.library.state.value.ready && player.state.value.connected && h.collections.state.value.ready }
        compose.runOnIdle { player.stop(); player.switchProfile("owner"); player.clearQueue() }
        runBlocking { h.library.state.value.roots.forEach { h.library.forgetFolder(it.uri) } }
        h.context.contentResolver.call(Uri.parse("content://dev.petrov.ymplayer2.test.control"), "fixtures", null, null)
        runBlocking { h.library.addFolder(TestMusicProvider.tree.toString(), Source.LOCAL) }
        waitFor { ids.size == 2 && h.taste.state.value.signedIn }
        compose.runOnIdle { player.setRepeatMode(RepeatMode.OFF); player.setShuffle(false) }
    }
    @After fun stop() { compose.runOnIdle { player.stop() } }

    @Test fun playlistReturnsFromWaveToSecondSongAtItsPositionAfterEngineRestart() {
        val tracks = ids
        val name = "Road ${SystemClock.uptimeMillis()}"
        assertTrue(runBlocking { h.collections.edit("owner", CollectionEdit.CreateMany(name, tracks)) })
        val list = h.collections.state.value.profile("owner").playlists.single { it.name == name }
        press("nav_library"); press("source_local"); press("category_PLAYLISTS"); press("playlist_${list.id}")
        press("collection_play")
        waitFor { player.state.value.origin.localList?.playlistId == list.id && player.state.value.playing }
        compose.runOnIdle { player.skip(1) }
        waitFor { player.state.value.current?.id == tracks[1] }
        compose.runOnIdle { player.seek(11); player.setRepeatMode(RepeatMode.ALL) }
        waitFor { player.state.value.positionSeconds in 11..13 }
        press("nav_player"); press("player_source_wave")
        waitFor { player.state.value.wave && player.state.value.playing }
        compose.runOnIdle { h.restartEngine() }
        waitFor { player.state.value.connected && player.state.value.wave && player.state.value.current != null }
        val shortcut = compose.onNodeWithTag("player_source_list")
        shortcut.performTouchInput { longClick() }
        compose.onNodeWithTag("local_list_history").assertIsDisplayed()
        assertTrue(player.state.value.wave)
        compose.onNodeWithText(name).assertIsDisplayed()
        compose.onNodeWithText("Отмена").performClick()
        shortcut.performClick()
        waitFor { player.state.value.current?.id == tracks[1] && player.state.value.playing && player.state.value.positionSeconds in 11..17 }
        assertEquals(RepeatMode.ALL, player.state.value.repeatMode)
        shortcut.assertIsSelected()
        compose.runOnIdle { player.toggle() }
        waitFor { !player.state.value.playing }
        val paused = player.state.value.positionSeconds
        shortcut.performClick()
        waitFor { player.state.value.playing && player.state.value.positionSeconds >= paused }
        assertEquals(tracks, runBlocking { player.queuePage(0).items.map(Track::id) })
    }

    @Test fun fiveFoldersKeepIndependentCursorsAndRemoteHoldDoesNotStartMusic() {
        val tracks = ids
        val folders = (1..6).map { LocalListTarget("folder:drive/$it", "Music", "Drive $it / Music") }
        for ((index, folder) in folders.withIndex()) {
            compose.runOnIdle { player.playList(tracks, tracks[index % 2], folder.origin()) }
            waitFor { player.state.value.localLists.recent.firstOrNull() == folder && player.state.value.playing }
            compose.runOnIdle { player.seek(index + 4) }
        }
        assertEquals(folders.drop(1).reversed(), player.state.value.localLists.recent)
        compose.runOnIdle { player.playMyWave() }
        waitFor { player.state.value.wave && player.state.value.playing }
        val shortcut = compose.onNodeWithTag("player_source_list")
        val input = InstrumentationRegistry.getInstrumentation()
        input.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.waitForIdle()
        shortcut.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        shortcut.assertIsFocused()
        val down = SystemClock.uptimeMillis()
        input.sendKeySync(KeyEvent(down, down, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, 0))
        SystemClock.sleep(ViewConfiguration.getLongPressTimeout() + 180L)
        input.sendKeySync(KeyEvent(down, SystemClock.uptimeMillis(), KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER,
            1, 0, -1, 0, KeyEvent.FLAG_LONG_PRESS))
        input.sendKeySync(KeyEvent(down, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER, 0))
        compose.onNodeWithTag("local_list_history").assertIsDisplayed()
        assertTrue(player.state.value.wave)
        compose.onNodeWithText("Drive 1 / Music").assertDoesNotExist()
        press("local_history_${folders[2].key}")
        waitFor { player.state.value.localLists.recent.first() == folders[2] && player.state.value.current?.id == tracks[0] &&
            player.state.value.playing && player.state.value.positionSeconds in 6..11 }
        assertEquals(5, player.state.value.localLists.recent.distinct().size)
        compose.runOnIdle { player.switchProfile("guest") }
        waitFor { player.state.value.profileId == "guest" }
        assertTrue(player.state.value.localLists.recent.isEmpty())
        compose.runOnIdle { player.switchProfile("owner") }
        waitFor { player.state.value.profileId == "owner" && player.state.value.current?.id == tracks[0] }
        assertEquals(folders[2], player.state.value.localLists.recent.first())
    }

    @Test fun folderFromLibraryReturnsAfterOfflineAndWaitsForDisconnectedStorage() {
        val tracks = ids
        val folder = runBlocking { h.mediaFiles.roots().single() }
        press("nav_library"); press("source_local"); press("library_folders")
        waitFor { compose.onAllNodesWithTag("file_${folder.uri}").fetchSemanticsNodes().isNotEmpty() }
        press("file_${folder.uri}"); press("play_folder")
        waitFor { player.state.value.origin.localList == LocalListTarget.folder(folder) && player.state.value.playing }
        press("nav_player")
        compose.runOnIdle { h.offline.setEnabled(true) }
        waitFor { h.offline.state.value.owner != null && h.offline.state.value.enabled }
        compose.runOnIdle { h.tasteLists["owner" to TasteKind.TRACK] = TasteList(setOf("1", "2")); h.taste.refresh(TasteKind.TRACK) }
        waitFor { h.taste.state.value.shelf(TasteKind.TRACK).list.liked == setOf("1", "2") }
        compose.runOnIdle { h.offline.sync() }
        waitFor { h.offline.state.value.tracks.size == 2 && !h.offline.state.value.running }
        compose.runOnIdle { player.select(tracks.last()); player.seek(9) }
        waitFor { player.state.value.current?.id == tracks.last() && player.state.value.positionSeconds in 9..12 }
        press("player_source_offline")
        waitFor { player.state.value.origin.source == PlaybackSource.OFFLINE && player.state.value.playing }
        runBlocking { h.library.forgetFolder(folder.rootUri) }
        waitFor { !h.library.state.value.scanning && ids.isEmpty() }
        press("player_source_list")
        waitFor { player.state.value.error != null && player.state.value.origin.source == PlaybackSource.OFFLINE }
        assertEquals(listOf(LocalListTarget.folder(folder)), player.state.value.localLists.recent)
        runBlocking { h.library.addFolder(TestMusicProvider.tree.toString(), Source.LOCAL) }
        waitFor { !h.library.state.value.scanning && ids.size == 2 }
        press("player_source_list")
        waitFor { player.state.value.origin.localList == LocalListTarget.folder(folder) && player.state.value.playing &&
            player.state.value.current?.id == tracks.last() && player.state.value.positionSeconds in 9..16 }
    }

    @Test fun arbitrary150TrackSelectionRestoresItsFullOrderPositionAndShuffle() {
        h.context.contentResolver.call(Uri.parse("content://dev.petrov.ymplayer2.test.control"), "bulk", "150", null)
        runBlocking { h.library.refresh() }
        val tracks = h.library.testTracks.filter { it.title.startsWith("bulk-") }.sortedByDescending(Track::title).map(Track::id)
        assertEquals(150, tracks.size)
        compose.runOnIdle { player.playQueue(tracks, tracks[119]) }
        waitFor { player.state.value.current?.id == tracks[119] && player.state.value.playing }
        val selection = player.state.value.localLists.recent.first()
        assertTrue(selection.selection)
        compose.runOnIdle { player.setShuffle(true); player.seek(13) }
        waitFor { player.state.value.shuffle && player.state.value.positionSeconds in 13..16 }
        press("player_source_wave")
        waitFor { player.state.value.wave && player.state.value.current?.source == Source.YANDEX }
        assertEquals(listOf(selection), player.state.value.localLists.recent)
        compose.runOnIdle { h.restartEngine() }
        waitFor { player.state.value.connected && player.state.value.wave && player.state.value.current != null }
        press("player_source_list")
        waitFor { player.state.value.playing && player.state.value.current?.id == tracks[119] && player.state.value.positionSeconds in 13..19 }
        assertEquals(119, player.state.value.index)
        assertTrue(player.state.value.shuffle)
        assertEquals(tracks, runBlocking { player.queuePage(0, 150).items.map(Track::id) })
    }

    @Test fun fivePinsSurviveProfileReloadWhileShortPressResumesSixthList() {
        val tracks = ids
        val folders = (1..6).map { LocalListTarget("folder:usb/$it", "Folder $it") }
        for (folder in folders.take(5)) {
            compose.runOnIdle { player.playList(tracks, origin = folder.origin()) }
            waitFor { player.state.value.localLists.recent.firstOrNull() == folder && player.state.value.playing }
        }
        val shortcut = compose.onNodeWithTag("player_source_list")
        shortcut.performTouchInput { longClick() }
        fun pin(folder: LocalListTarget) {
            val tag = "local_pin_${folder.key}"
            compose.onNodeWithTag("local_list_history").performScrollToNode(hasTestTag(tag))
            press(tag)
        }
        folders.take(5).forEach(::pin)
        assertEquals(folders.take(5).map { it.key }, player.state.value.localLists.pinned)
        assertEquals(folders[4], player.state.value.origin.localList)
        compose.onNodeWithText("Отмена").performClick()
        compose.runOnIdle { player.playList(tracks, tracks.last(), folders.last().origin()) }
        waitFor { player.state.value.localLists.recent.firstOrNull() == folders.last() && player.state.value.playing }
        compose.runOnIdle { player.seek(12); player.playMyWave() }
        waitFor { player.state.value.wave && player.state.value.playing }
        compose.runOnIdle { player.switchProfile("guest") }
        waitFor { player.state.value.profileId == "guest" }
        assertTrue(player.state.value.localLists.pinned.isEmpty())
        compose.runOnIdle { player.switchProfile("owner") }
        waitFor { player.state.value.profileId == "owner" && player.state.value.wave }
        assertEquals(folders.take(5), player.state.value.localLists.menu())
        shortcut.performTouchInput { longClick() }
        compose.onNodeWithTag("local_history_${folders.last().key}").assertDoesNotExist()
        compose.onNodeWithText("Отмена").performClick()
        shortcut.performClick()
        waitFor { player.state.value.current?.id == tracks.last() && player.state.value.positionSeconds in 12..17 &&
            player.state.value.playing && player.state.value.origin.localList == folders.last() }
        shortcut.performTouchInput { longClick() }
        pin(folders.first())
        compose.onNodeWithTag("local_list_history").performScrollToNode(hasTestTag("local_history_${folders.last().key}"))
        compose.onNodeWithTag("local_history_${folders.last().key}").assertIsDisplayed()
    }

    @Test fun queueBuiltByHandBecomesAListOnPlay() {
        val tracks = ids.reversed()
        compose.runOnIdle { player.enqueueMany(tracks) }
        waitFor { player.state.value.queueCount == 2 && !player.state.value.playing }
        assertTrue(player.state.value.localLists.recent.isEmpty())
        compose.runOnIdle { player.toggle() }
        waitFor { player.state.value.playing && player.state.value.origin.localList?.selection == true }
        compose.runOnIdle { player.seek(8); player.playMyWave() }
        waitFor { player.state.value.playing && player.state.value.wave }
        press("player_source_list")
        waitFor { player.state.value.playing && player.state.value.current?.id == tracks.first() && player.state.value.positionSeconds in 8..14 }
        assertEquals(tracks, runBlocking { player.queuePage(0).items.map(Track::id) })
    }
}
