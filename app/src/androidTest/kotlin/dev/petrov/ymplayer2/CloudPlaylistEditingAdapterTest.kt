package dev.petrov.ymplayer2

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.petrov.ymplayer2.core.*
import dev.petrov.ymplayer2.yandex.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CloudPlaylistEditingAdapterTest {
    private val owner = PlaylistOwner("owner", "123")
    private val playlist = CloudPlaylist("77", "123", "Road", 4)
    private suspend fun auth(scope: CoroutineScope) = AccountAuth(DemoCatalog().profiles, object : DeviceAuthApi {
        override val configured = true
        override suspend fun requestCode(profileId: String) = error("unused")
        override suspend fun poll(code: DeviceChallenge) = error("unused")
        override suspend fun account(credentials: OAuthCredentials) = error("unused")
    }, object : AccountStore {
        override suspend fun read(profileId: String) = AccountSession(YandexAccount(if (profileId == "owner") "123" else "456", "Fixture"), OAuthCredentials("fixture-$profileId", null, null))
        override suspend fun write(profileId: String, session: AccountSession?) = Unit
    }, scope).also { it.activate("owner"); yield() }

    private class Server {
        var title = "Road"
        var revision = 12L
        var rows = mutableListOf("1", "2", "1", "3")
        var shortRow: Int? = null
        var declaredCount: Int? = null
        var omitRevision = false
        var embeddedAlbums = false
        var failure: MusicException? = null
        var failReadBack = false
        var reads = 0
        var onRead: suspend () -> Unit = {}
        val writes = mutableListOf<Pair<String, Map<String, String>>>()
        fun response(): String {
            val tracks = JSONArray()
            rows.forEachIndexed { index, id ->
                val row = JSONObject().put("id", id).put("albumId", "7")
                if (index != shortRow) row.put("track", JSONObject().put("id", id).put("title", "Song $id"))
                if (embeddedAlbums) {
                    row.remove("albumId")
                    row.getJSONObject("track").put("albums", JSONArray().put(JSONObject().put("id", 7)))
                }
                tracks.put(row)
            }
            val result = JSONObject().put("kind", 77).put("owner", JSONObject().put("uid", 123))
                .put("title", title).put("trackCount", declaredCount ?: rows.size).put("tracks", tracks)
            if (!omitRevision) result.put("revision", revision)
            return JSONObject().put("result", result).toString()
        }
        fun api(accounts: AccountAuth) = YandexPlaylistApi(YandexMusicApi(accounts, MusicTransport { url, token, form ->
            assertEquals("fixture-owner", token)
            if (url.endsWith("/tracks")) """{"result":[]}"""
            else if (form == null) {
                reads++; onRead()
                if (failReadBack && writes.isNotEmpty()) throw MusicException(MusicFailure.NETWORK)
                response()
            } else {
                writes += url to form.toMap(); failure?.let { throw it }
                if (url.endsWith("/name")) title = form.toMap().getValue("value")
                else {
                    assertEquals(revision.toString(), form.toMap()["revision"])
                    val diff = JSONArray(form.toMap().getValue("diff"))
                    for (i in 0 until diff.length()) {
                        val op = diff.getJSONObject(i)
                        if (op.getString("op") == "delete") rows.subList(op.getInt("from"), op.getInt("to")).clear()
                        else rows.add(op.getInt("at"), op.getJSONArray("tracks").getJSONObject(0).getString("id"))
                    }
                }
                revision++; response()
            }
        }))
    }
    @Test fun renameUsesNameValueAndConfirmsServerTitle() = runBlocking {
        val server = Server(); val api = server.api(auth(this)); val before = api.load(owner, playlist)
        val after = api.rename(owner, before, " New name ")
        assertEquals("New name", after.playlist.title); assertEquals(before.tracks, after.tracks)
        assertEquals(mapOf("value" to "New name"), server.writes.single().second)
        assertTrue(server.writes.single().first.endsWith("/users/123/playlists/77/name")); assertEquals(3, server.reads)
    }
    @Test fun removeTargetsOnlyChosenDuplicateAndPreservesUnavailableSlot() = runBlocking {
        val server = Server().apply { shortRow = 1 }; val api = server.api(auth(this)); val before = api.load(owner, playlist)
        assertEquals(4, before.tracks.size); assertEquals("2", before.tracks[1].id)
        val after = api.edit(owner, before, CloudTrackEdit.Remove(2))
        assertEquals(listOf("1", "2", "3"), after.tracks.map { it.id })
        val diff = JSONArray(server.writes.single().second.getValue("diff")); assertEquals(1, diff.length())
        assertEquals("delete", diff.getJSONObject(0).getString("op")); assertEquals(2, diff.getJSONObject(0).getInt("from")); assertEquals(3, diff.getJSONObject(0).getInt("to"))
    }
    @Test fun moveInBothDirectionsUsesOneChangeWithDeleteThenInsertAtFinalIndex() = runBlocking {
        for ((from, to, expected) in listOf(Triple(0, 3, listOf("2", "1", "3", "1")), Triple(3, 0, listOf("3", "1", "2", "1")))) {
            val server = Server(); val api = server.api(auth(this)); val before = api.load(owner, playlist)
            val after = api.edit(owner, before, CloudTrackEdit.Move(from, to))
            assertEquals(expected, after.tracks.map { it.id }); assertEquals(4, after.playlist.trackCount)
            val form = server.writes.single().second; assertEquals("12", form["revision"])
            val diff = JSONArray(form.getValue("diff")); assertEquals(2, diff.length())
            assertEquals(from, diff.getJSONObject(0).getInt("from")); assertEquals(from + 1, diff.getJSONObject(0).getInt("to"))
            val insert = diff.getJSONObject(1); assertEquals("insert", insert.getString("op")); assertEquals(to, insert.getInt("at"))
            assertEquals(before.tracks[from].id, insert.getJSONArray("tracks").getJSONObject(0).getString("id"))
            assertEquals("7", insert.getJSONArray("tracks").getJSONObject(0).getString("albumId"))
        }
    }
    @Test fun staleRevisionOrOrderRejectsWriteBeforePost() = runBlocking {
        for (changedRevision in listOf(true, false)) {
            val server = Server(); val api = server.api(auth(this)); val before = api.load(owner, playlist)
            if (changedRevision) server.revision++ else server.rows.reverse()
            val failure = runCatching { api.edit(owner, before, CloudTrackEdit.Remove(0)) }.exceptionOrNull()
            assertEquals(409, (failure as MusicException).httpStatus); assertTrue(server.writes.isEmpty())
        }
    }
    @Test fun truncatedListsOrMissingRevisionCannotSupplyEditingIndices() = runBlocking {
        for (missingRevision in listOf(true, false)) {
            val server = Server().apply { if (missingRevision) omitRevision = true else declaredCount = 9 }
            assertTrue(runCatching { server.api(auth(this)).load(owner, playlist) }.isFailure)
            assertTrue(server.writes.isEmpty())
        }
    }
    @Test fun zeroRevisionAndEmbeddedAlbumsMatchObservedPublicPlaylistShape() = runBlocking {
        // Anonymous public playlist response observed on 2026-09-23 (see M7.4 evidence).
        val server = Server().apply { revision = 0; embeddedAlbums = true }
        val api = server.api(auth(this)); val before = api.load(owner, playlist)
        assertEquals(0L, before.revision); assertTrue(before.tracks.all { it.albumId == "7" && it.movable })
        val after = api.edit(owner, before, CloudTrackEdit.Move(0, 3))
        assertEquals("0", server.writes.single().second["revision"])
        assertEquals(listOf("2", "1", "3", "1"), after.tracks.map { it.id })
    }
    @Test fun changingProfileAfterValidationPreventsRenameAndTrackMutation() = runBlocking {
        for (rename in listOf(true, false)) {
            val server = Server(); val accounts = auth(this); val api = server.api(accounts); val before = api.load(owner, playlist)
            server.onRead = { accounts.activate("road"); yield() }
            assertTrue(runCatching { if (rename) api.rename(owner, before, "New") else api.edit(owner, before, CloudTrackEdit.Remove(0)) }.isFailure)
            assertTrue(server.writes.isEmpty())
        }
    }
    @Test fun failedWriteOrReadBackNeverReplaysTheMutation() = runBlocking {
        for (status in listOf(409, 403, 500, null)) {
            val server = Server(); val api = server.api(auth(this)); val before = api.load(owner, playlist)
            server.failure = MusicException(MusicFailure.NETWORK, status)
            assertTrue(runCatching { api.edit(owner, before, CloudTrackEdit.Move(0, 3)) }.isFailure)
            assertEquals(1, server.writes.size); assertEquals(listOf("1", "2", "1", "3"), server.rows)
        }
        val server = Server(); val api = server.api(auth(this)); val before = api.load(owner, playlist); server.failReadBack = true
        assertTrue(runCatching { api.edit(owner, before, CloudTrackEdit.Remove(0)) }.isFailure)
        assertEquals(1, server.writes.size); assertEquals(listOf("2", "1", "3"), server.rows)
    }
}
