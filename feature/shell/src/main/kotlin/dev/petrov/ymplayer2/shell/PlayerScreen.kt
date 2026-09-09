package dev.petrov.ymplayer2.shell

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.petrov.ymplayer2.core.*
import dev.petrov.ymplayer2.designsystem.*

@Composable internal fun PlayerScreen(state: PlaybackState, player: PlaybackController, wide: Boolean, short: Boolean, queue: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
    val compact = short || maxHeight < 520.dp || androidx.compose.ui.platform.LocalDensity.current.fontScale > 1.3f
    Row(Modifier.fillMaxSize().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Сейчас играет", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            if (compact) TransportControls(state, player, queue)
            if (compact) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    DemoArtwork(state.current?.tint ?: 0, Modifier.size(88.dp))
                    TrackHeading(state.current, Modifier.weight(1f))
                }
            } else {
                BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    DemoArtwork(state.current?.tint ?: 0, Modifier.size(minOf(maxWidth, if (wide) 280.dp else 208.dp)))
                }
                TrackHeading(state.current)
            }
            Column {
                Slider(state.positionSeconds.toFloat(), { player.seek(it.toInt()) }, Modifier.fillMaxWidth().testTag("progress"), enabled = state.current != null,
                    valueRange = 0f..(state.current?.durationSeconds ?: 1).toFloat())
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(secondsLabel(state.positionSeconds), style = MaterialTheme.typography.labelMedium, modifier = Modifier.testTag("position"))
                    Text(secondsLabel(state.current?.durationSeconds ?: 0), style = MaterialTheme.typography.labelMedium)
                }
            }
            if (!compact) TransportControls(state, player, queue)
            SourceSelector(player, state.profileId == "guest")
            Text("Демонстрационные данные. Play меняет состояние; звук и ход времени пока не подключены.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
        }
        if (wide) Column(Modifier.width(330.dp).fillMaxHeight()) { QueueScreen(state, player) }
    }
    }
}

@Composable private fun TransportControls(state: PlaybackState, player: PlaybackController, queue: () -> Unit) {
    val focus = remember { FocusRequester() }
    val isTv = LocalConfiguration.current.uiMode and android.content.res.Configuration.UI_MODE_TYPE_MASK == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
    LaunchedEffect(Unit) { if (isTv) focus.requestFocus() }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        ActionIcon(Icons.Default.SkipPrevious, "Предыдущий трек", { player.skip(-1) })
        ActionIcon(if (state.playing) Icons.Default.Pause else Icons.Default.PlayArrow, if (state.playing) "Пауза" else "Воспроизвести", player::toggle,
            Modifier.testTag("player_play").focusRequester(focus), state.current?.available == true, primary = true)
        ActionIcon(Icons.Default.SkipNext, "Следующий трек", { player.skip(1) })
        ActionIcon(Icons.Default.Stop, "Остановить", player::stop)
        ActionIcon(Icons.AutoMirrored.Filled.QueueMusic, "Очередь", queue)
    }
}

@Composable private fun TrackHeading(track: Track?, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(track?.title ?: "Очередь пуста", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("current_title"))
        Text(track?.artist ?: "Выберите источник", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(listOfNotNull(track?.source?.label, track?.let { if (it.offline) "Доступно офлайн" else "Онлайн" }).joinToString(" · "), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable private fun SourceSelector(player: PlaybackController, guest: Boolean) {
    var menu by remember { mutableStateOf(false) }
    Box {
        OutlinedButton({ menu = true }, Modifier.prismFocus().testTag("playback_source")) { Text("Источник воспроизведения"); Icon(Icons.Default.ExpandMore, null) }
        DropdownMenu(menu, { menu = false }) {
            (listOf<Source?>(null) + Source.entries.filter { !guest || it != Source.YANDEX }).forEach { source ->
                DropdownMenuItem({ Text(source?.label ?: "Вся коллекция") }, { player.chooseSource(source); menu = false })
            }
        }
    }
}

@Composable internal fun QueueScreen(state: PlaybackState, player: PlaybackController) {
    Column(Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
        Text("Очередь · ${state.queue.size}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 12.dp))
        Text("Выбор трека запускает его", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyColumn(Modifier.fillMaxWidth().weight(1f).testTag("queue_list"), contentPadding = PaddingValues(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(state.queue, key = { it.id }) { track -> TrackRow(track, state.current?.id == track.id, { player.select(track.id) }) }
        }
    }
}

@Composable internal fun TrackRow(track: Track, selected: Boolean = false, play: () -> Unit) {
    Surface(onClick = play, enabled = track.available, modifier = Modifier.fillMaxWidth().prismFocus().testTag("track_${track.id}"),
        shape = MaterialTheme.shapes.medium, color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            DemoArtwork(track.tint, Modifier.size(44.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(track.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                Text(track.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(if (track.available) "${track.source.label} · ${secondsLabel(track.durationSeconds)}" else "USB недоступен", style = MaterialTheme.typography.labelSmall, color = if (track.available) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
            }
            Icon(if (selected) Icons.Default.GraphicEq else Icons.Default.PlayArrow, if (selected) "Текущий трек" else "Воспроизвести трек", Modifier.size(24.dp))
        }
    }
}
