package dev.petrov.ymplayer2.library

import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract as Documents
import android.util.AtomicFile
import dev.petrov.ymplayer2.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong

/** Read-only SAF index. A failed root scan never replaces its last complete snapshot. */
class SafLibrary(context: Context, scope: CoroutineScope) : IndexedLocalLibrary {
    private val resolver = context.applicationContext.contentResolver
    private val file = AtomicFile(File(context.filesDir, "local-library.json"))
    private val artwork = ArtworkCache(File(context.cacheDir, "artwork"))
    private val index = LocalCatalogIndex(context, artwork)
    private val artworkChecked = mutableSetOf<String>()
    private val mutex = Mutex()
    private val loaded = CompletableDeferred<Unit>()
    private val refreshRequests = AtomicLong()
    private var coveredRefresh = 0L
    private val mutable = MutableStateFlow(LibrarySnapshot())
    @Volatile override var indexRevision: Long = 0
        private set
    override val state = mutable.asStateFlow()
    override val profiles = listOf(
        Profile("owner", "Основной", "Общий локальный каталог · своя очередь"),
        Profile("road", "В дороге", "Своя очередь и позиция"),
        Profile("guest", "Гость", "Общий локальный каталог · без аккаунта", true),
    )
    override fun tracks(profileId: String): List<Track> = error("Use indexed queries for the SAF catalog")

    override suspend fun playableTrackIds(source: Source?): List<String> {
        loaded.await()
        return withContext(Dispatchers.IO) { index.playableTrackIds(source) }
    }

    override suspend fun catalogTrackIds(source: Source?): List<String> {
        loaded.await()
        return withContext(Dispatchers.IO) { index.catalogTrackIds(source) }
    }

    override suspend fun adjacentTrack(currentId: String?, direction: Int, source: Source?, wrap: Boolean): Track? {
        loaded.await()
        return withContext(Dispatchers.IO) { index.adjacentTrack(currentId, direction, source, wrap) }
    }

    override suspend fun playbackWindow(currentId: String?, source: Source?): LocalPlaybackWindow? {
        loaded.await()
        return withContext(Dispatchers.IO) { index.playbackWindow(currentId, source) }
    }

    override suspend fun tracksByIds(ids: Collection<String>): Map<String, Track> {
        loaded.await()
        return withContext(Dispatchers.IO) { index.tracksByIds(ids) }
    }

    override suspend fun referencesByIds(ids: Collection<String>): Map<String, SavedTrack> {
        loaded.await()
        return withContext(Dispatchers.IO) { index.referencesByIds(ids) }
    }

    override suspend fun pageTracks(filter: CatalogFilter, descending: Boolean, group: String?,
        dimension: CatalogDimension, offset: Int, limit: Int): CatalogPage<Track> {
        loaded.await()
        return withContext(Dispatchers.IO) { index.pageTracks(filter, descending, group, dimension, offset, limit) }
    }

    override suspend fun pageGroups(filter: CatalogFilter, dimension: CatalogDimension,
        descending: Boolean, offset: Int, limit: Int): CatalogPage<CatalogGroup> {
        loaded.await()
        return withContext(Dispatchers.IO) { index.pageGroups(filter, dimension, descending, offset, limit) }
    }

    override suspend fun pageRecentTracks(filter: CatalogFilter, offset: Int, limit: Int): CatalogPage<Track> {
        loaded.await()
        return withContext(Dispatchers.IO) { index.pageRecentTracks(filter, offset, limit) }
    }

    init {
        monitorStorage(context, scope, this)
        scope.launch(Dispatchers.IO) {
            mutable.value = try {
                if (!index.initialized()) index.replace(readLegacyIndex(), artworkChecked)
                index.markUnavailable()
                indexRevision++
                index.readSummary()
            }
            catch (_: Exception) { LibrarySnapshot(ready = true, issue = "Не удалось прочитать индекс. Добавьте папку заново.") }
            loaded.complete(Unit)
            if (state.value.roots.isNotEmpty()) refresh()
            else mutable.value = state.value.copy(ready = true)
        }
    }

