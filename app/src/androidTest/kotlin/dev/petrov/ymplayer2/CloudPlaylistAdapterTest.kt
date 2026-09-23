package dev.petrov.ymplayer2

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.petrov.ymplayer2.core.*
import dev.petrov.ymplayer2.yandex.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CloudPlaylistAdapterTest {
    private val owner = PlaylistOwner("owner", "123")
    private val list = CloudPlaylist("77", "123", "Road", 999)
    private val track = Track("yandex:5:8", "Song", "Artist", "Album", Source.YANDEX, 60, false)
    private fun response(count: Int = 2) = """{"result":{"kind":77,"title":"Road","trackCount":$count,"revision":12,"owner":{"uid":123}}}"""
    private suspend fun auth(scope: CoroutineScope) = AccountAuth(DemoCatalog().profiles, object : DeviceAuthApi {
        override val configured = true
        override suspend fun requestCode(profileId: String) = error("unused")
        override suspend fun poll(code: DeviceChallenge) = error("unused")
        override suspend fun account(credentials: OAuthCredentials) = error("unused")
    }, object : AccountStore {
        override suspend fun read(profileId: String) = AccountSession(YandexAccount(if (profileId == "owner") "123" else "456", "Fixture"), OAuthCredentials("fixture-$profileId", null, null))
        override suspend fun write(profileId: String, session: AccountSession?) = Unit
    }, scope).also { it.activate("owner"); yield() }
    @Test fun creationUsesPrivateFormAndOwnTokenWhileForeignListsAreFiltered() = runBlocking {
        val api = YandexPlaylistApi(YandexMusicApi(auth(this), MusicTransport { url, token, form ->
            assertEquals("fixture-owner", token)
            when {
                url.endsWith("/list") -> """{"result":[{"kind":77,"title":"Road"},{"kind":88,"title":"Foreign","owner":{"uid":456}}]}"""
                else -> { assertTrue(url.endsWith("/users/123/playlists/create")); assertEquals(mapOf("title" to "Road", "visibility" to "private"), form!!.toMap()); response(0) }
            }
        }))
        assertEquals(listOf("77"), api.list(owner).map { it.id })
        assertEquals(0, api.create(owner, " Road ").trackCount)
    }
    @Test fun appendFetchesFreshRevisionAndPositionAndKeepsAlbumPair() = runBlocking {
        val urls = mutableListOf<String>()
        val api = YandexPlaylistApi(YandexMusicApi(auth(this), MusicTransport { url, _, form ->
            urls += url
            if (form == null) response() else {
                assertEquals("12", form.toMap()["revision"]); assertEquals("77", form.toMap()["kind"])
                val diff = JSONArray(form.toMap().getValue("diff")).getJSONObject(0)
                assertEquals("insert", diff.getString("op")); assertEquals(2, diff.getInt("at"))
                val row = diff.getJSONArray("tracks").getJSONObject(0)
                assertEquals("5", row.getString("id")); assertEquals("8", row.getString("albumId")); response(3)
            }
        }))
        assertEquals(3, api.add(owner, list, track).trackCount)
        assertTrue(urls[0].endsWith("/77")); assertTrue(urls[1].endsWith("/77/change")); assertEquals(2, urls.size)
    }
    @Test fun appendUsesZeroRevisionWhenServerReturnsIt() = runBlocking {
        var posted = false
        val api = YandexPlaylistApi(YandexMusicApi(auth(this), MusicTransport { _, _, form ->
            if (form == null) response(0).replace("\"revision\":12", "\"revision\":0")
            else {
                posted = true
                assertEquals("0", form.toMap()["revision"])
                response(1)
            }
        }))
        assertEquals(1, api.add(owner, list, track).trackCount)
        assertTrue(posted)
    }
    @Test fun missingChangeEndpointUsesRelativeButTimeoutConflictAndAccessNeverReplay() = runBlocking {
        for (status in listOf(404, 405, 409, 403, 500, null)) {
            val posts = mutableListOf<String>()
            val api = YandexPlaylistApi(YandexMusicApi(auth(this), MusicTransport { url, _, form ->
                if (form == null) response() else {
                    posts += url
                    if (url.endsWith("/change")) throw MusicException(MusicFailure.NETWORK, status)
                    response(3)
                }
            }))
            val result = runCatching { api.add(owner, list, track) }
            if (status in setOf(404,405)) { assertTrue(result.isSuccess); assertEquals(2, posts.size); assertTrue(posts.last().endsWith("/change-relative")) }
            else { assertTrue(result.isFailure); assertEquals(1, posts.size) }
        }
    }
    @Test fun deleteAcceptsEmptyResponseAndUsesLegacyFallbackOnlyForMissingEndpoint() = runBlocking {
        val paths = mutableListOf<String>()
        val api = YandexPlaylistApi(YandexMusicApi(auth(this), MusicTransport { url, _, form ->
            paths += url; assertEquals(mapOf("kind" to "77"), form!!.toMap())
            if (url.endsWith("/77/delete")) throw MusicException(MusicFailure.UNAVAILABLE, 404)
            ""
        }))
        api.delete(owner, list); assertEquals(2, paths.size); assertTrue(paths.last().endsWith("/playlists/delete"))
        assertTrue(runCatching { api.delete(owner, list.copy(ownerId = "456")) }.isFailure); assertEquals(2, paths.size)
    }
    @Test fun profileSwitchAfterReadPreventsTheWrite() = runBlocking {
        val accounts = auth(this); var writes = 0
        val api = YandexPlaylistApi(YandexMusicApi(accounts, MusicTransport { _, _, form ->
            if (form == null) { accounts.activate("road"); yield(); response() } else { writes++; response(3) }
        }))
        assertTrue(runCatching { api.add(owner, list, track) }.isFailure); assertEquals(0, writes)
    }
    @Test fun localAndIncompleteTracksFailBeforeAnyNetworkCall() = runBlocking {
        var calls = 0
        val api = YandexPlaylistApi(YandexMusicApi(auth(this), MusicTransport { _, _, _ -> calls++; response() }))
        assertTrue(runCatching { api.add(owner, list, track.copy(source = Source.LOCAL)) }.isFailure)
        assertTrue(runCatching { api.add(owner, list, track.copy(id = "yandex:5")) }.isFailure)
        assertEquals(0, calls)
    }
}
