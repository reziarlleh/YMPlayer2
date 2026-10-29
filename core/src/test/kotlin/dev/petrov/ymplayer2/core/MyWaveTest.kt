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
    @Test fun tenQueuedEchoesCannotExhaustDrainBeforeTheFirstFreshTrack() = runTest {
        val api = Api()
        val seen = (1..10).mapTo(hashSetOf()) { it.toString() }
        assertEquals("yandex:11:7", WaveLoader(api).load("owner", null, seen) { true }.tracks.single().track.id)
        assertEquals(11, api.starts + api.continuations)
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
    @Test fun wholeSequenceIsFilteredButOnlyOneNewTrackBecomesNextCursor() = runTest {
        val api = Api().apply { handle = { WaveBatch((1..5).flatMap { batch(it).tracks }, "current-session", "1") } }
        val next = WaveLoader(api).load("owner", batch(2), setOf("1", "2")) { true }
        assertEquals("yandex:3:7", next.tracks.single().track.id)
        assertEquals("3", next.cursor)
        assertEquals("batch3", next.tracks.single().batchId)
        assertEquals(1, api.continuations); assertEquals(0, api.starts)
    }
    @Test fun repeatedHistoryEntryRecoversInsteadOfCirclingOldCursors() = runTest {
        val api = Api().apply { handle = { if (it == 1) batch(1) else batch(3) } }
        val next = WaveLoader(api).load("owner", batch(2), setOf("1", "2")) { true }
        assertEquals("3", next.cursor); assertEquals(0, api.starts)
    }
    @Test fun oldCursorCanDrainHistoryWithoutRestartingPersonalizedSession() = runTest {
        val api = Api().apply { handle = { batch(if (it == 1) 1 else 3).copy(sessionId = "same") } }
        val next = WaveLoader(api).load("owner", batch(2).copy(sessionId = "same"), setOf("1", "2")) { true }
        assertEquals("same", next.sessionId)
        assertEquals(0, api.starts); assertEquals(2, api.continuations)
    }
    @Test fun trulyStuckCursorStillRecovers() = runTest {
        val api = Api().apply { handle = { if (it <= 2) batch(1) else batch(3) } }
        assertEquals("3", WaveLoader(api).load("owner", batch(2), setOf("1", "2")) { true }.cursor)
        assertEquals(1, api.starts); assertEquals(2, api.continuations)
    }
    @Test fun repeatedTenTrackBatchRecoversThroughAlternatePathWithoutPlayingHistoryAgain() = runTest {
        val seen = (1..10).map { it.toString() }.toSet()
        var recoveries = 0
        val api = object : MyWaveApi {
            override suspend fun start(profileId: String) = WaveBatch((1..10).flatMap { batch(it).tracks }, "stuck", "10")
            override suspend fun next(profileId: String, previous: WaveBatch) = start(profileId)
            override suspend fun recover(profileId: String, previous: WaveBatch?, request: WaveRequest): WaveBatch {
                recoveries++; return batch(11)
            }
            override suspend fun feedback(profileId: String, item: WaveTrack, type: WaveFeedback, playedSeconds: Int) = Unit
        }
        val loaded = WaveLoader(api).load("owner", batch(10), seen) { true }
        assertEquals("11", loaded.cursor); assertEquals(1, recoveries)
    }
    @Test fun recoveryUsesLastDrainedBatchInsteadOfOriginalCursor() = runTest {
        val request = WaveRequest("activity:road-trip", "Road", listOf("settingLanguage:russian"))
        val previous = batch(2).copy(request = request)
        var recovered: WaveBatch? = null
        val api = object : MyWaveApi {
            override suspend fun start(profileId: String): WaveBatch = error("Unexpected restart")
            override suspend fun next(profileId: String, previous: WaveBatch) = batch(3).copy(sessionId = "advanced-session")
            override suspend fun recover(profileId: String, previous: WaveBatch?, request: WaveRequest): WaveBatch {
                recovered = previous
                assertEquals("3", previous?.cursor)
                assertEquals("advanced-session", previous?.sessionId)
                assertEquals(request, previous?.request)
                return batch(4)
            }
            override suspend fun feedback(profileId: String, item: WaveTrack, type: WaveFeedback, playedSeconds: Int) = Unit
        }
        val result = WaveLoader(api).load("owner", previous, setOf("1", "2", "3"), request) { true }
        assertEquals("3", recovered?.cursor)
        assertEquals("4", result.cursor)
        assertEquals(request, result.request)
    }
    @Test fun drainDiagnosticsSeparateHistoryBlocksAndUnavailableWithoutFalseCursorAdvance() = runTest {
        val events = mutableListOf<OperationDiagnostic>()
        val rows = listOf(batch(1).tracks.single(), batch(2).tracks.single(),
            batch(3).tracks.single().let { it.copy(track = it.track.copy(available = false)) })
        val api = object : MyWaveApi {
            override suspend fun start(profileId: String) = WaveBatch(rows, cursor = "3")
            override suspend fun next(profileId: String, previous: WaveBatch) = start(profileId)
            override suspend fun recover(profileId: String, previous: WaveBatch?, request: WaveRequest) = start(profileId)
            override suspend fun feedback(profileId: String, item: WaveTrack, type: WaveFeedback, playedSeconds: Int) = Unit
        }
        try {
            WaveLoader(api, events::add).load("owner", WaveBatch(emptyList(), cursor = "3"), setOf("1")) {
                it.tasteTarget().key != "2"
            }
            fail("A completely filtered response must remain bounded and keep the filters")
        } catch (e: MusicException) { assertEquals(MusicFailure.NO_PROGRESS, e.failure) }
        assertEquals(8, events.size)
        events.forEach {
            assertEquals(3, it.returned); assertEquals(0, it.accepted)
            assertEquals(1, it.seen); assertEquals(1, it.blocked); assertEquals(1, it.unavailable)
            assertEquals(false, it.cursorAdvanced)
        }
    }
    @Test fun emptyDrainDoesNotEraseTheLastUsableRecoveryPoint() = runTest {
        var requests = 0
        val api = object : MyWaveApi {
            override suspend fun start(profileId: String): WaveBatch = error("Unexpected restart")
            override suspend fun next(profileId: String, previous: WaveBatch) =
                if (++requests == 1) batch(3) else WaveBatch(emptyList())
            override suspend fun recover(profileId: String, previous: WaveBatch?, request: WaveRequest): WaveBatch {
                assertEquals("3", previous?.cursor)
                assertEquals("session3", previous?.sessionId)
                return batch(4)
            }
            override suspend fun feedback(profileId: String, item: WaveTrack, type: WaveFeedback, playedSeconds: Int) = Unit
        }
        assertEquals("4", WaveLoader(api).load("owner", batch(2), setOf("1", "2", "3")) { true }.cursor)
    }
    @Test fun boundedHistoryRestoresAcrossRestartWithoutAlbumDuplicates() {
        val history = WaveHistory(3)
        listOf("1", "2", "3", "3", "4").forEach(history::remember)
        assertEquals(listOf("2", "3", "4"), history.seen.toList())
        val restored = WaveHistory(3); restored.restore(history.seen.toList())
        assertEquals(history.seen, restored.seen)
        restored.clear(); assertTrue(restored.seen.isEmpty())
    }
    @Test fun recoveryDelaysAreBoundedAndFatalAuthNeverRetries() {
        val retry = WaveRecovery()
        for (attempt in 1..3) {
            assertTrue(retry.failed(10_000L, MusicFailure.NETWORK))
            assertEquals(10_000L + attempt * 1000, retry.retryAtMillis)
            assertFalse(retry.due(10_000L + attempt * 1000 - 1))
            assertTrue(retry.due(10_000L + attempt * 1000))
            retry.consume(); assertNull(retry.retryAtMillis)
        }
        assertFalse(retry.failed(20_000, MusicFailure.NETWORK))
        retry.reset(); assertEquals(0, retry.attempts)
        assertFalse(retry.failed(20_000, MusicFailure.SIGN_IN))
        assertTrue(retry.fatal); assertFalse(retry.due(Long.MAX_VALUE))
        assertFalse(retry.failed(20_000, MusicFailure.ACCESS))
        assertTrue(retry.fatal)
        retry.reset(); assertFalse(retry.fatal)
    }
    @Test fun storageFailureCancelsNetworkBackoffUntilExplicitRetry() {
        val retry = WaveRecovery()
        assertTrue(retry.failed(10_000, MusicFailure.NETWORK))
        assertFalse(retry.failed(10_100, MusicFailure.STORAGE))
        assertTrue(retry.fatal)
        assertNull(retry.retryAtMillis)
        assertFalse(retry.due(Long.MAX_VALUE))
        retry.reset()
        assertFalse(retry.fatal)
        assertTrue(retry.failed(20_000, MusicFailure.NETWORK))
    }
    companion object {
        private fun batch(id: Int) = WaveBatch(listOf(WaveTrack(Track("yandex:$id:7", "Song", "Artist", "Album", Source.YANDEX, 5, false,
            artists = listOf(ArtistRef(id.toString(), "Artist"))), "batch$id")), "session$id", id.toString())
    }
}