    override suspend fun addFolder(uri: String, source: Source) = operation {
        require(source != Source.YANDEX)
        val tree = Uri.parse(uri)
        require(tree.scheme == "content" && Documents.isTreeUri(tree))
        resolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (state.value.roots.none { it.uri == uri }) {
            val doc = Documents.buildDocumentUriUsingTree(tree, Documents.getTreeDocumentId(tree))
            val name = resolver.query(doc, arrayOf(Documents.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            } ?: "Музыка"
            mutable.value = state.value.copy(roots = state.value.roots + LibraryRoot(uri, name, source))
        }
        scanAll()
    }

    override suspend fun refresh() {
        val request = refreshRequests.incrementAndGet()
        operation {
            // Coalesce only requests already present when that scan STARTED. A request
            // during an older scan may describe newly connected storage and must run.
            if (request > coveredRefresh) scanAll()
        }
    }

    override suspend fun forgetFolder(uri: String) = operation {
        // Only our index and our grant change; no DocumentsContract.deleteDocument call.
        val next = state.value.copy(
            roots = state.value.roots.filterNot { it.uri == uri },
            tracks = emptyList(), issue = null,
        )
        index.forgetRoot(uri)
        indexRevision++
        mutable.value = next
        runCatching { resolver.releasePersistableUriPermission(Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }

    private suspend fun operation(block: suspend () -> Unit) {
        loaded.await()
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val before = state.value
                mutable.value = state.value.copy(scanning = true, issue = null)
                try { block() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { mutable.value = before.copy(issue = "Не удалось открыть или сохранить папку. Проверьте доступ и повторите.") }
                finally { mutable.value = state.value.copy(scanning = false, ready = true) }
            }
        }
    }

    private suspend fun scanAll() {
        val coveringRequest = refreshRequests.get()
        val snapshot = state.value
        val updated = mutableMapOf<String, LibraryRoot>()
        for (root in snapshot.roots) {
            currentCoroutineContext().ensureActive()
            val result = try {
                val found = scan(root)
                index.replaceRoot(root, found, artworkChecked)
                root.copy(issue = null, trackCount = found.size)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                index.markRootUnavailable(root)
                root.copy(issue = "Папка недоступна или обход не завершён. Подключите носитель и обновите.")
            }
            updated[root.uri] = result
            indexRevision++
            mutable.value = state.value.copy(roots = snapshot.roots.map { updated[it.uri] ?: it }, tracks = emptyList())
        }
        artwork.trim()
        coveredRefresh = coveringRequest
    }

    private suspend fun scan(root: LibraryRoot): List<Track> {
        val tree = Uri.parse(root.uri)
        check(resolver.persistedUriPermissions.any { it.uri == tree && it.isReadPermission })
        val result = linkedMapOf<String, Track>()
        val pending = ArrayDeque<Pair<String, String>>()
        val seen = mutableSetOf<String>()
        pending.add(Documents.getTreeDocumentId(tree) to root.name)
        val columns = arrayOf(Documents.Document.COLUMN_DOCUMENT_ID, Documents.Document.COLUMN_DISPLAY_NAME,
            Documents.Document.COLUMN_MIME_TYPE, Documents.Document.COLUMN_SIZE, Documents.Document.COLUMN_LAST_MODIFIED)
        var entries = 0
        while (pending.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val (directory, folder) = pending.removeFirst()
            if (!seen.add(directory)) continue
            check(seen.size <= 5000) { "Directory limit" }
            val children = Documents.buildChildDocumentsUriUsingTree(tree, directory)
            val cursor = resolver.query(children, columns, null, null, null) ?: error("Provider returned no cursor")
            cursor.use {
                while (it.moveToNext()) {
                    currentCoroutineContext().ensureActive()
                    check(++entries <= 50000) { "Entry limit" }
                    val documentId = it.getString(0)
                    val name = it.getString(1) ?: "Аудио"
                    val mime = it.getString(2).orEmpty()
                    if (mime == Documents.Document.MIME_TYPE_DIR) {
                        pending.add(documentId to "$folder / $name")
                        continue
                    }
                    val extension = name.substringAfterLast('.', "").lowercase(java.util.Locale.ROOT)
                    // Media3 has no AIFF extractor. An audio/* MIME must not bypass this.
                    if (extension in aiffExtensions || mime.lowercase(java.util.Locale.ROOT) in aiffMimeTypes) continue
                    if (!mime.startsWith("audio/") && extension !in extensions) continue
                    val uri = Documents.buildDocumentUriUsingTree(tree, documentId)
                    val id = "local:" + MessageDigest.getInstance("SHA-256").digest(uri.toString().toByteArray()).joinToString("") { byte -> "%02x".format(byte) }
                    if (id in result) continue
                    val size = it.getLong(3)
                    val modified = it.getLong(4)
                    val cached = index.cachedTrack(id, artworkChecked)?.takeIf { track -> modified > 0 && track.modifiedMillis == modified && track.sizeBytes == size && id in artworkChecked && artwork.present(track.artworkUri) }
                    result[id] = cached?.copy(available = true, folder = folder, source = root.source)
                        ?: metadata(uri, id, root, name, folder, size, modified)
                }
                check(!it.extras.getBoolean(Documents.EXTRA_LOADING, false)) { "Incomplete provider listing" }
            }
        }
        return result.values.toList()
    }

    private fun metadata(uri: Uri, id: String, root: LibraryRoot, name: String, folder: String, size: Long, modified: Long): Track {
        var title = name.substringBeforeLast('.', name)
        var artist = "Неизвестный исполнитель"
        var album = "Без альбома"
        var genre = "Без жанра"
        var duration = 0
        var cover: String? = null
        // Invalid tags don't hide a file: the player reports a decoding error if necessary.
        runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                resolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                    retriever.setDataSource(descriptor.fileDescriptor)
                    fun tag(key: Int) = retriever.extractMetadata(key)?.trim()?.takeIf(String::isNotBlank)
                    title = tag(MediaMetadataRetriever.METADATA_KEY_TITLE) ?: title
                    artist = tag(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: artist
                    album = tag(MediaMetadataRetriever.METADATA_KEY_ALBUM) ?: album
                    genre = tag(MediaMetadataRetriever.METADATA_KEY_GENRE) ?: genre
                    duration = ((tag(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) / 1000).coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
                    cover = artwork.store(retriever.embeddedPicture)
                }
            } finally { retriever.release() }
        }
        artworkChecked += id
        return Track(id, title, artist, album, root.source, duration, true, genre = genre, folder = folder,
            tint = (id.hashCode() and Int.MAX_VALUE) % 6, uri = uri.toString(), rootId = root.uri, sizeBytes = size, modifiedMillis = modified, artworkUri = cover)
    }

    private fun readLegacyIndex(): LibrarySnapshot {
        if (!file.baseFile.exists()) return LibrarySnapshot()
        val json = JSONObject(file.openRead().bufferedReader().use { it.readText() })
        val roots = json.getJSONArray("roots").objects().map {
            LibraryRoot(it.getString("uri"), it.getString("name"), Source.valueOf(it.getString("source")))
        }
        val tracks = json.getJSONArray("tracks").objects().map {
            if (it.optBoolean("artworkChecked")) artworkChecked += it.getString("id")
            Track(it.getString("id"), it.getString("title"), it.getString("artist"), it.getString("album"), Source.valueOf(it.getString("source")),
                it.getInt("duration"), true, available = false, genre = it.getString("genre"), folder = it.getString("folder"), tint = it.getInt("tint"),
                uri = it.getString("uri"), rootId = it.getString("root"), sizeBytes = it.getLong("size"), modifiedMillis = it.getLong("modified"), artworkUri = artwork.uri(it.optString("artwork")))
        }
        return LibrarySnapshot(roots, tracks)
    }

    private fun JSONArray.objects() = (0 until length()).map(::getJSONObject)
    private companion object {
        val extensions = setOf("mp3", "m4a", "aac", "flac", "ogg", "opus", "wav", "amr")
        val aiffExtensions = setOf("aiff", "aif", "aifc")
        val aiffMimeTypes = setOf("audio/aiff", "audio/x-aiff", "audio/x-aifc")
    }
}
