package dev.petrov.ymplayer2.playback

import android.content.Context

enum class PlaybackOutput { MUSIC, RADIO, CLIPS }
data class LaunchState(val output: PlaybackOutput, val playing: Boolean, val stopped: Boolean = false)

/** Durable output ownership, separate from the music queue and from an Activity's saved Bundle. */
class LaunchStateStore(context: Context) {
    private val prefs = context.getSharedPreferences("launch-state", Context.MODE_PRIVATE)
    private val playback = context.getSharedPreferences("playback", Context.MODE_PRIVATE)
    fun read(profile: String): LaunchState = LaunchState(
        runCatching { PlaybackOutput.valueOf(prefs.getString("output:$profile", "MUSIC")!!) }.getOrDefault(PlaybackOutput.MUSIC),
        prefs.getBoolean("playing:$profile", false),
        !prefs.getBoolean("playing:$profile", false) && if (prefs.contains("stopped:$profile"))
            prefs.getBoolean("stopped:$profile", false) else legacyStopped(profile))
    // Legacy false had no Pause/Stop marker. Keep a nonzero saved position paused;
    // zero/unknown retains the old idle behaviour, always without autoplay.
    private fun legacyStopped(profile: String) = runCatching {
        org.json.JSONObject(playback.getString("queue:$profile", "{}")!!).optInt("position", 0) <= 0
    }.getOrDefault(true)
    fun write(profile: String, output: PlaybackOutput, playing: Boolean, stopped: Boolean = false) {
        val state = LaunchState(output, playing, !playing && stopped)
        if (read(profile) == state && prefs.contains("stopped:$profile")) return
        prefs.edit().putString("output:$profile", output.name).putBoolean("playing:$profile", playing)
            .putBoolean("stopped:$profile", state.stopped).apply()
    }
    /** Lifecycle fence: completes earlier apply writes without writing a stale snapshot. */
    fun flush() = prefs.edit().commit()
}
