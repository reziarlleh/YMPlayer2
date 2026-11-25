package dev.petrov.ymplayer2.core

import org.junit.Assert.*
import org.junit.Test

class LocalListHistoryTest {
    private val lists = (1..9).map { LocalListTarget("folder:$it", "Folder $it") }

    @Test fun fivePinsKeepTheirOrderWhileShortPressTracksAnotherList() {
        var history = LocalListHistory()
        lists.take(5).forEach { history = history.played(it).pin(it, true) }
        lists.drop(5).forEach { history = history.played(it) }
        assertEquals(lists.take(5), history.menu())
        assertEquals(lists.last(), history.recent.first())
        assertEquals(6, history.recent.size)
        history = history.pin(lists[2], false)
        assertEquals(listOf(lists[0], lists[1], lists[3], lists[4], lists.last()), history.menu())
        assertEquals(lists.last(), history.recent.first())
    }

    @Test fun pinningNeverPlaysAndOnlyUnpinnedHistoryIsEvicted() {
        var history = LocalListHistory()
        lists.take(3).forEach { history = history.played(it) }
        history = history.pin(lists[0], true)
        assertEquals(lists[2], history.recent.first())
        lists.drop(3).forEach { history = history.played(it) }
        assertEquals(listOf(lists[0]) + lists.takeLast(4).reversed(), history.menu())
        val renamed = lists[0].copy(title = "Renamed")
        history = history.played(renamed)
        assertEquals(renamed, history.menu().first())
        assertEquals(renamed, history.recent.first())
    }
}
