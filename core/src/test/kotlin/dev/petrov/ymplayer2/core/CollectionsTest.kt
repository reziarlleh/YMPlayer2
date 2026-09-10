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
}
