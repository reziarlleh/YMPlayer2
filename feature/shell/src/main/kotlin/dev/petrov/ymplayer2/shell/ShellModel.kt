package dev.petrov.ymplayer2.shell

import androidx.lifecycle.ViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import android.os.Bundle
import kotlinx.coroutines.launch
import dev.petrov.ymplayer2.core.*

/** The application injects the demo implementation; screens depend on contracts. */
class ShellModel(val catalog: Catalog, val player: PlaybackController, savedState: SavedStateHandle) : ViewModel() {
    init {
        viewModelScope.launch {
            player.state.collect { state ->
                savedState["playback"] = Bundle().apply {
                    putString("profile", state.profileId)
                    putStringArrayList("queue", ArrayList(state.queue.map(Track::id)))
                    putString("current", state.current?.id)
                    putInt("position", state.positionSeconds)
                }
            }
        }
    }
}

fun SavedStateHandle.playbackCheckpoint(): PlaybackCheckpoint? = get<Bundle>("playback")?.let {
    PlaybackCheckpoint(it.getString("profile") ?: return null, it.getStringArrayList("queue").orEmpty(), it.getString("current"), it.getInt("position"))
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
