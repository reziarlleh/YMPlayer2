package dev.petrov.ymplayer2

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.petrov.ymplayer2.core.*
import dev.petrov.ymplayer2.yandex.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecommendationAdapterTest {
    @Test fun productionTransportSendsJsonBodyAndFormBodyWithSeparateContentTypes() = runBlocking {
        val requests = mutableListOf<Triple<String, String, String>>()
        val transport = HttpsMusicTransport { address -> object : javax.net.ssl.HttpsURLConnection(java.net.URL(address)) {
            val body = java.io.ByteArrayOutputStream()
            override fun getOutputStream() = body
            override fun getInputStream() = """{"result":"ok"}""".byteInputStream()
            override fun getResponseCode(): Int {
                assertEquals("POST", requestMethod); assertEquals("OAuth fixture", getRequestProperty("Authorization"))
                assertFalse(instanceFollowRedirects)
                requests += Triple(address, getRequestProperty("Content-Type"), body.toString("UTF-8")); return 200
            }
            override fun disconnect() = Unit
            override fun usingProxy() = false
            override fun connect() = Unit
            override fun getCipherSuite() = "fixture"
            override fun getLocalCertificates(): Array<java.security.cert.Certificate>? = null
            override fun getServerCertificates(): Array<java.security.cert.Certificate> = emptyArray()
        } }
        transport.json("https://api.music.yandex.net/rotor/session/new", "fixture", """{"seeds":["user:onyourwave"],"queue":[]}""")
        transport.request("https://api.music.yandex.net/users/123/likes/tracks/add-multiple", "fixture", listOf("track-ids" to "1:7"))
        assertEquals("application/json; charset=UTF-8", requests[0].second)
        assertEquals("user:onyourwave", JSONObject(requests[0].third).getJSONArray("seeds").getString(0))
        assertEquals("application/x-www-form-urlencoded; charset=UTF-8", requests[1].second)
        assertEquals("track-ids=1%3A7", requests[1].third)
    }
    private suspend fun auth(scope: CoroutineScope) = AccountAuth(DemoCatalog().profiles, object : DeviceAuthApi {
        override val configured = true
        override suspend fun requestCode(profileId: String) = error("unused")
        override suspend fun poll(code: DeviceChallenge) = error("unused")
        override suspend fun account(credentials: OAuthCredentials) = error("unused")
    }, object : AccountStore {
        override suspend fun read(profileId: String) = AccountSession(YandexAccount("123", "Fixture"), OAuthCredentials("fixture", null, null))
        override suspend fun write(profileId: String, session: AccountSession?) = Unit
    }, scope).also { it.activate("owner"); yield() }
    private fun track(id: Int) = """{"id":$id,"title":"Song","durationMs":20000,"artists":[{"id":5,"name":"One"},{"id":6,"name":"Two"}],"albums":[{"id":7,"title":"Album"}]}"""
    private class Transport : MusicTransport {
        val writes = mutableListOf<Pair<String, List<Pair<String, String>>>>()
        var response: (String, List<Pair<String, String>>?) -> String = { _, _ -> """{"result":"ok"}""" }
        var body: JSONObject? = null
        var jsonResponse: (String) -> String = { error("unexpected JSON request: $it") }
        override suspend fun request(url: String, token: String?, form: List<Pair<String, String>>?): String {
            assertEquals("fixture", token)
            if (form != null) writes += url.substringAfter(".net") to form
            return response(url, form)
        }
        override suspend fun json(url: String, token: String, body: String): String {
            assertEquals("fixture", token); this.body = JSONObject(body); return jsonResponse(url)
        }
    }
    @Test fun favouriteShelvesPreserveArtistAlbumIdentityWithoutLoadingTheirTracks() = runBlocking {
        val transport = Transport().apply { response = { url, _ -> when {
            "/likes/artists" in url -> """{"result":[{"artist":{"id":5,"name":"One"},"timestamp":"2026-01-01"}]}"""
            "/likes/albums" in url -> """{"result":[{"id":7,"album":{"id":7,"title":"Album"}}]}"""
            else -> error("Unexpected request $url")
        } } }
        val music = YandexMusicApi(auth(this), transport)
        assertEquals(MusicKind.ARTISTS, music.page("owner", MusicRequest(kind = MusicKind.ARTISTS, collection = true), 0).entries.single().entity!!.kind)
        assertEquals("7", music.page("owner", MusicRequest(kind = MusicKind.ALBUMS, collection = true), 0).entries.single().entity!!.id)
        assertTrue(transport.writes.isEmpty())
    }
    @Test fun trackArtistAndAlbumWritesUseSeparateEndpointsAndFormKeys() = runBlocking {
        val transport = Transport().apply { response = { url, form -> when {
            form != null -> if ("/tracks/" in url) """{"result":{"revision":9}}""" else """{"result":"ok"}"""
            "/tracks?" in url -> """{"result":{"library":{"tracks":[]}}}"""
            else -> """{"result":[]}"""
        } } }
        val api = YandexTasteApi(YandexMusicApi(auth(this), transport))
        api.react("owner", TasteTarget(TasteKind.TRACK, "1:7", "Song"), TasteAction.LIKE)
        api.react("owner", TasteTarget(TasteKind.ARTIST, "5", "One"), TasteAction.BLOCK)
        api.react("owner", TasteTarget(TasteKind.ALBUM, "7", "Album"), TasteAction.LIKE)
        api.react("owner", TasteTarget(TasteKind.ARTIST, "5", "One"), TasteAction.UNBLOCK)
        assertEquals(listOf(
            "/users/123/likes/tracks/add-multiple" to listOf("track-ids" to "1:7"),
            "/users/123/dislikes/artists/add-multiple" to listOf("artist-ids" to "5"),
            "/users/123/likes/albums/add-multiple" to listOf("album-ids" to "7"),
            "/users/123/dislikes/artists/remove" to listOf("artist-ids" to "5")), transport.writes)
    }
    @Test fun unlikeIsNotDislikeAndLegacyRemoveFallbackOnlyUsesSameTrackTarget() = runBlocking {
        val transport = Transport().apply { response = { url, _ ->
            if (url.endsWith("/tracks/remove")) throw MusicException(MusicFailure.UNAVAILABLE)
            """{"result":{"revision":10}}"""
        } }
        YandexTasteApi(YandexMusicApi(auth(this), transport)).react("owner", TasteTarget(TasteKind.TRACK, "1:7", "Song"), TasteAction.UNLIKE)
        assertEquals(2, transport.writes.size)
        assertTrue(transport.writes.last().first.endsWith("/likes/tracks/1%3A7/remove"))
        assertTrue(transport.writes.none { "dislikes" in it.first })
    }
    @Test fun preferenceListsReadNestedLikesAndDirectDislikedArtists() = runBlocking {
        val transport = Transport().apply { response = { url, _ -> when {
            "/likes/artists" in url -> """{"result":[{"artist":{"id":5,"name":"One"}}]}"""
            "/dislikes/artists" in url -> """{"result":[{"id":6,"name":"Two"}]}"""
            "/likes/tracks" in url -> """{"result":{"library":{"tracks":[{"id":1,"albumId":7}]}}}"""
            else -> """{"result":{"library":{"tracks":[{"id":2,"albumId":8}]}}}"""
        } } }
        val api = YandexTasteApi(YandexMusicApi(auth(this), transport))
        assertEquals(TasteList(setOf("5"), setOf("6")), api.taste("owner", TasteKind.ARTIST))
        assertEquals(TasteList(setOf("1"), setOf("2")), api.taste("owner", TasteKind.TRACK))
    }
    @Test fun recommendationsUnwrapPersonalPlaylistAndFallbackFeed() = runBlocking {
        val playlist = """{"kind":42,"title":"For you","owner":{"uid":55},"trackCount":10}"""
        val transport = Transport().apply { response = { _, _ -> """{"result":{"blocks":[{"type":"personalplaylists","entities":[{"type":"personal-playlist","data":{"type":"playlistOfTheDay","ready":true,"data":$playlist}}]}]}}""" } }
        val music = YandexMusicApi(auth(this), transport)
        val request = MusicRequest(collection = true, recommended = true)
        assertEquals("55", music.page("owner", request, 0).entries.single().entity!!.ownerId)
        transport.response = { url, _ -> if ("landing3" in url) """{"result":{"blocks":[]}}""" else """{"result":{"generatedPlaylists":[{"data":$playlist}]}}""" }
        assertEquals("42", music.page("owner", request, 0).entries.single().entity!!.id)
    }
    @Test fun waveStartPostsLegacySessionBodyAndCarriesBatchAndAllArtists() = runBlocking {
        val transport = Transport().apply { jsonResponse = { url ->
            assertTrue(url.endsWith("/rotor/session/new"))
            """{"result":{"radioSessionId":"session","batch_id":"b1","sequence":[{"track":${track(1)}}]}}"""
        } }
        val api = YandexWaveApi(YandexMusicApi(auth(this), transport))
        val batch = api.start("owner")
        assertEquals("user:onyourwave", transport.body!!.getJSONArray("seeds").getString(0))
        assertEquals(0, transport.body!!.getJSONArray("queue").length())
        assertTrue(transport.body!!.getBoolean("includeTracksInResponse"))
        assertTrue(transport.body!!.getBoolean("includeWaveModel")); assertTrue(transport.body!!.getBoolean("interactive"))
        assertEquals("1", batch.cursor); assertEquals("session", batch.sessionId)
        assertEquals("b1", batch.tracks.single().batchId)
        assertEquals(listOf("5", "6"), batch.tracks.single().track.artists.map(ArtistRef::id))
    }
    @Test fun waveContinuationSkipsEchoedCursorAndResolvesLegacyIdAliases() = runBlocking {
        val transport = Transport().apply {
            jsonResponse = { url -> assertTrue(url.endsWith("/session/s/tracks")); """{"result":{"batchId":"b2","sequence":[{"track":${track(1)}},{"track":{"track_id":"2"}}]}}""" }
            response = { url, form -> assertTrue(url.endsWith("/tracks")); assertTrue(form!!.contains("track-ids" to "2")); """{"result":[${track(2)}]}""" }
        }
        val batch = YandexWaveApi(YandexMusicApi(auth(this), transport)).next("owner", WaveBatch(emptyList(), "s", "1"))
        assertEquals("1", transport.body!!.getJSONArray("queue").getString(0))
        assertEquals("2", batch.cursor); assertEquals("yandex:2:7", batch.tracks.single().track.id)
    }
    @Test fun waveStationFallbackAndFeedbackKeepBatchSeparateFromPermanentDislikes() = runBlocking {
        val transport = Transport().apply {
            jsonResponse = { throw MusicException(MusicFailure.UNAVAILABLE) }
            response = { url, form -> if (form == null) {
                assertTrue(url.endsWith("/user:onyourwave/tracks?settings2=True"))
                """{"result":{"batchId":"station-batch","sequence":[{"track":${track(1)}}]}}"""
            } else """{"result":"ok"}""" }
        }
        val api = YandexWaveApi(YandexMusicApi(auth(this), transport)); val batch = api.start("owner")
        assertEquals("", batch.sessionId)
        api.feedback("owner", batch.tracks.single(), WaveFeedback.RADIO_STARTED)
        api.feedback("owner", batch.tracks.single(), WaveFeedback.SKIP, 9)
        assertTrue(transport.writes.all { it.first == "/rotor/station/user:onyourwave/feedback?batch-id=station-batch" })
        assertTrue(transport.writes.first().second.contains("from" to "mobile-radio-user-123"))
        assertTrue(transport.writes.last().second.contains("totalPlayedSeconds" to "9"))
        assertTrue(transport.writes.last().second.contains("trackId" to "1"))
    }
    @Test fun echoedListeningHistoryDoesNotHideThirdAndFourthRecommendations() = runBlocking {
        var last = 3
        val transport = Transport().apply { jsonResponse = { url ->
            assertTrue(url.endsWith("/session/s/tracks"))
            """{"result":{"batchId":"b$last","sequence":[${(1..last).joinToString(",") { """{"track":${track(it)}}""" }}]}}"""
        } }
        val api = YandexWaveApi(YandexMusicApi(auth(this), transport))
        var previous = WaveBatch(emptyList(), "s", "2")
        for (id in 3..4) {
            last = id
            val next = WaveLoader(api).load("owner", previous, (1 until id).mapTo(hashSetOf()) { it.toString() }) { true }
            assertEquals(id.toString(), next.cursor)
            assertEquals("yandex:$id:7", next.tracks.single().track.id)
            assertEquals("b$id", next.tracks.single().batchId)
            previous = next
        }
    }
}
