@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
package dev.petrov.ymplayer2

import android.net.Uri
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.petrov.ymplayer2.core.*
import kotlinx.coroutines.runBlocking
import java.io.File
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

    @Test fun returningFromOfflineRestoresTheWholeMyWaveWindowAndPositionWithoutNetwork() = waveRoundTrip()

    @Test fun myWaveBookmarkSurvivesRestartWhileOfflineIsSelected() = waveRoundTrip(restart = true)

    @Test fun returningFromALocalListRestoresMyWaveWithoutRequestingANewSession() = waveRoundTrip(local = true)

    @Test fun stoppingOfflineDoesNotEraseTheParkedMyWave() = waveRoundTrip(stopOther = true)

    @Test fun returningFromRadioPreservesMyWavePositionAndPreparedFiles() = waveRoundTrip(radio = true)

    @Test fun explicitWaveStopCannotResurrectAParkedWindowViaOffline() {
        startWave()
        waitFor { player.state.value.queue.size == 10 && !player.state.value.waveLoading }
        val sessions = h.waveSelections.size
        compose.runOnIdle { player.seek(4); player.stop(); player.playOffline() }
        waitFor { player.state.value.origin.source == PlaybackSource.OFFLINE && player.state.value.playing }
        assertTrue(player.bufferedWaveAudioIds().isEmpty())
        compose.runOnIdle { player.playMyWave() }
        waitFor { player.state.value.wave && player.state.value.playing }
        assertEquals("Explicit Stop starts a fresh server session", sessions + 1, h.waveSelections.size)
        assertTrue("Explicit Stop discards the saved position", player.state.value.positionSeconds in 0..2)
    }

    @Test fun changedSettingsDoNotReviveTheParkedOldWave() {
        startWave()
        waitFor { player.state.value.queue.size == 10 && !player.state.value.waveLoading }
        val sessions = h.waveSelections.size
        compose.runOnIdle { player.waveSettings!!.load("ru"); player.playOffline() }
        waitFor { player.waveSettings!!.state.value.options != null && player.state.value.origin.source == PlaybackSource.OFFLINE }
        compose.runOnIdle { player.waveSettings!!.select("moodEnergy", "settingMoodEnergy:calm"); player.playMyWave() }
        waitFor { player.state.value.wave && player.state.value.playing }
        assertEquals(sessions + 1, h.waveSelections.size)
        assertTrue("Apply the new settings instead of resuming the old window", "settingMoodEnergy:calm" in h.waveSelections.last().settings)
        assertTrue(player.state.value.positionSeconds in 0..2)
    }

    @Test fun anotherProfileStartsItsOwnWaveInsteadOfResumingTheOwnersBookmark() {
        startWave()
        waitFor { player.state.value.queue.size == 10 && !player.state.value.waveLoading }
        compose.runOnIdle { h.systemPlayer.pause(); player.seek(4); player.saveForExit(); player.playOffline() }
        waitFor { player.state.value.origin.source == PlaybackSource.OFFLINE && player.state.value.playing }
        val prefs = h.context.getSharedPreferences("playback", 0)
        val ownerBookmark = prefs.getString("myWaveState:owner:1", null)
        assertNotNull(ownerBookmark)
        val sessions = h.waveSelections.size
        compose.runOnIdle { player.switchProfile("road") }
        waitFor { player.state.value.profileId == "road" && h.online.catalog.value.profileId == "road" && h.online.catalog.value.enabled }
        compose.runOnIdle { player.playMyWave() }
        waitFor { player.state.value.wave && player.state.value.playing && player.state.value.current != null }
        assertEquals(sessions + 1, h.waveSelections.size)
        assertTrue("The other account must not inherit the saved position", player.state.value.positionSeconds in 0..2)
        assertEquals(ownerBookmark, prefs.getString("myWaveState:owner:1", null))
        assertEquals("2", org.json.JSONObject(prefs.getString("queue:road", "")!!).getString("waveAccount"))
    }

    private fun waveRoundTrip(restart: Boolean = false, local: Boolean = false, stopOther: Boolean = false, radio: Boolean = false) {
        fun fullWave() = player.state.value.wave && player.state.value.queue.size == WaveQueueWindow.MAX_ITEMS &&
            !player.state.value.waveLoading && player.bufferedWaveAudioIds() == player.state.value.queue.mapTo(hashSetOf(), Track::id)
        compose.runOnIdle { player.playMyWave() }
        waitFor { player.state.value.playing && fullWave() }
        compose.runOnIdle { player.skip(1) }
        waitFor { player.state.value.current?.id == "yandex:2:7" && fullWave() }
        compose.runOnIdle { player.skip(1) }
        waitFor { player.state.value.current?.id == "yandex:3:7" && fullWave() }
        compose.runOnIdle { h.systemPlayer.pause(); player.seek(4); player.saveForExit() }
        val before = player.state.value
        val ids = before.queue.map(Track::id)
        val audio = ids.associateWith { File(Uri.parse(player.resolveStream("owner", it)).path!!) }
        val requests = h.waveRequests.size
        val sessions = h.waveSelections.size
        val streams = h.resolved.size
        val cursor = org.json.JSONObject(h.checkpointText()).getString("waveCursor")
        compose.runOnIdle {
            h.internet.available.value = false; h.waveFailure = MusicFailure.NETWORK; h.streamFailure = MusicFailure.NETWORK
            if (radio) h.radio.play(RadioStation("one", "Fixture station"))
            else if (local) player.playList(h.library.testTracks.map(Track::id), origin = PlaybackOrigin(PlaybackSource.LOCAL_FAVORITES))
            else player.playOffline()
        }
        val source = if (local) PlaybackSource.LOCAL_FAVORITES else PlaybackSource.OFFLINE
        if (radio) waitFor { h.radio.state.value.playing }
        else waitFor { player.state.value.origin.source == source && player.state.value.playing }
        assertEquals("Changing sources must retain all prepared wave files", ids.toSet(), player.bufferedWaveAudioIds())
        if (stopOther) {
            compose.runOnIdle { player.stop() }
            assertEquals("Stop affects only the active source", ids.toSet(), player.bufferedWaveAudioIds())
        }
        if (restart) {
            compose.runOnIdle { h.systemPlayer.pause(); player.saveForExit(); h.restartEngine() }
            waitFor { player.state.value.connected && player.state.value.origin.source == source && player.state.value.current != null }
            assertEquals(ids.toSet(), dev.petrov.ymplayer2.playback.WaveAudioBuffer(h.context) { "1" }.ids())
        }
        compose.runOnIdle { player.playMyWave() }
        waitFor { player.state.value.wave && player.state.value.playing && player.state.value.current?.id == before.current?.id }
        assertEquals("Restore the exact queue, including Previous and the prepared future", ids, player.state.value.queue.map(Track::id))
        assertTrue("Resume at the bookmarked position: ${player.state.value.positionSeconds}", player.state.value.positionSeconds in 4..7)
        assertEquals(before.index, player.state.value.index)
        assertEquals(cursor, org.json.JSONObject(h.checkpointText()).getString("waveCursor"))
        assertEquals("Returning is not a new server session", sessions, h.waveSelections.size)
        assertEquals("The full saved window needs no server request", requests, h.waveRequests.size)
        assertEquals("Cached audio needs no signed URL refresh", streams, h.resolved.size)
        ids.forEach { assertEquals(audio.getValue(it).path, Uri.parse(player.resolveStream("owner", it)).path) }
        compose.runOnIdle { player.skip(1) }
        waitFor { player.state.value.current?.id == ids[before.index + 1] && player.state.value.playing }
        compose.runOnIdle { player.skip(-1) }
        waitFor { player.state.value.current?.id == before.current?.id && player.state.value.playing }
    }

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
