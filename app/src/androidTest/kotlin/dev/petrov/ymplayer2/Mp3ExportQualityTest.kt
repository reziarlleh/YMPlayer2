@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
package dev.petrov.ymplayer2
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.petrov.ymplayer2.core.*
import dev.petrov.ymplayer2.offline.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class Mp3ExportQualityTest {
    @Test fun eachSelectedBitrateIsWrittenToRealMp3IncludingMp3SourceReencoding() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val input = File(context.cacheDir, "quality-source.mp3")
        val output = File(context.cacheDir, "quality-output.mp3")
        try {
            input.outputStream().use { stream -> PcmMp3Encoder(44100, 2, 128).use { encoder ->
                val samples = IntArray(4410) { (kotlin.math.sin(it * .06) * 500000000).toInt() }
                repeat(30) { encoder.write(samples, samples, samples.size, stream) }; encoder.finish(stream)
            } }
            val track = Track("local:quality", "Quality", "Fixture", "", Source.LOCAL, 3, false)
            for (quality in Mp3Quality.entries) {
                output.outputStream().use { Mp3AudioWriter(context).write(Uri.fromFile(input).toString(), track, 0, 1, it, quality) }
                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(output.absolutePath)
                    val format = extractor.getTrackFormat(0)
                    assertEquals("audio/mpeg", format.getString(MediaFormat.KEY_MIME))
                    assertTrue(format.getLong(MediaFormat.KEY_DURATION) > 2_500_000)
                    extractor.selectTrack(0)
                    val frame = java.nio.ByteBuffer.allocate(4 * 1024 * 1024)
                    assertTrue(extractor.readSampleData(frame, 0) >= 4)
                    // API23's extractor omits KEY_BIT_RATE. Inspect the actual
                    // MPEG1 Layer III frame, independently of container metadata.
                    assertEquals(0xff, frame.get(0).toInt() and 255)
                    assertEquals(0xfa, frame.get(1).toInt() and 0xfe)
                    val index = (frame.get(2).toInt() ushr 4) and 15
                    val kbps = listOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, -1)[index]
                    assertEquals("Actual frame bitrate for $quality", quality.kbps, kbps)
                } finally { extractor.release() }
            }
        } finally { input.delete(); output.delete() }
    }
}
