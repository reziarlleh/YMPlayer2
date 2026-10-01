package dev.petrov.ymplayer2

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.petrov.ymplayer2.clips.ClipMediaPreloader
import dev.petrov.ymplayer2.clips.ClipControlsView
import dev.petrov.ymplayer2.clips.ClipWaveController
import dev.petrov.ymplayer2.designsystem.skin.SkinPackageReader
import dev.petrov.ymplayer2.designsystem.skin.clipPalette
import dev.petrov.ymplayer2.yandex.YandexClipApi
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Actual H.264/AAC segments and production Media3. Does not claim a live Yandex session. */
@RunWith(AndroidJUnit4::class)
class ClipTransportPlaybackTest {
    @Test fun hlsPreloadsSamplesThenPlaysAcrossSegmentBoundary() = playSegmented("hls/index.m3u8", MimeTypes.APPLICATION_M3U8)
    @Test fun dashPreloadsSamplesThenPlaysAcrossSegmentBoundary() = playSegmented("dash/index.mpd", MimeTypes.APPLICATION_MPD)

    private fun playSegmented(path: String, mime: String) {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val context = instrument.targetContext
        val directory = File(context.cacheDir, "clip-transport-test")
        fun copy(relative: String) {
            val asset = "clip-transport/$relative"
            val names = instrument.context.assets.list(asset).orEmpty()
            if (names.isNotEmpty()) names.forEach { copy("$relative/$it") }
            else {
                val target = File(directory, relative)
                target.parentFile!!.mkdirs()
                instrument.context.assets.open(asset).use { input -> target.outputStream().use(input::copyTo) }
            }
        }
        copy(path.substringBefore('/'))
        val item = MediaItem.Builder().setMediaId(path).setUri(Uri.fromFile(File(directory, path))).setMimeType(mime).build()
        val loaded = CountDownLatch(1)
        var preloader: ClipMediaPreloader? = null
        var controller: ClipWaveController? = null
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        try {
            instrument.runOnMainSync {
                preloader = ClipMediaPreloader(context) { if (it.mediaId == path) loaded.countDown() }
                preloader!!.player.volume = 0f
                preloader!!.warmNext(item)
            }
            assertTrue("Segmented video samples must arrive before handoff", loaded.await(20, TimeUnit.SECONDS))
            instrument.runOnMainSync {
                assertTrue("Handoff must reuse the preloaded MediaSource", preloader!!.play(item))
                controller = ClipWaveController(YandexClipApi((context.applicationContext as PlayerApplication).accounts), "owner", preloader!!.player, scope)
            }
            val deadline = System.currentTimeMillis() + 20_000
            var passedBoundary = false
            var error: String? = null
            var recolored = false
            val palette = context.assets.open("skins/harbor.ymskin").use(SkinPackageReader::read).skin.clipPalette()
            while (!passedBoundary && error == null && System.currentTimeMillis() < deadline) {
                instrument.runOnMainSync {
                    val player = preloader!!.player
                    error = player.playerError?.toString()
                    if (!recolored && player.currentPosition >= 1500) {
                        val before = player.currentPosition
                        val controls = ClipControlsView(context, controller!!) { }
                        controls.updatePalette(palette)
                        assertTrue("Recolor must preserve progression without seek/reset", player.currentPosition in before..(before + 500))
                        assertTrue(player.playWhenReady)
                        assertEquals(path, player.currentMediaItem?.mediaId)
                        recolored = true
                    }
                    passedBoundary = player.playbackState == Player.STATE_READY && player.currentPosition >= 5_000
                }
                Thread.sleep(50)
            }
            assertNull(error)
            assertTrue("Playback must pass two 2-second segment boundaries", passedBoundary)
            assertTrue("Native overlay must recolor during real segmented playback", recolored)
            instrument.runOnMainSync {
                assertEquals(path, preloader!!.player.currentMediaItem?.mediaId)
                assertEquals(160, preloader!!.player.videoFormat?.width)
                assertEquals(90, preloader!!.player.videoFormat?.height)
            }
        } finally {
            preloader?.let { ready -> instrument.runOnMainSync { controller?.close(); if (controller == null) ready.player.release(); ready.release() } }
            scope.cancel()
        }
    }
}
