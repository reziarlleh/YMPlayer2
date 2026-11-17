package dev.petrov.ymplayer2

import android.content.Context
import dev.petrov.ymplayer2.core.PlaybackStartMode
import dev.petrov.ymplayer2.core.PlaybackStartPreferences

internal fun loadPlaybackStartSettings(context: Context): PlaybackStartPreferences {
    val prefs = context.getSharedPreferences("playback-start", Context.MODE_PRIVATE)
    val initial = prefs.all.filterKeys { it.startsWith("mode:") }.map { (key, value) ->
        key.removePrefix("mode:") to PlaybackStartMode.fromKey(value as? String)
    }.toMap()
    return PlaybackStartPreferences(initial) { profile, mode ->
        prefs.edit().apply { if (mode == null) remove("mode:$profile") else putString("mode:$profile", mode.name) }.apply()
    }
}
