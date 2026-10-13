package dev.petrov.ymplayer2.offline

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import de.sciss.jump3r.mp3.*
import de.sciss.jump3r.mpg.*
import dev.petrov.ymplayer2.core.Track
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** A desktop-Java sound wrapper is deliberately not used on Android. Encoder core is Jump3r/LAME LGPL2.1+. */
internal class PcmMp3Encoder(rate: Int, channels: Int) : AutoCloseable {
    private val lame = Lame()
    private val flags: LameGlobalFlags
    private val output = ByteArray(32 * 1024)
    init {
        val gain = GainAnalysis(); val bits = BitStream(); val presets = Presets(); val qpv = QuantizePVT()
        val quant = Quantize(); val vbr = VBRTag(); val version = Version(); val tags = ID3Tag()
        val reservoir = Reservoir(); val take = Takehiro(); val mpg = MPGLib(); val inter = Interface(); val common = Common()
        lame.setModules(gain, bits, presets, qpv, quant, vbr, version, tags, mpg)
        bits.setModules(gain, mpg, version, vbr); tags.setModules(bits, version); presets.setModules(lame)
        quant.setModules(bits, reservoir, qpv, take); qpv.setModules(take, reservoir, lame.enc.psy)
        reservoir.setModules(bits); take.setModules(qpv); vbr.setModules(lame, bits, version)
        mpg.setModules(inter, common); inter.setModules(vbr, common)
        flags = lame.lame_init().apply {
            num_channels = channels; in_samplerate = rate; out_samplerate = 44100; brate = 320
            mode = if (channels == 1) MPEGMode.MONO else MPEGMode.JOINT_STEREO
            VBR = VbrMode.vbr_off; quality = 3; bWriteVbrTag = false; write_id3tag_automatic = false
        }
        tags.id3tag_init(flags)
        if (lame.lame_init_params(flags) < 0) { close(); throw IOException("Unsupported PCM") }
    }
    fun write(left: IntArray, right: IntArray, size: Int, destination: OutputStream) {
        val bytes = lame.lame_encode_buffer_int(flags, left, right, size, output, 0, output.size)
        if (bytes < 0) throw IOException("MP3 encoder failed")
        destination.write(output, 0, bytes)
    }
    fun finish(destination: OutputStream) {
        val bytes = lame.lame_encode_flush(flags, output, 0, output.size)
        if (bytes < 0) throw IOException("MP3 encoder flush failed")
        destination.write(output, 0, bytes)
    }
    override fun close() { lame.lame_close(flags) }
}

