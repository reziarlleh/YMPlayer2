package dev.petrov.ymplayer2.core

import org.junit.Assert.*
import org.junit.Test

class WaveQueueWindowTest {
    private val tracks = (1..30).map { Track("yandex:$it", "Track $it", "Artist", "", Source.YANDEX, 30, false) }

    @Test fun firstWindowIsCurrentAndNineUpcomingTracks() {
        assertEquals(tracks.take(10), WaveQueueWindow.retain(tracks, tracks.first().id))
        assertFalse(WaveQueueWindow.needsMore(tracks, tracks.first().id))
    }

    @Test fun advancingEvictsOldHistoryButKeepsOnePreviousAndAtMostTenEntries() {
        val window = WaveQueueWindow.retain(tracks, tracks[12].id)
        assertEquals(tracks.subList(11, 21), window)
        assertEquals(tracks[11], window.first())
        assertEquals(tracks[12], window[1])
    }

    @Test fun shortTailNeedsRefillingWithoutReplayingDiscardedHistory() {
        val queue = tracks.take(10)
        assertEquals(tracks.subList(7, 10), WaveQueueWindow.retain(queue, tracks[8].id))
        assertTrue(WaveQueueWindow.needsMore(queue, tracks[8].id))
        assertTrue(WaveQueueWindow.needsMore(emptyList(), null))
    }
}
