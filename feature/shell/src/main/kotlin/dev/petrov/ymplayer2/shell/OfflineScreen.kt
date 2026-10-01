package dev.petrov.ymplayer2.shell

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.petrov.ymplayer2.core.*
import dev.petrov.ymplayer2.designsystem.prismFocus

@Composable internal fun OfflineScreen(offline: OfflineMusic, player: PlaybackController) {
    val state by offline.state.collectAsStateWithLifecycle()
    LazyColumn(Modifier.fillMaxSize().testTag("offline_list"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Мне нравится · офлайн", style = MaterialTheme.typography.headlineSmall) }
        if (state.tracks.isNotEmpty()) {
            item { Text("Сохранено: ${state.tracks.size} · ${state.bytes / (1024 * 1024)} МБ", Modifier.testTag("offline_summary")) }
            item { OutlinedButton({ player.playQueue(state.tracks.map(Track::id)) }, Modifier.fillMaxWidth().prismFocus().testTag("offline_play_all")) { Text("Слушать сохранённое") } }
            items(state.tracks, key = Track::id) { track ->
                Surface(onClick = { player.playQueue(state.tracks.map(Track::id), track.id) }, modifier = Modifier.fillMaxWidth().prismFocus().testTag("offline_track_${track.id}"), shape = MaterialTheme.shapes.medium) {
                    Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        TrackArtwork(track, Modifier.size(56.dp))
                        Column(Modifier.weight(1f)) {
                            Text(track.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(track.artist, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        Text(secondsLabel(track.durationSeconds), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        } else item {
            Text(when {
                !state.enabled -> "Офлайн-кэш выключен. Его можно включить в настройках приложения."
                state.owner == null -> "В этом профиле нет сохранённой музыки. Для синхронизации нужен вход в Яндекс."
                !state.ready || state.running -> "Сохранённая музыка загружается…"
                else -> "Сохранённых треков пока нет. Синхронизация доступна в настройках офлайн-кэша."
            }, Modifier.testTag("offline_empty"))
        }
    }
}
