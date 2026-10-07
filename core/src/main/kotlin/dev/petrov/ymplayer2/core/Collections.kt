package dev.petrov.ymplayer2.core

import kotlinx.coroutines.flow.StateFlow

/** A reference, never a copy of audio or a playable URI from stale metadata. */
data class SavedTrack(val id: String, val title: String, val artist: String, val source: Source, val duration: Int, val tint: Int) {
    fun resolve(catalog: Map<String, Track>): Track = catalog[id] ?: Track(id, title, artist, "", source, duration,
        offline = true, available = false, tint = tint, genre = "")
}
fun Track.saved() = SavedTrack(id, title, artist, source, durationSeconds, tint)
data class LocalPlaylist(val id: String, val name: String, val tracks: List<SavedTrack> = emptyList())
data class ProfileCollections(val playlists: List<LocalPlaylist> = emptyList(), val favorites: List<SavedTrack> = emptyList())
data class CollectionsState(val profiles: Map<String, ProfileCollections> = emptyMap(), val ready: Boolean = false,
    val writable: Boolean = false, val issue: String? = null) {
    fun profile(id: String) = profiles[id] ?: ProfileCollections()
}
sealed interface CollectionEdit {
    data class Create(val name: String, val initialTrackId: String? = null) : CollectionEdit
    data class Rename(val playlistId: String, val name: String) : CollectionEdit
    data class Delete(val playlistId: String) : CollectionEdit
    data class Add(val playlistId: String, val trackId: String) : CollectionEdit
    data class AddMany(val playlistId: String, val trackIds: List<String>) : CollectionEdit
    data class CreateMany(val name: String, val trackIds: List<String>) : CollectionEdit
    data class Remove(val playlistId: String, val trackId: String) : CollectionEdit
    data class Move(val playlistId: String, val trackId: String, val toIndex: Int) : CollectionEdit
    data class Favorite(val trackId: String, val selected: Boolean) : CollectionEdit
}
interface UserCollections {
    val state: StateFlow<CollectionsState>
    suspend fun edit(profileId: String, change: CollectionEdit): Boolean
    suspend fun reload()
}

/** Pure transition: persistence commits this result before publishing it. */
fun ProfileCollections.edited(change: CollectionEdit, catalog: Map<String, Track>, newId: () -> String): ProfileCollections {
    fun reference(id: String) = requireNotNull(catalog[id]?.takeIf { it.source != Source.YANDEX }) { "Трек отсутствует в локальном каталоге" }.saved()
    fun name(value: String, except: String? = null): String {
        val clean = value.trim()
        require(clean.isNotEmpty() && clean.length <= 80 && clean.none(Char::isISOControl)) { "Название должно содержать от 1 до 80 символов" }
        require(playlists.none { it.id != except && it.name.equals(clean, true) }) { "Плейлист с таким названием уже есть" }
        return clean
    }
    fun update(id: String, transform: (LocalPlaylist) -> LocalPlaylist): ProfileCollections {
        require(playlists.any { it.id == id }) { "Плейлист уже удалён" }
        return copy(playlists = playlists.map { if (it.id == id) transform(it) else it })
    }
    return when (change) {
        is CollectionEdit.Create -> copy(playlists = playlists + LocalPlaylist(newId(), name(change.name), listOfNotNull(change.initialTrackId?.let(::reference))))
        is CollectionEdit.Rename -> update(change.playlistId) { it.copy(name = name(change.name, it.id)) }
        is CollectionEdit.Delete -> copy(playlists = playlists.filterNot { it.id == change.playlistId })
        is CollectionEdit.Add -> update(change.playlistId) { list ->
            if (list.tracks.any { it.id == change.trackId }) list else list.copy(tracks = list.tracks + reference(change.trackId))
        }
        is CollectionEdit.AddMany -> {
            // Validate the entire input before committing, including IDs already in the list.
            val refs = change.trackIds.distinct().map(::reference)
            update(change.playlistId) { list -> list.copy(tracks = (list.tracks + refs).distinctBy(SavedTrack::id)) }
        }
        is CollectionEdit.CreateMany -> copy(playlists = playlists + LocalPlaylist(newId(), name(change.name), change.trackIds.distinct().map(::reference)))
        is CollectionEdit.Remove -> update(change.playlistId) { it.copy(tracks = it.tracks.filterNot { track -> track.id == change.trackId }) }
        is CollectionEdit.Move -> update(change.playlistId) { list ->
            val from = list.tracks.indexOfFirst { it.id == change.trackId }
            if (from < 0 || change.toIndex !in list.tracks.indices) list
            else list.copy(tracks = list.tracks.toMutableList().apply { add(change.toIndex, removeAt(from)) })
        }
        is CollectionEdit.Favorite -> if (!change.selected) copy(favorites = favorites.filterNot { it.id == change.trackId })
            else if (favorites.any { it.id == change.trackId }) this else copy(favorites = favorites + reference(change.trackId))
    }
}
