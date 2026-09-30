package dev.petrov.ymplayer2.playback

import dev.petrov.ymplayer2.core.*

/** Catalog position plus an optional shuffled ID order. Audio metadata stays in the index. */
internal class IndexedPlaybackOrder(private val library: IndexedLocalLibrary) {
    private var shuffled = emptyList<String>()
    private var revision = -1L
    private var source: Source? = null

    fun reset() { shuffled = emptyList(); revision = -1 }

    private suspend fun shuffleOrder(current: String?, selectedSource: Source?): List<String> {
        if (revision != library.indexRevision || selectedSource != source) {
            val observed = library.indexRevision
            val available = library.playableTrackIds(selectedSource)
            val set = available.toHashSet()
            val keep = if (selectedSource == source) shuffled.filter { it in set } else emptyList()
            val known = keep.toHashSet()
            val added = available.filterNot { it in known }.shuffled()
            shuffled = if (keep.isEmpty() && current in set) listOf(current!!) + added.filterNot { it == current }
                else keep + added
            revision = observed
            source = selectedSource
        }
        return shuffled
    }

    suspend fun adjacent(current: String?, direction: Int, source: Source?, shuffle: Boolean, repeat: RepeatMode): Track? {
        if (!shuffle) return library.adjacentTrack(current, direction, source, wrap = repeat == RepeatMode.ALL)
        val ids = shuffleOrder(current, source)
        if (ids.isEmpty()) return null
        val index = ids.indexOf(current)
        val next = if (index < 0) { if (direction > 0) 0 else ids.lastIndex } else index + direction
        val selected = ids.getOrNull(next) ?: if (repeat == RepeatMode.ALL)
            ids[if (direction > 0) 0 else ids.lastIndex] else return null
        return library.tracksByIds(listOf(selected))[selected]
    }

    suspend fun window(current: String?, source: Source?, shuffle: Boolean, repeat: RepeatMode): LocalPlaybackWindow? {
        val window = library.playbackWindow(current, source) ?: return null
        if (!shuffle) return window
        val ids = shuffleOrder(window.current.id, source)
        val index = ids.indexOf(window.current.id)
        fun neighbour(direction: Int): String? {
            if (index < 0 || ids.isEmpty()) return null
            return ids.getOrNull(index + direction) ?: if (repeat == RepeatMode.ALL)
                ids[if (direction > 0) 0 else ids.lastIndex] else null
        }
        val next = neighbour(1)?.takeUnless { it == window.current.id }
        val previous = neighbour(-1)?.takeUnless { it == window.current.id || it == next }
        val selected = listOfNotNull(previous, window.current.id, next)
        val tracks = library.tracksByIds(selected)
        return window.copy(media = selected.mapNotNull { tracks[it]?.takeIf(Track::available) })
    }
}
