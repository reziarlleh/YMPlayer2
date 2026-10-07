package dev.petrov.ymplayer2.core

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RadioTest {
    @Test fun duplicatePageWithChangingCursorDoesNotPaginateForever() = runTest {
        var calls = 0
        val api = Api().apply { catalog = { calls++; RadioPage(listOf(station), true, "page-$calls") } }
        val c = RadioController(auth(), api, Audio(), backgroundScope); runCurrent(); c.open(); runCurrent()
        assertTrue(c.state.value.hasNext)
        c.more(); runCurrent()
        assertEquals(1, c.state.value.stations.size)
        assertFalse(c.state.value.hasNext)
        c.more(); runCurrent(); assertEquals(2, calls)
    }
    @Test fun collectionDuplicatePageStopsWithoutDroppingSavedHeart() = runTest {
        var calls = 0
        val api = Api().apply { collection = { calls++; RadioPage(listOf(station), true, "page-$calls") } }
        val c = RadioController(auth(), api, Audio(), backgroundScope); runCurrent(); c.open(); runCurrent()
        c.refreshCollection(more = true); runCurrent()
        assertEquals(listOf(station), c.state.value.favourites)
        assertFalse(c.state.value.favouritesHaveNext)
    }
    @Test fun navigationRestoresPerProfileWithoutRestoringPlayback() = runTest {
        val saved = mutableMapOf<String, RadioNavigation>()
        val accounts = auth()
        val audio = Audio()
        val model = RadioController(accounts, Api(), audio, backgroundScope,
            readNavigation = saved::get, saveNavigation = { profile, state -> saved[profile] = state })
        accounts.activate("owner"); runCurrent()
        model.tab(RadioTab.CITIES); model.filter(RadioFilter("moscow", "Москва")); model.search("rock")
        accounts.activate("guest"); runCurrent()
        assertEquals(RadioTab.COLLECTION, model.state.value.tab)
        assertEquals("", model.state.value.query)
        assertNull(model.state.value.filter)
        accounts.activate("owner"); runCurrent()
        assertEquals(RadioTab.CITIES, model.state.value.tab)
        assertEquals("rock", model.state.value.query)
        assertEquals("moscow", model.state.value.filter?.slug)
        assertFalse(audio.state.value.ownsOutput)
    }
    private val station = RadioStation("one", "Station", streamSlug = "one-moscow")
    private class Api : RadioApi {
        var catalog: suspend (String?) -> RadioPage = { RadioPage(listOf(RadioStation("one", "Station"))) }
        var collection: suspend (String) -> RadioPage = { RadioPage(emptyList()) }
        var write: suspend (String, String, Boolean) -> Unit = { _, _, _ -> }
        val searches = mutableListOf<Pair<String, String?>>()
        var cityOptions: suspend () -> List<RadioFilter> = { listOf(RadioFilter("moscow", "Москва")) }
        var genreOptions: suspend () -> List<RadioFilter> = { listOf(RadioFilter("rock", "Rock")) }
        var card: suspend (String) -> RadioStation = { RadioStation(it, it) }
        var cityPage: (suspend (String?) -> RadioPage)? = null
        override suspend fun stations(region: String?, cursor: String?) = catalog(cursor)
        override suspend fun station(slug: String, region: String?) = card(slug)
        override suspend fun cities() = cityOptions()
        override suspend fun genres() = genreOptions()
        override suspend fun city(slug: String, cursor: String?) = cityPage?.invoke(cursor) ?: RadioPage(listOf(RadioStation("city", slug, streamSlug = slug), RadioStation("another", "Another", streamSlug = slug)))
        override suspend fun genre(slug: String, region: String?, cursor: String?) = RadioPage(listOf(RadioStation("genre", slug)))
        override suspend fun search(query: String, region: String?, cursor: String?): RadioPage {
            searches += query to region; return RadioPage(listOf(RadioStation(query, query)))
        }
        override suspend fun favourites(profile: String, region: String?, cursor: String?) = collection(profile)
        override suspend fun favouriteSlugs(profile: String) = setOf<String>()
        override suspend fun setFavourite(profile: String, slug: String, liked: Boolean) = write(profile, slug, liked)
        override suspend fun stream(station: RadioStation, region: String?) = error("unused")
        override suspend fun onAir(stationSlug: String, streamSlug: String) = error("unused")
    }
    private class Audio : RadioAudio {
        override val state = MutableStateFlow(RadioPlaybackState())
        override fun switchProfile(profile: String) { state.value = RadioPlaybackState(profile) }
        override fun play(station: RadioStation?, region: String?) { state.value = state.value.copy(station = station, ownsOutput = true) }
        override fun stop() { state.value = state.value.copy(playing = false) }
    }
    private fun TestScope.auth() = AccountAuth(DemoCatalog().profiles, object : DeviceAuthApi {
        override val configured = true
        override suspend fun requestCode(profileId: String) = error("unused")
        override suspend fun poll(code: DeviceChallenge) = error("unused")
        override suspend fun account(credentials: OAuthCredentials) = error("unused")
    }, object : AccountStore {
        override suspend fun read(profileId: String) = AccountSession(YandexAccount(profileId, profileId), OAuthCredentials("fixture", null, null))
        override suspend fun write(profileId: String, session: AccountSession?) = Unit
    }, backgroundScope).also { it.activate("owner"); runCurrent() }

    @Test fun searchIsDebouncedAndGlobalDespiteCityFilter() = runTest {
        val api = Api(); val c = RadioController(auth(), api, Audio(), backgroundScope); runCurrent(); c.open(); runCurrent()
        c.tab(RadioTab.CITIES); c.filter(RadioFilter("moscow", "Москва")); runCurrent()
        c.search("r"); advanceTimeBy(100); c.search("rock"); advanceTimeBy(350); runCurrent()
        assertEquals(listOf("rock" to null), api.searches)
        c.search(""); advanceTimeBy(350); runCurrent()
        assertEquals(listOf("city", "another"), c.state.value.stations.map { it.slug })
    }
    @Test fun staleNonCancellableCatalogCannotReplaceSearch() = runTest {
        val api = Api().apply { catalog = { withContext(NonCancellable) { delay(1000) }; RadioPage(listOf(station)) } }
        val c = RadioController(auth(), api, Audio(), backgroundScope); runCurrent(); c.open(); runCurrent()
        c.search("new"); advanceTimeBy(350); runCurrent(); advanceTimeBy(1000); runCurrent()
        assertEquals("new", c.state.value.stations.single().slug)
    }
    @Test fun paginationRemovesDuplicatesAndStopsRepeatedCursor() = runTest {
        val api = Api().apply { catalog = { cursor -> RadioPage(if (cursor == null) listOf(station) else listOf(station, station.copy(slug = "two", streamSlug = "two-moscow")), true, "cursor") } }
        val c = RadioController(auth(), api, Audio(), backgroundScope); runCurrent(); c.open(); runCurrent()
        assertTrue(c.state.value.hasNext); c.more(); runCurrent()
        assertEquals(2, c.state.value.stations.size); assertFalse(c.state.value.hasNext)
    }
    @Test fun freshCatalogAndCollectionKeepPaginationWhenFirstCursorMatchesPreviousList() = runTest {
        val api = Api().apply {
            catalog = { cursor -> RadioPage(listOf(station.copy(slug = cursor ?: "first")), true, "next") }
            collection = { RadioPage(listOf(station), true, "next") }
        }
        val c = RadioController(auth(), api, Audio(), backgroundScope); runCurrent(); c.open(); runCurrent()
        assertTrue(c.state.value.hasNext); assertTrue(c.state.value.favouritesHaveNext)
        c.tab(RadioTab.ALL); runCurrent()
        assertTrue(c.state.value.hasNext)
        c.refresh(); runCurrent()
        assertTrue(c.state.value.hasNext); assertTrue(c.state.value.favouritesHaveNext)
        c.more(); runCurrent()
        assertEquals(listOf("first", "next"), c.state.value.stations.map { it.slug })
        assertFalse(c.state.value.hasNext)
    }
    @Test fun heartWaitsForServerAndFailedWriteLeavesCollectionUnchanged() = runTest {
        val gate = CompletableDeferred<Unit>(); val api = Api().apply { write = { _, _, _ -> gate.await(); throw RadioException(RadioIssue.NETWORK) } }
        val c = RadioController(auth(), api, Audio(), backgroundScope); runCurrent(); c.open(); runCurrent()
        c.like(station); runCurrent(); assertTrue(c.state.value.pendingLikes.contains(station.slug)); assertTrue(c.state.value.favouriteSlugs.isEmpty())
        gate.complete(Unit); runCurrent(); assertTrue(c.state.value.favouriteSlugs.isEmpty()); assertTrue(c.state.value.pendingLikes.isEmpty())
        assertEquals(RadioIssue.NETWORK, c.state.value.collectionIssue)
    }
    @Test fun stationHeartTogglesStationOnly() = runTest {
        val writes = mutableListOf<Boolean>(); val api = Api().apply { write = { profile, slug, liked -> assertEquals("owner", profile); assertEquals(station.slug, slug); writes += liked } }
        val c = RadioController(auth(), api, Audio(), backgroundScope); runCurrent(); c.open(); runCurrent()
        c.like(station); runCurrent(); assertEquals(listOf(station), c.state.value.favourites)
        c.like(station); runCurrent(); assertTrue(c.state.value.favourites.isEmpty()); assertEquals(listOf(true, false), writes)
    }
    @Test fun profileSwitchRejectsLateCollectionAndHeartResults() = runTest {
        val auth = auth(); val gate = CompletableDeferred<Unit>(); val api = Api().apply { write = { _, _, _ -> withContext(NonCancellable) { gate.await() } } }
        val audio = Audio(); val c = RadioController(auth, api, audio, backgroundScope); runCurrent(); c.open(); runCurrent()
        c.like(station); runCurrent(); auth.activate("road"); runCurrent(); gate.complete(Unit); runCurrent()
        assertEquals("road", c.state.value.profileId); assertEquals("road", audio.state.value.profileId)
        assertTrue(c.state.value.favouriteSlugs.isEmpty()); assertTrue(c.state.value.pendingLikes.isEmpty())
        api.collection = { profile -> withContext(NonCancellable) { delay(1000) }; RadioPage(listOf(station.copy(slug = profile))) }
        c.refreshCollection(); runCurrent(); auth.activate("guest"); runCurrent(); advanceTimeBy(1000); runCurrent()
        assertFalse(c.state.value.signedIn); assertTrue(c.state.value.favourites.isEmpty())
    }
    @Test fun publicCatalogWorksWithoutLoginAndPlayDoesNotNeedMusicQueue() = runTest {
        val auth = auth(); auth.activate("guest"); runCurrent(); val audio = Audio()
        val c = RadioController(auth, Api(), audio, backgroundScope); runCurrent(); c.open(); runCurrent(); c.play(station)
        assertFalse(c.state.value.signedIn); assertTrue(c.state.value.stations.isNotEmpty())
        assertEquals(station, audio.state.value.station); assertTrue(audio.state.value.ownsOutput)
        c.like(station); runCurrent(); assertTrue(c.state.value.favourites.isEmpty())
    }
    @Test fun parallelCityAndGenreResponsesPreserveBothResultsAndFlags() = runTest {
        for (cityFirst in listOf(true, false)) {
            val cities = CompletableDeferred<List<RadioFilter>>()
            val genres = CompletableDeferred<List<RadioFilter>>()
            val api = Api().apply { cityOptions = { cities.await() }; genreOptions = { genres.await() } }
            val c = RadioController(auth(), api, Audio(), backgroundScope); runCurrent(); c.open(); runCurrent()
            assertTrue(c.state.value.citiesBusy); assertTrue(c.state.value.genresBusy)
            if (cityFirst) cities.complete(listOf(RadioFilter("moscow", "Москва"))) else genres.complete(listOf(RadioFilter("rock", "Rock")))
            runCurrent()
            assertEquals(!cityFirst, c.state.value.citiesBusy); assertEquals(cityFirst, c.state.value.genresBusy)
            if (cityFirst) genres.complete(listOf(RadioFilter("rock", "Rock"))) else cities.complete(listOf(RadioFilter("moscow", "Москва")))
            runCurrent()
            assertFalse(c.state.value.citiesBusy); assertFalse(c.state.value.genresBusy)
            assertEquals("moscow", c.state.value.cities.single().slug); assertEquals("rock", c.state.value.genres.single().slug)
        }
    }
    @Test fun optionsAreCachedAndExplicitRetryRecoversIndependentFailure() = runTest {
        var cityCalls = 0; var genreCalls = 0
        val api = Api().apply {
            cityOptions = { cityCalls++; listOf(RadioFilter("moscow", "Москва")) }
            genreOptions = { genreCalls++; if (genreCalls == 1) throw RadioException(RadioIssue.NETWORK); listOf(RadioFilter("rock", "Rock")) }
        }
        val c = RadioController(auth(), api, Audio(), backgroundScope); runCurrent(); c.open(); runCurrent()
        assertEquals(RadioIssue.NETWORK, c.state.value.genresIssue); assertNull(c.state.value.citiesIssue)
        c.tab(RadioTab.CITIES); runCurrent(); assertEquals(1, cityCalls)
        c.tab(RadioTab.GENRES); runCurrent(); assertNull(c.state.value.genresIssue); assertEquals(2, genreCalls)
        c.tab(RadioTab.ALL); c.tab(RadioTab.GENRES); runCurrent(); assertEquals(2, genreCalls)
        assertEquals("moscow", c.state.value.cities.single().slug)
    }
    @Test fun appendFailurePreservesRowsAndCursorWithoutAutomaticRetryLoop() = runTest {
        var calls = 0
        val api = Api().apply { catalog = { cursor ->
            calls++; if (cursor != null && calls == 2) throw RadioException(RadioIssue.NETWORK)
            if (cursor == null) RadioPage(listOf(station), true, "next") else RadioPage(listOf(station.copy(slug = "two")))
        } }
        val c = RadioController(auth(), api, Audio(), backgroundScope); runCurrent(); c.open(); runCurrent(); c.more(); runCurrent()
        assertEquals(listOf(station), c.state.value.stations); assertEquals("next", c.state.value.cursor)
        assertEquals(RadioIssue.NETWORK, c.state.value.issue)
        repeat(3) { c.more(); runCurrent() }; assertEquals(2, calls)
        c.retryMore(); runCurrent(); assertEquals(2, c.state.value.stations.size); assertFalse(c.state.value.hasNext)
    }
    @Test fun cityPaginationForwardsCursorAndKeepsSelectedFilter() = runTest {
        val cursors = mutableListOf<String?>()
        val api = Api().apply { cityPage = { cursor -> cursors += cursor
            if (cursor == null) RadioPage(listOf(station), true, "city-next") else RadioPage(listOf(station.copy(slug = "two"))) } }
        val c = RadioController(auth(), api, Audio(), backgroundScope); runCurrent(); c.open(); runCurrent()
        c.tab(RadioTab.CITIES); c.filter(RadioFilter("moscow", "Москва")); runCurrent(); c.more(); runCurrent()
        assertEquals(listOf(null, "city-next"), cursors); assertEquals(2, c.state.value.stations.size)
        assertEquals("moscow", c.state.value.filter?.slug); assertFalse(c.state.value.hasNext)
    }
    @Test fun stationDetailsDoNotPlayAndLateResultsCannotCrossCloseOrProfile() = runTest {
        val gate = CompletableDeferred<RadioStation>(); val auth = auth(); val audio = Audio()
        val api = Api().apply { card = { withContext(NonCancellable) { gate.await() } } }
        val c = RadioController(auth, api, audio, backgroundScope); runCurrent(); c.open(); runCurrent()
        c.openStation(station); runCurrent(); assertTrue(c.state.value.detailBusy); assertFalse(audio.state.value.ownsOutput)
        c.closeStation(); gate.complete(station.copy(description = "Full description")); runCurrent(); assertNull(c.state.value.detail)
        api.card = { delay(100); station }; c.openStation(station); runCurrent(); auth.activate("guest"); runCurrent(); advanceTimeBy(100); runCurrent()
        assertNull(c.state.value.detail); assertFalse(c.state.value.detailBusy)
    }

}
