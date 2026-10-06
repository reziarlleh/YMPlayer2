package dev.petrov.ymplayer2.core

import org.junit.Assert.*
import org.junit.Test

class PlaybackOriginTest {
    @Test fun entitySeedsUseTheirOwnIdentityAndRejectInvalidOrOwnerlessPlaylists() {
        assertEquals("track:7", MusicEntity("7", "Song", MusicKind.TRACKS).waveRequest()!!.station)
        assertEquals("artist:8", MusicEntity("8", "Artist", MusicKind.ARTISTS).waveRequest()!!.station)
        assertEquals("album:9", MusicEntity("9", "Album", MusicKind.ALBUMS).waveRequest()!!.station)
        assertEquals("playlist:100_3", MusicEntity("3", "Likes", MusicKind.PLAYLISTS, "100").waveRequest()!!.station)
        assertNull(MusicEntity("3", "List", MusicKind.PLAYLISTS).waveRequest())
        assertNull(MusicEntity("7?seeds=user:onyourwave", "Song", MusicKind.TRACKS).waveRequest())
        assertEquals("playlist:100_3", MusicRequest(collection = true).playbackOrigin("100").wave!!.station)
        assertNull(MusicRequest(query = "rock").playbackOrigin("100").wave)
        assertEquals("playlist:200_5", MusicRequest(entity = MusicEntity("5", "Recommended", MusicKind.PLAYLISTS, "200"),
            recommended = true).playbackOrigin("100").wave!!.station)
    }
    @Test fun modesAreExclusiveInEveryOrderAndPreservePositionAndOrigin() {
        val base = PlaybackState("owner", emptyList(), positionSeconds = 17,
            origin = PlaybackOrigin(PlaybackSource.LIST, "Album", WaveRequest("album:9", "Album")))
        for (repeat in listOf(RepeatMode.ALL, RepeatMode.ONE)) {
            val repeating = base.withShuffle(true).withContinuation(true).withRepeat(repeat)
            assertEquals(repeat, repeating.repeatMode); assertFalse(repeating.shuffle); assertFalse(repeating.continueWave)
            val shuffled = repeating.withShuffle(true)
            assertEquals(RepeatMode.OFF, shuffled.repeatMode); assertFalse(shuffled.continueWave)
            val continued = shuffled.withContinuation(true)
            assertEquals(RepeatMode.OFF, continued.repeatMode); assertFalse(continued.shuffle); assertTrue(continued.continueWave)
            assertEquals(base.origin, continued.origin); assertEquals(17, continued.positionSeconds)
        }
    }
    @Test fun finiteLocalListsShuffleIndefinitelyWithoutRepeatAndRestoreOnlyOneMode() {
        val player = DemoPlaybackController(DemoCatalog())
        player.playList(listOf("local:4", "local:5"), origin = PlaybackOrigin(PlaybackSource.LIST, "Road"))
        player.setRepeatMode(RepeatMode.ONE); player.setShuffle(true)
        repeat(20) { player.skip(1); assertTrue(player.state.value.playing) }
        assertEquals(RepeatMode.OFF, player.state.value.repeatMode)
        assertEquals("Road", DemoPlaybackController(DemoCatalog(), player.state.value.checkpoint()).state.value.origin.title)
        player.setContinueWave(true); assertFalse(player.state.value.continueWave)
    }
}
