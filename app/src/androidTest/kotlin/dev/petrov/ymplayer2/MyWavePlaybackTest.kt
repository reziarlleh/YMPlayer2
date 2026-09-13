@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
package dev.petrov.ymplayer2

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.petrov.ymplayer2.core.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MyWavePlaybackTest {
    @get:Rule val compose = createAndroidComposeRule<OnlineTestActivity>()
    private val fixture get() = compose.activity.harness
    private val player get() = fixture.player
    private fun waitFor(condition: () -> Boolean) {
        try { compose.waitUntil(25000, condition) }
        catch (e: Throwable) { throw AssertionError("Wave state=${player.state.value}; requests=${fixture.waveRequests}; resolved=${fixture.resolved}; buffered=${player.bufferedWaveAudioIds()}", e) }
    }
    @Before fun prepare() {
        waitFor { fixture.library.state.value.ready && player.state.value.connected }
        compose.runOnIdle { player.stop(); player.switchProfile("owner"); player.clearQueue() }
        runBlocking { fixture.library.state.value.roots.forEach { fixture.library.forgetFolder(it.uri) } }
        compose.activity.contentResolver.call(android.net.Uri.parse("content://dev.petrov.ymplayer2.test.control"), "fixtures", null, null)
        runBlocking { fixture.library.addFolder(TestMusicProvider.tree.toString(), Source.LOCAL) }
        waitFor { fixture.library.state.value.tracks.size == 2 && fixture.taste.state.value.shelf(TasteKind.ARTIST).ready }
    }
    @After fun stop() { compose.runOnIdle { player.stop() } }
    private fun start() {
        compose.onNodeWithTag("my_wave").performClick()
        waitFor { player.state.value.positionSeconds >= 1 && player.state.value.queue.size >= 2 }
    }
    @Test fun fastStartPrefetchAutomaticContinuationAndPausedRestore() {
        start()
        assertTrue(player.state.value.wave)
        compose.runOnIdle { player.seek(player.state.value.current!!.durationSeconds - 1) }
        waitFor { player.state.value.current?.id == "yandex:2:7" && player.state.value.positionSeconds >= 1 }
        waitFor { fixture.waveFeedback.any { it.third == WaveFeedback.FINISHED && it.second.track.id == "yandex:1:7" } }
        assertTrue(fixture.waveFeedback.any { it.third == WaveFeedback.STARTED && it.second.track.id == "yandex:2:7" })
        compose.runOnIdle { player.toggle(); fixture.restartEngine() }
        waitFor { player.state.value.connected && player.state.value.current?.id == "yandex:2:7" }
        assertFalse(player.state.value.playing); assertTrue(player.state.value.wave)
        assertEquals("6", player.state.value.current!!.artists.single().id)
        assertFalse(fixture.checkpointText().contains("fixture-"))
        compose.runOnIdle { player.toggle() }
        waitFor { player.state.value.positionSeconds >= 2 }
    }
    @Test fun restoredUpcomingTrackCanBeSelectedBeforeItsBufferExists() {
        start()
        compose.runOnIdle { player.toggle(); fixture.restartEngine() }
        waitFor { player.state.value.connected && player.state.value.current?.id == "yandex:1:7" }
        assertFalse(player.state.value.playing)
        assertTrue(player.bufferedWaveAudioIds().isEmpty())
        compose.runOnIdle { player.select("yandex:2:7") }
        waitFor { player.state.value.current?.id == "yandex:2:7" && player.state.value.positionSeconds >= 1 }
        waitFor { "yandex:3:7" in player.bufferedWaveAudioIds() }
        assertTrue(player.state.value.wave)
    }
    @Test fun transientThirdTrackRequestDoesNotEndWaveAfterSecondTrack() {
        start()
        compose.runOnIdle { fixture.waveFailuresRemaining = 3; player.seek(player.state.value.current!!.durationSeconds - 1) }
        waitFor { player.state.value.current?.id == "yandex:2:7" && player.state.value.waveIssue != null }
        compose.runOnIdle { player.seek(player.state.value.current!!.durationSeconds - 1) }
        waitFor { player.state.value.current?.id == "yandex:3:7" && player.state.value.positionSeconds >= 1 }
        for (id in 4..7) {
            waitFor { player.state.value.queue.size - player.state.value.index == 2 }
            compose.runOnIdle { player.seek(player.state.value.current!!.durationSeconds - 1) }
            waitFor { player.state.value.current?.id == "yandex:$id:7" && player.state.value.positionSeconds >= 1 }
        }
        assertTrue(player.state.value.wave); assertTrue(player.state.value.playing)
        assertEquals(0, fixture.waveFailuresRemaining)
        assertTrue(fixture.waveFeedback.any { it.third == WaveFeedback.FINISHED && it.second.track.id == "yandex:6:7" })
    }
    @Test fun nextAudioIsCompleteEarlyAndPlaysWithoutAnotherNetworkResolution() {
        start()
        assertTrue(player.state.value.positionSeconds < 10)
        assertEquals(setOf("yandex:2:7"), player.bufferedWaveAudioIds())
        assertEquals(1, fixture.resolved.count { it.second == "yandex:2:7" })
        compose.runOnIdle { fixture.streamFailure = MusicFailure.NETWORK; player.seek(player.state.value.current!!.durationSeconds - 1) }
        waitFor { player.state.value.current?.id == "yandex:2:7" && player.state.value.positionSeconds >= 2 }
        assertTrue(player.state.value.playing)
        assertEquals(1, fixture.resolved.count { it.second == "yandex:2:7" })
        compose.runOnIdle { fixture.streamFailure = null }
        waitFor { "yandex:3:7" in player.bufferedWaveAudioIds() }
        assertEquals(setOf("yandex:2:7", "yandex:3:7"), player.bufferedWaveAudioIds())
    }
    @Test fun badFirstAudioAndBadFutureAudioAreSkippedWithoutStoppingGoodTrack() {
        compose.runOnIdle { fixture.brokenStreamTrackIds = setOf("yandex:1:7", "yandex:3:7"); player.playMyWave() }
        waitFor { player.state.value.current?.id == "yandex:2:7" && player.state.value.positionSeconds >= 1 }
        waitFor { player.state.value.queue.lastOrNull()?.id == "yandex:4:7" }
        assertTrue(player.state.value.playing)
        assertFalse(player.state.value.queue.any { it.id == "yandex:3:7" })
        compose.runOnIdle { player.seek(player.state.value.current!!.durationSeconds - 1) }
        waitFor { player.state.value.current?.id == "yandex:4:7" && player.state.value.positionSeconds >= 1 }
        assertTrue(fixture.tasteWrites.isEmpty())
    }
    @Test fun endWaitsForExistingAudioPrefetchAndPauseStillWins() {
        val gate = CompletableDeferred<Unit>()
        compose.runOnIdle { fixture.streamGates = mapOf("yandex:2:7" to gate); player.playMyWave() }
        waitFor { player.state.value.positionSeconds >= 1 && fixture.resolved.any { it.second == "yandex:2:7" } }
        compose.runOnIdle { player.seek(player.state.value.current!!.durationSeconds - 1) }
        waitFor { player.state.value.positionSeconds >= 30 }
        compose.runOnIdle { fixture.systemPlayer.pause(); gate.complete(Unit) }
        waitFor { player.state.value.current?.id == "yandex:2:7" && !player.state.value.waveLoading }
        assertFalse(player.state.value.playing)
        assertEquals(1, fixture.resolved.count { it.second == "yandex:2:7" })
        compose.runOnIdle { fixture.systemPlayer.play() }
        waitFor { player.state.value.positionSeconds >= 1 }
        val next = CompletableDeferred<Unit>()
        compose.runOnIdle { fixture.streamGates = mapOf("yandex:4:7" to next) }
        waitFor { player.state.value.queue.lastOrNull()?.id == "yandex:3:7" }
        compose.runOnIdle { player.skip(1) }
        waitFor { fixture.resolved.any { it.second == "yandex:4:7" } }
        compose.runOnIdle { player.stop(); next.complete(Unit) }
        compose.waitForIdle()
        assertFalse(player.state.value.wave); assertFalse(player.state.value.playing)
        assertTrue(player.bufferedWaveAudioIds().isEmpty())
    }
    @Test fun systemPauseDuringNextRequestCannotRestartAudioOnLateReply() {
        compose.runOnIdle { fixture.waveDelayMillis = 1800; player.playMyWave() }
        waitFor { player.state.value.positionSeconds >= 1 && player.state.value.waveLoading }
        compose.runOnIdle { fixture.systemPlayer.seekToNext(); fixture.systemPlayer.pause() }
        waitFor { !player.state.value.waveLoading && player.state.value.current?.id == "yandex:2:7" }
        assertFalse(player.state.value.playing)
        compose.runOnIdle { fixture.systemPlayer.play() }
        waitFor { player.state.value.positionSeconds >= 1 && player.state.value.playing }
        assertTrue(fixture.waveFeedback.any { it.third == WaveFeedback.SKIP && it.second.track.id == "yandex:1:7" })
    }
    @Test fun artistBanDropsPrefetchAndCurrentTrackBanSkipsWithoutBanningArtist() {
        start()
        compose.runOnIdle { fixture.taste.react(TasteTarget(TasteKind.ARTIST, "6", "Второй исполнитель"), TasteAction.BLOCK) }
        waitFor { player.state.value.queue.none { it.artists.any { artist -> artist.id == "6" } } }
        compose.runOnIdle { fixture.taste.react(player.state.value.current!!.tasteTarget(), TasteAction.BLOCK) }
        waitFor { player.state.value.current?.id == "yandex:3:7" && player.state.value.positionSeconds >= 1 }
        assertEquals(setOf("1"), fixture.taste.state.value.shelf(TasteKind.TRACK).list.blocked)
        assertEquals(setOf("6"), fixture.taste.state.value.shelf(TasteKind.ARTIST).list.blocked)
        assertTrue(fixture.waveFeedback.any { it.third == WaveFeedback.DISLIKE && it.second.track.id == "yandex:1:7" })
    }
    @Test fun lateWaveCannotReplaceManualQueueOrNewProfile() {
        compose.runOnIdle { fixture.waveDelayMillis = 1200; player.playMyWave() }
        waitFor { player.state.value.waveLoading }
        val local = fixture.library.state.value.tracks.first().id
        compose.runOnIdle { player.playQueue(listOf(local)) }
        waitFor { player.state.value.positionSeconds >= 2 }
        assertFalse(player.state.value.wave); assertEquals(local, player.state.value.current?.id)
        compose.runOnIdle { player.playMyWave(); player.switchProfile("road") }
        waitFor { player.state.value.profileId == "road" && fixture.online.state.value.signedIn }
        Thread.sleep(1500)
        assertFalse(player.state.value.wave); assertFalse(player.state.value.playing)
        assertTrue(player.state.value.queue.none { it.source == Source.YANDEX })
    }
    @Test fun banningArtistAlsoRemovesUpcomingTracksFromRecommendedPlaylist() {
        compose.runOnIdle { fixture.online.collection() }
        waitFor { fixture.online.state.value.loaded }
        compose.runOnIdle { fixture.online.more() }
        waitFor { fixture.online.state.value.entries.size == 2 }
        compose.runOnIdle { player.playRecommendedQueue(fixture.online.state.value.entries.map { it.id }) }
        waitFor { player.state.value.positionSeconds >= 1 }
        compose.runOnIdle { fixture.taste.react(TasteTarget(TasteKind.ARTIST, "6", "Artist"), TasteAction.BLOCK) }
        waitFor { player.state.value.queue.size == 1 }
        assertEquals("yandex:1:7", player.state.value.current!!.id)
        assertTrue(player.state.value.playing); assertTrue(player.state.value.recommendations)
        assertFalse(player.state.value.wave)
    }
    @Test fun accessFailureDoesNotRetryAutomaticallyAtStartOrAtEnd() {
        compose.runOnIdle { fixture.waveFailure = MusicFailure.ACCESS; player.playMyWave() }
        waitFor { player.state.value.waveIssue != null }
        val startRequests = fixture.waveRequests.size
        Thread.sleep(1800)
        assertEquals(startRequests, fixture.waveRequests.size)
        compose.runOnIdle { fixture.waveFailure = null; player.retryWave() }
        waitFor { player.state.value.positionSeconds >= 1 && player.state.value.queue.size == 2 }
        compose.runOnIdle { fixture.waveFailure = MusicFailure.ACCESS; player.seek(29) }
        waitFor { player.state.value.current?.id == "yandex:2:7" && player.state.value.waveIssue != null }
        val endRequests = fixture.waveRequests.size
        compose.runOnIdle { player.seek(29) }
        waitFor { player.state.value.positionSeconds >= 30 }
        Thread.sleep(1800)
        assertEquals(endRequests, fixture.waveRequests.size)
        assertFalse(player.state.value.playing)
    }
    @Test fun failureCanRetryAndUiTargetsTrackArtistAndAlbumIndividually() {
        compose.runOnIdle { fixture.waveFailure = MusicFailure.NETWORK; player.playMyWave() }
        waitFor { player.state.value.waveIssue != null }
        compose.runOnIdle { fixture.waveFailure = null }
        compose.onNodeWithTag("wave_retry").performClick()
        waitFor { player.state.value.positionSeconds >= 1 }
        compose.onNodeWithTag("player_taste_TRACK_1_like").performClick()
        waitFor { "1" in fixture.taste.state.value.shelf(TasteKind.TRACK).list.liked }
        compose.onNodeWithTag("player_more").performClick()
        compose.onNodeWithTag("taste_ARTIST_5_like").performScrollTo().performClick()
        compose.onNodeWithTag("taste_ALBUM_7_like").performScrollTo().performClick()
        waitFor { "7" in fixture.taste.state.value.shelf(TasteKind.ALBUM).list.liked }
        assertEquals(setOf("1"), fixture.taste.state.value.shelf(TasteKind.TRACK).list.liked)
        assertEquals(setOf("5"), fixture.taste.state.value.shelf(TasteKind.ARTIST).list.liked)
        assertTrue(fixture.tasteWrites.all { it.second == TasteAction.LIKE })
        val label = androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("m6Screenshot")
        if (label != null && label.matches(Regex("[a-z0-9-]+"))) {
            compose.waitForIdle()
            val bitmap = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            java.io.File(compose.activity.getExternalFilesDir(null), "m6-$label.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
