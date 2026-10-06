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
class RadioProtocolTest {
    private fun auth(scope: CoroutineScope) = AccountAuth(DemoCatalog().profiles, object : DeviceAuthApi {
        override val configured = true
        override suspend fun requestCode(profileId: String) = error("unused")
        override suspend fun poll(code: DeviceChallenge) = error("unused")
        override suspend fun account(credentials: OAuthCredentials) = error("unused")
    }, object : AccountStore {
        private var saved: AccountSession? = AccountSession(YandexAccount("fixture", "Fixture"), OAuthCredentials("test-only", null, null))
        override suspend fun read(profileId: String) = saved
        override suspend fun write(profileId: String, session: AccountSession?) { saved = session }
    }, scope)

    @Test fun publicCatalogCitiesGenreSearchStreamAndWidgetsUseActualApi() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val api = YandexRadioApi(auth(scope))
            val first = api.stations(null)
            assertTrue(first.stations.isNotEmpty()); assertTrue(first.hasNext)
            assertTrue(api.stations(null, first.cursor).stations.isNotEmpty())
            assertTrue(api.cities().any { it.slug == "moscow" })
            assertTrue(api.genres().any { it.slug == "rock" })
            assertTrue(api.city("moscow").stations.any { it.regionName != null })
            assertTrue(api.genre("rock", null).stations.isNotEmpty())
            assertTrue(api.search("rock", null).stations.isNotEmpty())
            val station = api.station("europa-plus", null)
            val stream = api.stream(station, null)
            assertEquals(station.slug, stream.station.slug); assertTrue(publicRadioUrl(stream.url))
            assertTrue(api.onAir(stream.station.slug, stream.streamSlug).pollAfterMs in 5000..60000)
            try { api.favouriteSlugs("owner"); fail("No active session must not authorize personal reads") }
            catch (e: MusicException) { assertEquals(MusicFailure.SIGN_IN, e.failure) }
        } finally { scope.cancel() }
    }
    @Test fun stationCollectionPostsUseExistingSessionAndExpectedPayloadOnly() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val auth = auth(scope); auth.activate("owner")
            withTimeout(5000) { while (auth.state.value.phase != AuthPhase.SIGNED_IN) delay(10) }
            val calls = mutableListOf<Triple<String, String?, String?>>()
            val api = YandexRadioApi(auth, RadioTransport { path, token, body ->
                calls += Triple(path, token, body)
                if (path.endsWith("/slugs")) "{\"slugs\":[\"europa-plus\"]}" else "{}"
            }) { 1791280000000 }
            api.setFavourite("owner", "europa-plus", true)
            assertEquals("/radio/v1/collection/stations", calls[0].first)
            assertEquals("test-only", calls[0].second)
            val liked = JSONObject(calls[0].third!!).getJSONArray("stations").getJSONObject(0)
            assertEquals("europa-plus", liked.getString("slug")); assertEquals(1791280000, liked.getLong("timestamp"))
            api.setFavourite("owner", "europa-plus", false)
            assertEquals("/radio/v1/collection/stations/bulk-delete", calls[1].first)
            assertEquals("europa-plus", JSONObject(calls[1].third!!).getJSONArray("slugs").getString(0))
            assertEquals(setOf("europa-plus"), api.favouriteSlugs("owner"))
            auth.signOut()
            try { api.setFavourite("owner", "europa-plus", true); fail("Signed-out writes must be blocked") }
            catch (e: MusicException) { assertEquals(MusicFailure.SIGN_IN, e.failure) }
            assertEquals(3, calls.size)
        } finally { scope.cancel() }
    }
    @Test fun transportRejectsInvalidOauthAndUnsafeMediaUrls() = runBlocking {
        try {
            HttpsRadioTransport().request("/radio/v1/collection/stations/slugs", "invalid-test-only", null)
            fail("Invalid token must be rejected")
        } catch (e: RadioException) { assertEquals(RadioIssue.ACCESS, e.issue) }
        try {
            HttpsRadioTransport().request("/radio/v1/collection/stations", "invalid-test-only", "{\"stations\":[{\"slug\":\"europa-plus\",\"timestamp\":1791280000}]}")
            fail("Invalid token must not write a personal collection")
        } catch (e: RadioException) { assertEquals(RadioIssue.ACCESS, e.issue) }
        assertFalse(publicRadioUrl("http://station.example/live"))
        assertFalse(publicRadioUrl("https://user:password@station.example/live"))
        assertFalse(publicRadioUrl("https://station.example:8080/live"))
        assertTrue(publicRadioUrl("https://station.example/live.m3u8"))
    }
}
