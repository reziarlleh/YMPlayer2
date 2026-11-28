package dev.petrov.ymplayer2.core

/** Recommendation deduplication survives restart; it is not a playback queue. */
class WaveHistory(private val limit: Int = 4000) {
    private val keys = linkedSetOf<String>()
    var revision = 0L
        private set
    val seen: Set<String> get() = keys
    fun remember(key: String, refreshRecency: Boolean = false) {
        if (key.isBlank()) return
        if (refreshRecency) keys.remove(key)
        if (keys.add(key)) revision++
        while (keys.size > limit) keys.remove(keys.first())
    }
    fun clear() { keys.clear(); revision++ }
    /** Protect the active window and the last ten tracks outside that window. */
    fun personalWaveExclusions(queued: Set<String>): Set<String> =
        queued + keys.filterNot(queued::contains).takeLast(WaveQueueWindow.MAX_ITEMS)
    fun restore(saved: Iterable<String>) { clear(); saved.forEach { remember(it) } }
}
