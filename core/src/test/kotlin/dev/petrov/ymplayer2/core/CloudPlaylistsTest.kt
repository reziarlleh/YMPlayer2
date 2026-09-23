package dev.petrov.ymplayer2.core

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CloudPlaylistsTest {
    private val track = Track("yandex:1:7", "Song", "Artist", "Album", Source.YANDEX, 60, false)
    private val list = CloudPlaylist("77", "owner", "List", 2)
    private class Api : CloudPlaylistApi {
        val calls = mutableListOf<String>()
        var gate: suspend () -> Unit = {}
        var failCreate = false
        var failAdd = false
        var failEdit = false
        var snapshot = CloudPlaylistSnapshot(CloudPlaylist("77", "owner", "List", 3), 4,
            listOf(CloudPlaylistEntry("1", "7", "One"), CloudPlaylistEntry("2", "7", "Two"), CloudPlaylistEntry("1", "7", "One")))
        override suspend fun list(owner: PlaylistOwner) = listOf(CloudPlaylist("77", owner.accountId, "List"), CloudPlaylist("88", "foreign", "Other"))
        override suspend fun create(owner: PlaylistOwner, title: String): CloudPlaylist {
            calls += "create:$title"; gate()
            if (failCreate) throw MusicException(MusicFailure.NETWORK)
            return CloudPlaylist("99", owner.accountId, title)
        }
        override suspend fun add(owner: PlaylistOwner, playlist: CloudPlaylist, track: Track): CloudPlaylist {
            calls += "add:${playlist.id}"; gate()
            if (failAdd) throw MusicException(MusicFailure.NETWORK)
            return playlist.copy(trackCount = playlist.trackCount + 1)
        }
        override suspend fun delete(owner: PlaylistOwner, playlist: CloudPlaylist) { calls += "delete:${playlist.id}"; gate() }
        override suspend fun load(owner: PlaylistOwner, playlist: CloudPlaylist) = snapshot
        override suspend fun rename(owner: PlaylistOwner, snapshot: CloudPlaylistSnapshot, title: String): CloudPlaylistSnapshot {
            calls += "rename:$title"; gate()
            if (failEdit) throw MusicException(MusicFailure.NETWORK)
            return snapshot.copy(playlist = snapshot.playlist.copy(title = title), revision = snapshot.revision + 1).also { this.snapshot = it }
        }
        override suspend fun edit(owner: PlaylistOwner, snapshot: CloudPlaylistSnapshot, change: CloudTrackEdit): CloudPlaylistSnapshot {
            calls += "edit:$change"; gate()
            if (failEdit) throw MusicException(MusicFailure.RESPONSE, 409)
            val tracks = snapshot.edited(change)
            return snapshot.copy(playlist = snapshot.playlist.copy(trackCount = tracks.size), tracks = tracks, revision = snapshot.revision + 1).also { this.snapshot = it }
        }
    }
    private fun TestScope.auth() = AccountAuth(DemoCatalog().profiles, object : DeviceAuthApi {
        override val configured = true
        override suspend fun requestCode(profileId: String) = error("unused")
        override suspend fun poll(code: DeviceChallenge) = error("unused")
        override suspend fun account(credentials: OAuthCredentials) = error("unused")
    }, object : AccountStore {
        private val removed = mutableSetOf<String>()
        override suspend fun read(profileId: String) = if (profileId in removed) null else AccountSession(YandexAccount(profileId, profileId), OAuthCredentials("fixture", null, null))
        override suspend fun write(profileId: String, session: AccountSession?) { if (session == null) removed += profileId else removed -= profileId }
    }, backgroundScope).also { it.activate("owner"); runCurrent() }

    @Test fun createAndAppendIsSingleCommandAndBusyCannotDismissOrSubmitAgain() = runTest {
        val api = Api().apply { gate = { delay(100) } }; val changes = mutableListOf<String>()
        val c = CloudPlaylists(auth(), api, backgroundScope) { p, _ -> changes += p.id }; runCurrent()
        c.newPlaylist(track); c.create(" New "); c.create("Duplicate"); c.dismiss(); runCurrent()
        assertTrue(c.state.value.busy); assertTrue(changes.isEmpty())
        advanceTimeBy(201); runCurrent()
        assertEquals(listOf("create:New", "add:99"), api.calls)
        assertEquals(PlaylistDialog.RESULT, c.state.value.dialog); assertEquals(listOf("99", "99"), changes)
    }
    @Test fun partialCreateKeepsCreatedIdAndRetryDoesNotRecreate() = runTest {
        val api = Api().apply { failAdd = true }; val c = CloudPlaylists(auth(), api, backgroundScope); runCurrent()
        c.newPlaylist(track); c.create("New"); runCurrent()
        assertEquals("99", c.state.value.target?.id); assertTrue(c.state.value.message!!.contains("создан"))
        assertEquals(PlaylistDialog.CHOOSE, c.state.value.dialog); assertFalse(c.state.value.loaded)
        c.create("New"); runCurrent(); assertEquals(1, api.calls.count { it.startsWith("create") })
    }
    @Test fun ambiguousCreateRequiresReadAndNeverAutomaticallyRetries() = runTest {
        val api = Api().apply { failCreate = true }; val c = CloudPlaylists(auth(), api, backgroundScope); runCurrent()
        c.newPlaylist(null); c.create("New"); runCurrent(); advanceTimeBy(60000); runCurrent()
        assertEquals(listOf("create:New"), api.calls); assertNotNull(c.state.value.issue)
        c.refresh(); runCurrent(); assertTrue(c.state.value.loaded)
        assertEquals(listOf("77"), c.state.value.playlists.map { it.id })
    }
    @Test fun delayedCreateCannotAppendOrPublishAfterProfileSwitchEvenIfTransportIgnoresCancellation() = runTest {
        val auth = auth(); val api = Api().apply { gate = { withContext(NonCancellable) { delay(100) } } }
        val changes = mutableListOf<CloudPlaylist>(); val c = CloudPlaylists(auth, api, backgroundScope) { p, _ -> changes += p }; runCurrent()
        c.newPlaylist(track); c.create("Old owner"); runCurrent()
        auth.activate("road"); runCurrent(); advanceTimeBy(101); runCurrent()
        assertEquals(PlaylistOwner("road", "road"), c.state.value.owner)
        assertNull(c.state.value.dialog); assertTrue(changes.isEmpty()); assertEquals(listOf("create:Old owner"), api.calls)
    }
    @Test fun logoutDiscardsLateDeleteResult() = runTest {
        val auth = auth(); val api = Api().apply { gate = { withContext(NonCancellable) { delay(100) } } }
        var changed = false; val c = CloudPlaylists(auth, api, backgroundScope) { _, _ -> changed = true }; runCurrent()
        c.askDelete(list.entity()); c.delete(); runCurrent(); auth.signOut(); runCurrent(); advanceTimeBy(101); runCurrent()
        assertFalse(changed); assertNull(c.state.value.dialog)
    }
    @Test fun deleteNeedsExplicitConfirmationAndPublishesOnlyAfterSuccess() = runTest {
        val api = Api().apply { gate = { delay(100) } }; val deleted = mutableListOf<Boolean>()
        val c = CloudPlaylists(auth(), api, backgroundScope) { _, d -> deleted += d }; runCurrent()
        c.askDelete(list.entity()); runCurrent(); assertTrue(api.calls.isEmpty())
        c.dismiss(); c.delete(); runCurrent(); assertTrue(api.calls.isEmpty())
        c.askDelete(list.entity()); c.delete(); c.delete(); runCurrent(); assertTrue(deleted.isEmpty())
        advanceTimeBy(101); runCurrent(); assertEquals(listOf(true), deleted); assertEquals(listOf("delete:77"), api.calls)
    }
    @Test fun foreignListsAndLocalOrIncompleteTracksCannotBeWritten() = runTest {
        val api = Api(); val c = CloudPlaylists(auth(), api, backgroundScope); runCurrent()
        c.askDelete(list.copy(ownerId = "foreign").entity()); assertNull(c.state.value.dialog)
        c.choose(track.copy(source = Source.LOCAL)); assertNull(c.state.value.dialog)
        c.choose(track.copy(id = "yandex:1", albumId = null)); assertNull(c.state.value.dialog)
        c.choose(track); runCurrent(); c.add(list.copy(ownerId = "foreign")); runCurrent(); assertTrue(api.calls.isEmpty())
        assertEquals("1" to "7", track.copy(id = "yandex:1", albumId = "7").cloudTrackKey())
    }
    @Test fun repeatedAddWhilePendingWritesOnlyOnce() = runTest {
        val api = Api().apply { gate = { delay(100) } }; val c = CloudPlaylists(auth(), api, backgroundScope); runCurrent()
        c.choose(track); runCurrent(); val chosen = c.state.value.playlists.single()
        c.add(chosen); c.add(chosen); c.newPlaylist(track); runCurrent(); advanceTimeBy(101); runCurrent()
        assertEquals(listOf("add:77"), api.calls); assertEquals(PlaylistDialog.RESULT, c.state.value.dialog)
    }
    @Test fun editorUsesOccurrencesAndFinalDestinationInBothDirections() = runTest {
        val api = Api(); val c = CloudPlaylists(auth(), api, backgroundScope); runCurrent()
        c.openEditor(list.entity()); runCurrent()
        c.editorAction(PlaylistDialog.MOVE, 0); c.moveTrack(2); runCurrent()
        assertEquals(listOf("2", "1", "1"), c.state.value.snapshot!!.tracks.map { it.id })
        c.editorAction(PlaylistDialog.MOVE, 2); c.moveTrack(0); runCurrent()
        assertEquals(listOf("1", "2", "1"), c.state.value.snapshot!!.tracks.map { it.id })
        c.editorAction(PlaylistDialog.REMOVE, 2); c.removeTrack(); runCurrent()
        assertEquals(listOf("1", "2"), c.state.value.snapshot!!.tracks.map { it.id })
        assertEquals(PlaylistDialog.EDIT, c.state.value.dialog)
    }
    @Test fun renameAndRemoveNeedExplicitCommitAndBusyBlocksOtherActions() = runTest {
        val api = Api().apply { gate = { delay(100) } }; val c = CloudPlaylists(auth(), api, backgroundScope); runCurrent()
        c.openEditor(list.entity()); runCurrent()
        c.editorAction(PlaylistDialog.REMOVE, 0); c.backToEditor(); c.removeTrack(); runCurrent(); assertTrue(api.calls.isEmpty())
        c.editorAction(PlaylistDialog.RENAME); c.rename(" New title "); c.rename("Duplicate"); c.backToEditor(); c.dismiss(); runCurrent()
        assertTrue(c.state.value.busy); advanceTimeBy(101); runCurrent()
        assertEquals(listOf("rename:New title"), api.calls); assertEquals("New title", c.state.value.target?.title)
        assertEquals("1", c.state.value.snapshot!!.tracks.first().id)
    }
    @Test fun editFailureRequiresExplicitRefreshBeforeAnotherWrite() = runTest {
        val api = Api().apply { failEdit = true }; val c = CloudPlaylists(auth(), api, backgroundScope); runCurrent()
        c.openEditor(list.entity()); runCurrent(); c.editorAction(PlaylistDialog.REMOVE, 0); c.removeTrack(); runCurrent()
        assertEquals(PlaylistDialog.EDIT, c.state.value.dialog); assertFalse(c.state.value.loaded); assertNotNull(c.state.value.issue)
        c.editorAction(PlaylistDialog.REMOVE, 0); c.removeTrack(); advanceTimeBy(60000); runCurrent(); assertEquals(1, api.calls.size)
        api.failEdit = false; c.refreshEditor(); runCurrent(); c.editorAction(PlaylistDialog.REMOVE, 0); c.removeTrack(); runCurrent()
        assertEquals(2, api.calls.size); assertEquals(2, c.state.value.snapshot!!.tracks.size)
    }
    @Test fun profileChangeDiscardsNonCancellableRenameAndItsCallback() = runTest {
        val auth = auth(); val api = Api().apply { gate = { withContext(NonCancellable) { delay(100) } } }
        var published = 0; val c = CloudPlaylists(auth, api, backgroundScope) { _, _ -> published++ }; runCurrent()
        c.openEditor(list.entity()); runCurrent(); val before = published
        c.editorAction(PlaylistDialog.RENAME); c.rename("Old account"); runCurrent(); auth.activate("road"); runCurrent()
        advanceTimeBy(101); runCurrent()
        assertEquals(before, published); assertNull(c.state.value.snapshot); assertNull(c.state.value.dialog)
    }
    @Test fun unavailableOccurrenceCanBeRemovedButCannotBeMovedWithoutAlbum() = runTest {
        val api = Api().apply { snapshot = snapshot.copy(tracks = snapshot.tracks + CloudPlaylistEntry("3", null, "Unavailable")) }
        val c = CloudPlaylists(auth(), api, backgroundScope); runCurrent(); c.openEditor(list.entity()); runCurrent()
        c.editorAction(PlaylistDialog.MOVE, 3); assertEquals(PlaylistDialog.EDIT, c.state.value.dialog)
        c.editorAction(PlaylistDialog.REMOVE, 3); c.removeTrack(); runCurrent(); assertEquals(3, c.state.value.snapshot!!.tracks.size)
        c.editorAction(PlaylistDialog.MOVE, -1); assertEquals(PlaylistDialog.EDIT, c.state.value.dialog)
    }
}
