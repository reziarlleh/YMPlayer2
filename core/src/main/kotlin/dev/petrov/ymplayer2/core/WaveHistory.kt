package dev.petrov.ymplayer2.core

/** Recommendation deduplication survives restart; it is not a playback queue. */
class WaveHistory(private val limit: Int = 4000) {
    private val keys = linkedSetOf<String>()
    val seen: Set<String> get() = keys
    fun remember(key: String) {
        if (key.isBlank()) return
        keys.add(key)
        while (keys.size > limit) keys.remove(keys.first())
    }
    fun clear() = keys.clear()
    fun restore(saved: Iterable<String>) { clear(); saved.forEach(::remember) }
}
