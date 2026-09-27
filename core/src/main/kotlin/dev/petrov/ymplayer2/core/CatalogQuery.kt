package dev.petrov.ymplayer2.core

import java.util.PriorityQueue

/** A bounded view of catalog metadata. The audio files remain at their original source. */
data class CatalogFilter(
    val source: Source? = null,
    val offlineOnly: Boolean = false,
    val availableOnly: Boolean = false,
    val query: String = "",
)

data class CatalogPage<T>(val items: List<T>, val total: Int, val offset: Int) {
    val hasMore: Boolean get() = offset + items.size < total
}

data class CatalogGroup(val name: String, val count: Int, val sample: Track)

/** Query boundary for the UI. The SAF store can replace this with a disk index later. */
object CatalogQueries {
    fun tracks(source: List<Track>, filter: CatalogFilter, descending: Boolean = false,
        group: String? = null, groupOf: ((Track) -> String)? = null,
        offset: Int = 0, limit: Int = 80): CatalogPage<Track> {
        require(offset >= 0 && limit in 1..50_000 && offset <= 50_000 - limit)
        val wanted = offset + limit
        val order = compareBy<Track> { it.title.lowercase() }.thenBy(Track::id)
            .let { if (descending) it.reversed() else it }
        // Keep only the best requested prefix, even when the source has many tracks.
        val selected = PriorityQueue<Track>(wanted.coerceAtLeast(1), order.reversed())
        var total = 0
        for (track in source) {
            if (!matches(track, filter) || group != null && groupOf?.invoke(track) != group) continue
            total++
            if (selected.size < wanted) selected += track
            else if (order.compare(track, selected.peek()) < 0) {
                selected.remove()
                selected += track
            }
        }
        return CatalogPage(selected.toList().sortedWith(order).drop(offset).take(limit), total, offset)
    }

    fun groups(source: List<Track>, filter: CatalogFilter, groupOf: (Track) -> String,
        descending: Boolean = false, offset: Int = 0, limit: Int = 80): CatalogPage<CatalogGroup> {
        require(offset >= 0 && limit in 1..50_000 && offset <= 50_000 - limit)
        val counts = linkedMapOf<String, CatalogGroup>()
        for (track in source) {
            if (!matches(track, filter)) continue
            val name = groupOf(track)
            val old = counts[name]
            counts[name] = if (old == null) CatalogGroup(name, 1, track) else old.copy(count = old.count + 1)
        }
        val order = compareBy<CatalogGroup> { it.name.lowercase() }.thenBy(CatalogGroup::name)
            .let { if (descending) it.reversed() else it }
        return CatalogPage(counts.values.sortedWith(order).drop(offset).take(limit), counts.size, offset)
    }

    private fun matches(track: Track, filter: CatalogFilter): Boolean {
        if (filter.source != null && track.source != filter.source) return false
        if (filter.availableOnly && !track.available) return false
        if (filter.offlineOnly && (!track.offline || !track.available)) return false
        val query = filter.query.trim()
        return query.isEmpty() || track.title.contains(query, true) || track.artist.contains(query, true) || track.album.contains(query, true)
    }
}
