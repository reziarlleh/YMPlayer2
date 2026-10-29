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
class LikedFileStore(private val context: Context, private val diagnostic: (OperationDiagnostic) -> Unit = {}) : OfflineStore {
    private val automatic = context.applicationContext.getSharedPreferences("offline-downloads", Context.MODE_PRIVATE)
    private fun automaticKey(owner: OfflineOwner) = hash("${owner.profileId}\u0000${owner.accountId}")
    override suspend fun pendingDownloads(owner: OfflineOwner): Set<String> = withContext(Dispatchers.IO) {
        automatic.getStringSet(automaticKey(owner), emptySet())!!.toSet()
    }
    override suspend fun pendingDownloads(owner: OfflineOwner, keys: Set<String>) = withContext(Dispatchers.IO) {
        if (!automatic.edit().putStringSet(automaticKey(owner), keys.toSet()).commit()) throw IOException("Download queue persistence failed")
    }
    private val lock = Mutex()
    // A location lease prevents moving a download's destination. Network IO never holds the read lock.
    private val locationLease = Mutex()
    private val directories = CacheDirectories(context)
    private data class FileProof(val uri: String, val bytes: Long, val modified: Long, val checksum: String?)
    private val proofs = object : LinkedHashMap<String, FileProof>(256, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, FileProof>?) = size > 256
    }
    private val proofLock = Any()
    private var proofGeneration = 0L
    private var rootOwner: OfflineOwner? = null
    private val roots = java.util.concurrent.ConcurrentHashMap<String, StorageNode>()
    private fun proofKey(file: StorageNode) = "${file.parentFile!!.uri}/${file.name.removeSuffix(".sha256")}"
    private fun invalidate(file: StorageNode) = synchronized(proofLock) {
        proofs.remove(proofKey(file))
        Unit
    }
    private fun invalidateAll() { synchronized(proofLock) { proofs.clear(); proofGeneration++ }; roots.clear() }
    fun locationState() = directories.state()
    internal fun locationIdentity() = directories.identity()
    suspend fun move(destination: String?, progress: (Long, Long) -> Unit) = withContext(Dispatchers.IO) {
        locationLease.withLock { lock.withLock {
            invalidateAll()
            try { directories.migrate(destination, progress) } finally { invalidateAll() }
        } }
    }
    private fun hash(value: String) = digest(value.toByteArray())
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun root(owner: OfflineOwner, refresh: Boolean = false): StorageNode {
        if (rootOwner != owner) { invalidateAll(); rootOwner = owner }
        val identity = directories.identity().orEmpty()
        if (!refresh) roots[identity]?.let { return it }
        return directories.owner(owner).also { root ->
            // SAF rename is not atomic. Restore app-generated backups left by process death.
            root.listFiles()?.filter { it.name.matches(Regex("[a-f0-9]{64}\\.(json|audio|cover)(\\.sha256)?\\.backup")) }?.forEach { backup ->
                val name = backup.name.removeSuffix(".backup")
                if (root.child(name).exists()) backup.delete() else backup.rename(name)
            }
            roots.clear(); roots[identity] = root
        }
    }
    // UGC uploads have opaque IDs too. Disk names are SHA256 hashes, never raw identifiers.
    private fun key(id: String) = id.removePrefix("yandex:").substringBefore(':').also {
        require(it != "null" && it.matches(Regex("[A-Za-z0-9_-]{1,256}")))
    }
    private fun part(root: StorageNode, id: String, extension: String) = root.child(hash(key(id)) + extension)
    private fun checksum(file: StorageNode) = file.parentFile!!.child(file.name + ".sha256")
    private class Revoked : Exception()
    private fun check(allowed: () -> Boolean) { if (!allowed()) throw Revoked() }

    override suspend fun catalog(owner: OfflineOwner): List<Track> = withContext(Dispatchers.IO) {
        lock.withLock {
            val root = root(owner, refresh = true)
            root.listFiles()?.filter { it.name.endsWith(".part") && it.lastModified() < System.currentTimeMillis() - 86_400_000 }?.forEach(StorageNode::delete)
            root.listFiles()?.filter { it.name.endsWith(".json") && it.length() < 64 * 1024 }?.mapNotNull { metadata ->
                currentCoroutineContext().ensureActive()
                try {
                    val track = decode(JSONObject(metadata.readText()))
                    val audio = part(root, track.id, ".audio")
                    if (!audio.isFile || audio.length() !in 1..(128L * 1024 * 1024)) null else {
                        val cover = part(root, track.id, ".cover").takeIf { it.isFile && it.length() in 1..(8L * 1024 * 1024) }
                        track.copy(offline = true, available = true, uri = audio.uri.toString(),
                            artworkUri = cover?.let { it.uri.toString() }, sizeBytes = audio.length() + (cover?.length() ?: 0))
                    }
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { null }
            }?.sortedWith(compareBy(Track::artist, Track::title)).orEmpty()
        }
    }

    override suspend fun load(owner: OfflineOwner): List<Track> = withContext(Dispatchers.IO) {
        // Auditing every file is explicitly separate from the fast ready catalog. In
        // particular, hashing one slow USB file must not lock playback of another one.
        val tracks = catalog(owner)
        val root = lock.withLock { root(owner) }
        tracks.mapNotNull { track ->
            currentCoroutineContext().ensureActive()
            try { cached(root, track) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { null }
        }
    }

    override suspend fun sync(owner: OfflineOwner, track: Track, resolve: suspend () -> String, allowed: () -> Boolean, transfer: () -> Unit): OfflineItem = withContext(Dispatchers.IO) { locationLease.withLock {
        val root = lock.withLock { root(owner) }
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
                val valid = valid(audio, track.durationSeconds * 1000L, true)
                if (!valid) {
                    if (!track.available) throw MusicException(MusicFailure.UNAVAILABLE)
                    transfer(); check(allowed)
                    download(resolve(), audio, 128L * 1024 * 1024, track.durationSeconds * 1000L, true, allowed, transfer)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Revoked) { throw e }
            catch (e: OfflineException) { throw e }
            catch (e: Exception) { reportFailure(e); audioFailed = true }
            try {
                val valid = valid(cover, 0, false)
                if (!valid) {
                    val uri = track.artworkUri
                    if (uri == null) noCover = true
                    else { transfer(); check(allowed); download(uri, cover, 8L * 1024 * 1024, 0, false, allowed, transfer) }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Revoked) { throw e }
            catch (e: OfflineException) { throw e }
            catch (e: Exception) { reportFailure(e); coverFailed = true }
            val cached = cached(root, track)
            lock.withLock { check(allowed); OfflineItem(cached, audioFailed, coverFailed, noCover) }
        } catch (_: Revoked) { OfflineItem(null, false, false, false) }
    } }
    private fun reportFailure(error: Throwable) {
        val status = generateSequence(error) { it.cause }.take(20)
            .filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()?.responseCode
        diagnostic(diagnosticFailure(DiagnosticOperation.OFFLINE_DOWNLOAD, error, httpStatus = status))
    }

    override suspend fun retain(owner: OfflineOwner, keys: Set<String>, allowed: () -> Boolean) = withContext(Dispatchers.IO) {
        lock.withLock {
            if (!allowed()) return@withLock
            val keep = keys.mapTo(hashSetOf(), ::hash)
            root(owner).listFiles()?.filter { !it.name.endsWith(".part") && it.name.substringBefore('.') !in keep }?.forEach {
                invalidate(it)
                if (!it.delete() && it.exists()) throw IOException("Cannot remove offline entry")
            }
        }
        Unit
    }
    override suspend fun clear(owner: OfflineOwner) = withContext(Dispatchers.IO) {
        lock.withLock {
            val root = root(owner)
            invalidateAll()
            if (!root.deleteRecursively()) throw IOException("Cannot clear offline files")
        }
    }
    override suspend fun audio(owner: OfflineOwner, trackId: String): String? = withContext(Dispatchers.IO) {
        val identity = directories.identity()
        val selected = lock.withLock {
            val root = root(owner); val file = part(root, trackId, ".audio")
            val metadata = part(root, trackId, ".json")
            try {
                val track = decode(JSONObject(metadata.readText()))
                file to track.durationSeconds * 1000L
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { null }
        } ?: return@withContext null
        try {
            if (!valid(selected.first, selected.second, true)) return@withContext null
            lock.withLock {
                if (directories.identity() != identity || !selected.first.isFile || selected.first.length() <= 0) null
                else selected.first.uri.toString()
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { null }
    }
    private suspend fun cached(root: StorageNode, track: Track): Track? {
        val audio = part(root, track.id, ".audio")
        if (!valid(audio, track.durationSeconds * 1000L, true)) return null
        val cover = part(root, track.id, ".cover").takeIf { valid(it, 0, false) }
        return track.copy(offline = true, available = true, uri = audio.uri.toString(),
            artworkUri = cover?.let { it.uri.toString() }, sizeBytes = audio.length() + (cover?.length() ?: 0))
    }
    private suspend fun valid(file: StorageNode, duration: Long, audio: Boolean): Boolean {
        val generation = synchronized(proofLock) { proofGeneration }
        if (!file.isFile || file.length() <= 0 || file.length() > (if (audio) 128L else 8L) * 1024 * 1024) return false
        val before = proof(file)
        if (before.modified > 0 && before.checksum != null && synchronized(proofLock) { proofs[proofKey(file)] == before }) return true
        val valid = if (before.checksum != null) before.checksum == fileHash(file)
            else if (validate(file, duration, audio)) { remember(file); true } else false
        if (!valid) { invalidate(file); return false }
        val after = proof(file)
        if (before.uri != after.uri || before.bytes != after.bytes || before.modified != after.modified) return false
        if (before.checksum != null && before.checksum != after.checksum) return false
        if (after.modified > 0 && after.checksum != null) synchronized(proofLock) {
            if (proofGeneration == generation) proofs[proofKey(file)] = after
        }
        return true
    }
    private fun proof(file: StorageNode): FileProof {
        val expected = checksum(file).takeIf { it.isFile && it.length() <= 128 }?.readText()?.trim()
            ?.takeIf { it.matches(Regex("[a-f0-9]{64}")) }
        return FileProof(file.uri.toString(), file.length(), file.lastModified(), expected)
    }
    private suspend fun download(uri: String, target: StorageNode, limit: Long, duration: Long, audio: Boolean, allowed: () -> Boolean, transfer: () -> Unit) {
        val root = target.parentFile!!
        if (root.usableSpace < 32L * 1024 * 1024) throw OfflineException("Недостаточно свободного места для офлайн-музыки.")
        val temp = StorageNode.temporary(root, "download-")
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
                invalidate(target)
                checksum(target).let { if (it.exists()) it.delete() }
                target.replace(temp)
                write(checksum(target), digest.toByteArray())
                proof(target).takeIf { it.modified > 0 }?.let { verified -> synchronized(proofLock) { proofs[proofKey(target)] = verified } }
            }
        } finally { runCatching { source.close() }; temp.delete() }
    }
    private suspend fun fileHash(file: StorageNode): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val chunk = ByteArray(128 * 1024)
            while (true) { currentCoroutineContext().ensureActive(); val count = input.read(chunk); if (count < 0) break; digest.update(chunk, 0, count) }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    private suspend fun remember(file: StorageNode) = write(checksum(file), fileHash(file).toByteArray())
    private fun write(target: StorageNode, bytes: ByteArray) {
        val temp = StorageNode.temporary(target.parentFile!!, "metadata-")
        invalidate(target)
        try { temp.writeBytes(bytes); target.replace(temp) }
        finally { invalidate(target); roots.clear(); temp.delete() }
    }
    private suspend fun validate(file: StorageNode, expectedMillis: Long, audio: Boolean): Boolean {
        if (!audio) return try {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, file.uri)) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.setOnPartialImageListener { false }
                decoder.setTargetSampleSize(((maxOf(info.size.width, info.size.height) + 511) / 512).coerceAtLeast(1))
            }.recycle(); true
        } catch (_: Exception) { false }
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, file.uri, null)
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
