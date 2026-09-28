package dev.petrov.ymplayer2.core

import kotlinx.coroutines.flow.StateFlow

enum class Source(val label: String) { YANDEX("Яндекс"), LOCAL("Устройство"), USB("USB") }

data class ArtistRef(val id: String, val name: String)

data class Track(
    val id: String, val title: String, val artist: String, val album: String,
    val source: Source, val durationSeconds: Int, val offline: Boolean,
    val available: Boolean = true, val genre: String = "Электроника",
    val folder: String = "Музыка", val tint: Int = 0,
    val uri: String? = null, val rootId: String? = null,
    val sizeBytes: Long = 0, val modifiedMillis: Long = 0,
    val artworkUri: String? = null,
    val artists: List<ArtistRef> = emptyList(), val albumId: String? = null,
)

data class Profile(val id: String, val name: String, val description: String, val guest: Boolean = false)

interface Catalog {
    val profiles: List<Profile>
    fun tracks(profileId: String): List<Track>
}

data class LibraryRoot(val uri: String, val name: String, val source: Source, val issue: String? = null)
data class LibrarySnapshot(
    val roots: List<LibraryRoot> = emptyList(), val tracks: List<Track> = emptyList(),
    val scanning: Boolean = false, val ready: Boolean = false, val issue: String? = null,
)
interface LocalLibrary : Catalog {
    val state: StateFlow<LibrarySnapshot>
    suspend fun addFolder(uri: String, source: Source)
    suspend fun refresh()
    suspend fun forgetFolder(uri: String)
}

/** Optional disk-backed metadata queries; playback may still use [LocalLibrary.state]. */
interface IndexedLocalLibrary : LocalLibrary {
    val indexRevision: Long
    suspend fun pageTracks(filter: CatalogFilter, descending: Boolean = false, group: String? = null,
        dimension: CatalogDimension = CatalogDimension.TRACKS, offset: Int = 0, limit: Int = 80): CatalogPage<Track>
    suspend fun pageGroups(filter: CatalogFilter, dimension: CatalogDimension,
        descending: Boolean = false, offset: Int = 0, limit: Int = 80): CatalogPage<CatalogGroup>
}

enum class CatalogDimension { TRACKS, ALBUMS, ARTISTS, GENRES, FOLDERS }

/** Fictional, explicit fixtures. No provider, filesystem or audio access. */
class DemoCatalog : Catalog {
    override val profiles = listOf(
        Profile("owner", "Основной", "Демонстрационный профиль"),
        Profile("road", "В дороге", "Отдельная очередь и медиатека"),
        Profile("guest", "Гость", "Только локальный каталог", guest = true),
    )
    private val collection = listOf(
        Track("yandex:1", "Вечерний маршрут", "Северный свет", "Город после заката", Source.YANDEX, 238, true, tint = 0),
        Track("yandex:2", "Тихие улицы", "Параллели", "Город после заката", Source.YANDEX, 194, false, tint = 1),
        Track("usb:3", "Сквозь огни", "Эхо города", "Дальний свет", Source.USB, 263, true, folder = "USB / В дорогу", tint = 2),
        Track("local:4", "После дождя", "Тёплый воздух", "Пауза", Source.LOCAL, 215, true, genre = "Эмбиент", tint = 3),
        Track("local:5", "Очень длинное название: когда город засыпает, а дорога продолжается", "Оркестр далёких улиц и ночных поездов", "Обратная сторона города", Source.LOCAL, 321, true, genre = "Инструментальная", tint = 4),
        Track("usb:6", "Оставленный берег", "Эхо города", "Дальний свет", Source.USB, 187, true, available = false, folder = "USB / Архив", tint = 5),
    )
    override fun tracks(profileId: String): List<Track> = when (profileId) {
        "guest" -> collection.filter { it.source != Source.YANDEX }
        "road" -> collection.filter { it.offline }
        "owner" -> collection
        else -> emptyList()
    }
}
