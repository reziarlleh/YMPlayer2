package dev.petrov.ymplayer2.core

import kotlinx.coroutines.CancellationException

data class WaveTrack(val track: Track, val batchId: String)
data class WaveBatch(val tracks: List<WaveTrack>, val sessionId: String = "", val cursor: String = "")
enum class WaveFeedback(val wire: String) { RADIO_STARTED("radioStarted"), STARTED("trackStarted"), FINISHED("trackFinished"), SKIP("skip"), DISLIKE("dislike") }
interface MyWaveApi {
    suspend fun start(profileId: String): WaveBatch
    suspend fun next(profileId: String, previous: WaveBatch): WaveBatch
    suspend fun feedback(profileId: String, item: WaveTrack, type: WaveFeedback, playedSeconds: Int = 0)
}

/** 1.x fast first track + continuation/recovery, with bounded duplicate/blocked-track draining. */
class WaveLoader(private val api: MyWaveApi) {
    suspend fun load(profile: String, previous: WaveBatch?, seen: Set<String>, allows: (Track) -> Boolean): WaveBatch {
        var cursor = previous
        var lastFailure: Exception? = null
        var failures = 0
        repeat(8) {
            try {
                val batch = if (cursor == null) api.start(profile) else api.next(profile, cursor!!)
                val tracks = batch.tracks.filter { it.track.available && it.track.tasteTarget().key !in seen && allows(it.track) }
                    .distinctBy { it.track.tasteTarget().key }
                if (tracks.isNotEmpty()) return batch.copy(tracks = tracks)
                // Walk past already seen/blocked entries instead of repeatedly requesting the same cursor.
                cursor = if (batch.cursor.isBlank() || batch.cursor == cursor?.cursor) null else batch
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
