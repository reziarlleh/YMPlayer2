@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
package dev.petrov.ymplayer2

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.petrov.ymplayer2.core.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EntityWavePlaybackTest {
    @get:Rule val compose = createAndroidComposeRule<OnlineTestActivity>()
    private val h get() = compose.activity.harness
    private val player get() = h.player
    private fun waitFor(test: () -> Boolean) = compose.waitUntil(30000, test)
    @Before fun setup() {
        waitFor { h.library.state.value.ready && player.state.value.connected && h.collections.state.value.ready }
        compose.runOnIdle { player.stop(); player.switchProfile("owner"); player.clearQueue(); player.setRepeatMode(RepeatMode.OFF); player.setShuffle(false) }
        runBlocking { h.library.state.value.roots.forEach { h.library.forgetFolder(it.uri) } }
        compose.activity.contentResolver.call(android.net.Uri.parse("content://dev.petrov.ymplayer2.test.control"), "fixtures", null, null)
        runBlocking { h.library.addFolder(TestMusicProvider.tree.toString(), Source.LOCAL) }
        waitFor { h.library.testTracks.size == 2 && h.taste.state.value.signedIn }
    }
    @After fun stop() { compose.runOnIdle { player.stop() } }
    private fun screenshot(name: String) {
        compose.waitForIdle()
        val bitmap = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        java.io.File(compose.activity.getExternalFilesDir(null), "wave24-$name.png").outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }; bitmap.recycle()
    }
    @Test fun allFourSourcesKeepSeedThroughFeedbackNextAndPausedRestoration() {
        for (entity in listOf(MusicEntity("1", "Song", MusicKind.TRACKS), MusicEntity("5", "Artist", MusicKind.ARTISTS),
            MusicEntity("7", "Album", MusicKind.ALBUMS), MusicEntity("3", "List", MusicKind.PLAYLISTS, "1"))) {
            val request = entity.waveRequest()!!
            compose.runOnIdle { player.playWave(request) }
            waitFor { player.state.value.current?.id == "yandex:1:7" && player.state.value.positionSeconds >= 1 && player.state.value.queueCount >= 2 }
            assertEquals(request, h.waveSelections.last())
            compose.runOnIdle { player.skip(1) }
            waitFor { player.state.value.current?.id == "yandex:2:7" && player.state.value.positionSeconds >= 1 }
            assertTrue(h.waveFeedback.any { it.second.station == request.station && it.third == WaveFeedback.STARTED })
            compose.runOnIdle { player.toggle(); h.restartEngine() }
            waitFor { player.state.value.connected && player.state.value.current?.id == "yandex:2:7" }
            assertFalse(player.state.value.playing); assertEquals(request, player.state.value.origin.wave)
            compose.onNodeWithTag("continue_wave_mode").assertDoesNotExist()
            compose.onNodeWithTag("my_wave_settings").assertDoesNotExist()
        }
    }
    @Test fun lastTrackContinuesWithTheListSeedAndFiniteOriginSurvivesRestart() {
        compose.runOnIdle { h.online.open(MusicEntity("7", "Проверочный альбом", MusicKind.ALBUMS)) }
        waitFor { h.online.state.value.loaded }; compose.runOnIdle { h.online.more() }
        waitFor { h.online.state.value.entries.size == 2 }
        val tracks = h.online.state.value.entries.mapNotNull(MusicEntry::track)
        val origin = PlaybackOrigin(PlaybackSource.LIST, "Проверочный альбом", WaveRequest("album:7", "Проверочный альбом"))
        compose.runOnIdle { player.playList(tracks.map(Track::id), tracks.last().id, origin) }
        waitFor { player.state.value.current?.id == tracks.last().id && player.state.value.playing }
        compose.runOnIdle { player.setContinueWave(true); player.seek(7); player.toggle() }
        waitFor { player.state.value.continueWave && player.state.value.positionSeconds == 7 && !player.state.value.playing }
        compose.runOnIdle { h.restartEngine() }
        waitFor { player.state.value.connected && player.state.value.current?.id == tracks.last().id }
        assertEquals(origin, player.state.value.origin); assertTrue(player.state.value.continueWave); assertFalse(player.state.value.playing)
        compose.onNodeWithTag("my_wave").assertTextContains(origin.title)
        compose.onNodeWithTag("my_wave_settings").assertDoesNotExist()
        compose.runOnIdle { player.toggle(); player.seek(tracks.last().durationSeconds - 1) }
        waitFor { player.state.value.wave && player.state.value.positionSeconds >= 1 && player.state.value.queueCount >= 2 }
        assertEquals("album:7", h.waveSelections.last().station); assertFalse(player.state.value.continueWave)
    }
    @Test fun remoteOrderingAndContinuationExcludeEachOtherWithoutSeeking() {
        compose.runOnIdle { player.playList(h.library.testTracks.map(Track::id), origin = PlaybackOrigin(PlaybackSource.LIST, "Fixture", WaveRequest("playlist:1_3", "Fixture"))); player.seek(7); player.toggle() }
        waitFor { !player.state.value.playing && player.state.value.positionSeconds == 7 }
        compose.onNodeWithTag("continue_wave_mode").performClick()
        assertTrue(player.state.value.continueWave)
        compose.runOnIdle { h.systemPlayer.repeatMode = androidx.media3.common.Player.REPEAT_MODE_ONE }
        assertEquals(RepeatMode.ONE, player.state.value.repeatMode); assertFalse(player.state.value.continueWave)
        compose.runOnIdle { h.systemPlayer.shuffleModeEnabled = true }
        assertTrue(player.state.value.shuffle); assertEquals(RepeatMode.OFF, player.state.value.repeatMode)
        compose.onNodeWithTag("continue_wave_mode").performClick()
        assertTrue(player.state.value.continueWave); assertFalse(player.state.value.shuffle)
        assertEquals(7, player.state.value.positionSeconds); assertFalse(player.state.value.playing)
        screenshot("modes")
    }
    @Test fun shufflePassesTheListBoundaryAutomaticallyWithoutRepeatIncludingOneTrack() {
        for (ids in listOf(h.library.testTracks.map(Track::id), listOf(h.library.testTracks.first().id))) {
            compose.runOnIdle { player.playQueue(ids); player.setShuffle(true) }
            waitFor { player.state.value.playing }
            repeat(ids.size * 3) { compose.runOnIdle { player.skip(1) } }
            assertTrue(player.state.value.playing); assertEquals(RepeatMode.OFF, player.state.value.repeatMode)
            compose.runOnIdle { player.seek(player.state.value.current!!.durationSeconds - 1) }
            waitFor { player.state.value.positionSeconds in 1..5 && player.state.value.playing }
            assertTrue(player.state.value.shuffle)
        }
    }
    @Test fun sourcePickerSupportsFavoritesOfflineAndWaveAndNativeFocus() {
        compose.onNodeWithTag("my_wave_settings").assertDoesNotExist()
        val id = h.library.testTracks.first().id
        assertTrue(runBlocking { h.collections.edit("owner", CollectionEdit.Favorite(id, true)) })
        compose.onNodeWithTag("my_wave").performClick()
        val choice = compose.onNodeWithTag("player_source_favorites")
        choice.performSemanticsAction(SemanticsActions.RequestFocus) { it() }; choice.assertIsFocused()
        choice.performKeyInput { pressKey(Key.Enter) }
        waitFor { player.state.value.origin.source == PlaybackSource.LOCAL_FAVORITES && player.state.value.playing }
        compose.onNodeWithTag("my_wave").assertTextContains("Локальное избранное")
        compose.onNodeWithTag("my_wave_settings").assertDoesNotExist()
        compose.runOnIdle { h.offline.setEnabled(true) }
        waitFor { h.offline.state.value.ready && h.offline.state.value.owner != null }
        compose.runOnIdle { h.tasteLists["owner" to TasteKind.TRACK] = TasteList(setOf("1", "2")); h.taste.refresh(TasteKind.TRACK) }
        waitFor { h.taste.state.value.shelf(TasteKind.TRACK).list.liked == setOf("1", "2") }
        compose.runOnIdle { h.offline.sync() }
        waitFor { h.offline.state.value.tracks.size == 2 && !h.offline.state.value.running }
        compose.onNodeWithTag("my_wave").performClick(); screenshot("source-picker")
        compose.onNodeWithTag("player_source_offline").performClick()
        waitFor { player.state.value.origin.source == PlaybackSource.OFFLINE && player.state.value.playing }
        compose.onNodeWithTag("my_wave").assertTextContains("Оффлайн")
        compose.onNodeWithTag("my_wave_settings").assertDoesNotExist()
        compose.onNodeWithTag("my_wave").performClick(); compose.onNodeWithTag("player_source_wave").performClick()
        waitFor { player.state.value.wave && player.state.value.positionSeconds >= 1 }
        compose.runOnIdle { player.toggle() }
        waitFor { !player.state.value.playing }
        val settings = compose.onNodeWithTag("my_wave_settings")
        settings.performSemanticsAction(SemanticsActions.RequestFocus) { it() }; settings.assertIsFocused()
        settings.performKeyInput { pressKey(Key.DirectionCenter) }
        compose.onNodeWithTag("wave_settings_dialog").assertIsDisplayed()
        compose.runOnIdle { player.playQueue(h.library.testTracks.map(Track::id)) }
        waitFor { !player.state.value.wave && player.state.value.current?.source == Source.LOCAL }
        compose.onNodeWithTag("my_wave_settings").assertDoesNotExist()
        compose.onNodeWithTag("wave_settings_dialog").assertDoesNotExist()
    }
    @Test fun trackArtistAlbumAndPlaylistHaveTheirOwnMenuCommands() {
        compose.runOnIdle { h.online.open(MusicEntity("7", "Album", MusicKind.ALBUMS)) }
        waitFor { h.online.state.value.loaded }
        compose.runOnIdle { player.playQueue(h.online.state.value.entries.mapNotNull { it.track?.id }) }
        waitFor { player.state.value.current?.source == Source.YANDEX }
        compose.onNodeWithTag("player_more").performClick()
        for (seed in listOf("track:1", "artist:5", "album:7")) compose.onNodeWithTag("wave_by_$seed").assertExists()
        screenshot("track-menu")
        compose.onNodeWithTag("wave_by_track:1").performScrollTo().performClick()
        waitFor { player.state.value.wave && h.waveSelections.lastOrNull()?.station == "track:1" }
        compose.onNodeWithTag("nav_library").performClick()
        compose.runOnIdle { h.online.open(MusicEntity("3", "Playlist fixture", MusicKind.PLAYLISTS, "1")) }
        waitFor { h.online.state.value.loaded && h.online.state.value.request.entity?.kind == MusicKind.PLAYLISTS }
        compose.onNodeWithTag("detail_wave_menu_PLAYLISTS_3").performScrollTo().performClick()
        compose.onNodeWithTag("wave_by_playlist:1_3").performClick()
        waitFor { player.state.value.wave && h.waveSelections.lastOrNull()?.station == "playlist:1_3" }
    }
}
