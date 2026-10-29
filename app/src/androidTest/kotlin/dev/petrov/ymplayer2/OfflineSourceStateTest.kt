@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
package dev.petrov.ymplayer2

import android.net.Uri
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.petrov.ymplayer2.core.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

/** Real Media3 and persistent cache; fixture accounts/streams are local and have no personal data. */
@RunWith(AndroidJUnit4::class)
class OfflineSourceStateTest {
    @get:Rule val compose = createAndroidComposeRule<OnlineTestActivity>()
    private val h get() = compose.activity.harness
    private val player get() = h.player
    private val owner = OfflineOwner("owner", "1")
    private fun waitFor(condition: () -> Boolean) {
        try { compose.waitUntil(25_000, condition) }
        catch (failure: Throwable) { throw AssertionError("Offline source state: ${player.state.value}", failure) }
    }

    @Before fun prepare() {
        waitFor { h.library.state.value.ready && !h.library.state.value.scanning && player.state.value.connected }
        compose.runOnIdle {
            player.stop(); player.switchProfile("owner"); player.clearQueue(); h.offline.setEnabled(true)
            // Independent fixtures begin at track one. Cache clear/refill intentionally does not
            // promise to erase the user's separate source bookmark in production.
            h.context.getSharedPreferences("playback", 0).edit().apply {
                for (key in listOf("owner:1", "road:2")) { remove("offlineState:$key"); remove("offlineOrder:$key") }
            }.commit()
        }
        waitFor { h.offline.state.value.owner == owner && h.offline.state.value.ready }
        compose.runOnIdle { h.offline.clear() }
        waitFor { h.offline.state.value.ready && h.offline.state.value.tracks.isEmpty() }
        runBlocking { h.library.state.value.roots.forEach { h.library.forgetFolder(it.uri) } }
        compose.activity.contentResolver.call(Uri.parse("content://dev.petrov.ymplayer2.test.control"), "fixtures", null, null)
        runBlocking { h.library.addFolder(TestMusicProvider.tree.toString(), Source.LOCAL) }
        waitFor { h.library.testTracks.size == 2 }
        seedCache("owner")
    }
    @After fun cleanup() {
        compose.runOnIdle { h.offline.cancel(); h.streamGates = emptyMap(); player.stop(); h.internet.available.value = true }
    }

    @Test fun returningFromWaveRestoresOfflineTrackPositionAndShuffle() = roundTrip(shuffle = true)
    @Test fun returningFromWaveRestoresOfflineTrackPositionAndRepeatOne() = roundTrip(repeat = RepeatMode.ONE)
    @Test fun returningFromWaveRestoresOfflineTrackPositionAndRepeatList() = roundTrip(repeat = RepeatMode.ALL)

    @Test fun offlineBookmarkSurvivesAnEngineRestartWhileWaveIsSelected() {
        val id = prepareOfflineBookmark(shuffle = true)
        startWave()
        compose.runOnIdle { player.saveForExit(); h.restartEngine() }
        waitFor { player.state.value.connected && player.state.value.wave && player.state.value.current != null }
        returnOffline(id, shuffle = true)
    }

    @Test fun pausedOfflineProcessRestorePreservesPositionAndModeWithoutAutoplay() {
        val id = prepareOfflineBookmark(repeat = RepeatMode.ONE)
        compose.runOnIdle { player.saveForExit(); h.restartEngine() }
        waitFor { player.state.value.connected && player.state.value.current?.id == id && player.state.value.positionSeconds == 4 }
        assertFalse("A metadata/process restore must preserve explicit Pause", player.state.value.playing)
        assertModes(RepeatMode.ONE, false)
        assertEquals(PlaybackSource.OFFLINE, player.state.value.origin.source)
    }

    @Test fun anotherProfileCannotOverwriteTheOwnersOfflineBookmark() {
        val id = prepareOfflineBookmark(shuffle = true)
        startWave()
        compose.runOnIdle { player.switchProfile("road") }
        waitFor { player.state.value.profileId == "road" && h.offline.state.value.owner == OfflineOwner("road", "2") && h.offline.state.value.ready }
        seedCache("road")
        compose.runOnIdle { player.playOffline() }
        waitFor { player.state.value.origin.source == PlaybackSource.OFFLINE && player.state.value.playing }
        compose.runOnIdle { player.seek(9); player.setRepeatMode(RepeatMode.ALL); player.saveForExit(); player.switchProfile("owner") }
        waitFor { player.state.value.profileId == "owner" && h.offline.state.value.owner == owner && h.offline.state.value.ready }
        returnOffline(id, shuffle = true)
    }

