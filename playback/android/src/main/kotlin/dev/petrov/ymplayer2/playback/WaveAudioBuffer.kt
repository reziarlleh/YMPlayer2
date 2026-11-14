package dev.petrov.ymplayer2.playback

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.AtomicFile
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import dev.petrov.ymplayer2.core.*
import kotlinx.coroutines.*
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import org.json.JSONArray
import org.json.JSONObject

/** Durable bounded wave-window audio, separate from liked-track offline synchronization.
 * A complete verified file is published atomically; the engine never sees a partial download.
 * Adapted from 1.x prefetchForPlayback / CachedMediaValidator, without its permanent cache. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class WaveAudioBuffer(private val context: Context, private val account: (String) -> String?) {
    private data class Audio(val account: String, val file: File, val bytes: Long)
    private val lock = Any()
    private val files = mutableMapOf<Pair<String, String>, Audio>()
    private val root = File(context.filesDir, "wave-audio")
    private val index = AtomicFile(File(context.filesDir, "wave-audio-index.json"))
    private var generation = 0L
    private var directory: File? = null
    private var retained: Pair<String, Set<String>>? = null

    init {
        // Only atomically published, complete files can rejoin a restored queue.
        // A missing/truncated file is a cache miss; it never changes track or position.
        runCatching {
            val rows = JSONArray(index.openRead().bufferedReader().use { it.readText() })
            for (i in 0 until rows.length()) {
                val row = rows.getJSONObject(i)
                val name = row.getString("file")
                if (!name.matches(Regex("[A-Za-z0-9-]+\\.audio"))) continue
                val file = File(root, name)
                val bytes = row.getLong("bytes")
                if (bytes in 1..MAX_AUDIO_BYTES && file.isFile && file.length() == bytes)
                    files[row.getString("profile") to row.getString("track")] = Audio(row.getString("account"), file, bytes)
            }
        }
    }

    fun uri(profile: String, track: String): String? = synchronized(lock) {
        files[profile to track]?.takeIf { it.account == account(profile) && it.file.isFile && it.file.length() == it.bytes }
            ?.let { Uri.fromFile(it.file).toString() }
    }
    fun ids(): Set<String> = synchronized(lock) { files.keys.filter { uri(it.first, it.second) != null }.mapTo(hashSetOf()) { it.second } }
    /** Identity for migrating old checkpoints, not permission to read another account's files. */
    fun accountId(profile: String): String? = synchronized(lock) {
        files.filterKeys { it.first == profile }.values.map { it.account }.distinct().singleOrNull()
    }
    fun retain(profile: String, keep: Set<String>) = synchronized(lock) {
        retained = profile to keep.toSet()
        val remove = files.keys.filter { it.first != profile || it.second !in keep }
        remove.forEach { files.remove(it)?.file?.delete() }
        if (remove.isNotEmpty()) runCatching { writeIndex() }
    }
    fun cancelPending() = synchronized(lock) { generation++; retained = null }
    fun clear(profile: String? = null) = synchronized(lock) {
        generation++
        retained = null
        // A local/signed-out profile must not erase another profile's parked wave.
        val remove = files.keys.filter { profile == null || it.first == profile }
        remove.forEach { files.remove(it)?.file?.delete() }
        runCatching { writeIndex() }
        // In-flight temporary files are removed by their worker's finally block.
    }

    suspend fun prepare(profile: String, track: Track, valid: () -> Boolean = { true }, resolve: suspend () -> String) = withContext(Dispatchers.IO) {
        val ticket = synchronized(lock) { generation }
        val owner = account(profile) ?: throw CancellationException("Wave account is not ready")
        if (uri(profile, track.id) != null) return@withContext
        val root = synchronized(lock) { directory ?: storage { newDirectory() }.also { directory = it } }
        val temp = storage { File.createTempFile("audio-", ".part", root) }
        var published = false
        val source = DefaultDataSource.Factory(context, DefaultHttpDataSource.Factory()
            .setUserAgent("Yandex-Music-API").setConnectTimeoutMs(15_000).setReadTimeoutMs(20_000)).createDataSource()
        try {
            val resolved = resolve()
            ensureActive()
            if (!valid()) throw CancellationException("Wave audio request was replaced")
            val length = source.open(DataSpec(Uri.parse(resolved)))
            if (length > MAX_AUDIO_BYTES) throw MusicException(MusicFailure.UNAVAILABLE)
            var bytes = 0L
            val fileOutput = storage { temp.outputStream() }
            fileOutput.buffered().use { output ->
                val chunk = ByteArray(64 * 1024)
                while (true) {
                    ensureActive()
                    if (!valid()) throw CancellationException("Wave audio request was replaced")
                    val count = source.read(chunk, 0, chunk.size)
                    if (count == C.RESULT_END_OF_INPUT) break
                    bytes += count
                    if (bytes > MAX_AUDIO_BYTES) throw MusicException(MusicFailure.UNAVAILABLE)
                    storage { output.write(chunk, 0, count) }
                }
                storage { output.flush() }
                storage { fileOutput.fd.sync() }
            }
            if (bytes == 0L || length >= 0 && bytes != length) throw IOException("Incomplete wave audio")
            validate(temp, track.durationSeconds * 1_000L)
            ensureActive()
            synchronized(lock) {
                if (generation != ticket || owner != account(profile) || !valid() || retained?.let { it.first != profile || track.id !in it.second } == true)
                    throw CancellationException("Wave buffer was cleared or the track left its window")
                val complete = File(root, temp.name.removeSuffix(".part") + ".audio")
                if (!temp.renameTo(complete)) throw WaveAudioStorageException()
                val key = profile to track.id
                val previous = files.put(key, Audio(owner, complete, bytes))
                try { writeIndex() } catch (e: Exception) {
                    files.remove(key); previous?.let { files[key] = it }; complete.delete(); throw e
                }
                previous?.file?.delete()
                published = true
            }
        } finally {
            runCatching { source.close() }
            if (!published) temp.delete()
        }
    }

    private fun writeIndex() = storage {
        val rows = JSONArray(files.map { (key, audio) -> JSONObject().put("profile", key.first).put("track", key.second)
            .put("account", audio.account).put("file", audio.file.name).put("bytes", audio.bytes) })
        val output = index.startWrite()
        try { output.write(rows.toString().toByteArray(Charsets.UTF_8)); index.finishWrite(output) }
        catch (e: Exception) { index.failWrite(output); throw e }
    }

    private fun newDirectory(): File {
        if (!root.isDirectory && !root.mkdirs()) throw WaveAudioStorageException()
        synchronized(initializedRoots) {
            if (initializedRoots.add(root.absolutePath)) {
                val complete = files.values.mapTo(hashSetOf()) { it.file.name }
                root.listFiles()?.filter { it.name !in complete }?.forEach { it.delete() }
                File(context.cacheDir, "wave-audio").deleteRecursively() // Retired, process-local buffer only.
            }
        }
        return root
    }

    private suspend fun validate(file: File, expectedMillis: Long) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            val index = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw MusicException(MusicFailure.UNAVAILABLE)
            extractor.selectTrack(index)
            // Before API28 the extractor cannot report sample size before reading.
            // Reserve the existing maximum once, never publish a truncated packet.
            var buffer = ByteBuffer.allocate(if (android.os.Build.VERSION.SDK_INT >= 28) 64 * 1024 else 4 * 1024 * 1024)
            var first = 0L; var last = 0L; var delta = 0L; var packets = 0
            // AAC encoder priming may precede zero; an absent sample track indicates EOS.
            while (extractor.sampleTrackIndex >= 0) {
                currentCoroutineContext().ensureActive()
                val expected = if (android.os.Build.VERSION.SDK_INT >= 28) extractor.sampleSize else null
                if (expected != null && (expected <= 0 || expected > 4 * 1024 * 1024)) throw MusicException(MusicFailure.UNAVAILABLE)
                if (expected != null && buffer.capacity() < expected) buffer = ByteBuffer.allocate(expected.toInt())
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size <= 0 || size > 4 * 1024 * 1024 || expected != null && size.toLong() != expected ||
                    packets > 0 && extractor.sampleTime < last) throw MusicException(MusicFailure.UNAVAILABLE)
                if (packets == 0) first = extractor.sampleTime else delta = extractor.sampleTime - last
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
        // The ten-entry window bounds complete files; one candidate and current download
        // can temporarily be in flight. Oversized/partial media is never published.
        private const val MAX_AUDIO_BYTES = 128L * 1024 * 1024
        private val initializedRoots = hashSetOf<String>()
        private inline fun <T> storage(block: () -> T): T = try { block() }
            catch (e: IOException) { throw WaveAudioStorageException(e) }
    }
}

/** A local cache write is not evidence of an internet outage. No path is logged. */
internal class WaveAudioStorageException(cause: IOException? = null) : IOException("Temporary wave storage unavailable", cause)
