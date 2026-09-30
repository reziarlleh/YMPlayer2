package dev.petrov.ymplayer2

import dev.petrov.ymplayer2.core.*
import kotlinx.coroutines.runBlocking

/** Test assertions may inspect a complete fixture; production uses IDs/pages. */
internal val LocalLibrary.testTracks: List<Track>
    get() = if (this is IndexedLocalLibrary) runBlocking {
        buildList {
            var offset = 0
            do {
                val page = pageTracks(CatalogFilter(), offset = offset, limit = 500)
                addAll(page.items)
                offset += page.items.size
            } while (page.items.isNotEmpty() && offset < page.total)
        }
    } else state.value.tracks
