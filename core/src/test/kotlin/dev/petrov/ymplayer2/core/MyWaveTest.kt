package dev.petrov.ymplayer2.core

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class MyWaveTest {
    private class Api : MyWaveApi {
        var starts = 0; var continuations = 0
        var handle: suspend (Int) -> WaveBatch = { batch(it) }
        override suspend fun start(profileId: String): WaveBatch { starts++; return handle(starts + continuations) }
        override suspend fun next(profileId: String, previous: WaveBatch): WaveBatch { continuations++; return handle(starts + continuations) }
        override suspend fun feedback(profileId: String, item: WaveTrack, type: WaveFeedback, playedSeconds: Int) = Unit
    }
    @Test fun skipsDuplicateTrackAcrossAlbumsAndBlockedArtistsBeforeReturning() = runTest {
        val api = Api(); val result = WaveLoader(api).load("owner", null, setOf("1")) { it.artists.none { a -> a.id == "2" } }
        assertEquals("yandex:3:7", result.tracks.single().track.id)
        assertEquals(1, api.starts); assertEquals(2, api.continuations)
        assertEquals("batch3", result.tracks.single().batchId)
    }
    @Test fun brokenSessionRecoversWithNewSession() = runTest {
        val api = Api().apply { handle = { if (it == 1) throw MusicException(MusicFailure.UNAVAILABLE) else batch(it) } }
        assertEquals("session2", WaveLoader(api).load("owner", batch(0), emptySet()) { true }.sessionId)
        assertEquals(1, api.starts); assertEquals(1, api.continuations)
    }
    @Test fun repeatedEmptyBatchesAndNetworkFailuresAreBounded() = runTest {
        val empty = Api().apply { handle = { WaveBatch(emptyList()) } }
        try { WaveLoader(empty).load("owner", null, emptySet()) { true }; fail() } catch (_: MusicException) { }
        assertEquals(8, empty.starts)
        val offline = Api().apply { handle = { throw MusicException(MusicFailure.NETWORK) } }
        try { WaveLoader(offline).load("owner", null, emptySet()) { true }; fail() } catch (_: MusicException) { }
        assertEquals(3, offline.starts)
    }
    @Test fun cancellationAndAccessFailureDoNotStartAnotherSession() = runTest {
        for (failure in listOf(CancellationException(), MusicException(MusicFailure.ACCESS))) {
            val api = Api().apply { handle = { throw failure } }
            try { WaveLoader(api).load("owner", null, emptySet()) { true }; fail() } catch (_: Exception) { }
            assertEquals(1, api.starts)
        }
    }
    companion object {
        private fun batch(id: Int) = WaveBatch(listOf(WaveTrack(Track("yandex:$id:7", "Song", "Artist", "Album", Source.YANDEX, 5, false,
            artists = listOf(ArtistRef(id.toString(), "Artist"))), "batch$id")), "session$id", id.toString())
    }
}
