package dev.petrov.ymplayer2

import android.content.Context
import dev.petrov.ymplayer2.core.*

internal fun loadAudioQuality(context: Context): AudioQualityPreferences {
    val prefs = context.getSharedPreferences("audio-quality", Context.MODE_PRIVATE)
    return AudioQualityPreferences(AudioQualities(
        AudioQuality.fromKey(prefs.getString("stream", null)), AudioQuality.fromKey(prefs.getString("cache", null)))) {
        prefs.edit().putString("stream", it.stream.key).putString("cache", it.cache.key).apply()
    }
}
