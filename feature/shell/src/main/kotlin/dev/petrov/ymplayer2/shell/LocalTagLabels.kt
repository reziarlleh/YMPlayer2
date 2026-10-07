package dev.petrov.ymplayer2.shell

import androidx.compose.runtime.Composable
import dev.petrov.ymplayer2.core.*
import dev.petrov.ymplayer2.localization.trMessage

@Composable internal fun Track.artistLabel(): String =
    if (source != Source.YANDEX && artist.isBlank()) trMessage("Неизвестный исполнитель") else artist

@Composable internal fun localGroupLabel(name: String, category: Category): String =
    if (name.isNotBlank()) name else when (category) {
        Category.ARTISTS -> trMessage("Неизвестный исполнитель")
        Category.ALBUMS -> trMessage("Без альбома")
        Category.GENRES -> trMessage("Без жанра")
        else -> name
    }
