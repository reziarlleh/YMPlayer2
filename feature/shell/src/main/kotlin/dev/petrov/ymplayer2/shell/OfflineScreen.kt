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

@Composable internal fun OfflineScreen(offline: OfflineMusic, player: PlaybackController, start: () -> Unit, account: () -> Unit) {
    val state by offline.state.collectAsStateWithLifecycle()
    var confirmClear by remember(state.owner) { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize().testTag("offline_list"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Мне нравится · офлайн", style = MaterialTheme.typography.headlineSmall) }
        item { Text("Сохраняются только понравившиеся треки и их обложки. Любимые исполнители и альбомы целиком не загружаются.") }
        if (state.owner == null) {
            item { Text("Войдите в Яндекс в этом профиле. Если вход уже сохранён, обновите сведения об аккаунте.") }
            item { Button(account, Modifier.prismFocus()) { Text("Открыть аккаунт") } }
        } else {
            item { Text("Сохранено: ${state.tracks.size} · ${state.bytes / (1024 * 1024)} МБ", Modifier.testTag("offline_summary")) }
            item { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Загружать только по Wi-Fi", Modifier.weight(1f))
                Switch(state.wifiOnly, offline::setWifiOnly, Modifier.prismFocus().testTag("offline_wifi"))
            } }
            item {
                if (state.running) OutlinedButton(offline::cancel, Modifier.fillMaxWidth().prismFocus().testTag("offline_cancel")) { Text("Остановить синхронизацию") }
                else Button(start, Modifier.fillMaxWidth().prismFocus().testTag("offline_sync"), enabled = state.ready) { Text("Синхронизировать «Мне нравится»") }
            }
            if (!state.ready || state.running) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            state.message?.let { message -> item { Text(message, Modifier.testTag("offline_status")) } }
            if (state.audioFailures + state.coverFailures + state.noCover > 0) item {
                Text("Не загружено аудио: ${state.audioFailures}. Ошибки обложек: ${state.coverFailures}. Без обложки у источника: ${state.noCover}. Повторная синхронизация проверит и восстановит недостающее.")
            }
            item { TextButton({ confirmClear = true }, Modifier.prismFocus().testTag("offline_clear"), enabled = state.ready) { Text("Удалить офлайн-файлы") } }
            if (state.tracks.isNotEmpty()) {
                item { OutlinedButton({ player.playQueue(state.tracks.map(Track::id)) }, Modifier.prismFocus().testTag("offline_play_all")) { Text("Слушать сохранённое") } }
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
            } else if (state.ready && !state.running) item { Text("Сохранённых треков пока нет. Запустите синхронизацию при доступной сети.") }
        }
    }
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false }, title = { Text("Удалить офлайн-файлы?") },
        text = { Text("Будут удалены аудио и обложки этого аккаунта в текущем профиле. Лайки в Яндексе сохранятся.") },
        confirmButton = { TextButton({ confirmClear = false; offline.clear() }, Modifier.testTag("offline_clear_confirm")) { Text("Удалить файлы") } },
        dismissButton = { TextButton({ confirmClear = false }) { Text("Отмена") } })
}
