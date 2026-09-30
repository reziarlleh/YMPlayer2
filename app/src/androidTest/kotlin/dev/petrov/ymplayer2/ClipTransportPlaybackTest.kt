package dev.petrov.ymplayer2

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.petrov.ymplayer2.clips.ClipMediaPreloader
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
        try {
            instrument.runOnMainSync {
                preloader = ClipMediaPreloader(context) { if (it.mediaId == path) loaded.countDown() }
                preloader!!.player.volume = 0f
                preloader!!.warmNext(item)
            }
            assertTrue("Segmented video samples must arrive before handoff", loaded.await(20, TimeUnit.SECONDS))
            instrument.runOnMainSync {
                assertTrue("Handoff must reuse the preloaded MediaSource", preloader!!.play(item))
            }
            val deadline = System.currentTimeMillis() + 20_000
            var passedBoundary = false
            var error: String? = null
            while (!passedBoundary && error == null && System.currentTimeMillis() < deadline) {
                instrument.runOnMainSync {
                    val player = preloader!!.player
                    error = player.playerError?.toString()
                    passedBoundary = player.playbackState == Player.STATE_READY && player.currentPosition >= 5_000
                }
                Thread.sleep(50)
            }
            assertNull(error)
            assertTrue("Playback must pass two 2-second segment boundaries", passedBoundary)
            instrument.runOnMainSync {
                assertEquals(path, preloader!!.player.currentMediaItem?.mediaId)
                assertEquals(160, preloader!!.player.videoFormat?.width)
                assertEquals(90, preloader!!.player.videoFormat?.height)
            }
        } finally {
            preloader?.let { ready -> instrument.runOnMainSync { ready.player.release(); ready.release() } }
        }
    }
}
