package dev.petrov.ymplayer2.playback

import android.content.Context

enum class PlaybackOutput { MUSIC, RADIO, CLIPS }
data class LaunchState(val output: PlaybackOutput, val playing: Boolean)

/** Durable output ownership, separate from the music queue and from an Activity's saved Bundle. */
class LaunchStateStore(context: Context) {
    private val prefs = context.getSharedPreferences("launch-state", Context.MODE_PRIVATE)
    fun read(profile: String): LaunchState = LaunchState(
        runCatching { PlaybackOutput.valueOf(prefs.getString("output:$profile", "MUSIC")!!) }.getOrDefault(PlaybackOutput.MUSIC),
        prefs.getBoolean("playing:$profile", false))
    fun write(profile: String, output: PlaybackOutput, playing: Boolean) {
        val state = LaunchState(output, playing)
        if (read(profile) == state) return
        prefs.edit().putString("output:$profile", output.name).putBoolean("playing:$profile", playing).apply()
    }
    /** Lifecycle fence: completes earlier apply writes without writing a stale snapshot. */
    fun flush() = prefs.edit().commit()
}
