package dev.petrov.ymplayer2.yandex

import dev.petrov.ymplayer2.core.*
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale

/** Port of 1.x getMyWaveFastStart / getMoreMyWave / rotorStationFeedback. */
class YandexWaveApi(private val music: YandexMusicApi) : MyWaveApi {
    override suspend fun start(profileId: String): WaveBatch = start(profileId, WaveRequest())
    override suspend fun start(profileId: String, request: WaveRequest): WaveBatch = music.account(profileId) { token, _ ->
        try {
            val body = JSONObject().put("seeds", JSONArray(request.seeds)).put("queue", JSONArray())
                .put("includeTracksInResponse", true).put("includeWaveModel", true).put("interactive", true)
            val result = music.api(token, "/rotor/session/new", json = body) as JSONObject
            val batch = parse(token, result, result.optString("radioSessionId"), request)
            if (batch.tracks.isNotEmpty()) batch else legacy(token, "", request)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            // Never silently drop user-selected settings or turn an activity into the default wave.
            if (request.settings.isNotEmpty() || e is MusicException && e.failure in setOf(MusicFailure.SIGN_IN, MusicFailure.ACCESS)) throw e
            legacy(token, "", request)
        }
    }
    override suspend fun next(profileId: String, previous: WaveBatch): WaveBatch = music.account(profileId) { token, _ ->
        if (previous.sessionId.isBlank() || previous.cursor.isBlank()) legacy(token, previous.cursor, previous.request)
        else {
            val result = music.api(token, "/rotor/session/${encode(previous.sessionId)}/tracks",
                json = JSONObject().put("queue", JSONArray(listOf(previous.cursor)))) as JSONObject
            parse(token, result, previous.sessionId, previous.request, previous.cursor)
        }
    }
    private suspend fun legacy(token: String, cursor: String, request: WaveRequest): WaveBatch {
        if (request.settings.isNotEmpty()) throw MusicException(MusicFailure.UNAVAILABLE)
        val result = music.api(token, "/rotor/station/${stationPath(request.station)}/tracks?settings2=True" + if (cursor.isBlank()) "" else "&queue=${encode(cursor)}") as JSONObject
        return parse(token, result, "", request, cursor)
    }
    private suspend fun parse(token: String, result: JSONObject, session: String, request: WaveRequest, skip: String = ""): WaveBatch {
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
        val tracks = candidates.mapNotNull { (id, track) -> (track ?: resolved[id])?.let { WaveTrack(it, batch, request.station) } }
        return WaveBatch(tracks, session, tracks.firstOrNull()?.track?.tasteTarget()?.key ?: skip, request)
    }
    override suspend fun feedback(profileId: String, item: WaveTrack, type: WaveFeedback, playedSeconds: Int) {
        if (item.batchId.isBlank()) return
        music.account(profileId) { token, uid ->
            val form = mutableListOf("type" to type.wire, "timestamp" to String.format(Locale.US, "%.3f", System.currentTimeMillis() / 1000.0))
            if (type == WaveFeedback.RADIO_STARTED) form += "from" to "mobile-radio-user-$uid"
            else form += "trackId" to item.track.tasteTarget().key
            if (type in setOf(WaveFeedback.FINISHED, WaveFeedback.SKIP)) form += "totalPlayedSeconds" to playedSeconds.coerceAtLeast(0).toString()
            val result = music.api(token, "/rotor/station/${stationPath(item.station)}/feedback?batch-id=${encode(item.batchId)}", form)
            if (result != "ok") throw MusicException(MusicFailure.RESPONSE)
        }
    }
    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")
    private fun stationPath(value: String) = encode(value).replace("%3A", ":")
    override suspend fun options(profileId: String, language: String): WaveOptions = music.account(profileId) { token, _ ->
        val result = music.api(token, "/rotor/wave/settings?seeds=user%3Aonyourwave&language=${encode(language)}") as JSONObject
        parseOptions(result)
    }
    companion object {
        /** Use the modern blocks, not the broader station list or defaultStation's incidental user ID. */
        fun parseOptions(result: JSONObject): WaveOptions {
            val groups = mutableListOf<WaveOptionGroup>()
            val contexts = result.optJSONArray("blocks")?.objects().orEmpty().filter { it.optString("type") == "contexts" }
                .flatMap { it.optJSONArray("items")?.objects().orEmpty() }.mapNotNull {
                    val id = it.optJSONObject("id") ?: return@mapNotNull null
                    val type = id.optString("type"); val tag = id.optString("tag")
                    if (type !in setOf("activity", "genre", "mood") || tag.isBlank() || it.optString("name").isBlank()) null
                    else WaveOption("$type:$tag", it.getString("name"))
                }.distinctBy(WaveOption::seed)
            if (contexts.isNotEmpty()) groups += WaveOptionGroup("contexts", "Под занятие",
                listOf(WaveOption("user:onyourwave", "Любое", true)) + contexts)
            val restrictions = result.optJSONObject("settingRestrictions") ?: throw MusicException(MusicFailure.RESPONSE)
            for (key in listOf("diversity", "moodEnergy", "language")) {
                val group = restrictions.optJSONObject(key) ?: continue
                val values = group.optJSONArray("possibleValues")?.objects().orEmpty().mapNotNull {
                    val seed = it.optString("serializedSeed"); val title = it.optString("name")
                    if (seed.isBlank() || title.isBlank()) null else WaveOption(seed, title, it.optBoolean("unspecified"))
                }.distinctBy(WaveOption::seed)
                if (values.isNotEmpty()) groups += WaveOptionGroup(key, group.optString("name", key), values)
            }
            if (groups.isEmpty()) throw MusicException(MusicFailure.RESPONSE)
            return WaveOptions(groups)
        }
    }
}
