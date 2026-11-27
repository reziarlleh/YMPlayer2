package dev.petrov.ymplayer2

import dev.petrov.ymplayer2.core.*
import dev.petrov.ymplayer2.localization.*
import dev.petrov.ymplayer2.playback.CarRow
import kotlinx.coroutines.flow.first

internal class CarLocalLibrary(private val app: PlayerApplication) {
    private val player get() = app.playback
    private val profile get() = player.state.value.profileId
    private suspend fun collections() = app.collections.state.first { it.ready }.profile(profile)
    fun song(track: Track, play: () -> Unit) = CarRow(track.id, track.title, track.artist,
        app.carArtwork.uri(track.artworkUri), prepare = { play })

    fun root() = listOf(
        folder("folders", Msg.files_dirs) {
            app.library.state.first { it.ready }
            app.mediaFiles.roots().map { directory(it) }
        },
        folder("favorites", Msg.player_source_favorites) { saved(LocalListTarget.favorites, collections().favorites) },
        folder("lists", Msg.msg_2efea0f8794f) {
            carGroups(collections().playlists) { list ->
                CarRow(list.id, list.name, children = { saved(LocalListTarget.playlist(list.id, list.name), list.tracks) })
            }
        },
        folder("recent", Msg.local_list_history, ::recent),
        folder("tracks", Msg.msg_042185cacc93) { tracks(0) },
    )

    private suspend fun tracks(offset: Int): List<CarRow> {
        app.library.state.first { it.ready }
        val page = app.library.pageTracks(CatalogFilter(availableOnly = true), offset = offset, limit = 30)
        return page.items.map { song -> song(song) { player.select(song.id) } } + listOfNotNull(
            if (page.hasMore) more(offset.toString()) { tracks(offset + page.items.size) } else null)
    }
    suspend fun recent(): List<CarRow> {
        val lists = collections()
        val history = player.state.value.localLists
        return (history.recent.take(1) + history.menu()).distinctBy(LocalListTarget::key).mapNotNull { target ->
            val playlist = target.playlistId?.let { id -> lists.playlists.find { it.id == id } ?: return@mapNotNull null }
            val name = playlist?.name ?: target.title.ifBlank { tr(Msg.player_source_favorites) }
            val members = if (target == LocalListTarget.favorites) lists.favorites else playlist?.tracks
            CarRow(target.key, name, prepare = { { player.resumeLocalList(target, members) } })
        }
    }
    private fun saved(target: LocalListTarget, members: List<SavedTrack>): List<CarRow> {
        val ids = members.map(SavedTrack::id)
        if (ids.isEmpty()) return emptyList()
        return listOf(button("play", Msg.msg_bdd37eb21746) { player.resumeLocalList(target, members) }) +
            carGroups(members) { ref -> CarRow(ref.id, ref.title, ref.artist, prepare = {
                require(app.library.tracksByIds(listOf(ref.id))[ref.id]?.available == true)
                val play: () -> Unit = { player.playList(ids, ref.id, target.origin()) }
                play
            }) }
    }
    private fun directory(entry: MediaFileEntry): CarRow = CarRow(entry.fileKey, entry.name, children = {
        val entries = app.mediaFiles.children(entry).filter(MediaFileEntry::available)
            .sortedWith(compareByDescending<MediaFileEntry> { it.directory }.thenBy(mediaFileNameOrder) { it.name })
        val target = LocalListTarget.folder(entry)
        val playFolder = CarRow("play", tr(Msg.files_play_tree), prepare = {
            val ids = app.mediaFiles.tracks(listOf(entry)).map(Track::id)
            require(ids.isNotEmpty())
            val action: () -> Unit = { player.playList(ids, origin = target.origin()) }
            action
        })
        listOf(playFolder) + carGroups(entries) { file ->
            if (file.directory) directory(file) else CarRow(file.fileKey, file.name, prepare = {
                val tracks = app.mediaFiles.tracks(listOf(entry))
                val id = tracks.first { it.id == file.trackId }.id
                val action: () -> Unit = { player.playList(tracks.map(Track::id), id, target.origin()) }
                action
            })
        }
    })
    suspend fun search(query: String): List<CarRow> {
        val tracks = app.library.pageTracks(CatalogFilter(query = query, availableOnly = true), limit = 30).items
        val ids = tracks.map(Track::id)
        return tracks.map { song -> song(song) { player.playList(ids, song.id, PlaybackOrigin(PlaybackSource.LIST)) } }
    }
}
