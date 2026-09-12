package dev.petrov.ymplayer2.shell

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.petrov.ymplayer2.core.*
import dev.petrov.ymplayer2.designsystem.*
import dev.petrov.ymplayer2.designsystem.skin.*

@Composable internal fun PlayerScreen(state: PlaybackState, player: PlaybackController, wide: Boolean, short: Boolean, queue: () -> Unit, demo: Boolean = true, folders: () -> Unit = {},
    taste: MusicTaste? = null, signIn: () -> Unit = {}) {
    var details by remember(state.current?.id, state.profileId) { mutableStateOf(false) }
    if (details && taste != null) state.current?.let { TrackTasteDialog(it, taste) { details = false } }
    BoxWithConstraints(Modifier.fillMaxSize()) {
    val compact = short || maxHeight < 520.dp || androidx.compose.ui.platform.LocalDensity.current.fontScale > 1.3f
    Row(Modifier.fillMaxSize().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val title: @Composable (Modifier) -> Unit = { Text("Сейчас играет", it, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
                if (taste != null) {
                    val account by taste.state.collectAsStateWithLifecycle()
                    if (maxWidth >= 340.dp && androidx.compose.ui.platform.LocalDensity.current.fontScale <= 1.3f) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        title(Modifier.weight(1f))
                        WaveButton(player, account.signedIn, signIn, modifier = Modifier.widthIn(max = 180.dp))
                    } else Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        title(Modifier)
                        WaveButton(player, account.signedIn, signIn)
                    }
                } else title(Modifier)
            }
            if (compact) TransportControls(state, player, queue)
            if (compact) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    TrackArtwork(state.current, Modifier.size(88.dp))
                    TrackHeading(state.current, Modifier.weight(1f), taste)
                }
            } else {
                BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    TrackArtwork(state.current, Modifier.size(minOf(maxWidth, if (wide) 280.dp else if (taste != null && state.current?.source == Source.YANDEX) 160.dp else 208.dp)))
                }
                TrackHeading(state.current, taste = taste)
            }
            Column {
                Slider(state.positionSeconds.toFloat(), { player.seek(it.toInt()) }, Modifier.fillMaxWidth().testTag("progress"), enabled = state.current != null,
                    valueRange = 0f..(state.current?.durationSeconds ?: 1).coerceAtLeast(1).toFloat())
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(secondsLabel(state.positionSeconds), style = MaterialTheme.typography.labelMedium, modifier = Modifier.testTag("position"))
                    Text(secondsLabel(state.current?.durationSeconds ?: 0), style = MaterialTheme.typography.labelMedium)
                }
            }
            if (!compact) TransportControls(state, player, queue)
            if (state.wave) Text("Моя волна · рекомендации Яндекса", color = MaterialTheme.colorScheme.primary, modifier = Modifier.testTag("wave_mode"))
            else PlaybackModes(state, player)
            if (state.waveLoading) Text("Подбираем следующий трек…", Modifier.testTag("wave_loading"))
            state.waveIssue?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
                OutlinedButton(player::retryWave, Modifier.prismFocus().testTag("wave_retry")) { Text("Продолжить волну") }
            }
            if (taste != null && state.current?.source == Source.YANDEX) {
                TextButton({ details = true }, Modifier.prismFocus().testTag("player_artist_actions")) { Text("Исполнители и альбом · отметки") }
            }
            if (state.buffering) Text("Подготовка аудио…", color = MaterialTheme.colorScheme.primary)
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            SourceSelector(player, state.profileId == "guest" || !demo)
            if (demo) Text("Демонстрационные данные. Play меняет состояние; звук и ход времени пока не подключены.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            else {
                OutlinedButton(folders, Modifier.prismFocus().testTag("manage_folders")) { SkinIcon(UiIcon.FOLDER, null); Spacer(Modifier.width(8.dp)); Text("Папки с музыкой") }
                if (state.current == null) Text("Добавьте папку с музыкой или выберите трек в медиатеке.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(8.dp))
        }
        if (wide) Column(Modifier.width(330.dp).fillMaxHeight()) { QueueScreen(state, player, taste) }
    }
    }
}