internal class Mp3AudioWriter(private val context: Context) {
    private fun open(extractor: MediaExtractor, uri: String) {
        val parsed = Uri.parse(uri)
        if (parsed.scheme in listOf("https", "http")) extractor.setDataSource(uri, mapOf("User-Agent" to "Yandex-Music-API"))
        else extractor.setDataSource(context, parsed, null)
    }
    suspend fun write(uri: String, track: Track, index: Int, count: Int, output: OutputStream) {
        val extractor = MediaExtractor()
        try {
            open(extractor, uri)
            val audio = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw IOException("No audio stream")
            extractor.selectTrack(audio)
            val format = extractor.getTrackFormat(audio)
            if (format.containsKey("crypto-key")) throw IOException("Protected audio")
            output.write(tags(track, index, count))
            if (format.getString(MediaFormat.KEY_MIME) == "audio/mpeg") copyMp3(extractor, output)
            else transcode(extractor, format, output)
        } finally { extractor.release() }
    }
    private suspend fun copyMp3(extractor: MediaExtractor, output: OutputStream) {
        var buffer = ByteBuffer.allocate(64 * 1024); var packets = 0
        while (extractor.sampleTrackIndex >= 0) {
            currentCoroutineContext().ensureActive()
            if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED != 0) throw IOException("Protected audio")
            val size = extractor.sampleSize
            if (size !in 1..(4L * 1024 * 1024)) throw IOException("Invalid audio packet")
            if (size > buffer.capacity()) buffer = ByteBuffer.allocate(size.toInt())
            buffer.clear(); val read = extractor.readSampleData(buffer, 0)
            if (read.toLong() != size) throw IOException("Incomplete MP3")
            output.write(buffer.array(), 0, read); packets++
            if (!extractor.advance()) break
        }
        if (packets == 0) throw IOException("Empty MP3")
    }
    private suspend fun transcode(extractor: MediaExtractor, input: MediaFormat, destination: OutputStream) {
        val decoder = MediaCodec.createDecoderByType(input.getString(MediaFormat.KEY_MIME)!!)
        var encoder: PcmMp3Encoder? = null
        try {
            input.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            decoder.configure(input, null, null, 0); decoder.start()
            val info = MediaCodec.BufferInfo(); var ended = false; var sentEnd = false; var decoded = 0L
            var format = input; var lastProgress = System.nanoTime()
            while (!ended) {
                currentCoroutineContext().ensureActive()
                if (!sentEnd) {
                    val slot = decoder.dequeueInputBuffer(10_000)
                    if (slot >= 0) {
                        val buffer = decoder.getInputBuffer(slot)!!
                        if (extractor.sampleTrackIndex >= 0 && extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED != 0) throw IOException("Protected audio")
                        val size = if (extractor.sampleTrackIndex < 0) -1 else extractor.readSampleData(buffer, 0)
                        if (size < 0) { sentEnd = true; decoder.queueInputBuffer(slot, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM) }
                        else { decoder.queueInputBuffer(slot, 0, size, extractor.sampleTime.coerceAtLeast(0), 0); extractor.advance() }
                    }
                }
                val slot = decoder.dequeueOutputBuffer(info, 10_000)
                if (slot == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) format = decoder.outputFormat
                if (slot >= 0) {
                    try {
                        if (info.size > 0) {
                            val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            require(channels in 1..8)
                            val rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            val pcmEncoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) format.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                            val sampleBytes = when (pcmEncoding) { AudioFormat.ENCODING_PCM_16BIT -> 2; AudioFormat.ENCODING_PCM_FLOAT, AudioFormat.ENCODING_PCM_32BIT -> 4; AudioFormat.ENCODING_PCM_24BIT_PACKED -> 3; else -> throw IOException("Unsupported PCM encoding") }
                            val buffer = decoder.getOutputBuffer(slot)!!.duplicate().order(ByteOrder.LITTLE_ENDIAN)
                            buffer.position(info.offset); buffer.limit(info.offset + info.size)
                            if (encoder == null) encoder = PcmMp3Encoder(rate, minOf(channels, 2))
                            val left = IntArray(8192); val right = IntArray(8192)
                            while (buffer.remaining() >= channels * sampleBytes) {
                                currentCoroutineContext().ensureActive()
                                val frames = minOf(left.size, buffer.remaining() / (channels * sampleBytes))
                                for (i in 0 until frames) {
                                    val samples = FloatArray(channels) { readSample(buffer, pcmEncoding) }
                                    val center = if (channels > 2) samples[2] * .5f else 0f
                                    left[i] = ((samples[0] + center + if (channels > 4) samples[4] * .35f else 0f).coerceIn(-1f, 1f) * Int.MAX_VALUE).toInt()
                                    right[i] = ((samples[if (channels == 1) 0 else 1] + center + if (channels > 5) samples[5] * .35f else 0f).coerceIn(-1f, 1f) * Int.MAX_VALUE).toInt()
                                }
                                encoder.write(left, right, frames, destination); decoded += frames
                            }
                            lastProgress = System.nanoTime()
                        }
                        ended = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    } finally { decoder.releaseOutputBuffer(slot, false) }
                }
                if (System.nanoTime() - lastProgress > 30_000_000_000L) throw IOException("Audio decoder stalled")
            }
            if (decoded == 0L) throw IOException("Empty decoded audio")
            encoder!!.finish(destination)
        } finally { encoder?.close(); runCatching { decoder.stop() }; decoder.release() }
    }
    private fun readSample(buffer: ByteBuffer, encoding: Int): Float = when (encoding) {
        AudioFormat.ENCODING_PCM_16BIT -> buffer.short / 32768f
        AudioFormat.ENCODING_PCM_FLOAT -> buffer.float.let { if (it.isFinite()) it else 0f }
        AudioFormat.ENCODING_PCM_32BIT -> buffer.int / 2147483648f
        else -> { val a = buffer.get().toInt() and 255; val b = buffer.get().toInt() and 255; val c = buffer.get().toInt(); ((c shl 16) or (b shl 8) or a) / 8388608f }
    }
    private fun tags(track: Track, index: Int, count: Int): ByteArray {
        val frames = ByteArrayOutputStream()
        for ((id, value) in listOf("TIT2" to track.title, "TPE1" to track.artist, "TALB" to track.album, "TRCK" to "${index + 1}/$count")) {
            val text = byteArrayOf(1) + value.take(1000).toByteArray(Charsets.UTF_16)
            val out = DataOutputStream(frames); out.writeBytes(id); out.writeInt(text.size); out.writeShort(0); out.write(text)
        }
        val bytes = frames.toByteArray(); val size = bytes.size
        return byteArrayOf(73, 68, 51, 3, 0, 0, ((size shr 21) and 127).toByte(), ((size shr 14) and 127).toByte(),
            ((size shr 7) and 127).toByte(), (size and 127).toByte()) + bytes
    }
}
