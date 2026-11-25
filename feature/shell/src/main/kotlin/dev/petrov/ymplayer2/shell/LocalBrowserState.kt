package dev.petrov.ymplayer2.shell

import androidx.compose.runtime.*
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import dev.petrov.ymplayer2.core.*

/** Large selections survive a configuration change without placing thousands of URI strings in a Bundle. */
internal class LocalBrowserState(private val saved: SavedStateHandle) : ViewModel() {
    var path by mutableStateOf(emptyList<MediaFileEntry>())
        private set
    val savedPath: List<String> get() = saved.get<ArrayList<String>>("path").orEmpty()
    var focused by mutableStateOf<String?>(null)
    val position = mutableMapOf<String, Pair<Int, Int>>()
    val lastFocus = mutableMapOf<String, String>()
    var query by mutableStateOf("")
    var searching by mutableStateOf(false)
    var selecting by mutableStateOf(false)
    var range by mutableStateOf(false)
    var rangeStart by mutableStateOf<String?>(null)
    var order by mutableStateOf(saved.get<String>("order")?.let { value ->
        FileOrder.entries.find { it.name == value }
    } ?: FileOrder.NAME)
        private set
    var selected by mutableStateOf<Map<String, MediaFileEntry>>(emptyMap())
        private set
    val discoveredFiles = mutableStateMapOf<String, Set<String>>()

    fun navigate(next: List<MediaFileEntry>, restoreFocus: String? = null) {
        path = next
        saved["path"] = ArrayList(next.map(MediaFileEntry::uri))
        focused = restoreFocus ?: lastFocus[next.lastOrNull()?.uri.orEmpty()]
        query = ""; searching = false; stopRange()
    }

    fun returnToParent() = navigate(path.dropLast(1), path.last().uri)

    fun sort(next: FileOrder) { order = next; saved["order"] = next.name; stopRange() }

    fun stopRange() { range = false; rangeStart = null }

    fun toggle(entries: List<MediaFileEntry>, addOnly: Boolean = false) {
        if (entries.isEmpty()) return
        val withIds = entries.filter { it.trackId != null }
        val remove = !addOnly && withIds.all { it.fileKey in selected }
        selected = LinkedHashMap(selected).apply {
            withIds.forEach { entry ->
                if (remove) remove(entry.fileKey) else putIfAbsent(entry.fileKey, entry)
            }
        }
    }

    fun clearSelection() { selecting = false; selected = emptyMap(); stopRange() }
}

internal enum class FileOrder { NAME, REVERSE_NAME, NEWEST, OLDEST }

internal fun FileOrder.comparator(): Comparator<MediaFileEntry> {
    val names = compareBy(mediaFileNameOrder, MediaFileEntry::name).thenBy(MediaFileEntry::uri)
    return when (this) {
        FileOrder.NAME -> names
        FileOrder.REVERSE_NAME -> names.reversed()
        FileOrder.NEWEST -> compareByDescending(MediaFileEntry::modifiedMillis).then(names)
        FileOrder.OLDEST -> compareBy(MediaFileEntry::modifiedMillis).then(names)
    }

}

internal fun selectedFolderCounts(selected: Collection<MediaFileEntry>): Map<Pair<String, String>, Int> {
    val counts = mutableMapOf<Pair<String, String>, Int>()
    selected.forEach { file ->
        var index = file.path.lastIndexOf(" / ")
        while (index >= 0) {
            val key = file.rootUri to file.path.substring(0, index)
            counts[key] = counts.getOrDefault(key, 0) + 1
            index = file.path.lastIndexOf(" / ", index - 1)
        }
    }
    return counts
}
