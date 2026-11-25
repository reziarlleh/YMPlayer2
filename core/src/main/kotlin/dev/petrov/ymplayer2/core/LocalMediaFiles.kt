package dev.petrov.ymplayer2.core

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

data class MediaFileEntry(
    val uri: String, val rootUri: String, val parentUri: String?, val name: String,
    val path: String, val source: Source, val directory: Boolean, val available: Boolean,
    val canCreate: Boolean, val canRename: Boolean, val canDelete: Boolean,
    val trackId: String?, val sizeBytes: Long, val modifiedMillis: Long,
    /** Independent of which Android tree grant exposes the same document. */
    val fileKey: String = uri,
)

data class FileBatchResult(val completed: Int, val failed: List<String>)

interface LocalMediaFiles {
    suspend fun roots(): List<MediaFileEntry>
    suspend fun children(folder: MediaFileEntry): List<MediaFileEntry>
    suspend fun tracks(entries: List<MediaFileEntry>): List<Track>
    suspend fun createFolder(parent: MediaFileEntry, name: String): MediaFileEntry
    suspend fun rename(entry: MediaFileEntry, name: String): MediaFileEntry
    suspend fun transfer(entries: List<MediaFileEntry>, destination: MediaFileEntry, move: Boolean): FileBatchResult
    suspend fun delete(entries: List<MediaFileEntry>): FileBatchResult
}

/** Compare runs of digits by magnitude without parsing an unbounded number. */
val mediaFileNameOrder: Comparator<String> = Comparator { a, b ->
    var i = 0
    var j = 0
    while (i < a.length && j < b.length) {
        if (a[i] in '0'..'9' && b[j] in '0'..'9') {
            val startA = i
            val startB = j
            while (i < a.length && a[i] in '0'..'9') i++
            while (j < b.length && b[j] in '0'..'9') j++
            val numA = a.substring(startA, i).trimStart('0')
            val numB = b.substring(startB, j).trimStart('0')
            val comparison = numA.length.compareTo(numB.length).takeIf { it != 0 } ?: numA.compareTo(numB)
            if (comparison != 0) return@Comparator comparison
        } else {
            val comparison = a[i++].lowercaseChar().compareTo(b[j++].lowercaseChar())
            if (comparison != 0) return@Comparator comparison
        }
    }
    (a.length - i).compareTo(b.length - j).takeIf { it != 0 } ?: a.compareTo(b)
}

suspend fun LocalMediaFiles.audioFiles(
    entries: List<MediaFileEntry>,
    order: Comparator<MediaFileEntry> = compareBy(mediaFileNameOrder, MediaFileEntry::name).thenBy(MediaFileEntry::uri),
): List<MediaFileEntry> {
    val result = linkedMapOf<String, MediaFileEntry>()
    val pending = ArrayDeque(entries)
    val directories = mutableSetOf<String>()
    while (pending.isNotEmpty()) {
        currentCoroutineContext().ensureActive()
        val item = pending.removeFirst()
        if (!item.directory) {
            if (item.trackId != null) result.putIfAbsent(item.fileKey, item)
        } else if (directories.add(item.fileKey)) {
            val (folders, files) = children(item).partition(MediaFileEntry::directory)
            files.sortedWith(order).forEach { file -> if (file.trackId != null) result.putIfAbsent(file.fileKey, file) }
            folders.sortedWith(order).asReversed().forEach(pending::addFirst)
        }
    }
    return result.values.toList()
}
