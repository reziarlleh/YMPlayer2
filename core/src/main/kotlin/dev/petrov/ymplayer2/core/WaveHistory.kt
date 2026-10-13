package dev.petrov.ymplayer2.core

/** Recommendation deduplication survives restart; it is not a playback queue. */
class WaveHistory(private val limit: Int = 4000) {
    private val keys = linkedSetOf<String>()
    var revision = 0L
        private set
    val seen: Set<String> get() = keys
    fun remember(key: String) {
        if (key.isBlank()) return
        if (keys.add(key)) revision++
        while (keys.size > limit) keys.remove(keys.first())
    }
    fun clear() { keys.clear(); revision++ }
    fun restore(saved: Iterable<String>) { clear(); saved.forEach(::remember) }
}
