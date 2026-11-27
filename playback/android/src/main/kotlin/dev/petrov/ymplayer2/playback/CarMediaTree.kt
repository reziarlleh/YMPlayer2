package dev.petrov.ymplayer2.playback

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import kotlinx.coroutines.flow.Flow
import java.security.MessageDigest

/** App-owned rows. Prepare resolves a selection without touching the engine until Play. */
class CarRow(
    val key: String,
    val title: String,
    val subtitle: String? = null,
    val artwork: Uri? = null,
    val children: (suspend () -> List<CarRow>)? = null,
    val prepare: (suspend () -> (() -> Unit))? = null,
)

interface CarCatalog {
    val owner: String
    val changes: Flow<Unit>
    suspend fun ready()
    fun root(): List<CarRow>
    suspend fun search(query: String): List<CarRow>
    fun resume()
}

interface CarClipAudio {
    val canPrevious: Boolean
    val canNext: Boolean
    fun play()
    fun pause()
    fun stop()
    fun skip(direction: Int)
    fun close(preserveIntent: Boolean = false)
}

class CarAccountRequired : Exception()
internal class CarSelection(val play: () -> Unit)

/** No caller-supplied URI can reach playback. A stale ID is resolved by walking the same
 * profile's real catalogue; cached pages only shorten the walk. */
internal class CarMediaTree(private val catalog: CarCatalog) {
    companion object {
        const val ROOT = "ymp_car"
        fun rootItem() = MediaItem.Builder().setMediaId(ROOT).setMediaMetadata(MediaMetadata.Builder()
            .setTitle("YMPlayer2").setIsBrowsable(true).setIsPlayable(false).build()).build()
    }

    private data class Entry(val id: String, val row: CarRow) {
        fun item(): MediaItem = MediaItem.Builder().setMediaId(id).setMediaMetadata(MediaMetadata.Builder()
            .setTitle(row.title).setSubtitle(row.subtitle).setArtist(row.subtitle).setArtworkUri(row.artwork)
            .setIsBrowsable(row.children != null).setIsPlayable(row.prepare != null).build()).build()
    }

    private val pages = object : LinkedHashMap<String, List<Entry>>(32, .75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, List<Entry>>) = size > 32
    }

    fun clear(): List<String> = pages.keys.toList().also { pages.clear() }
    private fun hash(value: String) = android.util.Base64.encodeToString(
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).copyOf(16),
        android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP)
    private fun base() = "$ROOT/${hash(catalog.owner)}"
    private fun index(parent: String, rows: List<CarRow>) = rows.distinctBy(CarRow::key).map {
        Entry("$parent/${hash(it.key)}", it)
    }.also { pages[parent] = it }

    private suspend fun rows(parent: String, fresh: Boolean): List<Entry> {
        require(parent.length <= 8192 && (parent == base() || parent.startsWith(base() + "/")))
        if (!fresh) pages[parent]?.let { return it }
        val owner = catalog.owner
        val searchPrefix = base() + "/s:"
        val result = when {
            parent == base() -> catalog.root()
            parent.startsWith(searchPrefix) && '/' !in parent.removePrefix(searchPrefix) ->
                catalog.search(Uri.decode(parent.removePrefix(searchPrefix)).also { require(it.length in 1..200) })
            else -> find(parent)?.row?.children?.invoke() ?: throw NoSuchElementException()
        }
        require(catalog.owner == owner)
        return index(parent, result)
    }
    private suspend fun find(id: String): Entry? {
        val parent = id.substringBeforeLast('/', "")
        if (!parent.startsWith(base()) || id.length > 8192 || id.count { it == '/' } > 128) return null
        return rows(parent, fresh = false).find { it.id == id }
    }
    suspend fun children(id: String): List<MediaItem> {
        catalog.ready()
        return rows(if (id == ROOT) base() else id, fresh = true).map(Entry::item)
    }
    suspend fun item(id: String): MediaItem? {
        if (id == ROOT) return rootItem()
        catalog.ready()
        return find(id)?.item()
    }
    private fun searchParent(query: String) = base() + "/s:" + Uri.encode(query.trim().take(200))
    suspend fun search(query: String, fresh: Boolean): List<MediaItem> {
        catalog.ready()
        if (query.isBlank()) return emptyList()
        return rows(searchParent(query), fresh).map(Entry::item)
    }
    suspend fun selection(request: MediaItem): MediaItem {
        catalog.ready()
        val owner = catalog.owner
        val query = request.requestMetadata.searchQuery
        val entry = when {
            query == null -> find(request.mediaId)
            query.isBlank() -> Entry(ROOT, CarRow("resume", "YMPlayer2", prepare = { catalog::resume }))
            else -> {
                val results = rows(searchParent(query), fresh = false)
                val matches = results.sortedByDescending { it.row.title.equals(query.trim(), true) }
                matches.firstNotNullOfOrNull { result ->
                    if (result.row.prepare != null) result else {
                        val children = result.row.children?.invoke().orEmpty()
                        (children.find { it.key == "play" } ?: children.find { it.prepare != null })
                            ?.let { Entry(result.id, it) }
                    }
                }
            }
        } ?: throw NoSuchElementException()
        val action = entry.row.prepare?.invoke() ?: throw NoSuchElementException()
        require(owner == catalog.owner)
        return entry.item().buildUpon().setUri("ymplayer2://browser/car")
            .setTag(CarSelection { if (catalog.owner == owner) action() }).build()
    }
}
