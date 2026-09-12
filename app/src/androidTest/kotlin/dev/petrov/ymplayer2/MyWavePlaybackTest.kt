@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
package dev.petrov.ymplayer2

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.petrov.ymplayer2.core.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MyWavePlaybackTest {
    @get:Rule val compose = createAndroidComposeRule<OnlineTestActivity>()
    private val fixture get() = compose.activity.harness
    private val player get() = fixture.player
    private fun waitFor(condition: () -> Boolean) = compose.waitUntil(25000, condition)
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
        compose.onNodeWithTag("my_wave").performScrollTo().performClick()
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
    @Test fun failureCanRetryAndUiTargetsTrackArtistAndAlbumIndividually() {
        compose.runOnIdle { fixture.waveFailure = MusicFailure.NETWORK; player.playMyWave() }
        waitFor { player.state.value.waveIssue != null }
        compose.runOnIdle { fixture.waveFailure = null }
        compose.onNodeWithTag("wave_retry").performScrollTo().performClick()
        waitFor { player.state.value.positionSeconds >= 1 }
        compose.onNodeWithTag("player_taste_TRACK_1_like").performScrollTo().performClick()
        waitFor { "1" in fixture.taste.state.value.shelf(TasteKind.TRACK).list.liked }
        compose.onNodeWithTag("player_artist_actions").performScrollTo().performClick()
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
