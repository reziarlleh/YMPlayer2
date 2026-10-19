package dev.petrov.ymplayer2

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.petrov.ymplayer2.core.*
import dev.petrov.ymplayer2.yandex.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WaveSettingsAdapterTest {
    private fun fixture() = JSONObject(InstrumentationRegistry.getInstrumentation().context.assets
        .open("wave24-settings-public.json").bufferedReader().use { it.readText() })
    private suspend fun auth(scope: CoroutineScope): AccountAuth {
        val session = AccountSession(YandexAccount("123", "Fixture"), OAuthCredentials("fixture-token", null, null))
        return AccountAuth(DemoCatalog().profiles, object : DeviceAuthApi {
            override val configured = true
            override suspend fun requestCode(profileId: String) = error("unused")
            override suspend fun poll(code: DeviceChallenge) = error("unused")
            override suspend fun account(credentials: OAuthCredentials) = session.account!!
        }, object : AccountStore {
            override suspend fun read(profileId: String) = session
            override suspend fun write(profileId: String, session: AccountSession?) = Unit
        }, scope).also { it.activate("owner"); yield() }
    }
    @Test fun realPublicSettingsShapeKeepsRegionalLabelsAndNeverUsesDefaultUserId() = runBlocking {
        val options = YandexWaveApi(YandexMusicApi(auth(this), MusicTransport { url, token, form ->
            assertTrue(url.contains("/rotor/wave/settings?seeds=user%3Aonyourwave"))
            assertEquals("fixture-token", token); assertNull(form); fixture().toString()
        })).options("owner", "ru")
        assertTrue("Any must come first in every group", options.groups.all { it.values.first().unspecified })
        assertEquals(4, options.groups.size)
        val contexts = options.groups.first().values
        assertTrue(contexts.any { it.seed == "activity:road-trip" }); assertTrue(contexts.any { it.seed == "mood:relaxed" })
        assertFalse(contexts.any { it.seed.contains("server-default") })
        assertEquals("Казахский", options.groups.last().values.first { it.seed == "settingLanguage:russian" }.title)
        assertFalse(options.groups.last().values.any { it.seed == "settingLanguage:ru" })
    }
    @Test fun customSeedsContinuationAndFeedbackStayOnChosenActivity() = runBlocking {
        val selection = WaveRequest("activity:road-trip", "В дороге", listOf("settingMoodEnergy:calm", "settingLanguage:russian"))
        val requests = mutableListOf<String>()
        val transport = object : MusicTransport {
            override suspend fun json(url: String, token: String, body: String): String {
                assertEquals("fixture-token", token); requests += url
                val request = JSONObject(body)
                if (url.endsWith("/new")) assertEquals(selection.seeds, (0 until request.getJSONArray("seeds").length()).map { request.getJSONArray("seeds").getString(it) })
                else assertEquals("7", request.getJSONArray("queue").getString(0))
                return """{"result":{"radioSessionId":"custom-session","batchId":"b1","sequence":[{"track":{"id":7,"title":"Fixture","durationMs":30000,"available":true,"artists":[],"albums":[{"id":1,"title":"Album"}]}}]}}"""
            }
            override suspend fun request(url: String, token: String?, form: List<Pair<String, String>>?): String {
                assertTrue(url.contains("activity:road-trip/feedback")); requests += url
                return """{"result":"ok"}"""
            }
        }
        val api = YandexWaveApi(YandexMusicApi(auth(this), transport))
        val first = api.start("owner", selection)
        assertEquals(selection, first.request); assertEquals(selection.station, first.tracks.single().station)
        api.next("owner", first); api.feedback("owner", first.tracks.single(), WaveFeedback.STARTED)
        assertEquals(3, requests.size); assertTrue(requests[1].contains("custom-session/tracks"))
    }
    @Test fun echoedWholeBatchAdvancesItsLastCursorWithoutStartingANewSession() = runBlocking {
        val cursors = mutableListOf<String>()
        val transport = object : MusicTransport {
            override suspend fun json(url: String, token: String, body: String): String {
                assertTrue(url.endsWith("/kept-session/tracks"))
                val cursor = JSONObject(body).getJSONArray("queue").getString(0)
                cursors.add(cursor)
                val ids = if (cursor == "3") listOf(1, 2) else listOf(3, 4)
                val sequence = org.json.JSONArray(ids.map { id -> JSONObject().put("track", JSONObject()
                    .put("id", id).put("title", "Track $id").put("available", true)
                    .put("durationMs", 30000).put("artists", org.json.JSONArray()).put("albums", org.json.JSONArray())) })
                return JSONObject().put("result", JSONObject().put("batchId", "echo")
                    .put("sequence", sequence)).toString()
            }
            override suspend fun request(url: String, token: String?, form: List<Pair<String, String>>?): String = error("No legacy reset")
        }
        val api = YandexWaveApi(YandexMusicApi(auth(this), transport))
        val next = WaveLoader(api).load("owner", WaveBatch(emptyList(), "kept-session", "3"), setOf("1", "2", "3")) { true }
        assertEquals("4", next.tracks.single().track.tasteTarget().key)
        assertEquals(listOf("3", "2"), cursors)
        assertEquals("kept-session", next.sessionId)
    }
    @Test fun failedCustomSessionDoesNotFallBackToUnfilteredWave() = runBlocking {
        var legacyRequests = 0
        val api = YandexWaveApi(YandexMusicApi(auth(this), object : MusicTransport {
            override suspend fun json(url: String, token: String, body: String): String = throw MusicException(MusicFailure.NETWORK)
            override suspend fun request(url: String, token: String?, form: List<Pair<String, String>>?): String { legacyRequests++; error("must not drop settings") }
        }))
        try { api.start("owner", WaveRequest(settings = listOf("settingLanguage:russian"))); fail() }
        catch (e: MusicException) { assertEquals(MusicFailure.NETWORK, e.failure) }
        assertEquals(0, legacyRequests)
    }
    @Test fun repeatedTenTrackSessionUsesLegacyRecoveryWithOriginalStationAndCursor() = runBlocking {
        var legacy = 0
        fun batch(ids: List<Int>) = JSONObject().put("result", JSONObject().put("batchId", "fixture")
            .put("sequence", org.json.JSONArray(ids.map { id -> JSONObject().put("track", JSONObject().put("id", id)
                .put("title", "Track $id").put("available", true).put("durationMs", 30000)
                .put("artists", org.json.JSONArray()).put("albums", org.json.JSONArray())) }))).toString()
        val api = YandexWaveApi(YandexMusicApi(auth(this), object : MusicTransport {
            override suspend fun json(url: String, token: String, body: String) = batch((1..10).toList())
            override suspend fun request(url: String, token: String?, form: List<Pair<String, String>>?): String {
                assertTrue(url.contains("/rotor/station/user:onyourwave/tracks")); assertTrue(url.contains("queue=10"))
                legacy++; return batch(listOf(11))
            }
        }))
        val next = WaveLoader(api).load("owner", WaveBatch(emptyList(), "stuck", "10"), (1..10).map { it.toString() }.toSet()) { true }
        assertEquals("11", next.cursor); assertEquals(1, legacy)
    }
    @Test fun objectSessionAndLegacyFallbackNeverReplaceTheRequestedSeed() = runBlocking {
        for (seed in listOf("track:7", "artist:8", "album:9", "playlist:100_3")) {
            var modern = 0; var legacy = 0
            val request = WaveRequest(seed, "Fixture")
            val api = YandexWaveApi(YandexMusicApi(auth(this), object : MusicTransport {
                override suspend fun json(url: String, token: String, body: String): String {
                    modern++
                    assertEquals(seed, JSONObject(body).getJSONArray("seeds").getString(0))
                    throw MusicException(MusicFailure.NETWORK)
                }
                override suspend fun request(url: String, token: String?, form: List<Pair<String, String>>?): String {
                    legacy++; assertTrue(url.contains("/station/$seed/tracks")); assertFalse(url.contains("user:onyourwave"))
                    return """{"result":{"batchId":"b1","sequence":[{"track":{"id":7,"title":"Fixture","durationMs":30000,"available":true,"artists":[],"albums":[{"id":1,"title":"Album"}]}}]}}"""
                }
            }))
            val batch = api.start("owner", request)
            assertEquals(request, batch.request); assertEquals(seed, batch.tracks.single().station)
            api.next("owner", batch)
            assertEquals(1, modern); assertEquals(2, legacy)
        }
    }
    @Test fun temporarySessionFailureThenThreeLegacyTracksRecoversWithoutReplayingHistory() = runBlocking {
        var sessions = 0
        var stations = 0
        val api = YandexWaveApi(YandexMusicApi(auth(this), object : MusicTransport {
            override suspend fun json(url: String, token: String, body: String): String {
                assertTrue(url.endsWith("/rotor/session/new"))
                sessions++
                if (sessions == 1) throw MusicException(MusicFailure.NETWORK, httpStatus = 503)
                val request = JSONObject(body)
                assertEquals("user:onyourwave", request.getJSONArray("seeds").getString(0))
                assertEquals(listOf("3"), request.getJSONArray("queue").let { rows ->
                    (0 until rows.length()).map(rows::getString)
                })
                return waveReply(listOf(4, 5), "recovered-session")
            }
            override suspend fun request(url: String, token: String?, form: List<Pair<String, String>>?): String {
                assertTrue(url.contains("/rotor/station/user:onyourwave/tracks"))
                stations++
                return waveReply(listOf(1, 2, 3))
            }
        }))
        val loader = WaveLoader(api)
        val history = linkedSetOf<String>()
        var previous: WaveBatch? = null
        val selected = (1..4).map {
            val next = loader.load("owner", previous, history) { true }
            previous = next
            next.tracks.single().track.tasteTarget().key.also(history::add)
        }
        assertEquals(listOf("1", "2", "3", "4"), selected)
        assertEquals(setOf("1", "2", "3", "4"), history)
        assertEquals("recovered-session", previous!!.sessionId)
        assertEquals(2, sessions)
        assertTrue(stations >= 3)
    }
    @Test fun stuckLegacyRecoveryUsesLatestDrainedCursorAndPreservesEveryObjectSeed() = runBlocking {
        for (seed in listOf("track:7", "artist:8", "album:9", "playlist:100_3")) {
            val request = WaveRequest(seed, "Fixture")
            val stationCursors = mutableListOf<String>()
            var sessions = 0
            val api = YandexWaveApi(YandexMusicApi(auth(this), object : MusicTransport {
                override suspend fun json(url: String, token: String, body: String): String {
                    assertTrue(url.endsWith("/rotor/session/new")); sessions++
                    val sent = JSONObject(body)
                    assertEquals(listOf(seed), sent.getJSONArray("seeds").let { rows ->
                        (0 until rows.length()).map(rows::getString)
                    })
                    assertEquals("3", sent.getJSONArray("queue").getString(0))
                    return waveReply(listOf(4), "object-session")
                }
                override suspend fun request(url: String, token: String?, form: List<Pair<String, String>>?): String {
                    assertTrue(url.contains("/station/$seed/tracks"))
                    stationCursors += url.substringAfter("&queue=")
                    return waveReply(listOf(2, 3))
                }
            }))
            val next = WaveLoader(api).load("owner", WaveBatch(emptyList(), cursor = "1", request = request),
                setOf("1", "2", "3"), request) { true }
            assertEquals(listOf("1", "3"), stationCursors)
            assertEquals(1, sessions)
            assertEquals("4", next.cursor)
            assertEquals(request, next.request)
            assertEquals(seed, next.tracks.single().station)
        }
    }
    @Test fun customRecoveryRetainsAllSettingsAndNeverUsesLegacy() = runBlocking {
        val selection = WaveRequest("activity:road-trip", "В дороге", listOf("settingMoodEnergy:calm", "settingLanguage:russian"))
        var sessions = 0
        val api = YandexWaveApi(YandexMusicApi(auth(this), object : MusicTransport {
            override suspend fun json(url: String, token: String, body: String): String {
                assertTrue(url.endsWith("/rotor/session/new")); sessions++
                val sent = JSONObject(body)
                assertEquals(selection.seeds, sent.getJSONArray("seeds").let { rows ->
                    (0 until rows.length()).map(rows::getString)
                })
                assertEquals("3", sent.getJSONArray("queue").getString(0))
                return waveReply(listOf(4), "custom-recovered")
            }
            override suspend fun request(url: String, token: String?, form: List<Pair<String, String>>?): String = error("Custom settings cannot be dropped")
        }))
        val recovered = api.recover("owner", WaveBatch(emptyList(), "old-session", "3", selection), selection)
        assertEquals(1, sessions)
        assertEquals("custom-recovered", recovered.sessionId)
        assertEquals(selection, recovered.request)
        assertEquals(selection.station, recovered.tracks.single().station)
    }
    @Test fun unavailableModernRecoveryFallsBackToSameLegacyStationAndCursor() = runBlocking {
        val selection = WaveRequest("artist:8", "Fixture")
        var sessions = 0
        var stations = 0
        val api = YandexWaveApi(YandexMusicApi(auth(this), object : MusicTransport {
            override suspend fun json(url: String, token: String, body: String): String {
                assertTrue(url.endsWith("/rotor/session/new")); sessions++
                val sent = JSONObject(body)
                assertEquals(selection.station, sent.getJSONArray("seeds").getString(0))
                assertEquals("9", sent.getJSONArray("queue").getString(0))
                throw MusicException(MusicFailure.NETWORK, httpStatus = 503)
            }
            override suspend fun request(url: String, token: String?, form: List<Pair<String, String>>?): String {
                assertTrue(url.contains("/station/artist:8/tracks"))
                assertTrue(url.contains("&queue=9")); stations++
                return waveReply(listOf(10))
            }
        }))
        val recovered = api.recover("owner", WaveBatch(emptyList(), cursor = "9", request = selection), selection)
        assertEquals(1, sessions); assertEquals(1, stations)
        assertEquals("10", recovered.cursor)
        assertEquals(selection, recovered.request)
        assertEquals(selection.station, recovered.tracks.single().station)
    }
    @Test fun authenticationFailureDuringLegacyRecoveryNeverFallsBackOrResetsTheWave() = runBlocking {
        for (failure in listOf(MusicFailure.SIGN_IN, MusicFailure.ACCESS)) {
            var stations = 0
            val api = YandexWaveApi(YandexMusicApi(auth(this), object : MusicTransport {
                override suspend fun json(url: String, token: String, body: String): String = throw MusicException(failure)
                override suspend fun request(url: String, token: String?, form: List<Pair<String, String>>?): String {
                    stations++; error("Authentication failure must not fall back")
                }
            }))
            try { api.recover("owner", WaveBatch(emptyList(), cursor = "3"), WaveRequest()); fail("Authentication failure accepted") }
            catch (e: MusicException) { assertEquals(failure, e.failure) }
            assertEquals(0, stations)
        }
    }
    @Test fun emptyCustomRecoveryKeepsCursorForTheNextAttempt() = runBlocking {
        val request = WaveRequest(settings = listOf("settingLanguage:russian"))
        val cursors = mutableListOf<String>()
        val api = YandexWaveApi(YandexMusicApi(auth(this), object : MusicTransport {
            override suspend fun json(url: String, token: String, body: String): String {
                cursors += JSONObject(body).getJSONArray("queue").getString(0)
                return waveReply(emptyList(), "new-session")
            }
            override suspend fun request(url: String, token: String?, form: List<Pair<String, String>>?): String = error("No unfiltered fallback")
        }))
        val empty = api.recover("owner", WaveBatch(emptyList(), "old-session", "3", request), request)
        assertEquals("3", empty.cursor)
        api.recover("owner", empty, request)
        assertEquals(listOf("3", "3"), cursors)
    }
    @Test fun failedLegacyAfterEmptyModernReplyIsNotRequestedTwice() = runBlocking {
        var stations = 0
        val api = YandexWaveApi(YandexMusicApi(auth(this), object : MusicTransport {
            override suspend fun json(url: String, token: String, body: String) = waveReply(emptyList(), "empty-session")
            override suspend fun request(url: String, token: String?, form: List<Pair<String, String>>?): String {
                stations++
                throw MusicException(MusicFailure.NETWORK, httpStatus = 503)
            }
        }))
        try { api.recover("owner", WaveBatch(emptyList(), cursor = "3"), WaveRequest()); fail() }
        catch (e: MusicException) { assertEquals(503, e.httpStatus) }
        assertEquals(1, stations)
    }
    private fun waveReply(ids: List<Int>, session: String = ""): String = JSONObject().put("result", JSONObject()
        .put("radioSessionId", session).put("batchId", "fixture")
        .put("sequence", org.json.JSONArray(ids.map { id -> JSONObject().put("track", JSONObject().put("id", id)
            .put("title", "Track $id").put("available", true).put("durationMs", 30000)
            .put("artists", org.json.JSONArray()).put("albums", org.json.JSONArray())) }))).toString()
}
