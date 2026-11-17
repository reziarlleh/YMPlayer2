package dev.petrov.ymplayer2.core

import org.junit.Assert.*
import org.junit.Test

class PlaybackStartModeTest {
    @Test fun oldUnknownAndNewProfilesDefaultToAutoAndRemainIndependent() {
        val saved = mutableMapOf<String, PlaybackStartMode>()
        val modes = PlaybackStartPreferences { id, mode -> if (mode == null) saved.remove(id) else saved[id] = mode }
        assertEquals(PlaybackStartMode.AUTO, modes.mode("local"))
        assertEquals(PlaybackStartMode.AUTO, PlaybackStartMode.fromKey("future"))
        modes.set("owner", PlaybackStartMode.ON)
        modes.set("local", PlaybackStartMode.OFF)
        assertEquals(PlaybackStartMode.ON, PlaybackStartPreferences(saved).mode("owner"))
        assertEquals(PlaybackStartMode.AUTO, modes.mode("new_user"))
        modes.remove("owner")
        assertFalse(saved.containsKey("owner"))
        assertEquals(PlaybackStartMode.AUTO, modes.mode("owner"))
        assertEquals(PlaybackStartMode.OFF, modes.mode("local"))
    }
    @Test fun backgroundReturnIsANewEntryButRotationAndVideoHandoffAreNot() {
        val entries = PlaybackUiEntries()
        val main = Any(); val video = Any(); val rotated = Any()
        assertTrue(entries.started(main))
        assertFalse(entries.started(main))
        assertFalse(entries.started(video))
        assertFalse(entries.stopped(main, false))
        assertFalse(entries.stopped(video, true))
        assertFalse(entries.started(rotated))
        assertTrue(entries.stopped(rotated, false))
        assertTrue(entries.started(rotated))
    }
}
