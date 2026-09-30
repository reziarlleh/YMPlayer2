package dev.petrov.ymplayer2.playback

import dev.petrov.ymplayer2.core.*
import org.json.JSONArray
import org.json.JSONObject

/** Persisted labels, never a playable URI or a resolved catalog row. */
internal data class PlaybackReference(val saved: SavedTrack, val album: String = "",
    val artwork: String? = null, val albumId: String? = null, val artists: List<ArtistRef> = emptyList()) {
    val id get() = saved.id
    fun remoteTrack(enabled: Boolean) = Track(id, saved.title, saved.artist, album, Source.YANDEX,
        saved.duration, offline = false, available = enabled, tint = saved.tint,
        folder = "Яндекс Музыка", artworkUri = artwork, artists = artists, albumId = albumId)
    fun json() = JSONObject().put("id", id).put("title", saved.title).put("artist", saved.artist)
        .put("source", saved.source.name).put("duration", saved.duration).put("tint", saved.tint)
        .put("album", album).put("artwork", artwork).put("albumId", albumId)
        .put("artists", JSONArray(artists.map { JSONObject().put("id", it.id).put("name", it.name) }))
    companion object {
        fun from(track: Track) = if (track.source == Source.YANDEX)
            PlaybackReference(track.saved(), track.album, track.artworkUri, track.albumId, track.artists)
            else PlaybackReference(track.saved())
        fun read(item: JSONObject) = PlaybackReference(SavedTrack(item.getString("id"), item.getString("title"),
            item.getString("artist"), Source.valueOf(item.getString("source")), item.getInt("duration"), item.optInt("tint")),
            item.optString("album"), item.optString("artwork").takeIf(String::isNotBlank),
            item.optString("albumId").takeIf(String::isNotBlank), item.optJSONArray("artists")?.let { rows ->
                (0 until rows.length()).map { rows.getJSONObject(it).let { ArtistRef(it.getString("id"), it.getString("name")) } }
            }.orEmpty())
    }
}

/** Explicit playlist order and optional shuffled IDs. Only the selected neighbours resolve. */
internal class ReferencePlaybackOrder {
    var references = emptyList<PlaybackReference>(); private set
    var ids = emptySet<String>(); private set
    var revision = 0L; private set
    private var positions = emptyMap<String, Int>()
    private var orderedIds = emptyList<String>()
    private var shuffled = emptyList<String>()
    fun replace(next: List<PlaybackReference>) {
        references = next.distinctBy(PlaybackReference::id)
        positions = references.mapIndexed { index, ref -> ref.id to index }.toMap()
        ids = positions.keys
        orderedIds = references.map(PlaybackReference::id)
        val keep = shuffled.filter { it in ids }
        val kept = keep.toHashSet()
        shuffled = if (shuffled.isEmpty()) emptyList() else keep + ids.filterNot { it in kept }.shuffled()
        revision++
    }
    fun resetShuffle() { shuffled = emptyList() }
    fun index(id: String?) = positions[id] ?: -1
    fun reference(id: String?) = references.getOrNull(index(id))
    private fun order(current: String?, shuffle: Boolean): List<String> {
        if (!shuffle) return orderedIds
        if (shuffled.isEmpty()) shuffled = listOfNotNull(current?.takeIf { it in ids }) + ids.filterNot { it == current }.shuffled()
        return shuffled
    }
    suspend fun adjacent(current: String?, direction: Int, shuffle: Boolean, repeat: RepeatMode,
        resolve: suspend (PlaybackReference) -> Track): Track? {
        val order = order(current, shuffle)
        if (order.isEmpty()) return null
        var position = order.indexOf(current)
        if (position < 0) position = if (direction > 0) -1 else order.size
        repeat(order.size) {
            position += direction
            if (position !in order.indices) {
                if (repeat != RepeatMode.ALL) return null
                position = if (direction > 0) 0 else order.lastIndex
            }
            val track = resolve(reference(order[position])!!)
            if (track.available) return track
        }
        return null
    }
}
