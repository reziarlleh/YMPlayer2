package dev.petrov.ymplayer2.library

import android.content.Context
import android.provider.DocumentsContract.Document
import android.util.AtomicFile
import dev.petrov.ymplayer2.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

class LocalFileManager(context: Context, private val library: SafLibrary, private val collections: LocalCollections) : LocalMediaFiles {
    private val storage = LocalFileStorage(context.applicationContext.contentResolver) { library.state.value.roots }
    private val operations = Mutex()
    private val pending = AtomicFile(File(context.filesDir, "local-file-relinks.json"))
    private val renameIntent = AtomicFile(File(context.filesDir, "local-file-rename.json"))

    override suspend fun roots(): List<MediaFileEntry> = withContext(Dispatchers.IO) {
        library.state.value.roots.map(storage::root)
    }

    override suspend fun children(folder: MediaFileEntry): List<MediaFileEntry> = withContext(Dispatchers.IO) {
        storage.list(folder).filter { it.directory || it.trackId != null }
    }

    override suspend fun tracks(entries: List<MediaFileEntry>): List<Track> = withContext(Dispatchers.IO) {
        val files = audioFiles(entries)
        val ids = files.mapNotNull { it.trackId }
        var indexed = library.tracksByIds(ids)
        if (ids.any { indexed[it]?.available != true }) {
            library.refresh()
            indexed = library.tracksByIds(ids)
        }
        ids.map { id -> checkNotNull(indexed[id]?.takeIf(Track::available)) { "Music file is unavailable" } }
    }

    override suspend fun createFolder(parent: MediaFileEntry, name: String): MediaFileEntry = mutate {
        storage.create(parent, name, Document.MIME_TYPE_DIR)
    }

    override suspend fun rename(entry: MediaFileEntry, name: String): MediaFileEntry = mutate {
        val original = storage.stat(entry)
        if (original.name == name) return@mutate original
        if (original.trackId != null) require(supportedLocalAudio(name, "") ||
            original.name.substringAfterLast('.', "").equals(name.substringAfterLast('.', ""), true))
        val before = snapshot(original)
        checkMutableTree(before)
        val parent = storage.parent(original)
        check(storage.list(parent).none { it.uri != original.uri && it.name.equals(name, true) })
        val references = sourceReferences(before, savedByFile())
        write(renameIntent, JSONObject().put("root", original.rootUri).put("parent", parent.uri)
            .put("path", original.path.substringBeforeLast(" / ")).put("oldName", original.name)
            .put("newName", name).put("references", references).toString())
        val renamed = storage.rename(original, name)
        rememberLinks(references, snapshot(renamed))
        applyPending()
        renameIntent.delete()
        library.refresh()
        renamed
    }

    override suspend fun transfer(entries: List<MediaFileEntry>, destination: MediaFileEntry, move: Boolean): FileBatchResult = mutate {
        val target = storage.stat(destination)
        check(target.canCreate)
        val destinationEntries = mutableMapOf<String, LocalFileStorage.ChildrenInUse>()
        val savedByKey = if (move) savedByFile() else emptyMap()
        batch(entries) { tree ->
            if (move) {
                checkMutableTree(tree)
                check(tree.all { it.entry.canDelete })
            }
            check(tree.none { storage.identity(it.entry) == storage.identity(target) }) { "Destination inside source" }
            val copies = ArrayList<MediaFileEntry>(tree.size)
            try {
                for (item in tree) {
                    currentCoroutineContext().ensureActive()
                    val src = item.entry
                    val parent = if (item.parent < 0) target else copies[item.parent]
                    val occupied = destinationEntries.getOrPut(parent.uri) { storage.childrenInUse(parent) }
                    val copy = storage.create(parent, src.name, storage.mime(src), occupied)
                    copies.add(copy)
                    check(copy.name == src.name) { "Provider changed the requested name" }
                    if (!src.directory) {
                        copyVerified(src, copy)
                        copies[copies.lastIndex] = storage.stat(copy)
                    }
                }
                if (move) {
                    checkUnchanged(tree)
                    rememberLinks(sourceReferences(tree, savedByKey), tree.mapIndexed { i, node -> node.copy(entry = copies[i]) })
                }
            } catch (failure: Exception) {
                // Only entries created in this operation may be removed.
                copies.asReversed().forEach { copy -> runCatching { deleteEmptyOrFile(copy) } }
                throw failure
            }
            if (move) {
                // The destination now has the complete verified data. Keep it even if
                // removal of a source item fails partway or the process is interrupted.
                try { deleteTree(tree) } finally { applyPending() }
            }
        }
    }

