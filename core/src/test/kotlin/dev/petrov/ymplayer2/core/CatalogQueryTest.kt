package dev.petrov.ymplayer2.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogQueryTest {
    private fun track(number: Int, source: Source = Source.LOCAL, available: Boolean = true,
        album: String = "Альбом"): Track = Track("id:$number", "Трек ${number.toString().padStart(4, '0')}",
        "Исполнитель", album, source, 180, true, available)

    @Test fun pagesAreStableAndBoundedForLargeCatalog() {
        val tracks = (0 until 5_000).reversed().map(::track)
        val first = CatalogQueries.tracks(tracks, CatalogFilter(), limit = 80)
        val second = CatalogQueries.tracks(tracks, CatalogFilter(), offset = 80, limit = 80)
        assertEquals(5_000, first.total)
        assertEquals(80, first.items.size)
        assertEquals("id:0", first.items.first().id)
        assertEquals("id:79", first.items.last().id)
        assertEquals("id:80", second.items.first().id)
        assertTrue(first.hasMore)
        assertFalse(CatalogQueries.tracks(tracks, CatalogFilter(), offset = 4_960, limit = 80).hasMore)
    }

    @Test fun sourceAvailabilityAndSearchAreCombined() {
        val tracks = listOf(track(1, Source.USB, false), track(2, Source.USB), track(3))
        val page = CatalogQueries.tracks(tracks, CatalogFilter(Source.USB, availableOnly = true, query = "трек"))
        assertEquals(listOf("id:2"), page.items.map(Track::id))
        assertEquals(1, page.total)
        assertEquals(0, CatalogQueries.tracks(tracks, CatalogFilter(Source.YANDEX)).total)
    }

    @Test fun groupsCountWithoutMaterializingTrackLists() {
        val tracks = listOf(track(1, album = "Б"), track(2, album = "А"), track(3, album = "А"))
        val groups = CatalogQueries.groups(tracks, CatalogFilter(), Track::album, limit = 1)
        assertEquals(2, groups.total)
        assertEquals("А", groups.items.single().name)
        assertEquals(2, groups.items.single().count)
        assertTrue(groups.hasMore)
        val detail = CatalogQueries.tracks(tracks, CatalogFilter(), group = "А", groupOf = Track::album)
        assertEquals(listOf("id:2", "id:3"), detail.items.map(Track::id))
    }
}
