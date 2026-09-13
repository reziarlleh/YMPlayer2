package dev.petrov.ymplayer2.yandex

import dev.petrov.ymplayer2.core.*
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale

/** Port of 1.x getMyWaveFastStart / getMoreMyWave / rotorStationFeedback. */
class YandexWaveApi(private val music: YandexMusicApi) : MyWaveApi {
    override suspend fun start(profileId: String): WaveBatch = music.account(profileId) { token, _ ->
        try {
            val body = JSONObject().put("seeds", JSONArray(listOf(STATION))).put("queue", JSONArray())
                .put("includeTracksInResponse", true).put("includeWaveModel", true).put("interactive", true)
            val result = music.api(token, "/rotor/session/new", json = body) as JSONObject
            val batch = parse(token, result, result.optString("radioSessionId"))
            if (batch.tracks.isNotEmpty()) batch else legacy(token, "")
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { legacy(token, "") }
    }
    override suspend fun next(profileId: String, previous: WaveBatch): WaveBatch = music.account(profileId) { token, _ ->
        if (previous.sessionId.isBlank() || previous.cursor.isBlank()) legacy(token, previous.cursor)
        else {
            val result = music.api(token, "/rotor/session/${encode(previous.sessionId)}/tracks",
                json = JSONObject().put("queue", JSONArray(listOf(previous.cursor)))) as JSONObject
            parse(token, result, previous.sessionId, previous.cursor)
        }
    }
    private suspend fun legacy(token: String, cursor: String): WaveBatch {
        val result = music.api(token, "/rotor/station/$STATION/tracks?settings2=True" + if (cursor.isBlank()) "" else "&queue=${encode(cursor)}") as JSONObject
        return parse(token, result, "", cursor)
    }
    private suspend fun parse(token: String, result: JSONObject, session: String, skip: String = ""): WaveBatch {
        val batch = result.optString("batchId").ifBlank { result.optString("batch_id") }
        val sequence = result.optJSONArray("sequence")?.objects().orEmpty()
        // Preserve the returned candidates for core to exclude the whole listening history.
        // Returning only the first candidate can hide a new item behind an older echoed item.
        val candidates = linkedMapOf<String, Track?>()
        for (row in sequence) {
            val embedded = row.optJSONObject("track")
            val id = (listOf("id", "trackId", "track_id", "realId").map { embedded?.optString(it).orEmpty() } +
                listOf(row.optString("trackId"), row.optString("track"))).firstOrNull { it.isNotBlank() }.orEmpty().substringBefore(':')
            if (!id.matches(Regex("[0-9]+")) || id == skip) continue
            val direct = embedded?.takeIf { it.optString("title").isNotBlank() && it.optString("id") == id }?.let { music.trackEntry(it)?.track }
            candidates.putIfAbsent(id, direct)
        }
        val unresolved = candidates.filterValues { it == null }.keys.toList()
        val resolved = if (unresolved.isEmpty()) emptyMap() else music.tracks(token, unresolved).associateBy { it.tasteTarget().key }
        val tracks = candidates.mapNotNull { (id, track) -> (track ?: resolved[id])?.let { WaveTrack(it, batch) } }
        return WaveBatch(tracks, session, tracks.firstOrNull()?.track?.tasteTarget()?.key ?: skip)
    }
    override suspend fun feedback(profileId: String, item: WaveTrack, type: WaveFeedback, playedSeconds: Int) {
        if (item.batchId.isBlank()) return
        music.account(profileId) { token, uid ->
            val form = mutableListOf("type" to type.wire, "timestamp" to String.format(Locale.US, "%.3f", System.currentTimeMillis() / 1000.0))
            if (type == WaveFeedback.RADIO_STARTED) form += "from" to "mobile-radio-user-$uid"
            else form += "trackId" to item.track.tasteTarget().key
            if (type in setOf(WaveFeedback.FINISHED, WaveFeedback.SKIP)) form += "totalPlayedSeconds" to playedSeconds.coerceAtLeast(0).toString()
            val result = music.api(token, "/rotor/station/$STATION/feedback?batch-id=${encode(item.batchId)}", form)
            if (result != "ok") throw MusicException(MusicFailure.RESPONSE)
        }
    }
    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")
    companion object { private const val STATION = "user:onyourwave" }
}
