@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
package dev.petrov.ymplayer2

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.petrov.ymplayer2.core.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class CloudPlaylistPlaybackTest {
    @get:Rule val compose = createAndroidComposeRule<OnlineTestActivity>()
    private val h get() = compose.activity.harness
    private val player get() = h.player
    private fun waitFor(test: () -> Boolean) = compose.waitUntil(30000, test)
    @Before fun setup() {
        waitFor { h.library.state.value.ready && player.state.value.connected }
        compose.runOnIdle { player.stop(); player.switchProfile("owner"); player.clearQueue() }
        runBlocking { h.library.state.value.roots.forEach { h.library.forgetFolder(it.uri) } }
        compose.activity.contentResolver.call(Uri.parse("content://dev.petrov.ymplayer2.test.control"), "fixtures", null, null)
        runBlocking { h.library.addFolder(TestMusicProvider.tree.toString(), Source.LOCAL) }
        waitFor { h.library.testTracks.size == 2 && h.cloudPlaylists.state.value.owner == PlaylistOwner("owner", "1") }
    }
    @After fun stop() { compose.runOnIdle { h.cloudGate?.complete(Unit); player.setRepeatMode(RepeatMode.OFF); player.stop() } }
    private fun click(tag: String, list: String = "cloud_dialog_list") {
        compose.onNodeWithTag(list).performScrollToNode(hasTestTag(tag)); compose.onNodeWithTag(tag).performClick()
    }
    private fun snapshot(name: String) {
        compose.waitForIdle()
        val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(compose.activity.getExternalFilesDir(null), name).outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) }; image.recycle()
    }
    private fun searchAndPlay() {
        compose.onNodeWithTag("nav_search").performClick(); compose.onNodeWithTag("source_yandex").performClick()
        compose.onNodeWithTag("online_list").performScrollToNode(hasTestTag("online_query"))
        compose.onNodeWithTag("online_query").performTextInput("fixture"); compose.onNodeWithTag("online_query").performImeAction()
        waitFor { h.online.state.value.loaded && !h.online.state.value.loading }
        click("online_play_all", "online_list"); waitFor { player.state.value.positionSeconds >= 1 }
        compose.onNodeWithTag("nav_player").performClick()
    }
    private fun chooseFromPlayer() {
        compose.onNodeWithTag("player_more").performClick()
        compose.onNodeWithTag("cloud_add_track").performScrollTo().performClick()
        waitFor { h.cloudPlaylists.state.value.loaded }
    }
    @Test fun createAppendAndConfirmedDeleteKeepAudioAndSurviveRecreation() {
        searchAndPlay(); val current = player.state.value.current!!.id
        chooseFromPlayer(); snapshot("cloud-choose.png"); click("cloud_new")
        compose.onNodeWithTag("cloud_title").performTextInput("Новый дорожный список")
        // A phone's software keyboard owns arrows while editing; this is the TV remote path.
        if (compose.activity.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_TYPE_MASK == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION) {
            compose.onNodeWithTag("cloud_title").performKeyInput { pressKey(Key.DirectionDown) }
            compose.onNodeWithTag("cloud_create_confirm").assertIsFocused()
        }
        compose.onNodeWithTag("cloud_title").performImeAction()
        snapshot("cloud-create.png")
        compose.runOnIdle { h.cloudGate = CompletableDeferred() }
        click("cloud_create_confirm"); waitFor { h.cloudPlaylists.state.value.busy }
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("cloud_close").assertIsNotEnabled()
        compose.runOnIdle { h.cloudGate!!.complete(Unit) }
        waitFor { h.cloudPlaylists.state.value.dialog == PlaylistDialog.RESULT && !h.cloudPlaylists.state.value.busy }
        assertEquals(2, h.cloudWrites.size); assertTrue(player.state.value.playing); assertEquals(current, player.state.value.current?.id)
        val created = h.cloudRows.getValue("owner").last(); assertEquals(1, created.trackCount)
        snapshot("cloud-created.png"); compose.onNodeWithTag("cloud_close").performClick()
        compose.onNodeWithTag("nav_library").performClick()
        compose.onNodeWithTag("online_kind_PLAYLISTS").performScrollTo().performClick()
        waitFor { !h.online.state.value.loading && h.online.state.value.entries.any { it.entity?.id == created.id } }
        click("online_entity_${created.id}", "online_list")
        waitFor { h.online.state.value.request.entity?.id == created.id }
        click("cloud_delete", "online_list"); snapshot("cloud-delete.png")
        compose.onNodeWithTag("cloud_close").performClick(); assertEquals(2, h.cloudWrites.size)
        click("cloud_delete", "online_list"); click("cloud_delete_confirm")
        waitFor { h.cloudPlaylists.state.value.dialog == PlaylistDialog.RESULT && !h.online.state.value.loading }
        assertTrue(h.cloudRows.getValue("owner").none { it.id == created.id })
        assertTrue(h.online.state.value.entries.none { it.entity?.id == created.id })
        assertTrue(player.state.value.playing); assertEquals(current, player.state.value.current?.id)
        compose.onNodeWithTag("cloud_close").performClick()
    }
    @Test fun existingListAppendFromSearchAndPartialCreationAreVisible() {
        searchAndPlay()
        compose.onNodeWithTag("nav_search").performClick()
        compose.onNodeWithTag("online_list").performScrollToNode(hasTestTag("track_more_yandex:1:7"))
        compose.onNodeWithTag("track_more_yandex:1:7").performClick()
        compose.onNodeWithTag("cloud_add_track").performScrollTo().performClick()
        waitFor { h.cloudPlaylists.state.value.loaded }; click("cloud_playlist_77")
        waitFor { h.cloudPlaylists.state.value.dialog == PlaylistDialog.RESULT }
        assertEquals(1, h.cloudRows.getValue("owner").first().trackCount)
        compose.onNodeWithTag("cloud_close").performClick(); compose.onNodeWithTag("nav_player").performClick()
        chooseFromPlayer(); click("cloud_new")
        compose.onNodeWithTag("cloud_title").performTextInput("Частичный результат"); compose.onNodeWithTag("cloud_title").performImeAction()
        compose.runOnIdle { h.cloudFailAdd = true }; click("cloud_create_confirm")
        waitFor { h.cloudPlaylists.state.value.issue != null && !h.cloudPlaylists.state.value.busy }
        assertTrue(h.cloudPlaylists.state.value.message!!.contains("создан")); assertFalse(h.cloudPlaylists.state.value.loaded)
        snapshot("cloud-partial.png"); val creates = h.cloudWrites.count { it.startsWith("create") }
        click("cloud_refresh"); waitFor { h.cloudPlaylists.state.value.loaded }
        assertEquals(creates, h.cloudWrites.count { it.startsWith("create") })
        assertTrue(player.state.value.playing)
    }
    @Test fun switchingProfileClosesDialogAndDiscardsLateResult() {
        searchAndPlay(); chooseFromPlayer()
        compose.runOnIdle { h.cloudGate = CompletableDeferred() }; click("cloud_playlist_77")
        waitFor { h.cloudPlaylists.state.value.busy }
        compose.runOnIdle { player.switchProfile("road"); h.cloudGate!!.complete(Unit) }
        waitFor { h.cloudPlaylists.state.value.owner == PlaylistOwner("road", "2") }
        assertNull(h.cloudPlaylists.state.value.dialog); assertNull(h.cloudPlaylists.state.value.message)
        compose.onNodeWithTag("cloud_close").assertDoesNotExist()
        assertEquals(0, h.cloudRows.getValue("road").single().trackCount)
    }
    private fun openEditor() {
        compose.runOnIdle { h.seedCloudEditor(); player.setRepeatMode(RepeatMode.ONE) }
        compose.runOnIdle { h.online.collection(MusicKind.PLAYLISTS) }
        compose.onNodeWithTag("nav_library").performClick()
        compose.onNodeWithTag("source_yandex").performClick()
        compose.onNodeWithTag("online_kind_PLAYLISTS").performScrollTo().performClick()
        waitFor { !h.online.state.value.loading && h.online.state.value.entries.any { it.entity?.id == "77" } }
        click("online_entity_77", "online_list"); waitFor { h.online.state.value.request.entity?.id == "77" }
        click("cloud_edit", "online_list"); waitFor { h.cloudPlaylists.state.value.loaded && !h.cloudPlaylists.state.value.busy }
    }
    private fun awaitEditor() = waitFor { h.cloudPlaylists.state.value.dialog == PlaylistDialog.EDIT && !h.cloudPlaylists.state.value.busy && h.cloudPlaylists.state.value.loaded }
    @Test fun playlistTrackKeepsItsActionsInsideOneCard() {
        compose.onNodeWithTag("nav_library").performClick()
        compose.onNodeWithTag("source_yandex").performClick()
        compose.runOnIdle { h.online.collection(MusicKind.PLAYLISTS) }
        waitFor { h.online.state.value.entries.any { it.entity?.id == "77" } }
        click("online_entity_77", "online_list")
        waitFor { h.online.state.value.entries.any { it.track?.id == "yandex:1:7" } }
        compose.onNodeWithTag("online_list").performScrollToNode(hasTestTag("track_card_yandex:1:7"))
        val card = compose.onNodeWithTag("track_card_yandex:1:7").fetchSemanticsNode().boundsInRoot
        listOf("track_yandex:1:7", "catalog_yandex:1:7_artist_5", "enqueue_yandex:1:7", "track_more_yandex:1:7").forEach { tag ->
            assertTrue("$tag outside track card", card.contains(compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.center))
        }
        snapshot("cloud-playlist-card.png")
        compose.onNodeWithTag("catalog_yandex:1:7_artist_5").performClick()
        waitFor { h.online.state.value.request.entity?.let { it.id == "5" && it.kind == MusicKind.ARTISTS } == true }
    }
    @Test fun draggingAnOccurrenceCommitsOneFinalServerMove() {
        openEditor()
        val writes = h.cloudWrites.size
        if (compose.activity.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_TYPE_MASK == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION) {
            // A remote cannot drag; keep the position control for the same operation.
            click("cloud_move_0")
            compose.onNodeWithTag("cloud_move_position").performTextReplacement("2")
            click("cloud_move_confirm")
        } else {
            compose.onNodeWithTag("cloud_dialog_list").performScrollToNode(hasTestTag("cloud_drag_0"))
            val first = compose.onNodeWithTag("cloud_drag_0").fetchSemanticsNode().boundsInRoot.center
            val second = compose.onNodeWithTag("cloud_drag_1").fetchSemanticsNode().boundsInRoot.center
            compose.onNodeWithTag("cloud_drag_0").performTouchInput {
                down(center)
                advanceEventTime(700)
                moveBy(Offset(0f, (second.y - first.y) / 2f))
                moveBy(Offset(0f, (second.y - first.y) / 2f))
                up()
            }
        }
        awaitEditor()
        assertEquals(listOf("2", "1", "1"), h.cloudPlaylists.state.value.snapshot!!.tracks.map { it.id })
        assertEquals(writes + 1, h.cloudWrites.size)
    }
    @Test fun renameMoveAndRemoveOccurrenceKeepAudioAndSurviveRecreation() {
        searchAndPlay(); val queue = player.state.value.queue.map { it.id }; val current = player.state.value.current!!.id
        openEditor(); snapshot("cloud-editor.png"); click("cloud_rename")
        compose.onNodeWithTag("cloud_rename_title").performTextReplacement("Мои дорожные треки")
        compose.onNodeWithTag("cloud_rename_title").performImeAction(); snapshot("cloud-rename.png")
        compose.runOnIdle { h.cloudGate = CompletableDeferred() }; click("cloud_rename_confirm")
        waitFor { h.cloudPlaylists.state.value.busy }; compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("cloud_close").assertIsNotEnabled(); compose.runOnIdle { h.cloudGate!!.complete(Unit) }
        awaitEditor(); assertEquals("Мои дорожные треки", h.cloudPlaylists.state.value.target!!.title)
        click("cloud_move_0"); compose.onNodeWithTag("cloud_move_position").performTextReplacement("3")
        compose.onNodeWithTag("cloud_move_position").performImeAction(); snapshot("cloud-move.png")
        click("cloud_move_confirm"); awaitEditor()
        assertEquals(listOf("2", "1", "1"), h.cloudPlaylists.state.value.snapshot!!.tracks.map { it.id })
        click("cloud_remove_2"); snapshot("cloud-remove.png"); compose.onNodeWithTag("cloud_close").performClick()
        assertEquals(2, h.cloudWrites.size)
        click("cloud_remove_2"); click("cloud_remove_confirm"); awaitEditor()
        assertEquals(listOf("2", "1"), h.cloudPlaylists.state.value.snapshot!!.tracks.map { it.id })
        assertEquals(3, h.cloudWrites.size); assertTrue(player.state.value.playing)
        assertEquals(current, player.state.value.current?.id); assertEquals(queue, player.state.value.queue.map { it.id })
        snapshot("cloud-edited.png"); compose.onNodeWithTag("cloud_close").performClick()
        waitFor { h.online.state.value.request.entity?.title == "Мои дорожные треки" }
        compose.onNodeWithText("Мои дорожные треки").assertExists()
    }
    @Test fun uncertainEditorWriteNeedsRefreshAndNeverRemovesNextOccurrence() {
        searchAndPlay(); openEditor(); compose.runOnIdle { h.cloudFailAfterEdit = true }
        click("cloud_remove_0"); click("cloud_remove_confirm")
        waitFor { !h.cloudPlaylists.state.value.busy && h.cloudPlaylists.state.value.issue != null }
        assertFalse(h.cloudPlaylists.state.value.loaded); assertEquals(listOf("2", "1"), h.cloudTracks["owner:77"]!!.map { it.id })
        compose.onNodeWithTag("cloud_remove_0").assertDoesNotExist()
        snapshot("cloud-editor-recovery.png"); val writes = h.cloudWrites.size
        compose.runOnIdle { h.cloudFailAfterEdit = false }; click("cloud_editor_refresh"); awaitEditor()
        assertEquals(writes, h.cloudWrites.size); assertEquals(listOf("2", "1"), h.cloudPlaylists.state.value.snapshot!!.tracks.map { it.id })
        assertTrue(player.state.value.playing)
    }
    @Test fun profileSwitchClosesPendingEditorAndDoesNotRenameOtherAccount() {
        searchAndPlay(); openEditor(); click("cloud_rename")
        compose.onNodeWithTag("cloud_rename_title").performTextReplacement("Старый профиль")
        compose.onNodeWithTag("cloud_rename_title").performImeAction()
        compose.runOnIdle { h.cloudGate = CompletableDeferred() }; click("cloud_rename_confirm")
        waitFor { h.cloudPlaylists.state.value.busy }; compose.runOnIdle { player.switchProfile("road"); h.cloudGate!!.complete(Unit) }
        waitFor { h.cloudPlaylists.state.value.owner == PlaylistOwner("road", "2") }
        assertNull(h.cloudPlaylists.state.value.dialog); assertNull(h.cloudPlaylists.state.value.snapshot)
        assertEquals("Другой аккаунт", h.cloudRows.getValue("road").single().title)
        compose.onNodeWithTag("cloud_close").assertDoesNotExist()
    }
}
