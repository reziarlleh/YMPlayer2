package dev.petrov.ymplayer2.core

import kotlinx.coroutines.CancellationException

data class WaveTrack(val track: Track, val batchId: String, val station: String = "user:onyourwave")
data class WaveBatch(val tracks: List<WaveTrack>, val sessionId: String = "", val cursor: String = "", val request: WaveRequest = WaveRequest())
enum class WaveFeedback(val wire: String) { RADIO_STARTED("radioStarted"), STARTED("trackStarted"), FINISHED("trackFinished"), SKIP("skip"), DISLIKE("dislike") }
interface MyWaveApi {
    suspend fun start(profileId: String): WaveBatch
    suspend fun start(profileId: String, request: WaveRequest): WaveBatch = start(profileId).copy(request = request)
    suspend fun options(profileId: String, language: String): WaveOptions = throw MusicException(MusicFailure.UNAVAILABLE)
    suspend fun next(profileId: String, previous: WaveBatch): WaveBatch
    suspend fun recover(profileId: String, previous: WaveBatch?, request: WaveRequest): WaveBatch = start(profileId, request)
    suspend fun feedback(profileId: String, item: WaveTrack, type: WaveFeedback, playedSeconds: Int = 0)
}

/** 1.x fast first track + continuation/recovery, with bounded duplicate/blocked-track draining. */
class WaveLoader(private val api: MyWaveApi, private val diagnostic: (OperationDiagnostic) -> Unit = {}) {
    suspend fun load(profile: String, previous: WaveBatch?, seen: Set<String>, request: WaveRequest = previous?.request ?: WaveRequest(), allows: (Track) -> Boolean): WaveBatch {
        var cursor = previous
        var recoveryPoint = previous
        var lastFailure: Exception? = null
        var failures = 0
        val drained = mutableSetOf<String>()
        var recovering = false
        previous?.cursor?.takeIf(String::isNotBlank)?.let { drained.add(it) }
        // A complete ten-track queued window may be echoed before the first fresh
        // candidate. Keep the short baseline, with a bounded allowance for history.
        val drainBudget = (seen.size.toLong() + 1).coerceIn(8L, (WaveQueueWindow.MAX_ITEMS + 8).toLong()).toInt()
        repeat(drainBudget) {
            try {
                val requestCursor = if (recovering) recoveryPoint else cursor
                val response = if (recovering) api.recover(profile, recoveryPoint, request)
                    else if (cursor == null) api.start(profile, request) else api.next(profile, cursor!!)
                recovering = false
                val batch = response.copy(request = request, tracks = response.tracks.map { it.copy(station = request.station) })
                var unavailable = 0; var heard = 0; var blocked = 0
                val tracks = batch.tracks.filter {
                    when {
                        !it.track.available -> { unavailable++; false }
                        it.track.tasteTarget().key in seen -> { heard++; false }
                        !allows(it.track) -> { blocked++; false }
                        else -> true
                    }
                }
                    .distinctBy { it.track.tasteTarget().key }
                diagnostic(OperationDiagnostic(DiagnosticOperation.WAVE_DRAIN, returned = batch.tracks.size, accepted = tracks.size,
                    attempt = it + 1, session = batch.sessionId.isNotBlank(), cursorAdvanced = batch.cursor.isNotBlank() && batch.cursor != requestCursor?.cursor,
                    unavailable = unavailable, seen = heard, blocked = blocked))
                // Keep the last server position even when the selected candidates were filtered.
                // Clearing the request cursor to trigger recovery must not rewind this position.
                if (batch.cursor.isNotBlank()) recoveryPoint = batch
                if (tracks.isNotEmpty()) {
                    val next = tracks.first()
                    return batch.copy(tracks = listOf(next), cursor = next.track.tasteTarget().key)
                }
                // Walk past already seen/blocked entries instead of repeatedly requesting the same cursor.
                // A cursor can be old without being stuck: the server may echo history.
                // Reset only after the same cursor is returned twice during this drain.
                recovering = batch.cursor.isBlank() || !drained.add(batch.cursor)
                cursor = if (recovering) null else batch
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                lastFailure = e
                if (e is MusicException && e.failure in setOf(MusicFailure.SIGN_IN, MusicFailure.ACCESS)) throw e
                if (++failures >= 3) throw e
                diagnostic(diagnosticFailure(DiagnosticOperation.WAVE_NEXT, e))
                recovering = failures == 1
                cursor = if (recovering) null else recoveryPoint
            }
        }
        throw lastFailure ?: MusicException(MusicFailure.NO_PROGRESS)
    }
}

/** 1.x continuation delays: 1, 2, 3 seconds, then an explicit retry. Uses a monotonic clock. */
class WaveRecovery {
    var attempts: Int = 0
        private set
    var retryAtMillis: Long? = null
        private set
    var fatal: Boolean = false
        private set
    fun failed(nowMillis: Long, failure: MusicFailure): Boolean {
        retryAtMillis = null
        fatal = failure in setOf(MusicFailure.SIGN_IN, MusicFailure.ACCESS, MusicFailure.STORAGE)
        if (fatal || attempts >= 3) return false
        retryAtMillis = nowMillis + 1_000L * ++attempts
        return true
    }
    fun due(nowMillis: Long) = retryAtMillis?.let { nowMillis >= it } == true
    fun consume() { retryAtMillis = null }
    fun reset() { attempts = 0; retryAtMillis = null; fatal = false }
}
