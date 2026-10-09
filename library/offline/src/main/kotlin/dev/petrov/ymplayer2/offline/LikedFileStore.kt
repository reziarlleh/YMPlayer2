package dev.petrov.ymplayer2.offline

import android.content.Context
import android.graphics.ImageDecoder
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.*
import dev.petrov.ymplayer2.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.StandardCopyOption.*
import java.security.MessageDigest

/** Permanent, account-scoped files. Audio, genuine artwork and checksums are independent.
 * Port of 1.x cacheLiked/CacheFileIntegrity/CachedMediaValidator; never writes a logo as a cover. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class LikedFileStore(private val context: Context) : OfflineStore {
    private val lock = Mutex()
    private fun hash(value: String) = digest(value.toByteArray())
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun root(owner: OfflineOwner) = File(context.filesDir, "offline-liked/v1/" + hash(owner.profileId + "\u0000" + owner.accountId))
    // UGC uploads have opaque IDs too. Disk names are SHA256 hashes, never raw identifiers.
    private fun key(id: String) = id.removePrefix("yandex:").substringBefore(':').also {
        require(it != "null" && it.matches(Regex("[A-Za-z0-9_-]{1,256}")))
    }
    private fun part(root: File, id: String, extension: String) = File(root, hash(key(id)) + extension)
    private fun checksum(file: File) = File(file.path + ".sha256")
    private class Revoked : Exception()
    private fun check(allowed: () -> Boolean) { if (!allowed()) throw Revoked() }

    override suspend fun catalog(owner: OfflineOwner): List<Track> = withContext(Dispatchers.IO) {
        lock.withLock {
            val root = root(owner)
            root.listFiles()?.filter { it.name.endsWith(".part") && it.lastModified() < System.currentTimeMillis() - 86_400_000 }?.forEach(File::delete)
            root.listFiles()?.filter { it.name.endsWith(".json") && it.length() < 64 * 1024 }?.mapNotNull { metadata ->
                currentCoroutineContext().ensureActive()
                try {
                    val track = decode(JSONObject(metadata.readText()))
                    val audio = part(root, track.id, ".audio")
                    if (!audio.isFile || audio.length() !in 1..(128L * 1024 * 1024)) null else {
                        val cover = part(root, track.id, ".cover").takeIf { it.isFile && it.length() in 1..(8L * 1024 * 1024) }
                        track.copy(offline = true, available = true, uri = Uri.fromFile(audio).toString(),
                            artworkUri = cover?.let { Uri.fromFile(it).toString() }, sizeBytes = audio.length() + (cover?.length() ?: 0))
                    }
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { null }
            }?.sortedWith(compareBy(Track::artist, Track::title)).orEmpty()
        }
    }

    override suspend fun load(owner: OfflineOwner): List<Track> = withContext(Dispatchers.IO) {
        lock.withLock {
            val root = root(owner)
            root.listFiles()?.filter { it.name.endsWith(".part") && it.lastModified() < System.currentTimeMillis() - 86_400_000 }?.forEach(File::delete)
            root.listFiles()?.filter { it.name.endsWith(".json") && it.length() < 64 * 1024 }?.mapNotNull { metadata ->
                currentCoroutineContext().ensureActive()
                try { cached(root, decode(JSONObject(metadata.readText()))) }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { null }
            }?.sortedWith(compareBy(Track::artist, Track::title)).orEmpty()
        }
    }

    override suspend fun sync(owner: OfflineOwner, track: Track, resolve: suspend () -> String, allowed: () -> Boolean, transfer: () -> Unit): OfflineItem = withContext(Dispatchers.IO) {
        val root = root(owner)
        val audio = part(root, track.id, ".audio")
        val cover = part(root, track.id, ".cover")
        try {
            lock.withLock {
                check(allowed)
                if (!root.isDirectory && !root.mkdirs()) throw IOException("Cannot create offline directory")
                write(part(root, track.id, ".json"), encode(track).toString().toByteArray())
            }
            var audioFailed = false; var coverFailed = false; var noCover = false
            try {
                val valid = lock.withLock { valid(audio, track.durationSeconds * 1000L, true) }
                if (!valid) {
                    if (!track.available) throw MusicException(MusicFailure.UNAVAILABLE)
                    transfer(); check(allowed)
                    download(resolve(), audio, 128L * 1024 * 1024, track.durationSeconds * 1000L, true, allowed, transfer)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Revoked) { throw e }
            catch (e: OfflineException) { throw e }
            catch (_: Exception) { audioFailed = true }
            try {
                val valid = lock.withLock { valid(cover, 0, false) }
                if (!valid) {
                    val uri = track.artworkUri
                    if (uri == null) noCover = true
                    else { transfer(); check(allowed); download(uri, cover, 8L * 1024 * 1024, 0, false, allowed, transfer) }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Revoked) { throw e }
            catch (e: OfflineException) { throw e }
            catch (_: Exception) { coverFailed = true }
            lock.withLock { check(allowed); OfflineItem(cached(root, track), audioFailed, coverFailed, noCover) }
        } catch (_: Revoked) { OfflineItem(null, false, false, false) }
    }

    override suspend fun retain(owner: OfflineOwner, keys: Set<String>, allowed: () -> Boolean) = withContext(Dispatchers.IO) {
        lock.withLock {
            if (!allowed()) return@withLock
            val keep = keys.mapTo(hashSetOf(), ::hash)
            root(owner).listFiles()?.filter { !it.name.endsWith(".part") && it.name.substringBefore('.') !in keep }?.forEach {
                if (!it.delete() && it.exists()) throw IOException("Cannot remove offline entry")
            }
        }
        Unit
    }
    override suspend fun clear(owner: OfflineOwner) = withContext(Dispatchers.IO) {
        lock.withLock { if (!root(owner).deleteRecursively()) throw IOException("Cannot clear offline files") }
    }
    override suspend fun audio(owner: OfflineOwner, trackId: String): String? = withContext(Dispatchers.IO) {
        lock.withLock {
            val root = root(owner); val file = part(root, trackId, ".audio")
            val metadata = part(root, trackId, ".json")
            try {
                val track = decode(JSONObject(metadata.readText()))
                if (valid(file, track.durationSeconds * 1000L, true)) Uri.fromFile(file).toString() else null
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { null }
        }
    }
    private suspend fun cached(root: File, track: Track): Track? {
        val audio = part(root, track.id, ".audio")
        if (!valid(audio, track.durationSeconds * 1000L, true)) return null
        val cover = part(root, track.id, ".cover").takeIf { valid(it, 0, false) }
        return track.copy(offline = true, available = true, uri = Uri.fromFile(audio).toString(),
            artworkUri = cover?.let { Uri.fromFile(it).toString() }, sizeBytes = audio.length() + (cover?.length() ?: 0))
    }
    private suspend fun valid(file: File, duration: Long, audio: Boolean): Boolean {
        if (!file.isFile || file.length() <= 0 || file.length() > (if (audio) 128L else 8L) * 1024 * 1024) return false
        val expected = checksum(file).takeIf { it.isFile && it.length() <= 128 }?.readText()?.trim()
        if (expected?.matches(Regex("[a-f0-9]{64}")) == true) return expected == fileHash(file)
        if (!validate(file, duration, audio)) return false
        remember(file)
        return true
    }
    private suspend fun download(uri: String, target: File, limit: Long, duration: Long, audio: Boolean, allowed: () -> Boolean, transfer: () -> Unit) {
        val root = target.parentFile!!
        if (root.usableSpace < 32L * 1024 * 1024) throw OfflineException("Недостаточно свободного места для офлайн-музыки.")
        val temp = File.createTempFile("download-", ".part", root)
        val source = DefaultDataSource.Factory(context, DefaultHttpDataSource.Factory().setUserAgent("Yandex-Music-API")
            .setConnectTimeoutMs(15_000).setReadTimeoutMs(20_000)).createDataSource()
        try {
            transfer(); check(allowed)
            val length = source.open(DataSpec(Uri.parse(uri)))
            if (length > limit) throw IOException("File exceeds offline limit")
            if (length > root.usableSpace - 16L * 1024 * 1024) throw OfflineException("Недостаточно свободного места для офлайн-музыки.")
            var bytes = 0L
            temp.outputStream().buffered().use { output ->
                val chunk = ByteArray(64 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive(); check(allowed); transfer()
                    val count = source.read(chunk, 0, chunk.size)
                    if (count == C.RESULT_END_OF_INPUT) break
                    bytes += count
                    if (bytes > limit) throw IOException("File exceeds offline limit")
                    if (root.usableSpace < 16L * 1024 * 1024) throw OfflineException("Недостаточно свободного места для офлайн-музыки.")
                    output.write(chunk, 0, count)
                }
            }
            if (bytes <= 0 || length >= 0 && length != bytes || !validate(temp, duration, audio)) throw IOException("Incomplete offline media")
            val digest = fileHash(temp)
            currentCoroutineContext().ensureActive()
            lock.withLock {
                check(allowed)
                // A crash between these renames leaves an unknown checksum, requiring packet/image validation.
                Files.deleteIfExists(checksum(target).toPath())
                Files.move(temp.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
                write(checksum(target), digest.toByteArray())
            }
        } finally { runCatching { source.close() }; temp.delete() }
    }
    private suspend fun fileHash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val chunk = ByteArray(128 * 1024)
            while (true) { currentCoroutineContext().ensureActive(); val count = input.read(chunk); if (count < 0) break; digest.update(chunk, 0, count) }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    private suspend fun remember(file: File) = write(checksum(file), fileHash(file).toByteArray())
    private fun write(target: File, bytes: ByteArray) {
        val temp = File.createTempFile("metadata-", ".part", target.parentFile)
        try { temp.writeBytes(bytes); Files.move(temp.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING) }
        finally { temp.delete() }
    }
    private suspend fun validate(file: File, expectedMillis: Long, audio: Boolean): Boolean {
        if (!audio) return try {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.setOnPartialImageListener { false }
                decoder.setTargetSampleSize(((maxOf(info.size.width, info.size.height) + 511) / 512).coerceAtLeast(1))
            }.recycle(); true
        } catch (_: Exception) { false }
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            val index = (0 until extractor.trackCount).firstOrNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true } ?: return false
            extractor.selectTrack(index)
            val format = extractor.getTrackFormat(index)
            val duration = expectedMillis.takeIf { it > 0 } ?: if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) / 1000 else 0
            var buffer = ByteBuffer.allocate(64 * 1024)
            var first = 0L; var last = 0L; var delta = 0L; var samples = 0
            // Negative AAC priming timestamps are valid. Track index, not PTS sign, marks EOS.
            while (extractor.sampleTrackIndex >= 0) {
                currentCoroutineContext().ensureActive()
                val size = extractor.sampleSize
                if (size <= 0 || size > 4 * 1024 * 1024 || samples > 0 && extractor.sampleTime < last) return false
                if (size > buffer.capacity()) buffer = ByteBuffer.allocate(size.toInt())
                buffer.clear()
                if (extractor.readSampleData(buffer, 0).toLong() != size) return false
                if (samples == 0) first = extractor.sampleTime else delta = extractor.sampleTime - last
                last = extractor.sampleTime; samples++
                if (!extractor.advance()) break
            }
            return samples > 0 && (duration <= 0 || (last - first + delta) / 1000 + (duration / 50).coerceIn(2000, 5000) >= duration)
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { return false }
        finally { extractor.release() }
    }
    private fun encode(t: Track) = JSONObject().put("id", t.id).put("title", t.title).put("artist", t.artist).put("album", t.album)
        .put("duration", t.durationSeconds).put("genre", t.genre).put("artwork", t.artworkUri).put("albumId", t.albumId)
        .put("artists", JSONArray().apply { t.artists.forEach { put(JSONObject().put("id", it.id).put("name", it.name)) } })
    private fun decode(j: JSONObject) = Track(j.getString("id"), j.getString("title"), j.getString("artist"), j.optString("album"), Source.YANDEX,
        j.getInt("duration"), false, genre = j.optString("genre"), folder = "Мне нравится · офлайн", artworkUri = j.optString("artwork").takeIf { it.isNotBlank() },
        artists = j.optJSONArray("artists")?.let { rows -> (0 until rows.length()).map { rows.getJSONObject(it).let { ArtistRef(it.getString("id"), it.getString("name")) } } }.orEmpty(),
        albumId = j.optString("albumId").takeIf { it.isNotBlank() })
}
