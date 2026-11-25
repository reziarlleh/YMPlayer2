package dev.petrov.ymplayer2.core

enum class PlaylistOrder { TITLE, ARTIST, ALBUM, FILE, ADDED }

/** Stable ties keep the existing order when several local files have the same tags. */
fun orderedPlaylist(rows: List<SavedTrack>, catalog: Map<String, Track>, order: PlaylistOrder): List<String> {
    if (order == PlaylistOrder.ADDED) return rows.sortedBy(SavedTrack::addedOrder).map(SavedTrack::id)
    val tracks = rows.map { it.resolve(catalog) }
    val byTitle = compareBy<Track, String>(mediaFileNameOrder, Track::title)
    val byFile = compareBy<Track, String>(mediaFileNameOrder) { it.fileName ?: it.title }
    val comparator = when (order) {
        PlaylistOrder.TITLE -> byTitle
        PlaylistOrder.ARTIST -> compareBy<Track, String>(mediaFileNameOrder, Track::artist).then(byTitle)
        PlaylistOrder.ALBUM -> compareBy<Track> { it.album.isBlank() }
            .thenBy(mediaFileNameOrder, Track::album)
            .thenBy { it.discNumber ?: 1 }.thenBy { it.trackNumber ?: Int.MAX_VALUE }.then(byFile)
        PlaylistOrder.FILE -> byFile
        PlaylistOrder.ADDED -> error("Handled above")
    }
    return tracks.sortedWith(comparator).map(Track::id)
}

/** Insert the group in its playlist order, independent of the order of checkmarks. Null means end. */
fun movePlaylistGroup(ids: List<String>, moving: Set<String>, before: String?): List<String> {
    if (moving.isEmpty() || before != null && (before in moving || before !in ids)) return ids
    val (group, retained) = ids.partition { it in moving }
    val position = if (before == null) retained.size else retained.indexOf(before)
    return retained.toMutableList().apply { addAll(position, group) }
}
