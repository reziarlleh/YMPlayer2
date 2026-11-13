package dev.petrov.ymplayer2.offline

import android.content.Context
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
    private val catalogIndex = CatalogMetadataIndex(context)
    private val catalogGeneration = java.util.concurrent.atomic.AtomicLong()
    private val backupName = Regex("[a-f0-9]{64}\\.(json|audio|cover)(\\.sha256)?\\.backup")
    private data class FileProof(val uri: String, val bytes: Long, val modified: Long, val checksum: String?)
    private val proofs = object : LinkedHashMap<String, FileProof>(256, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, FileProof>?) = size > 256
    }
    private val proofLock = Any()
    private var proofGeneration = 0L
    @Volatile private var rootOwner: OfflineOwner? = null
    private val roots = java.util.concurrent.ConcurrentHashMap<String, StorageNode>()
    private fun proofKey(file: StorageNode) = "${file.parentFile!!.uri}/${file.name.removeSuffix(".sha256")}"
    private fun invalidate(file: StorageNode) = synchronized(proofLock) {
        proofs.remove(proofKey(file))
        Unit
    }
    private fun invalidateAll() {
        catalogGeneration.incrementAndGet()
        synchronized(proofLock) { proofs.clear(); proofGeneration++ }; roots.clear()
    }
    fun locationState() = directories.state()
    internal fun locationIdentity() = directories.identity()
    suspend fun move(destination: String?, progress: (Long, Long) -> Unit) = withContext(Dispatchers.IO) {
        locationLease.withLock { lock.withLock {
            invalidateAll()
            catalogIndex.clearLocation(directories.identity())
            try { directories.migrate(destination, progress) }
            finally { catalogIndex.clearLocation(directories.identity()); invalidateAll() }
        } }
    }
    private fun hash(value: String) = digest(value.toByteArray())
    private fun digest(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val hex = "0123456789abcdef"
        return CharArray(digest.size * 2) { index ->
            val value = digest[index / 2].toInt() and 255
            hex[if (index % 2 == 0) value ushr 4 else value and 15]
        }.concatToString()
    }
    private fun root(owner: OfflineOwner, refresh: Boolean = false, recover: Boolean = true): StorageNode {
        if (rootOwner != owner) { invalidateAll(); rootOwner = owner }
        val identity = directories.identity().orEmpty()
        if (!refresh) roots[identity]?.let { return it }
        return directories.owner(owner).also { root ->
            // SAF rename is not atomic. Restore app-generated backups left by process death.
            if (recover) recoverBackups(owner, identity, root, root.listFiles().orEmpty())
            roots.clear(); roots[identity] = root
        }
    }
    private fun recoverBackups(owner: OfflineOwner, identity: String?, root: StorageNode, files: List<StorageNode>): Boolean {
        val backups = files.filter { it.name.matches(backupName) }
        if (backups.isEmpty()) return false
        catalogGeneration.incrementAndGet()
        catalogIndex.remove(owner, identity, backups.mapTo(hashSetOf()) { it.name.removeSuffix(".backup") })
        backups.forEach { backup ->
            val name = backup.name.removeSuffix(".backup")
            if (root.child(name).exists()) backup.delete() else backup.rename(name)
        }
        return true
    }
    // UGC uploads have opaque IDs too. Disk names are SHA256 hashes, never raw identifiers.
    private fun key(id: String) = id.removePrefix("yandex:").substringBefore(':').also {
        require(it != "null" && it.matches(Regex("[A-Za-z0-9_-]{1,256}")))
    }
    private fun part(root: StorageNode, id: String, extension: String) = root.child(hash(key(id)) + extension)
    private fun checksum(file: StorageNode) = file.parentFile!!.child(file.name + ".sha256")
    private fun invalidMarker(file: StorageNode) = file.parentFile!!.child(file.name + ".invalid")
    private class Revoked : Exception()
    private fun check(allowed: () -> Boolean) { if (!allowed()) throw Revoked() }

    private data class CatalogSnapshot(val files: Map<String, StorageNode.Entry>, val metadata: Map<String, JSONObject>, val tracks: List<Track>)
    override suspend fun catalog(owner: OfflineOwner): List<Track> = withContext(Dispatchers.IO) { inspectCatalog(owner).tracks }
    private suspend fun inspectCatalog(owner: OfflineOwner): CatalogSnapshot {
        // Resolving a root is brief. Enumerating and reading thousands of metadata
        // documents must not hold the mutex needed to prepare a selected track.
        val (root, identity, initialGeneration) = lock.withLock {
            Triple(root(owner, refresh = true, recover = false), directories.identity(), catalogGeneration.get())
        }
        var generation = initialGeneration
        var inventory = root.listSnapshot().orEmpty()
        val backups = inventory.filter { it.name.matches(backupName) }
        if (backups.isNotEmpty()) {
            generation = lock.withLock {
                recoverBackups(owner, identity, root, backups.map { it.node })
                catalogGeneration.get()
            }
            inventory = root.listSnapshot().orEmpty()
        }
        val indexed = catalogIndex.read(owner, identity)
        val nextIndex = linkedMapOf<String, CatalogMetadataIndex.Entry>()
        val files = inventory.associateBy { it.name }
        val trackMetadata = hashMapOf<String, JSONObject>()
        val expired = System.currentTimeMillis() - 86_400_000
        // These app-generated temporary files never contribute to the catalog.
        inventory.filter { it.name.endsWith(".part") && it.modified in 1 until expired }.forEach { it.node.delete() }
        val tracks = inventory.filter { it.name.endsWith(".json") && it.isFile && it.bytes in 1 until 64L * 1024 }
            .mapNotNull { metadata ->
                currentCoroutineContext().ensureActive()
                try {
                    val reused = indexed[metadata.name]?.takeIf { it.matches(metadata) }?.let { record ->
                        runCatching { record to decode(record.metadata).also { key(it.id) } }.getOrNull()
                    }
                    val (record, track) = reused ?: CatalogMetadataIndex.Entry(metadata.node.uri.toString(), metadata.bytes,
                        metadata.modified, JSONObject(metadata.node.readText())).let { record ->
                            record to decode(record.metadata).also { key(it.id) }
                        }
                    if (metadata.modified > 0) nextIndex[metadata.name] = record
                    trackMetadata[key(track.id)] = record.metadata
                    val prefix = hash(key(track.id))
                    val audio = files["$prefix.audio"]?.takeIf { it.isFile && it.bytes in 1..(128L * 1024 * 1024) && "$prefix.audio.invalid" !in files }
                    val cover = files["$prefix.cover"]?.takeIf { it.isFile && it.bytes in 1..(8L * 1024 * 1024) && "$prefix.cover.invalid" !in files }
                    audio?.let { track.copy(offline = true, available = true, uri = it.node.uri.toString(),
                        artworkUri = cover?.node?.uri?.toString(), sizeBytes = it.bytes + (cover?.bytes ?: 0),
                        artworkRevision = cover?.let { image -> "${image.bytes}:${image.modified}:${record.metadata.optString("coverRevision")}" }) }
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { null }
            }.sortedWith(compareBy(Track::artist, Track::title))
        currentCoroutineContext().ensureActive()
        val job = currentCoroutineContext()[Job]
        catalogIndex.replace(owner, identity, nextIndex) {
            job?.isActive != false && catalogGeneration.get() == generation && rootOwner == owner && directories.identity() == identity
        }
        return CatalogSnapshot(files, trackMetadata, tracks)
    }

    /** One fresh inventory for a complete pass. Completion markers are not body-hash proofs. */
    override suspend fun planSync(owner: OfflineOwner, tracks: List<Track>): OfflineSyncPlan = withContext(Dispatchers.IO) {
        val snapshot = inspectCatalog(owner)
        val cached = snapshot.tracks.associateBy { key(it.id) }
        val ready = linkedMapOf<String, OfflineItem>()
        val missing = hashSetOf<String>()
        for (track in tracks) {
            currentCoroutineContext().ensureActive()
            val id = key(track.id); val prefix = hash(id)
            val metadata = snapshot.metadata[id]
            fun completed(extension: String, expected: Long, limit: Long): Boolean {
                val file = snapshot.files[prefix + extension] ?: return false
                val marker = snapshot.files[prefix + extension + ".sha256"] ?: return false
                return file.isFile && file.bytes in 1..limit && (expected <= 0 || expected == file.bytes) &&
                    marker.isFile && marker.bytes == 64L && prefix + extension + ".invalid" !in snapshot.files
            }
            val audio = completed(".audio", metadata?.optLong("audioBytes") ?: 0, 128L * 1024 * 1024)
            if (!audio) missing += id
            val cover = completed(".cover", metadata?.optLong("coverBytes") ?: 0, 8L * 1024 * 1024)
            val original = metadata?.let { runCatching { decode(it) }.getOrNull() }
            val coverSource = metadata?.optString("coverSource")?.takeIf { it.isNotBlank() && it != "null" } ?: original?.artworkUri
            val sameCover = track.artworkUri == null || track.artworkUri == coverSource
            val noCover = track.artworkUri == null && snapshot.files[prefix + ".cover"] == null
            if (audio && (cover && sameCover || noCover) && original != null &&
                encode(original).toString() == encode(track).toString()) {
                cached[id]?.let { ready[id] = OfflineItem(it, false, false, noCover) }
            }
        }
        OfflineSyncPlan(ready, missing)
    }

    override suspend fun load(owner: OfflineOwner): List<Track> = withContext(Dispatchers.IO) {
        // Auditing every file is explicitly separate from the fast ready catalog. In
        // particular, hashing one slow USB file must not lock playback of another one.
        val tracks = catalog(owner)
        val root = lock.withLock { root(owner) }
        tracks.mapNotNull { track ->
            currentCoroutineContext().ensureActive()
            try { cached(root, track, reuseProof = false) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { null }
        }
    }

    override suspend fun sync(owner: OfflineOwner, track: Track, resolve: suspend () -> String, allowed: () -> Boolean, transfer: () -> Unit): OfflineItem =
        syncTrack(owner, track, resolve, allowed, transfer, verifyExisting = true)
    override suspend fun syncIncremental(owner: OfflineOwner, track: Track, resolve: suspend () -> String, allowed: () -> Boolean, transfer: () -> Unit): OfflineItem =
        syncTrack(owner, track, resolve, allowed, transfer, verifyExisting = false)
    private suspend fun syncTrack(owner: OfflineOwner, track: Track, resolve: suspend () -> String, allowed: () -> Boolean,
        transfer: () -> Unit, verifyExisting: Boolean): OfflineItem = withContext(Dispatchers.IO) { locationLease.withLock {
        val root = lock.withLock { root(owner) }
        val identity = directories.identity()
        val audio = part(root, track.id, ".audio")
        val cover = part(root, track.id, ".cover")
        try {
            val metadata = part(root, track.id, ".json")
            val original = runCatching { metadata.takeIf { it.isFile && it.length() in 1 until 64L * 1024 }?.readText()?.let(::JSONObject) }.getOrNull()
            val originalTrack = original?.let { runCatching { decode(it) }.getOrNull() }
            val originalCover = original?.optString("coverSource")?.takeIf { it.isNotBlank() && it != "null" } ?: originalTrack?.artworkUri
            val encoded = encode(track)
            for (field in listOf("audioBytes", "coverBytes")) if (original?.has(field) == true) encoded.put(field, original.getLong(field))
            if (original?.has("coverRevision") == true) encoded.put("coverRevision", original.getString("coverRevision"))
            encoded.put("coverSource", originalCover ?: JSONObject.NULL)
            suspend fun saveMetadata() {
                check(allowed)
                catalogGeneration.incrementAndGet()
                catalogIndex.remove(owner, identity, setOf(metadata.name))
                lock.withLock {
                    check(allowed)
                    if (!root.isDirectory && !root.mkdirs()) throw IOException("Cannot create offline directory")
                    write(metadata, encoded.toString().toByteArray())
                }
            }
            // Keep metadata before the first download so cancellation after completed
            // audio does not orphan that file. Unchanged completed rows never get here.
            if (originalTrack == null || encode(originalTrack).toString() != encode(track).toString()) saveMetadata()
            var audioFailed = false; var coverFailed = false; var noCover = false
            var audioReady = false; var coverReady = false; var coverReplaced = false
            fun completed(file: StorageNode, expected: Long, limit: Long): Boolean {
                if (!file.isFile) return false
                val bytes = file.length()
                return bytes in 1..limit && (expected <= 0 || bytes == expected) &&
                    checksum(file).let { it.isFile && it.length() == 64L } && !invalidMarker(file).exists()
            }
            try {
                audioReady = !verifyExisting && completed(audio, original?.optLong("audioBytes") ?: 0, 128L * 1024 * 1024)
                if (!audioReady) audioReady = valid(audio, track.durationSeconds * 1000L, true, reuseProof = false)
                if (!audioReady) {
                    if (!track.available) throw MusicException(MusicFailure.UNAVAILABLE)
                    transfer(); check(allowed)
                    download(resolve(), audio, 128L * 1024 * 1024, track.durationSeconds * 1000L, true, allowed, transfer)
                    audioReady = true
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Revoked) { throw e }
            catch (e: OfflineException) { throw e }
            catch (e: Exception) { reportFailure(e); audioFailed = true }
            try {
                val sourceChanged = track.artworkUri != null && track.artworkUri != originalCover
                coverReady = !sourceChanged && !verifyExisting && completed(cover, original?.optLong("coverBytes") ?: 0, 8L * 1024 * 1024)
                if (!coverReady && !sourceChanged) coverReady = valid(cover, 0, false, reuseProof = false)
                if (!coverReady) {
                    val uri = track.artworkUri
                    if (uri == null) noCover = true
                    else {
                        transfer(); check(allowed); download(uri, cover, 8L * 1024 * 1024, 0, false, allowed, transfer)
                        coverReady = true; coverReplaced = true
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Revoked) { throw e }
            catch (e: OfflineException) { throw e }
            catch (e: Exception) {
                reportFailure(e); coverFailed = true
                // An unavailable replacement must not hide an intact older picture.
                coverReady = valid(cover, 0, false, reuseProof = false)
            }
            if (audioReady) encoded.put("audioBytes", audio.length())
            if (coverReady) encoded.put("coverBytes", cover.length())
            // A repair may restore identical bytes with an unchanged provider mtime.
            // Its new revision must still retry an earlier failed UI image read.
            if (coverReplaced) encoded.put("coverRevision", java.util.UUID.randomUUID().toString())
            encoded.put("coverSource", (if (coverReplaced) track.artworkUri else originalCover) ?: JSONObject.NULL)
            if (original?.toString() != encoded.toString()) saveMetadata()
            val result = if (audioReady && audio.isFile && audio.length() > 0) track.copy(offline = true, available = true,
                uri = audio.uri.toString(), artworkUri = cover.takeIf { coverReady }?.uri?.toString(),
                sizeBytes = audio.length() + if (coverReady) cover.length() else 0L,
                artworkRevision = if (coverReady) coverRevision(cover, encoded.optString("coverRevision")) else null) else null
            lock.withLock { check(allowed); OfflineItem(result, audioFailed, coverFailed, noCover) }
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
            catalogGeneration.incrementAndGet()
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
            catalogIndex.clear(owner, directories.identity())
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
    private suspend fun cached(root: StorageNode, track: Track, reuseProof: Boolean = true): Track? {
        val audio = part(root, track.id, ".audio")
        if (!valid(audio, track.durationSeconds * 1000L, true, reuseProof)) return null
        val cover = part(root, track.id, ".cover").takeIf { valid(it, 0, false, reuseProof) }
        return track.copy(offline = true, available = true, uri = audio.uri.toString(),
            artworkUri = cover?.let { it.uri.toString() }, sizeBytes = audio.length() + (cover?.length() ?: 0),
            artworkRevision = cover?.let { track.artworkRevision ?: coverRevision(it) })
    }
    private fun coverRevision(file: StorageNode, revision: String? = null): String {
        val verified = revision?.takeIf { it.isNotBlank() } ?: synchronized(proofLock) { proofs[proofKey(file)]?.checksum }
            ?: checksum(file).takeIf { it.isFile && it.length() <= 128 }?.readText()?.trim().orEmpty()
        return "${file.length()}:${file.lastModified()}:$verified"
    }
    private suspend fun valid(file: StorageNode, duration: Long, audio: Boolean, reuseProof: Boolean = true): Boolean {
        val generation = synchronized(proofLock) { proofGeneration }
        var integrityEpoch = catalogGeneration.get()
        if (!file.isFile || file.length() <= 0 || file.length() > (if (audio) 128L else 8L) * 1024 * 1024) return false
        val before = proof(file)
        // Playback may reuse an unchanged proof. An explicit audit/sync must hash
        // again: providers can report coarse timestamps for same-size edits.
        if (reuseProof && !invalidMarker(file).exists() && before.modified > 0 && before.checksum != null && synchronized(proofLock) { proofs[proofKey(file)] == before }) return true
        val valid = if (before.checksum != null) before.checksum == fileHash(file)
            else if (validate(file, duration, audio)) { remember(file); integrityEpoch = catalogGeneration.get(); true } else false
        if (!valid) {
            invalidate(file)
            lock.withLock {
                // Never mark a replacement completed by another writer as corrupt.
                if (catalogGeneration.get() == integrityEpoch) {
                    val marker = invalidMarker(file)
                    if (!marker.exists()) write(marker, byteArrayOf(1))
                }
            }
            return false
        }
        val after = proof(file)
        if (before.uri != after.uri || before.bytes != after.bytes || before.modified != after.modified) return false
        if (before.checksum != null && before.checksum != after.checksum) return false
        lock.withLock {
            if (catalogGeneration.get() == integrityEpoch) {
                val marker = invalidMarker(file)
                if (marker.exists() && marker.delete()) catalogGeneration.incrementAndGet()
            }
        }
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
                catalogGeneration.incrementAndGet()
                invalidate(target)
                checksum(target).let { if (it.exists()) it.delete() }
                target.replace(temp)
                write(checksum(target), digest.toByteArray())
                invalidMarker(target).let { if (it.exists()) it.delete() }
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
        catalogGeneration.incrementAndGet()
        val temp = StorageNode.temporary(target.parentFile!!, "metadata-")
        invalidate(target)
        try { temp.writeBytes(bytes); target.replace(temp) }
        finally { invalidate(target); roots.clear(); temp.delete() }
    }
    private suspend fun validate(file: StorageNode, expectedMillis: Long, audio: Boolean): Boolean {
        if (!audio) return validCover(context, file)
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, file.uri, null)
            val index = (0 until extractor.trackCount).firstOrNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true } ?: return false
            extractor.selectTrack(index)
            val format = extractor.getTrackFormat(index)
            val duration = expectedMillis.takeIf { it > 0 } ?: if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) / 1000 else 0
            var buffer = ByteBuffer.allocate(if (android.os.Build.VERSION.SDK_INT >= 28) 64 * 1024 else 4 * 1024 * 1024)
            var first = 0L; var last = 0L; var delta = 0L; var samples = 0
            // Negative AAC priming timestamps are valid. Track index, not PTS sign, marks EOS.
            while (extractor.sampleTrackIndex >= 0) {
                currentCoroutineContext().ensureActive()
                val expected = if (android.os.Build.VERSION.SDK_INT >= 28) extractor.sampleSize else null
                if (expected != null && (expected <= 0 || expected > 4 * 1024 * 1024) || samples > 0 && extractor.sampleTime < last) return false
                if (expected != null && expected > buffer.capacity()) buffer = ByteBuffer.allocate(expected.toInt())
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size <= 0 || size > 4 * 1024 * 1024 || expected != null && size.toLong() != expected) return false
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
