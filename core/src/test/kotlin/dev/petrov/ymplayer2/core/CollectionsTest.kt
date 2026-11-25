package dev.petrov.ymplayer2.core

import org.junit.Assert.*
import org.junit.Test

class CollectionsTest {
    private val catalog = DemoCatalog().tracks("owner").associateBy(Track::id)
    private fun ProfileCollections.edit(change: CollectionEdit) = edited(change, catalog) { "playlist-1" }
    @Test fun editsPreserveOrderAndRejectDuplicateMembers() {
        var data = ProfileCollections().edit(CollectionEdit.Create(" В дорогу "))
        assertEquals("В дорогу", data.playlists.single().name)
        data = data.edit(CollectionEdit.Add("playlist-1", "local:4")).edit(CollectionEdit.Add("playlist-1", "local:5"))
        data = data.edit(CollectionEdit.Add("playlist-1", "local:4")).edit(CollectionEdit.Move("playlist-1", "local:5", 0))
        assertEquals(listOf("local:5", "local:4"), data.playlists.single().tracks.map { it.id })
        assertEquals(data, data.edit(CollectionEdit.Move("playlist-1", "local:5", -1)))
        data = data.edit(CollectionEdit.Rename("playlist-1", "Ночь")).edit(CollectionEdit.Remove("playlist-1", "local:4"))
        assertEquals("Ночь", data.playlists.single().name)
        assertEquals(listOf("local:5"), data.playlists.single().tracks.map { it.id })
    }
    @Test fun invalidNamesAndCloudTracksCannotEnterLocalCollections() {
        val data = ProfileCollections().edit(CollectionEdit.Create("Дорога"))
        listOf(" ", "x".repeat(81), "A\nB", "дорога").forEach { name ->
            assertThrows(IllegalArgumentException::class.java) { data.edit(CollectionEdit.Create(name)) }
        }
        assertThrows(IllegalArgumentException::class.java) { data.edit(CollectionEdit.Add("playlist-1", "missing")) }
        assertThrows(IllegalArgumentException::class.java) { data.edit(CollectionEdit.Favorite("yandex:1", true)) }
        assertEquals(data, data.edit(CollectionEdit.Rename("playlist-1", "Дорога")))
    }
    @Test fun favoritesAndPlaylistsAreIndependentAndRetainUnavailableNames() {
        var data = ProfileCollections().edit(CollectionEdit.Create("USB", "usb:6"))
        data = data.edit(CollectionEdit.Favorite("usb:6", true)).edit(CollectionEdit.Favorite("usb:6", true))
        assertEquals(1, data.favorites.size)
        val ref = data.playlists.single().tracks.single()
        val missing = ref.resolve(emptyMap())
        assertEquals("Оставленный берег", missing.title)
        assertFalse(missing.available); assertNull(missing.uri)
        val restored = catalog.getValue("usb:6").copy(available = true, title = "Обновлённый тег")
        assertEquals(restored, ref.resolve(mapOf(restored.id to restored)))
        data = data.edit(CollectionEdit.Delete("playlist-1"))
        assertEquals(1, data.favorites.size)
        assertTrue(data.edit(CollectionEdit.Favorite("usb:6", false)).favorites.isEmpty())
    }
    @Test fun profileCollectionsDoNotShareMutableLists() {
        val owner = ProfileCollections().edit(CollectionEdit.Create("В дорогу", "local:4"))
        val guest = ProfileCollections().edit(CollectionEdit.Favorite("local:5", true))
        val state = CollectionsState(mapOf("owner" to owner, "guest" to guest))
        val changed = state.copy(profiles = state.profiles + ("owner" to owner.edit(CollectionEdit.Delete("playlist-1"))))
        assertEquals(owner, state.profile("owner")); assertEquals(guest, changed.profile("guest"))
    }
    @Test fun batchAddsKeepSelectionOrderDeduplicateAndRejectWholeInvalidBatch() {
        val data = ProfileCollections().edit(CollectionEdit.CreateMany("Batch", listOf("local:5", "local:4", "local:5")))
        assertEquals(listOf("local:5", "local:4"), data.playlists.single().tracks.map { it.id })
        val changed = data.edit(CollectionEdit.AddMany("playlist-1", listOf("local:4", "usb:3", "usb:3")))
        assertEquals(listOf("local:5", "local:4", "usb:3"), changed.playlists.single().tracks.map { it.id })
        for (ids in listOf(listOf("usb:3", "missing"), listOf("usb:3", "yandex:1"))) {
            assertThrows(IllegalArgumentException::class.java) { data.edit(CollectionEdit.AddMany("playlist-1", ids)) }
        }
        assertEquals(listOf("local:5", "local:4"), data.playlists.single().tracks.map { it.id })
    }
    @Test fun explicitPlaylistPlaybackFiltersUnavailableAndForeignTracks() {
        val player = DemoPlaybackController(DemoCatalog())
        player.switchProfile("guest")
        player.playQueue(listOf("local:5", "yandex:1", "local:4", "local:5", "usb:6"), "local:4")
        assertEquals(listOf("local:5", "local:4"), player.state.value.queue.map { it.id })
        assertEquals("local:4", player.state.value.current?.id); assertTrue(player.state.value.playing)
        val before = player.state.value
        player.playQueue(listOf("usb:6", "missing"))
        assertEquals(before, player.state.value)
    }
    @Test fun reorderOneHundredFiftyMoveGroupUndoAndProtectConcurrentEdits() {
        val songs = (1..150).associate { number ->
            val track = catalog.getValue("local:4").copy(id = "local:$number", title = "Song $number")
            track.id to track
        }
        val ids = songs.keys.toList()
        val moving = ids.subList(119, 130).reversed().toSet()
        val moved = movePlaylistGroup(ids, moving, ids[4])
        assertEquals(ids.subList(119, 130), moved.subList(4, 15))
        assertEquals(ids.filterNot(moving::contains), moved.filterNot(moving::contains))
        val initial = ProfileCollections().edited(CollectionEdit.CreateMany("150", ids), songs) { "p" }
        val result = initial.edited(CollectionEdit.Reorder("p", ids, moved), emptyMap()) { error("new ID") }
        assertEquals(ids, orderedPlaylist(result.playlists.single().tracks, songs, PlaylistOrder.ADDED))
        assertEquals(initial, result.edited(CollectionEdit.Reorder("p", moved, ids), emptyMap()) { error("new ID") })
        val changed = result.edited(CollectionEdit.RemoveMany("p", setOf(ids.last())), emptyMap()) { error("new ID") }
        assertThrows(IllegalArgumentException::class.java) {
            changed.edited(CollectionEdit.Reorder("p", moved, ids), emptyMap()) { error("new ID") }
        }
    }
    @Test fun albumSortUsesDiscAndTrackTagsAndFilesUseNaturalOrder() {
        val prototype = catalog.getValue("local:4").copy(album = "A")
        val songs = listOf(
            prototype.copy(id = "a", fileName = "2.mp3", discNumber = 2, trackNumber = 1),
            prototype.copy(id = "b", fileName = "10.mp3", discNumber = 1, trackNumber = 10),
            prototype.copy(id = "c", fileName = "3.mp3", discNumber = 1, trackNumber = 2))
        val refs = songs.map(Track::saved)
        assertEquals(listOf("c", "b", "a"), orderedPlaylist(refs, songs.associateBy(Track::id), PlaylistOrder.ALBUM))
        assertEquals(listOf("a", "c", "b"), orderedPlaylist(refs, songs.associateBy(Track::id), PlaylistOrder.FILE))
    }
    @Test fun builtInFavoritesSupportsAtomicBatchOrderAndRemoval() {
        val ids = listOf("local:4", "usb:3", "local:5")
        val profile = ProfileCollections().edit(CollectionEdit.FavoriteMany(ids + ids.first()))
        assertEquals(ids, profile.favorites.map(SavedTrack::id))
        val reversed = profile.edit(CollectionEdit.Reorder(null, ids, ids.reversed()))
        assertEquals(ids, orderedPlaylist(reversed.favorites, catalog, PlaylistOrder.ADDED))
        assertTrue(reversed.playlists.isEmpty())
        assertEquals(listOf("usb:3"), reversed.edit(CollectionEdit.RemoveMany(null, setOf("local:4", "local:5"))).favorites.map(SavedTrack::id))
    }
}
