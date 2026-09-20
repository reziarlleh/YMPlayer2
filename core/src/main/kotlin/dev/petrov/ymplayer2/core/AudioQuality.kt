package dev.petrov.ymplayer2.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AudioQuality(val key: String, val label: String, val targetKbps: Int?) {
    AUTO("auto", "Авто", null),
    ECONOMY("economy", "Экономия · 128 кбит/с", 128),
    STANDARD("standard", "Стандарт · 192 кбит/с", 192),
    HIGH("high", "Высокое · 320 кбит/с", 320),
    MAX("max", "Максимальное", null);

    companion object {
        fun fromKey(value: String?) = entries.firstOrNull { it.key.equals(value?.trim(), true) } ?: AUTO
    }
}

data class AudioQualities(val stream: AudioQuality = AudioQuality.AUTO, val cache: AudioQuality = AudioQuality.AUTO)

/** Device preferences, independent from accounts. Changing them never commands playback or rewrites files. */
class AudioQualityPreferences(initial: AudioQualities = AudioQualities(), private val save: (AudioQualities) -> Unit = {}) {
    private val mutable = MutableStateFlow(initial)
    val state = mutable.asStateFlow()
    fun setStream(value: AudioQuality) = update(state.value.copy(stream = value))
    fun setCache(value: AudioQuality) = update(state.value.copy(cache = value))
    private fun update(value: AudioQualities) {
        if (value == state.value) return
        save(value)
        mutable.value = value
    }
}
