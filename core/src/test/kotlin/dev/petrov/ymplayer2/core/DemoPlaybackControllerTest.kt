package dev.petrov.ymplayer2.core

import org.junit.Assert.*
import org.junit.Test

class DemoPlaybackControllerTest {
    private val catalog = DemoCatalog()
    private val player = DemoPlaybackController(catalog)
    @Test fun profilesKeepSeparatePausedSessions() {
        player.toggle(); player.seek(83)
        player.switchProfile("guest")
        assertFalse(player.state.value.playing)
        assertTrue(player.state.value.queue.none { it.source == Source.YANDEX })
        player.select("local:4"); player.seek(21)
        player.switchProfile("owner")
        assertEquals(83, player.state.value.positionSeconds)
        assertFalse(player.state.value.playing)
        player.switchProfile("guest")
        assertEquals("local:4", player.state.value.current?.id)
        assertEquals(21, player.state.value.positionSeconds)
    }
    @Test fun sourceSelectionStopsAndWaitsForPlay() {
        player.toggle(); player.seek(80); player.chooseSource(Source.USB)
        assertFalse(player.state.value.playing)
        assertEquals(0, player.state.value.positionSeconds)
        assertTrue(player.state.value.queue.all { it.source == Source.USB })
    }
    @Test fun seekClampsAndStopResets() {
        player.seek(-1); assertEquals(0, player.state.value.positionSeconds)
        player.seek(Int.MAX_VALUE); assertEquals(238, player.state.value.positionSeconds)
        player.toggle(); player.stop()
        assertEquals(0, player.state.value.positionSeconds); assertFalse(player.state.value.playing)
    }
    @Test fun unavailableAndForeignTracksCannotPlay() {
        val before = player.state.value
        player.select("usb:6"); assertEquals(before, player.state.value)
        player.switchProfile("guest")
        val guest = player.state.value
        player.select("yandex:1"); assertEquals(guest, player.state.value)
    }
    @Test fun queueEndStopsWithoutPlayingUnavailableTrack() {
        player.select("local:5"); player.skip(1)
        assertFalse(player.state.value.playing)
        assertEquals("local:5", player.state.value.current?.id)
        player.skip(-1); assertEquals("local:4", player.state.value.current?.id)
    }
    @Test fun browsingCatalogDoesNotChangePlayback() {
        player.toggle(); player.seek(100)
        val before = player.state.value
        catalog.tracks("guest").filter { it.title.contains("улиц") }
        assertEquals(before, player.state.value)
    }
    @Test fun emptySourceIsSafe() {
        player.switchProfile("guest"); player.chooseSource(Source.YANDEX)
        player.toggle(); player.seek(12); player.skip(1); player.select("unknown")
        assertNull(player.state.value.current); assertFalse(player.state.value.playing)
        assertEquals(0, player.state.value.positionSeconds)
    }
    @Test fun processCheckpointRestoresPausedAndRejectsForeignIds() {
        player.switchProfile("guest"); player.chooseSource(Source.LOCAL); player.select("local:5"); player.seek(112)
        val restored = DemoPlaybackController(catalog, player.state.value.checkpoint())
        assertEquals("local:5", restored.state.value.current?.id)
        assertEquals(112, restored.state.value.positionSeconds)
        assertFalse(restored.state.value.playing)
        val invalid = DemoPlaybackController(catalog, PlaybackCheckpoint("guest", listOf("yandex:1", "local:4", "local:4"), "yandex:1", 900))
        assertEquals(listOf("local:4"), invalid.state.value.queue.map(Track::id))
        assertEquals(215, invalid.state.value.positionSeconds)
    }
    @Test fun queueEditsPreserveCurrentPositionAndClearSurvivesRestore() {
        player.select("local:4"); player.seek(72)
        player.moveInQueue("local:4", 0)
        player.removeFromQueue("yandex:1")
        assertEquals("local:4", player.state.value.current?.id)
        assertEquals(72, player.state.value.positionSeconds)
        assertTrue(player.state.value.playing)
        val before = player.state.value
        player.moveInQueue("local:4", -1); player.moveInQueue("missing", 0); player.enqueue("local:4"); player.enqueue("usb:6")
        assertEquals(before, player.state.value)
        player.removeFromQueue("local:4")
        assertFalse(player.state.value.playing)
        assertEquals(0, player.state.value.positionSeconds)
        player.clearQueue()
        assertTrue(DemoPlaybackController(catalog, player.state.value.checkpoint()).state.value.queue.isEmpty())
        player.enqueue("local:4")
        assertEquals(listOf("local:4"), player.state.value.queue.map(Track::id))
        assertFalse(player.state.value.playing)
    }
    @Test fun repeatAndShuffleKeepCurrentTrackAndProfileSettings() {
        player.select("local:5"); player.seek(30)
        player.setRepeatMode(RepeatMode.ALL); player.setShuffle(true)
        assertEquals("local:5", player.state.value.current?.id)
        assertEquals(30, player.state.value.positionSeconds)
        val restored = DemoPlaybackController(catalog, player.state.value.checkpoint())
        assertTrue(restored.state.value.shuffle)
        assertEquals(RepeatMode.ALL, restored.state.value.repeatMode)
        player.switchProfile("guest")
        assertFalse(player.state.value.shuffle)
        assertEquals(RepeatMode.OFF, player.state.value.repeatMode)
        player.switchProfile("owner"); player.setShuffle(false); player.skip(1)
        assertEquals("yandex:1", player.state.value.current?.id)
        player.setRepeatMode(RepeatMode.ONE); player.skip(1)
        assertEquals("yandex:2", player.state.value.current?.id)
    }
    @Test fun shuffledTraversalVisitsEveryAvailableTrackBeforeRepeating() {
        player.setRepeatMode(RepeatMode.ALL); player.setShuffle(true)
        val available = player.state.value.queue.filter { it.available }.map(Track::id).toSet()
        val visited = mutableListOf<String>()
        repeat(available.size) { visited += player.state.value.current!!.id; player.skip(1) }
        assertEquals(available, visited.toSet())
        assertEquals(visited.first(), player.state.value.current?.id)
    }
}
