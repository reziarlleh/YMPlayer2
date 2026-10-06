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
    suspend fun feedback(profileId: String, item: WaveTrack, type: WaveFeedback, playedSeconds: Int = 0)
}

/** 1.x fast first track + continuation/recovery, with bounded duplicate/blocked-track draining. */
class WaveLoader(private val api: MyWaveApi) {
    suspend fun load(profile: String, previous: WaveBatch?, seen: Set<String>, request: WaveRequest = previous?.request ?: WaveRequest(), allows: (Track) -> Boolean): WaveBatch {
        var cursor = previous
        var lastFailure: Exception? = null
        var failures = 0
        repeat(8) {
            try {
                val response = if (cursor == null) api.start(profile, request) else api.next(profile, cursor!!)
                val batch = response.copy(request = request, tracks = response.tracks.map { it.copy(station = request.station) })
                val tracks = batch.tracks.filter { it.track.available && it.track.tasteTarget().key !in seen && allows(it.track) }
                    .distinctBy { it.track.tasteTarget().key }
                if (tracks.isNotEmpty()) {
                    val next = tracks.first()
                    return batch.copy(tracks = listOf(next), cursor = next.track.tasteTarget().key)
                }
                // Walk past already seen/blocked entries instead of repeatedly requesting the same cursor.
                cursor = if (batch.cursor.isBlank() || batch.cursor == cursor?.cursor || batch.cursor in seen) null else batch
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                lastFailure = e
                if (e is MusicException && e.failure in setOf(MusicFailure.SIGN_IN, MusicFailure.ACCESS)) throw e
                if (++failures >= 3) throw e
                cursor = if (failures == 1) null else previous
            }
        }
        throw lastFailure ?: MusicException(MusicFailure.UNAVAILABLE)
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
        fatal = failure in setOf(MusicFailure.SIGN_IN, MusicFailure.ACCESS)
        if (fatal || attempts >= 3) return false
        retryAtMillis = nowMillis + 1_000L * ++attempts
        return true
    }
    fun due(nowMillis: Long) = retryAtMillis?.let { nowMillis >= it } == true
    fun consume() { retryAtMillis = null }
    fun reset() { attempts = 0; retryAtMillis = null; fatal = false }
}
