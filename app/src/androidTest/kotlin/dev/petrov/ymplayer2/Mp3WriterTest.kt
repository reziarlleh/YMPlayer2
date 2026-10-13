@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
package dev.petrov.ymplayer2

import android.media.*
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.petrov.ymplayer2.core.*
import dev.petrov.ymplayer2.offline.Mp3AudioWriter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
class Mp3WriterTest {
    private val instrument get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrument.targetContext
    private fun asset(name: String) = File(context.cacheDir, "export-writer-input." + name.substringAfterLast('.')).also { file ->
        instrument.context.assets.open(name).use { input -> file.outputStream().use(input::copyTo) }
    }
    private fun write(input: File, duration: Int): File {
        val output = File(context.cacheDir, "export-writer-result.mp3")
        val track = Track("local-test", "Название", "Исполнитель", "Альбом", Source.LOCAL, duration, true)
        runBlocking { output.outputStream().use { Mp3AudioWriter(context).write(Uri.fromFile(input).toString(), track, 0, 1, it) } }
        return output
    }
    private fun packets(file: File): List<List<Byte>> {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(file.path); ex.selectTrack(0)
            assertEquals("audio/mpeg", ex.getTrackFormat(0).getString(MediaFormat.KEY_MIME))
            val buffer = ByteBuffer.allocate(64 * 1024)
            return buildList { while (ex.sampleTrackIndex >= 0) {
                buffer.clear(); val count = ex.readSampleData(buffer, 0)
                add(MessageDigest.getInstance("SHA-256").digest(buffer.array().copyOf(count)).toList())
                if (!ex.advance()) break
            } }
        } finally { ex.release() }
    }
    private fun decode(file: File, seconds: Int) {
        val ex = MediaExtractor(); var codec: MediaCodec? = null
        try {
            ex.setDataSource(file.path); ex.selectTrack(0)
            val format = ex.getTrackFormat(0).apply { setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT) }
            codec = MediaCodec.createDecoderByType("audio/mpeg").apply { configure(format, null, null, 0); start() }
            val info = MediaCodec.BufferInfo(); var inputEnded = false; var outputEnded = false
            var samples = 0L; var energy = 0.0; var rate = 44100; var channels = 1
            val until = System.currentTimeMillis() + 60_000
            while (!outputEnded && System.currentTimeMillis() < until) {
                if (!inputEnded) {
                    val slot = codec.dequeueInputBuffer(10_000)
                    if (slot >= 0) {
                        val size = if (ex.sampleTrackIndex < 0) -1 else ex.readSampleData(codec.getInputBuffer(slot)!!, 0)
                        if (size < 0) { inputEnded = true; codec.queueInputBuffer(slot, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM) }
                        else { codec.queueInputBuffer(slot, 0, size, ex.sampleTime, 0); ex.advance() }
                    }
                }
                val slot = codec.dequeueOutputBuffer(info, 10_000)
                if (slot == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) { rate = codec.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE); channels = codec.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT) }
                if (slot >= 0) {
                    val buffer = codec.getOutputBuffer(slot)!!.duplicate().order(ByteOrder.LITTLE_ENDIAN)
                    buffer.position(info.offset); buffer.limit(info.offset + info.size)
                    while (buffer.remaining() >= 2) { val sample = buffer.short / 32768.0; energy += sample * sample; samples++ }
                    outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0; codec.releaseOutputBuffer(slot, false)
                }
            }
            assertTrue("Complete MP3 decode", outputEnded)
            assertTrue("Decoded duration=$samples/$rate/$channels", samples / rate / channels >= seconds - 1)
            assertTrue("Export must contain audible samples", energy / samples > 0.0001)
        } finally { runCatching { codec?.stop() }; codec?.release(); ex.release() }
    }
    @Test fun genuineMp3KeepsEveryCompressedPacketAndDecodes() {
        val input = asset("cover.mp3"); val output = write(input, 30)
        try { assertEquals(packets(input), packets(output)); decode(output, 30) }
        finally { input.delete(); output.delete() }
    }
    @Test fun aacIsConvertedToAudibleFullLengthMp3() {
        val input = asset("clip-transport/fixture.mp4"); val output = write(input, 12)
        try { assertTrue(packets(output).size > 100); decode(output, 12) }
        finally { input.delete(); output.delete() }
    }
}