    @Test fun removingBookmarkedCachedTrackFallsBackToAnExistingTrack() {
        val id = prepareOfflineBookmark(repeat = RepeatMode.ALL)
        startWave()
        compose.runOnIdle { h.taste.react(TasteTarget(TasteKind.TRACK, "2:7", "Second"), TasteAction.UNLIKE) }
        waitFor { h.offline.state.value.tracks.none { it.id == id } && h.taste.state.value.shelf(TasteKind.TRACK).list.liked == setOf("1") }
        compose.runOnIdle { player.playOffline() }
        waitFor { player.state.value.origin.source == PlaybackSource.OFFLINE && player.state.value.current?.id == "yandex:1:7" && player.state.value.playing }
        assertTrue("A missing bookmark starts the surviving track at its beginning", player.state.value.positionSeconds in 0..2)
        assertModes(RepeatMode.ALL, false)
    }

    private fun seedCache(profile: String) {
        compose.runOnIdle { h.tasteLists[profile to TasteKind.TRACK] = TasteList(setOf("1", "2")); h.taste.refresh(TasteKind.TRACK) }
        waitFor { h.taste.state.value.profileId == profile && h.taste.state.value.shelf(TasteKind.TRACK).list.liked == setOf("1", "2") && !h.taste.state.value.shelf(TasteKind.TRACK).busy }
        compose.runOnIdle { h.offline.sync() }
        waitFor { !h.offline.state.value.running && h.offline.state.value.tracks.size == 2 }
    }

    private fun prepareOfflineBookmark(repeat: RepeatMode = RepeatMode.OFF, shuffle: Boolean = false): String {
        compose.runOnIdle { player.playOffline() }
        waitFor { player.state.value.origin.source == PlaybackSource.OFFLINE && player.state.value.playing && player.state.value.current?.id == "yandex:1:7" }
        compose.runOnIdle { player.skip(1) }
        waitFor { player.state.value.current?.id == "yandex:2:7" && player.state.value.playing }
        compose.runOnIdle {
            h.systemPlayer.pause(); player.seek(4); player.setRepeatMode(repeat)
            if (shuffle) player.setShuffle(true)
            player.saveForExit()
        }
        waitFor { !player.state.value.playing && player.state.value.positionSeconds == 4 }
        assertModes(repeat, shuffle)
        return "yandex:2:7"
    }

    private fun startWave() {
        compose.runOnIdle { player.playMyWave() }
        waitFor { player.state.value.wave && player.state.value.playing && player.state.value.current != null }
        assertModes(RepeatMode.OFF, false)
    }

    private fun roundTrip(repeat: RepeatMode = RepeatMode.OFF, shuffle: Boolean = false) {
        val id = prepareOfflineBookmark(repeat, shuffle)
        startWave()
        returnOffline(id, repeat, shuffle)
    }

    private fun returnOffline(id: String, repeat: RepeatMode = RepeatMode.OFF, shuffle: Boolean = false) {
        compose.runOnIdle { player.playOffline() }
        waitFor { player.state.value.origin.source == PlaybackSource.OFFLINE && player.state.value.playing }
        assertEquals("Explicit Offline returns to its own selected track", id, player.state.value.current?.id)
        assertTrue("Explicit Offline resumes its saved position, not zero: ${player.state.value.positionSeconds}", player.state.value.positionSeconds in 4..6)
        assertModes(repeat, shuffle)
    }

    private fun assertModes(repeat: RepeatMode, shuffle: Boolean) {
        assertEquals(repeat, player.state.value.repeatMode)
        assertEquals(shuffle, player.state.value.shuffle)
        assertFalse("Wave continuation must remain mutually exclusive with cached playback ordering", player.state.value.continueWave)
        if (shuffle) assertEquals(RepeatMode.OFF, player.state.value.repeatMode)
        if (repeat != RepeatMode.OFF) assertFalse(player.state.value.shuffle)
    }
}
