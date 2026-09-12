package dev.petrov.ymplayer2.core

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OnlineMusicTest {
    private class Api : OnlineMusicApi {
        val requests = mutableListOf<Pair<MusicRequest, Int>>()
        var handle: suspend (MusicRequest, Int) -> MusicPage = { request, _ -> MusicPage(listOf(row(request.query))) }
        override suspend fun page(profileId: String, request: MusicRequest, page: Int): MusicPage {
            requests += request to page
            return handle(request, page)
        }
        override suspend fun stream(profileId: String, trackId: String) = error("not used by the screen")
    }
    private fun TestScope.auth(): AccountAuth {
        val saved = mutableMapOf("owner" to AccountSession(YandexAccount("1", "One"), OAuthCredentials("fixture-one", null, null)),
            "road" to AccountSession(YandexAccount("2", "Two"), OAuthCredentials("fixture-two", null, null)))
        val device = object : DeviceAuthApi {
            override val configured = true
            override suspend fun requestCode(profileId: String) = error("not used")
            override suspend fun poll(code: DeviceChallenge) = error("not used")
            override suspend fun account(credentials: OAuthCredentials) = error("not used")
        }
        return AccountAuth(DemoCatalog().profiles, device, object : AccountStore {
            override suspend fun read(profileId: String) = saved[profileId]
            override suspend fun write(profileId: String, session: AccountSession?) { if (session == null) saved.remove(profileId) else saved[profileId] = session }
        }, backgroundScope).also { it.activate("owner"); runCurrent() }
    }
    @Test fun typingOnlyRequestsLastQueryAfterDebounce() = runTest {
        val api = Api(); val music = OnlineMusic(auth(), api, backgroundScope); runCurrent()
        music.search("a"); advanceTimeBy(150); music.search("album"); advanceTimeBy(349); runCurrent()
        assertTrue(api.requests.isEmpty())
        advanceTimeBy(1); runCurrent()
        assertEquals("album", api.requests.single().first.query)
        assertEquals("album", music.state.value.entries.single().id)
    }
    @Test fun ignoredCancellationCannotOverwriteNewSearch() = runTest {
        val api = Api().apply { handle = { request, _ -> withContext(NonCancellable) { delay(if (request.query == "old") 1000 else 1) }; MusicPage(listOf(row(request.query))) } }
        val music = OnlineMusic(auth(), api, backgroundScope); runCurrent()
        music.search("old", debounce = false); runCurrent()
        music.search("new", debounce = false); runCurrent(); advanceTimeBy(1100); runCurrent()
        assertEquals("new", music.state.value.entries.single().id)
        assertEquals(listOf("new"), music.catalog.value.tracks.map { it.id })
    }
    @Test fun failedMoreKeepsFirstPageAndRetriesSameOffset() = runTest {
        var fail = true
        val api = Api().apply { handle = { _, page ->
            if (page == 1 && fail) throw MusicException(MusicFailure.NETWORK)
            MusicPage(listOf(row(if (page == 0) "one" else "two")), if (page == 0) 1 else null)
        } }
        val music = OnlineMusic(auth(), api, backgroundScope); runCurrent()
        music.search("song", debounce = false); runCurrent(); music.more(); runCurrent()
        assertEquals(listOf("one"), music.state.value.entries.map { it.id })
        assertEquals(1, music.state.value.failedPage); assertNotNull(music.state.value.issue)
        fail = false; music.retry(); runCurrent()
        assertEquals(listOf(0, 1, 1), api.requests.map { it.second })
        assertEquals(listOf("one", "two"), music.state.value.entries.map { it.id })
        assertNull(music.state.value.issue)
    }
    @Test fun duplicatePageStopsPaginationWithoutDuplicateTracks() = runTest {
        val api = Api().apply { handle = { _, page -> MusicPage(listOf(row("same")), page + 1) } }
        val music = OnlineMusic(auth(), api, backgroundScope); runCurrent()
        music.search("song", debounce = false); runCurrent(); music.more(); runCurrent()
        assertEquals(1, music.state.value.entries.size); assertNull(music.state.value.nextPage)
    }
    @Test fun emptyPageIsValidAndStopsPagination() = runTest {
        val api = Api().apply { handle = { _, _ -> MusicPage(emptyList(), 1) } }
        val music = OnlineMusic(auth(), api, backgroundScope); runCurrent()
        music.search("absent", debounce = false); runCurrent()
        assertTrue(music.state.value.loaded); assertTrue(music.state.value.entries.isEmpty())
        assertNull(music.state.value.issue); assertNull(music.state.value.nextPage)
    }
    @Test fun profileSwitchDiscardsLateResultsAndGuestNeverRequests() = runTest {
        val auth = auth()
        val api = Api().apply { handle = { _, _ -> withContext(NonCancellable) { delay(1000) }; MusicPage(listOf(row("old-account"))) } }
        val music = OnlineMusic(auth, api, backgroundScope); runCurrent()
        music.search("song", debounce = false); runCurrent(); auth.activate("guest"); runCurrent()
        advanceTimeBy(1100); runCurrent(); music.search("anything", debounce = false); runCurrent()
        assertEquals("guest", music.state.value.profileId); assertFalse(music.state.value.signedIn)
        assertTrue(music.state.value.entries.isEmpty()); assertTrue(music.catalog.value.tracks.isEmpty())
        assertEquals(1, api.requests.size)
    }
    @Test fun logoutClearsOnlineMetadataAndNeverTouchesOtherProfileToken() = runTest {
        val auth = auth(); val music = OnlineMusic(auth, Api(), backgroundScope); runCurrent()
        music.search("song", debounce = false); runCurrent(); auth.signOut(); runCurrent()
        assertFalse(music.state.value.signedIn); assertTrue(music.catalog.value.tracks.isEmpty())
        auth.activate("road"); runCurrent()
        assertEquals("fixture-two", auth.withSession("road") { it.credentials.accessToken })
    }
    @Test fun sessionRejectsWrongProfileAndLateLogoutResponse() = runTest {
        val auth = auth()
        assertEquals(MusicFailure.SIGN_IN, runCatching { auth.withSession("road") { "wrong" } }.exceptionOrNull().let { (it as MusicException).failure })
        val response = async { runCatching { auth.withSession("owner") { delay(1000); "late-url" } } }
        runCurrent(); auth.signOut(); runCurrent(); advanceTimeBy(1001); runCurrent()
        assertEquals(MusicFailure.SIGN_IN, (response.await().exceptionOrNull() as MusicException).failure)
    }
    @Test fun upRestoresParentResultsAndCancelsDetail() = runTest {
        val api = Api().apply { handle = { request, _ ->
            if (request.entity != null) withContext(NonCancellable) { delay(1000) }
            MusicPage(listOf(row(request.entity?.title ?: request.query)))
        } }
        val music = OnlineMusic(auth(), api, backgroundScope); runCurrent()
        music.search("parent", MusicKind.ALBUMS, false); runCurrent()
        music.open(MusicEntity("1", "detail", MusicKind.ALBUMS)); runCurrent(); assertTrue(music.up())
        advanceTimeBy(1100); runCurrent()
        assertEquals("parent", music.state.value.entries.single().id)
        assertFalse(music.up())
    }
    @Test fun visibleRowsRemainPlayableAfterLookupCacheEviction() = runTest {
        val api = Api().apply { handle = { request, _ -> MusicPage((1..4100).map { row("${request.query}-$it") }) } }
        val music = OnlineMusic(auth(), api, backgroundScope); runCurrent()
        music.search("large", debounce = false); runCurrent()
        assertEquals(4000, music.catalog.value.tracks.size)
        assertEquals(4100, music.tracksForPlayback("owner").size)
        assertTrue(music.tracksForPlayback("owner").any { it.id == "large-1" })
        assertTrue(music.tracksForPlayback("road").isEmpty())
    }
    companion object {
        private fun row(id: String) = MusicEntry(id, id, "Artist", Track(id, id, "Artist", "Album", Source.YANDEX, 60, false))
    }
}