@Composable private fun TransportControls(state: PlaybackState, player: PlaybackController, queue: () -> Unit) {
    val focus = remember { FocusRequester() }
    val isTv = LocalConfiguration.current.uiMode and android.content.res.Configuration.UI_MODE_TYPE_MASK == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
    LaunchedEffect(state.current?.available) { if (isTv && state.current?.available == true) focus.requestFocus() }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        ActionIcon(UiIcon.PREVIOUS, "Предыдущий трек", { player.skip(-1) })
        ActionIcon(if (state.playing) UiIcon.PAUSE else UiIcon.PLAY, if (state.playing) "Пауза" else "Воспроизвести", player::toggle,
            Modifier.testTag("player_play").focusRequester(focus), state.current?.available == true || state.waveLoading, primary = true)
        ActionIcon(UiIcon.NEXT, "Следующий трек", { player.skip(1) })
        ActionIcon(UiIcon.STOP, "Остановить", player::stop)
        ActionIcon(UiIcon.QUEUE, "Очередь", queue)
    }
}

@Composable private fun TrackHeading(track: Track?, modifier: Modifier = Modifier, taste: MusicTaste? = null) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val title: @Composable () -> Unit = {
            Text(track?.title ?: "Очередь пуста", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("current_title"))
        }
        if (taste != null && track?.source == Source.YANDEX) {
            TasteLabel(taste, track.tasteTarget(), "player", title)
            ArtistTasteLabels(track, taste, "player")
        } else {
            title()
            Text(track?.artist ?: "Выберите источник", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Text(listOfNotNull(track?.source?.label, track?.let { if (it.offline) "Доступно офлайн" else "Онлайн" }).joinToString(" · "), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable private fun PlaybackModes(state: PlaybackState, player: PlaybackController) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FilterChip(state.repeatMode != RepeatMode.OFF, { player.setRepeatMode(state.repeatMode.next()) },
            { Text(when (state.repeatMode) { RepeatMode.OFF -> "Повтор выключен"; RepeatMode.ALL -> "Повтор очереди"; RepeatMode.ONE -> "Повтор трека" }) },
            Modifier.heightIn(min = 48.dp).prismFocus().testTag("repeat_mode"),
            leadingIcon = { SkinIcon(if (state.repeatMode == RepeatMode.ONE) UiIcon.REPEAT_ONE else UiIcon.REPEAT, null) })
        FilterChip(state.shuffle, { player.setShuffle(!state.shuffle) }, { Text(if (state.shuffle) "В случайном порядке" else "По порядку") },
            Modifier.heightIn(min = 48.dp).prismFocus().testTag("shuffle_mode"), leadingIcon = { SkinIcon(UiIcon.SHUFFLE, null) })
    }
}

@Composable private fun SourceSelector(player: PlaybackController, guest: Boolean) {
    var menu by remember { mutableStateOf(false) }
    Box {
        OutlinedButton({ menu = true }, Modifier.prismFocus().testTag("playback_source")) { Text("Источник воспроизведения"); SkinIcon(UiIcon.EXPAND, null) }
        DropdownMenu(menu, { menu = false }) {
            (listOf<Source?>(null) + Source.entries.filter { !guest || it != Source.YANDEX }).forEach { source ->
                DropdownMenuItem({ Text(source?.label ?: "Вся коллекция") }, { player.chooseSource(source); menu = false })
            }
        }
    }
}

@Composable internal fun QueueScreen(state: PlaybackState, player: PlaybackController, taste: MusicTaste? = null) {
    var editing by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
        Text("Очередь · ${state.queue.size}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (state.shuffle) "Случайный порядок включён" else "Выбор трека запускает его", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ActionIcon(if (editing) UiIcon.CHECK else UiIcon.EDIT, if (editing) "Завершить редактирование" else "Изменить очередь", { editing = !editing }, Modifier.testTag("queue_edit"), enabled = state.queue.isNotEmpty())
        }
        if (editing && state.queue.isNotEmpty()) {
            Text("Удаление текущего трека ставит воспроизведение на паузу.", style = MaterialTheme.typography.bodySmall)
            TextButton({ player.clearQueue(); editing = false }, Modifier.heightIn(min = 48.dp).prismFocus().testTag("queue_clear")) {
                SkinIcon(UiIcon.CLEAR_QUEUE, null); Spacer(Modifier.width(8.dp)); Text("Очистить очередь")
            }
        }
        if (state.queue.isEmpty()) Text("Добавьте треки кнопкой «В очередь» в медиатеке.", Modifier.padding(vertical = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyColumn(Modifier.fillMaxWidth().weight(1f).testTag("queue_list"), contentPadding = PaddingValues(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(state.queue.size, key = { state.queue[it].id }) { index ->
                val track = state.queue[index]
                Column {
                    TrackRow(track, state.current?.id == track.id, { player.select(track.id) }, taste = taste, location = "queue")
                    if (editing) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        ActionIcon(UiIcon.UP, "Выше: ${track.title}", { player.moveInQueue(track.id, index - 1) }, Modifier.testTag("queue_up_${track.id}"), enabled = index > 0)
                        ActionIcon(UiIcon.DOWN, "Ниже: ${track.title}", { player.moveInQueue(track.id, index + 1) }, Modifier.testTag("queue_down_${track.id}"), enabled = index < state.queue.lastIndex)
                        ActionIcon(UiIcon.REMOVE, "Удалить из очереди: ${track.title}", { player.removeFromQueue(track.id) }, Modifier.testTag("queue_remove_${track.id}"))
                    }
                }
            }
        }
    }
}

@Composable internal fun TrackRow(track: Track, selected: Boolean = false, play: () -> Unit, enqueue: (() -> Unit)? = null, queued: Boolean = false, more: (() -> Unit)? = null,
    taste: MusicTaste? = null, location: String = "catalog") {
    val onlineTaste = taste?.takeIf { track.source == Source.YANDEX }
    val actions: @Composable () -> Unit = {
        if (enqueue != null) ActionIcon(if (queued) UiIcon.CHECK else UiIcon.ADD_QUEUE, if (queued) "Уже в очереди: ${track.title}" else "В очередь: ${track.title}", enqueue,
            Modifier.testTag("enqueue_${track.id}"), enabled = track.available && !queued)
        if (more != null) ActionIcon(UiIcon.MORE, "Действия: ${track.title}", more, Modifier.testTag("track_more_${track.id}"))
    }
    Column(Modifier.fillMaxWidth()) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
    Surface(onClick = play, enabled = track.available, modifier = Modifier.weight(1f).prismFocus().testTag("track_${track.id}"),
        shape = MaterialTheme.shapes.medium, color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TrackArtwork(track, Modifier.size(44.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(track.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                if (onlineTaste == null) Text(track.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(if (track.available) "${track.source.label} · ${secondsLabel(track.durationSeconds)}" else if (track.source == Source.YANDEX) "Трек недоступен" else "Файл недоступен", style = MaterialTheme.typography.labelSmall, color = if (track.available) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
            }
            SkinIcon(if (selected) UiIcon.NOW_PLAYING else UiIcon.PLAY, if (selected) "Текущий трек" else "Воспроизвести трек", Modifier.size(24.dp))
        }
    }
    if (onlineTaste != null) TasteIcons(onlineTaste, track.tasteTarget(), location)
    else actions()
    }
    if (onlineTaste != null) {
        val shelf = onlineTaste.state.collectAsStateWithLifecycle().value.shelf(TasteKind.TRACK)
        if (shelf.issue != null) TextButton({ onlineTaste.refresh(TasteKind.TRACK) }, Modifier.prismFocus(), enabled = !shelf.busy) { Text("Обновить отметки трека", color = MaterialTheme.colorScheme.error) }
        Column(Modifier.padding(start = 12.dp)) { ArtistTasteLabels(track, onlineTaste, "${location}_${track.id}") }
    }
    if (onlineTaste != null && (enqueue != null || more != null)) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { actions() }
    }
}

@Composable private fun ArtistTasteLabels(track: Track, taste: MusicTaste, location: String) {
    if (track.artists.isEmpty()) Text(track.artist, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    track.artists.distinctBy(ArtistRef::id).forEach { artist ->
        TasteLabel(taste, TasteTarget(TasteKind.ARTIST, artist.id, artist.name), location) {
            Column {
                Text("Исполнитель", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(artist.name, style = MaterialTheme.typography.bodyLarge, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
