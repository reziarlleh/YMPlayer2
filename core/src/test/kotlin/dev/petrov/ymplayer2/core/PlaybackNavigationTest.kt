package dev.petrov.ymplayer2.core
import org.junit.Assert.*
import org.junit.Test
class PlaybackNavigationTest {
    private fun track(id: String, available: Boolean = true) = Track(id, id, "", "", Source.LOCAL, 30, false, available = available)
    @Test fun unavailableRowsAndQueueBoundariesDisableSkip() {
        val s = PlaybackState("owner", listOf(track("1"), track("2", false), track("3")))
        assertFalse(s.canSkipPrevious); assertTrue(s.canSkipNext)
        assertTrue(s.copy(index = 2).canSkipPrevious); assertFalse(s.copy(index = 2).canSkipNext)
        assertTrue(s.copy(index = 2, shuffle = true).canSkipNext)
        assertFalse(s.copy(queue = emptyList()).canSkipNext)
    }
    @Test fun diskOrderSuppliesAvailabilityBeyondMetadataWindow() {
        val s = PlaybackState("owner", listOf(track("9")), index = 8, queueOffset = 8, queueTotal = 10,
            previousAvailable = false, nextAvailable = true)
        assertFalse(s.canSkipPrevious); assertTrue(s.canSkipNext)
    }
    @Test fun waveWithoutPreparedSuccessorHasNoNextCommand() {
        assertFalse(PlaybackState("owner", listOf(track("1")), wave = true).canSkipNext)
    }
}
