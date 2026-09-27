package dev.petrov.ymplayer2

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.petrov.ymplayer2.clips.ClipMediaPreloader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class ClipPreloadTest {
    @Test fun nextMediaBytesAreLoadedBeforeHandoff() {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val context = instrument.targetContext
        val fixture = File(context.cacheDir, "clip-preload-fixture.mp3")
        instrument.context.assets.open("cover.mp3").use { input ->
            fixture.outputStream().use { output -> input.copyTo(output) }
        }
        val loaded = CountDownLatch(1)
        val item = MediaItem.Builder().setMediaId("next")
            .setUri(Uri.fromFile(fixture)).setMimeType(MimeTypes.AUDIO_MPEG).build()
        var preloader: ClipMediaPreloader? = null
        try {
            instrument.runOnMainSync {
                preloader = ClipMediaPreloader(context) { if (it.mediaId == "next") loaded.countDown() }
                preloader!!.player.volume = 0f
                preloader!!.warmNext(item)
            }
            assertTrue("Media3 did not load the next item's initial samples", loaded.await(15, TimeUnit.SECONDS))
            instrument.runOnMainSync {
                assertTrue("Handoff must use the preloaded MediaSource", preloader!!.play(item))
                assertEquals("next", preloader!!.player.currentMediaItem?.mediaId)
            }
        } finally {
            preloader?.let { ready -> instrument.runOnMainSync {
                ready.player.release()
                ready.release()
            } }
            fixture.delete()
        }
    }
}
