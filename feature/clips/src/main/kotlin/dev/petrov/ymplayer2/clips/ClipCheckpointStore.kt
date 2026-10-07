package dev.petrov.ymplayer2.clips

import android.content.Context
import dev.petrov.ymplayer2.yandex.YandexClip
import org.json.JSONArray
import org.json.JSONObject

data class ClipCheckpoint(val clip: YandexClip, val sessionId: String, val positionMs: Long, val playing: Boolean)

/** Clip descriptors only, including the API's public preview fallback. No resolved full-stream URL or token. */
class ClipCheckpointStore(context: Context) {
    private val prefs = context.getSharedPreferences("clip-checkpoints", Context.MODE_PRIVATE)
    fun read(profile: String): ClipCheckpoint? = runCatching {
        val data = JSONObject(prefs.getString(profile, "")!!)
        val clip = data.getJSONObject("clip")
        val tracks = clip.optJSONArray("tracks") ?: JSONArray()
        ClipCheckpoint(YandexClip(clip.getString("id"), clip.getString("title"), clip.getString("artist"),
            clip.getString("player"), clip.optString("thumbnail").takeIf(String::isNotBlank),
            clip.optString("preview").takeIf(String::isNotBlank),
            clip.optLong("duration"), (0 until tracks.length()).map(tracks::getString), clip.optString("batch")),
            data.getString("session"), data.optLong("position").coerceAtLeast(0), data.optBoolean("playing"))
    }.getOrNull()
    fun write(profile: String, checkpoint: ClipCheckpoint) {
        val clip = checkpoint.clip
        val data = JSONObject().put("session", checkpoint.sessionId).put("position", checkpoint.positionMs)
            .put("playing", checkpoint.playing).put("clip", JSONObject().put("id", clip.id).put("title", clip.title)
                .put("artist", clip.artist).put("player", clip.playerId).put("thumbnail", clip.thumbnailUrl.orEmpty())
                .put("preview", clip.previewUrl.orEmpty())
                .put("duration", clip.durationSeconds).put("tracks", JSONArray(clip.trackIds)).put("batch", clip.batchId))
        prefs.edit().putString(profile, data.toString()).apply()
    }
    fun flush() = prefs.edit().commit()
}
