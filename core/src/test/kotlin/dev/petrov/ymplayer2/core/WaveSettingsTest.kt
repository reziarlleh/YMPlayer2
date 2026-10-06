package dev.petrov.ymplayer2.core

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WaveSettingsTest {
    private val options = WaveOptions(listOf(
        WaveOptionGroup("contexts", "Под занятие", listOf(WaveOption("user:onyourwave", "Любое", true), WaveOption("activity:driving", "В дороге"))),
        WaveOptionGroup("language", "По языку", listOf(WaveOption("settingLanguage:russian", "Казахский"), WaveOption("settingLanguage:any", "Любой", true)))))
    private class Api : MyWaveApi {
        var read: suspend (String) -> WaveOptions = { error("unset") }
        var failNext = false
        val starts = mutableListOf<WaveRequest>()
        override suspend fun options(profileId: String, language: String) = read(profileId)
        override suspend fun start(profileId: String) = error("request must be passed")
        override suspend fun start(profileId: String, request: WaveRequest): WaveBatch {
            starts += request
            return WaveBatch(listOf(WaveTrack(Track("yandex:7:1", "Song", "Artist", "Album", Source.YANDEX, 30, false), "batch")), "new", "7", request)
        }
        override suspend fun next(profileId: String, previous: WaveBatch): WaveBatch {
            if (failNext) throw MusicException(MusicFailure.NETWORK)
            return start(profileId, previous.request)
        }
        override suspend fun feedback(profileId: String, item: WaveTrack, type: WaveFeedback, playedSeconds: Int) = Unit
    }
    private fun TestScope.auth() = AccountAuth(DemoCatalog().profiles, object : DeviceAuthApi {
        override val configured = true
        override suspend fun requestCode(profileId: String) = error("unused")
        override suspend fun poll(code: DeviceChallenge) = error("unused")
        override suspend fun account(credentials: OAuthCredentials) = error("unused")
    }, object : AccountStore {
        override suspend fun read(profileId: String) = AccountSession(YandexAccount("uid-$profileId", "Fixture"), OAuthCredentials("fixture", null, null))
        override suspend fun write(profileId: String, session: AccountSession?) = Unit
    }, backgroundScope).also { it.activate("owner"); runCurrent() }

    @Test fun regionLabelDoesNotChangeWireValueAndChoicesPersistForOnlyThatAccount() = runTest {
        val auth = auth(); val api = Api().apply { read = { options } }
        val saved = mutableMapOf<String, Map<String, String>>()
        val settings = WaveSettings(auth, api, backgroundScope, { saved[it].orEmpty() }, { key, value -> saved[key] = value }); runCurrent()
        settings.load("ru"); runCurrent()
        settings.select("contexts", "activity:driving"); settings.select("language", "settingLanguage:russian")
        assertEquals(listOf("activity:driving", "settingLanguage:russian"), settings.request().seeds)
        assertEquals("Казахский", settings.state.value.options!!.groups.last().values.first().title)
        assertEquals(WaveRequest(), settings.request("road"))
        settings.select("language", "invented-value")
        assertEquals("settingLanguage:russian", settings.state.value.selected["language"])
        auth.activate("road"); runCurrent(); assertTrue(settings.state.value.selected.isEmpty())
        auth.activate("owner"); runCurrent(); assertEquals("activity:driving", settings.request().station)
        assertEquals(setOf("owner:uid-owner"), saved.keys)
        settings.load("ru"); runCurrent()
        settings.reset(); assertEquals(WaveRequest(), settings.request())
    }
    @Test fun staleChoicesAndLateRepliesCannotCrossProfileBoundary() = runTest {
        val auth = auth(); val gate = CompletableDeferred<WaveOptions>()
        val api = Api().apply { read = { withContext(NonCancellable) { gate.await() } } }
        val settings = WaveSettings(auth, api, backgroundScope, { mapOf("language" to "obsolete") }); runCurrent()
        settings.load("ru"); runCurrent(); assertTrue(settings.state.value.loading)
        auth.activate("road"); runCurrent(); gate.complete(options); runCurrent()
        assertEquals("road", settings.state.value.profileId); assertNull(settings.state.value.options)
        settings.load("en"); runCurrent(); assertTrue(settings.state.value.selected.isEmpty())
        assertFalse(settings.state.value.loading)
    }
    @Test fun failedContinuationRestartsTheSelectedStationWithTheSameSettings() = runTest {
        val request = WaveRequest("activity:driving", "В дороге", listOf("settingMoodEnergy:calm", "settingLanguage:russian"))
        val api = Api().apply { failNext = true }
        val result = WaveLoader(api).load("owner", WaveBatch(emptyList(), "expired", "6", request), emptySet()) { true }
        assertEquals(listOf(request), api.starts)
        assertEquals(request, result.request); assertEquals(request.station, result.tracks.single().station)
    }
}
