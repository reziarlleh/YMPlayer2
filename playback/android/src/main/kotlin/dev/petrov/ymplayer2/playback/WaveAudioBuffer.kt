package dev.petrov.ymplayer2.playback

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import dev.petrov.ymplayer2.core.*
import kotlinx.coroutines.*
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.util.UUID

/** Ephemeral current/next audio, separate from future liked-track offline synchronization.
 * A complete verified file is published atomically; the engine never sees a partial download.
 * Adapted from 1.x prefetchForPlayback / CachedMediaValidator, without its permanent cache. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class WaveAudioBuffer(private val context: Context) {
    private val lock = Any()
    private val files = mutableMapOf<Pair<String, String>, File>()
    private var generation = 0L
    private var directory: File? = null

    fun uri(profile: String, track: String): String? = synchronized(lock) {
        files[profile to track]?.takeIf { it.isFile }?.let { Uri.fromFile(it).toString() }
    }
    fun ids(): Set<String> = synchronized(lock) { files.keys.mapTo(hashSetOf()) { it.second } }
    fun retain(profile: String, keep: Set<String>) = synchronized(lock) {
        files.keys.filter { it.first != profile || it.second !in keep }.forEach { files.remove(it)?.delete() }
    }
    fun clear() = synchronized(lock) {
        generation++
        files.values.forEach(File::delete); files.clear()
        // In-flight temporary files are removed by their worker's finally block.
    }

    suspend fun prepare(profile: String, track: Track, resolve: suspend () -> String) = withContext(Dispatchers.IO) {
        val ticket = synchronized(lock) { generation }
        if (uri(profile, track.id) != null) return@withContext
        val root = synchronized(lock) { directory ?: newDirectory(context).also { directory = it } }
        val temp = File.createTempFile("audio-", ".part", root)
        var published = false
        val source = DefaultDataSource.Factory(context, DefaultHttpDataSource.Factory()
            .setUserAgent("Yandex-Music-API").setConnectTimeoutMs(15_000).setReadTimeoutMs(20_000)).createDataSource()
        try {
            val length = source.open(DataSpec(Uri.parse(resolve())))
            if (length > MAX_AUDIO_BYTES) throw MusicException(MusicFailure.UNAVAILABLE)
            var bytes = 0L
            temp.outputStream().buffered().use { output ->
                val chunk = ByteArray(64 * 1024)
                while (true) {
                    ensureActive()
                    val count = source.read(chunk, 0, chunk.size)
                    if (count == C.RESULT_END_OF_INPUT) break
                    bytes += count
                    if (bytes > MAX_AUDIO_BYTES) throw MusicException(MusicFailure.UNAVAILABLE)
                    output.write(chunk, 0, count)
                }
            }
            if (bytes == 0L || length >= 0 && bytes != length) throw IOException("Incomplete wave audio")
            validate(temp, track.durationSeconds * 1_000L)
            ensureActive()
            synchronized(lock) {
                if (generation != ticket) throw CancellationException("Wave buffer was cleared")
                val complete = File(root, temp.name.removeSuffix(".part") + ".audio")
                if (!temp.renameTo(complete)) throw IOException("Cannot publish wave audio")
                files.put(profile to track.id, complete)?.delete()
                published = true
            }
        } finally {
            runCatching { source.close() }
            if (!published) temp.delete()
        }
    }

    private suspend fun validate(file: File, expectedMillis: Long) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            val index = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw MusicException(MusicFailure.UNAVAILABLE)
            extractor.selectTrack(index)
            var buffer = ByteBuffer.allocate(64 * 1024)
            var first = -1L; var last = -1L; var delta = 0L; var packets = 0
            while (extractor.sampleTime >= 0) {
                currentCoroutineContext().ensureActive()
                val size = extractor.sampleSize
                if (size <= 0 || size > 4 * 1024 * 1024) throw MusicException(MusicFailure.UNAVAILABLE)
                if (buffer.capacity() < size) buffer = ByteBuffer.allocate(size.toInt())
                buffer.clear()
                if (extractor.readSampleData(buffer, 0).toLong() != size || extractor.sampleTime < last) throw MusicException(MusicFailure.UNAVAILABLE)
                if (first < 0) first = extractor.sampleTime else delta = extractor.sampleTime - last
                last = extractor.sampleTime; packets++
                if (!extractor.advance()) break
            }
            val tolerance = (expectedMillis / 50).coerceIn(2_000, 5_000)
            if (packets == 0 || expectedMillis > 0 && (last - first + delta) / 1000 + tolerance < expectedMillis) throw MusicException(MusicFailure.UNAVAILABLE)
        } catch (e: CancellationException) { throw e }
        catch (e: MusicException) { throw e }
        catch (_: Exception) { throw MusicException(MusicFailure.UNAVAILABLE) }
        finally { extractor.release() }
    }

    companion object {
        private const val MAX_AUDIO_BYTES = 128L * 1024 * 1024 // current + next <= 1.x 256 MiB temporary limit
        private val initializedRoots = hashSetOf<String>()
        @Synchronized private fun newDirectory(context: Context): File {
            val root = File(context.cacheDir, "wave-audio")
            if (initializedRoots.add(root.absolutePath)) root.deleteRecursively() // leftovers from the previous process only
            return File(root, UUID.randomUUID().toString()).apply { mkdirs() }
        }
    }
}
