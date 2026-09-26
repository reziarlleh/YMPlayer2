package dev.petrov.ymplayer2.yandex

import dev.petrov.ymplayer2.core.AccountAuth
import dev.petrov.ymplayer2.core.MusicException
import dev.petrov.ymplayer2.core.MusicFailure
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.time.Instant

data class YandexClip(
    val id: String,
    val title: String,
    val artist: String,
    val playerId: String,
    val thumbnailUrl: String?,
    val previewUrl: String?,
    val durationSeconds: Long,
    val trackIds: List<String>,
    val batchId: String,
)

data class ClipBatch(val clips: List<YandexClip>, val pumpkin: Boolean)
data class ClipSession(val id: String, val batch: ClipBatch)
data class ClipStream(val url: String, val mimeType: String?, val preview: Boolean)
enum class ClipFeedback(val wire: String) {
    QUEUE_STARTED("combinedQueueStarted"), STARTED("playableItemStarted"),
    FINISHED("playableItemFinished"), SKIPPED("playableItemSkip")
}

/** Clip Wave protocol of 1.x, scoped to the active 2.x account and isolated from audio playback. */
class YandexClipApi(
    private val accounts: AccountAuth,
    private val transport: ClipTransport = HttpsClipTransport(),
) {
    suspend fun start(profileId: String): ClipSession = scoped(profileId) { token ->
        val body = JSONObject().put("supportedTypes", JSONArray().put("clip")).put("queue", JSONArray())
        val result = unwrap(transport.post(api("/rotor/combined/session/new"), token, body))
        val id = result.optString("sessionId").takeIf { validId(it) } ?: throw MusicException(MusicFailure.RESPONSE)
        ClipSession(id, batch(result))
    }

    suspend fun next(profileId: String, sessionId: String, queueIds: List<String>): ClipBatch = scoped(profileId) { token ->
        val id = segment(sessionId)
        val queue = JSONArray()
        queueIds.filter(::validId).distinct().take(40).forEach {
            queue.put(JSONObject().put("type", "clip").put("id", it))
        }
        batch(unwrap(transport.post(api("/rotor/combined/session/$id/next"), token,
            JSONObject().put("queue", queue))))
    }

    suspend fun stream(profileId: String, clip: YandexClip): ClipStream = scoped(profileId) { token ->
        val preview = clip.previewUrl?.let { ClipStream(it, mime(it, ""), true) }
        if (!validId(clip.playerId)) return@scoped preview ?: throw MusicException(MusicFailure.UNAVAILABLE)
        val response = try {
            JSONObject(transport.get("https://frontend.vh.yandex.ru/player/${segment(clip.playerId)}.json" +
                "?service=ya-music&from=YandexMusicAndroidTVKPHD", token))
        } catch (e: CancellationException) { throw e }
          catch (_: Exception) { return@scoped preview ?: throw MusicException(MusicFailure.UNAVAILABLE) }
        val content = response.optJSONObject("content") ?: response.optJSONObject("result")?.optJSONObject("content")
        val episode = content?.optJSONObject("actual_episode")
        select(episode?.optJSONArray("streams"))
            ?: select(content?.optJSONArray("streams"))
            ?: mediaUrl(content?.optString("content_url").orEmpty())?.let { ClipStream(it, mime(it, ""), false) }
            ?: preview ?: throw MusicException(MusicFailure.UNAVAILABLE)
    }

    suspend fun feedback(profileId: String, sessionId: String, clip: YandexClip?, type: ClipFeedback, playedSeconds: Float = 0f) {
        scoped(profileId) { token ->
            val event = JSONObject().put("type", type.wire).put("timestamp", Instant.now().toString())
            if (type == ClipFeedback.QUEUE_STARTED) event.put("from", "YandexMusicAndroidTVKPHD")
            else event.put("playable", JSONObject().put("type", "clip").put("id", segment(clip?.id.orEmpty())))
            if (type == ClipFeedback.FINISHED || type == ClipFeedback.SKIPPED)
                event.put("totalPlayedSeconds", playedSeconds.takeIf(Float::isFinite)?.coerceAtLeast(0f) ?: 0f)
            val body = JSONObject().put("event", event)
            clip?.batchId?.takeIf(String::isNotBlank)?.let { body.put("batchId", it) }
            transport.post(api("/rotor/session/${segment(sessionId)}/feedback"), token, body)
        }
    }

    private suspend fun <T> scoped(profileId: String, block: suspend (String) -> T): T = try {
        accounts.withSession(profileId) { block(it.credentials.accessToken) }
    } catch (e: CancellationException) { throw e }
      catch (e: MusicException) { throw e }
      catch (_: Exception) { throw MusicException(MusicFailure.RESPONSE) }

    private fun batch(result: JSONObject): ClipBatch {
        val batchId = result.optString("batchId")
        val rows = result.optJSONArray("list") ?: JSONArray()
        val clips = rows.objects().mapNotNull { row ->
            if (!row.optString("type", "clip").equals("clip", true)) return@mapNotNull null
            val data = row.optJSONObject("data") ?: row.takeIf { it.has("clipId") } ?: return@mapNotNull null
            val id = data.optString("clipId").takeIf(::validId) ?: return@mapNotNull null
            val artists = data.optJSONArray("artists")?.objects().orEmpty().map { it.optString("name").trim() }
                .filter(String::isNotBlank).joinToString(", ").take(500)
            val ids = data.optJSONArray("trackIds")?.let { a -> (0 until a.length()).map { a.optString(it) }
                .filter(::validId) }.orEmpty()
            YandexClip(id, data.optString("title").ifBlank { "Clip Wave" }.take(500), artists,
                data.optString("playerId"), mediaUrl(data.optString("thumbnail").replace("%%", "1280x720")),
                mediaUrl(data.optString("previewUrl")), data.optLong("duration").coerceAtLeast(0), ids, batchId)
        }
        return ClipBatch(clips, result.optBoolean("pumpkin"))
    }

    private fun unwrap(text: String): JSONObject {
        val root = JSONObject(text)
        if (root.has("error")) throw MusicException(MusicFailure.RESPONSE)
        return root.optJSONObject("result") ?: root
    }
    private fun select(rows: JSONArray?): ClipStream? {
        val candidates = rows?.objects().orEmpty().mapNotNull { row ->
            if ((row.optJSONObject("drmConfig") ?: row.optJSONObject("drm_config"))?.length() ?: 0 > 0) return@mapNotNull null
            val url = mediaUrl(row.optString("url")) ?: return@mapNotNull null
            val type = row.optString("stream_type").lowercase()
            (when { "hls" in type -> 3; "dash" in type -> 2; else -> 1 }) to ClipStream(url, mime(url, type), false)
        }
        return candidates.maxByOrNull { it.first }?.second
    }
    private fun mediaUrl(value: String): String? {
        val url = when {
            value.startsWith("//") -> "https:$value"
            value.startsWith("https://") -> value
            !value.contains("://") && value.isNotBlank() -> "https://$value"
            else -> return null
        }
        return url.takeIf(::isYandexMediaUrl)
    }
    private fun mime(url: String, type: String): String? {
        val hint = "$type $url".lowercase()
        return when { "hls" in hint || ".m3u8" in hint -> "application/x-mpegURL"
            "dash" in hint || ".mpd" in hint -> "application/dash+xml"
            ".mp4" in hint -> "video/mp4"; else -> null }
    }
    private fun api(path: String) = "https://api.music.yandex.net$path"
    private fun validId(value: String) = value.isNotBlank() && value.length <= 256 && !value.any { it.isISOControl() }
    private fun segment(value: String): String {
        if (!validId(value)) throw MusicException(MusicFailure.RESPONSE)
        return URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    }
}