    override suspend fun delete(entries: List<MediaFileEntry>): FileBatchResult = mutate {
        batch(entries) { tree ->
            checkMutableTree(tree)
            check(tree.all { it.entry.canDelete })
            deleteTree(tree)
        }
    }

    private suspend fun batch(entries: List<MediaFileEntry>, operation: suspend (List<Node>) -> Unit): FileBatchResult {
        var completed = 0
        val failed = mutableListOf<String>()
        val covered = mutableSetOf<String>()
        for (entry in entries.sortedWith(compareByDescending<MediaFileEntry> { it.directory }.thenBy { it.path.length })) {
            currentCoroutineContext().ensureActive()
            try {
                applyPending()
                if (storage.identity(entry) in covered) continue
                val tree = snapshot(storage.stat(entry))
                covered += tree.map { storage.identity(it.entry) }
                operation(tree)
                completed++
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { failed.add(entry.name) }
        }
        library.refresh()
        return FileBatchResult(completed, failed)
    }

    private data class Node(val entry: MediaFileEntry, val parent: Int, val relative: List<String>)

    private suspend fun snapshot(entry: MediaFileEntry): List<Node> {
        val nodes = mutableListOf(Node(entry, -1, emptyList()))
        val seen = mutableSetOf(storage.identity(entry))
        var pos = 0
        while (pos < nodes.size) {
            currentCoroutineContext().ensureActive()
            val item = nodes[pos]
            if (item.entry.directory) for (child in storage.list(item.entry, forMutation = true)) {
                check(seen.add(storage.identity(child))) { "Ambiguous directory contents" }
                check(nodes.size < 50_000) { "Too many files" }
                nodes.add(Node(child, pos, item.relative + child.name))
            }
            pos++
        }
        return nodes
    }

    private fun checkMutableTree(tree: List<Node>) {
        require(tree.first().entry.parentUri != null) { "Music root cannot be changed" }
        val protected = library.state.value.roots.map { storage.identity(storage.root(it)) }.toSet()
        check(tree.none { storage.identity(it.entry) in protected }) { "Contains a registered music root" }
    }

    private suspend fun checkUnchanged(tree: List<Node>) {
        val current = snapshot(storage.stat(tree.first().entry)).associateBy { it.entry.uri }
        check(current.size == tree.size && tree.all { node ->
            val now = current[node.entry.uri]?.entry
            now != null && now.name == node.entry.name && now.sizeBytes == node.entry.sizeBytes &&
                now.modifiedMillis == node.entry.modifiedMillis
        }) { "The source changed while copying" }
    }

    private suspend fun deleteTree(tree: List<Node>) {
        for (node in tree.asReversed()) {
            currentCoroutineContext().ensureActive()
            deleteEmptyOrFile(node.entry)
        }
    }

    private fun deleteEmptyOrFile(entry: MediaFileEntry) {
        if (entry.directory) check(storage.list(entry, forMutation = true).isEmpty()) { "Folder changed" }
        storage.delete(entry)
    }

    private suspend fun copyVerified(src: MediaFileEntry, copy: MediaFileEntry) {
        val sourceHash = MessageDigest.getInstance("SHA-256")
        val outputHash = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        var bytes = 0L
        storage.input(src).use { from ->
            storage.output(copy).use { to ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = from.read(buffer)
                    if (count < 0) break
                    to.write(buffer, 0, count)
                    sourceHash.update(buffer, 0, count)
                    bytes += count
                }
            }
        }
        check(src.sizeBytes < 0 || bytes == src.sizeBytes) { "Source length changed" }
        storage.input(copy).use { verify ->
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = verify.read(buffer)
                if (count < 0) break
                outputHash.update(buffer, 0, count)
            }
        }
        check(sourceHash.digest().contentEquals(outputHash.digest())) { "Copy verification failed" }
    }

    private suspend fun savedByFile(): Map<String, List<SavedTrack>> {
        val state = collections.state.first { it.ready }
        check(state.writable)
        val saved = state.profiles.values.flatMap { it.favorites + it.playlists.flatMap(LocalPlaylist::tracks) }.distinctBy { it.id }
        val indexed = library.tracksByIds(saved.map { it.id })
        return saved.groupBy { ref ->
            val track = indexed[ref.id]
            val root = track?.rootId
            val uri = track?.uri
            if (root == null || uri == null) ref.id else
                storage.identity(MediaFileEntry(uri, root, null, "", "", track.source, false, false, false, false, false, ref.id, 0, 0))
        }
    }

    private fun sourceReferences(nodes: List<Node>, byFile: Map<String, List<SavedTrack>>): JSONArray {
        val result = JSONArray()
        for (item in nodes) {
            val id = item.entry.trackId ?: continue
            val refs = byFile[item.entry.fileKey].orEmpty() + byFile[id].orEmpty()
            for (reference in refs.distinctBy { it.id }) {
                result.put(JSONObject().put("old", reference.id).put("relative", JSONArray(item.relative))
                    .put("bytes", item.entry.sizeBytes).put("title", reference.title).put("artist", reference.artist)
                    .put("duration", reference.duration).put("tint", reference.tint))
            }
        }
        return result
    }

    private fun rememberLinks(references: JSONArray, newNodes: List<Node>) {
        if (references.length() == 0) return
        val byPath = newNodes.associateBy { it.relative }
        val json = JSONArray()
        for (i in 0 until references.length()) {
            val ref = references.getJSONObject(i)
            val parts = ref.getJSONArray("relative")
            val target = byPath.getValue(List(parts.length(), parts::getString)).entry
            val bytes = ref.getLong("bytes")
            check(bytes < 0 || target.sizeBytes < 0 || bytes == target.sizeBytes)
            json.put(ref.put("new", requireNotNull(target.trackId)).put("source", target.source.name))
        }
        write(pending, json.toString())
    }

    private fun write(journal: AtomicFile, text: String) {
        val file = journal.startWrite()
        try { file.write(text.toByteArray()); journal.finishWrite(file) }
        catch (e: Exception) { journal.failWrite(file); throw e }
    }

    private fun read(journal: AtomicFile): String? {
        val file = try { journal.openRead() } catch (_: java.io.FileNotFoundException) { return null }
        return file.bufferedReader().use { it.readText() }
    }

    private suspend fun recoverRename() {
        val record = JSONObject(read(renameIntent) ?: return)
        library.state.first { it.ready }
        val root = storage.root(library.state.value.roots.first { it.uri == record.getString("root") })
        check(root.available)
        val parent = root.copy(uri = record.getString("parent"), path = record.getString("path"))
        val entries = storage.list(parent)
        val original = entries.singleOrNull { it.name == record.getString("oldName") }
        val renamed = entries.singleOrNull { it.name == record.getString("newName") }
        if (renamed == null) {
            check(original != null) { "Rename destination unavailable" }
        } else {
            check(original == null) { "Rename result ambiguous" }
            rememberLinks(record.getJSONArray("references"), snapshot(renamed))
            applyPending()
        }
        renameIntent.delete()
        library.refresh()
    }

    /** Keeps the journal for a retry if collections are temporarily unavailable at startup. */
    suspend fun recoverPending() = withContext(Dispatchers.IO) {
        operations.withLock {
            try { applyPending(); recoverRename() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { android.util.Log.w("YMPlayer2", "Local playlist relink pending", failure) }
        }
    }

    private suspend fun applyPending() {
        val array = JSONArray(read(pending) ?: return)
        collections.state.first { it.ready }
        collections.remapTracks(buildMap {
            for (i in 0 until array.length()) {
                val j = array.getJSONObject(i)
                put(j.getString("old"), Track(j.getString("new"), j.getString("title"), j.getString("artist"), "",
                    Source.valueOf(j.getString("source")), j.getInt("duration"), true, tint = j.getInt("tint")))
            }
        })
        pending.delete()
    }

    private suspend fun <T> mutate(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        operations.withLock {
            applyPending()
            recoverRename()
            block()
        }
    }
}
