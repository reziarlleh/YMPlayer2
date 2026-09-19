package dev.petrov.ymplayer2.shell

import androidx.lifecycle.ViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import android.os.Bundle
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import dev.petrov.ymplayer2.core.*

/** Screens depend on contracts; the debug demo supplies its own explicit fixtures. */
class ShellModel(val catalog: Catalog, val player: PlaybackController, savedState: SavedStateHandle, val collections: UserCollections? = null, val accounts: AccountAuth? = null, val online: OnlineMusic? = null, val taste: MusicTaste? = null, val offline: OfflineMusic? = null) : ViewModel() {
    val local get() = catalog as? LocalLibrary
    val library = local?.state ?: kotlinx.coroutines.flow.MutableStateFlow(LibrarySnapshot(ready = true))
    fun addFolder(uri: String, source: Source) { viewModelScope.launch { local?.addFolder(uri, source) } }
    fun refresh() { viewModelScope.launch { local?.refresh() } }
    fun forgetFolder(uri: String) { viewModelScope.launch { local?.forgetFolder(uri) } }
    init {
        accounts?.let { auth -> viewModelScope.launch {
            player.state.map { it.profileId }.distinctUntilChanged().collect(auth::activate)
        } }
        viewModelScope.launch {
            if (local != null) return@launch // Real checkpoints live in the service, never a large Activity Bundle.
            player.state.collect { state ->
                savedState["playback"] = Bundle().apply {
                    putString("profile", state.profileId)
                    putStringArrayList("queue", ArrayList(state.queue.map(Track::id)))
                    putString("current", state.current?.id)
                    putInt("position", state.positionSeconds)
                    putString("repeat", state.repeatMode.name)
                    putBoolean("shuffle", state.shuffle)
                }
            }
        }
    }
}

fun SavedStateHandle.playbackCheckpoint(): PlaybackCheckpoint? = get<Bundle>("playback")?.let {
    PlaybackCheckpoint(it.getString("profile") ?: return null, it.getStringArrayList("queue").orEmpty(), it.getString("current"), it.getInt("position"),
        runCatching { RepeatMode.valueOf(it.getString("repeat").orEmpty()) }.getOrDefault(RepeatMode.OFF), it.getBoolean("shuffle"))
}

enum class CatalogState(val label: String) {
    READY("Загружен"), EMPTY("Пустой"), ERROR("Ошибка"), OFFLINE("Нет сети"),
}
enum class Category(val label: String) {
    TRACKS("Треки"), ALBUMS("Альбомы"), ARTISTS("Исполнители"), GENRES("Жанры"), FOLDERS("Папки"), PLAYLISTS("Плейлисты"),
}
fun Track.group(category: Category): String = when (category) {
    Category.TRACKS -> title
    Category.ALBUMS -> album
    Category.ARTISTS -> artist
    Category.GENRES -> genre
    Category.FOLDERS -> if (source == Source.YANDEX) "Яндекс · коллекция" else folder
    Category.PLAYLISTS -> if (offline) "С собой" else "Открытия"
}
fun secondsLabel(seconds: Int) = "%d:%02d".format(seconds / 60, seconds % 60)
