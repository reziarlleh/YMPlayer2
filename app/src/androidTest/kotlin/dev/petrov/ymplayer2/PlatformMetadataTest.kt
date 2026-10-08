@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
package dev.petrov.ymplayer2

import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.PlaybackState
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.media3.session.MediaSession
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.petrov.ymplayer2.core.Source
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.json.JSONObject
import java.io.File

/** Consumes framework metadata/transport, not the Media3 metadata being supplied. */
@RunWith(AndroidJUnit4::class)
class PlatformMetadataTest {
    @get:Rule val compose = createAndroidComposeRule<OnlineTestActivity>()
    private val f get() = compose.activity.harness
    private lateinit var session: MediaSession
    private lateinit var controller: MediaController
    private fun waitFor(condition: () -> Boolean) = compose.waitUntil(25000, condition)
    @Before fun prepare() {
        waitFor { f.library.state.value.ready && f.player.state.value.connected }
        compose.runOnIdle { f.radio.release(); f.player.stop(); f.player.switchProfile("owner"); f.player.clearQueue() }
        runBlocking { f.library.state.value.roots.forEach { f.library.forgetFolder(it.uri) } }
        val control = android.net.Uri.parse("content://dev.petrov.ymplayer2.test.control")
        compose.activity.contentResolver.call(control, "fixtures", null, null)
        compose.activity.contentResolver.call(control, "artwork", null, null)
        runBlocking { f.library.addFolder(TestMusicProvider.tree.toString(), Source.LOCAL) }
        waitFor { f.library.testTracks.size == 3 }
        compose.runOnIdle {
            session = MediaSession.Builder(compose.activity, f.systemPlayer).setId("metadata-test").build()
            controller = MediaController(compose.activity, session.platformToken)
        }
    }
    @After fun stop() {
        compose.runOnIdle { if (::session.isInitialized) session.release(); f.radio.release(); f.player.stop() }
    }
    @Test fun musicPublishesDisplayFieldsAndArtThenClearsOldArtworkOnNext() {
        val cover = f.library.testTracks.single { it.artworkUri != null }
        val bare = f.library.testTracks.first { it.artworkUri == null }
        compose.runOnIdle { f.player.playQueue(listOf(cover.id, bare.id), cover.id) }
        waitFor { controller.metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART) != null }
        val metadata = controller.metadata!!
        assertEquals(cover.title, metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE))
        assertEquals(cover.artist, metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE))
        assertEquals(cover.album, metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION))
        assertTrue(metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)!!.sameAs(metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)))
        assertNotNull(metadata.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON))
        capture("music-art")
        controller.transportControls.pause()
        waitFor { controller.playbackState?.state == PlaybackState.STATE_PAUSED }
        assertNotNull(controller.metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART))
        controller.transportControls.play()
        waitFor { controller.playbackState?.state == PlaybackState.STATE_PLAYING }
        controller.transportControls.skipToNext()
        waitFor { controller.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) == bare.title }
        assertEquals(bare.title, controller.metadata!!.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE))
        assertEquals("", controller.metadata!!.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE))
        assertNull(controller.metadata!!.getBitmap(MediaMetadata.METADATA_KEY_ART))
        assertNull(controller.metadata!!.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART))
        assertNull(controller.metadata!!.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON))
        capture("music-next-no-art")
    }
    @Test fun radioKeepsStationAndOnAirPresentationWithLogoAndExternalStopPlay() {
        val logo = f.library.testTracks.single { it.artworkUri != null }.artworkUri
        val station = f.radioApi.one.copy(logoUri = logo)
        compose.runOnIdle { f.radio.play(station) }
        waitFor { f.radio.state.value.playing && controller.metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART) != null &&
            controller.metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)?.contains("Тестовый исполнитель") == true }
        val metadata = controller.metadata!!
        assertEquals(station.name, metadata.getString(MediaMetadata.METADATA_KEY_TITLE))
        assertEquals(station.name, metadata.getString(MediaMetadata.METADATA_KEY_ALBUM))
        assertEquals(station.name, metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE))
        assertEquals("Тестовая композиция · Тестовый исполнитель", metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE))
        assertEquals(station.regionName, metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION))
        assertEquals(station.name, metadata.description.title.toString())
        assertEquals(metadata.getString(MediaMetadata.METADATA_KEY_ARTIST), metadata.description.subtitle.toString())
        capture("radio-playing")
        controller.transportControls.pause()
        waitFor { !f.radio.state.value.playing && !f.radio.state.value.buffering && controller.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) == null }
        capture("radio-stopped")
        controller.transportControls.play()
        waitFor { f.radio.state.value.playing && controller.metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART) != null }
        assertEquals(station.name, controller.metadata!!.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE))
        capture("radio-resumed")
    }
    private fun capture(name: String) {
        val metadata = controller.metadata
        val data = JSONObject().put("state", controller.playbackState?.state)
        for (key in listOf(MediaMetadata.METADATA_KEY_TITLE, MediaMetadata.METADATA_KEY_ARTIST, MediaMetadata.METADATA_KEY_ALBUM,
            MediaMetadata.METADATA_KEY_DISPLAY_TITLE, MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION)) {
            data.put(key, metadata?.getString(key) ?: JSONObject.NULL)
        }
        for (key in listOf(MediaMetadata.METADATA_KEY_ART, MediaMetadata.METADATA_KEY_ALBUM_ART, MediaMetadata.METADATA_KEY_DISPLAY_ICON)) {
            val bitmap = metadata?.getBitmap(key)
            data.put(key, bitmap?.let { "${it.width}x${it.height}" } ?: JSONObject.NULL)
        }
        val directory = File(compose.activity.getExternalFilesDir(null), "metadata-beta").apply { mkdirs() }
        File(directory, "$name.json").writeText(data.toString(2))
    }
}
