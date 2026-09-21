package dev.petrov.ymplayer2.shell

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.petrov.ymplayer2.core.*
import dev.petrov.ymplayer2.designsystem.*
import dev.petrov.ymplayer2.designsystem.skin.*

/** Only artwork is flexible. Transport and actions never live in a scroll container. */
@Composable internal fun PlayerScreen(state: PlaybackState, player: PlaybackController, wide: Boolean, short: Boolean, queue: () -> Unit, demo: Boolean = true, folders: () -> Unit = {},
    taste: MusicTaste? = null, signIn: () -> Unit = {}, artist: (ArtistRef) -> Unit = {}, equalizer: (Boolean) -> Unit = {}, playlists: CloudPlaylists? = null) {
    var details by remember(state.current?.id, state.profileId) { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var issue by remember { mutableStateOf(false) }
    if (details && taste != null) state.current?.let { TrackTasteDialog(it, taste, artist = artist, extra = {
        playlists?.let { lists -> AddToCloudPlaylist(state.current!!, lists) { details = false } }
        HorizontalDivider()
        TextButton({ details = false; menu = true }, Modifier.prismFocus().testTag("player_sources")) { Text("Источник и папки") }
        TextButton({ details = false; equalizer(true) }, Modifier.prismFocus()) { Text("Выбрать эквалайзер / DSP") }
    }) { details = false } }
    val message = state.waveIssue ?: state.error
    if (issue) AlertDialog(onDismissRequest = { issue = false }, title = { Text("Воспроизведение") },
        text = { Text(message ?: if (state.waveLoading) "Подбираем следующий трек…" else "Подготовка аудио…") },
        confirmButton = { TextButton({ issue = false }) { Text("Закрыть") } })
    val account = taste?.state?.collectAsStateWithLifecycle()?.value
    val toolbar: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (taste != null) Button({ if (account?.signedIn == true) player.playMyWave() else signIn() },
                Modifier.weight(1f).height(48.dp).prismFocus().testTag("my_wave"), enabled = !state.waveLoading,
                contentPadding = PaddingValues(horizontal = 10.dp)) {
                SkinIcon(UiIcon.WAVE, null); Spacer(Modifier.width(6.dp))
                Text("Моя волна", maxLines = 1, overflow = TextOverflow.Ellipsis)
            } else Text("Сейчас играет", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 1)
            ActionIcon(UiIcon.EQUALIZER, "Эквалайзер / DSP", { equalizer(false) }, Modifier.testTag("player_equalizer"))
            ActionIcon(UiIcon.QUEUE, "Очередь", queue, Modifier.testTag("player_queue"))
        }
    }
    val actions: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            if (taste != null && state.current?.source == Source.YANDEX) TasteIcons(taste, state.current!!.tasteTarget(), "player")
            val shelf = account?.shelf(TasteKind.TRACK)
            if (shelf?.issue != null && state.current?.source == Source.YANDEX) ActionIcon(UiIcon.REFRESH, "Обновить отметки трека", { taste?.refresh(TasteKind.TRACK) },
                Modifier.testTag("player_taste_TRACK_${state.current!!.tasteTarget().key}_retry"), enabled = !shelf.busy)
            else if (message != null || state.waveLoading || state.buffering) ActionIcon(if (message != null) UiIcon.REFRESH else UiIcon.UNKNOWN,
                message ?: "Подготовка аудио", { if (state.waveIssue != null) player.retryWave() else issue = true },
                Modifier.testTag(if (state.waveIssue != null) "wave_retry" else if (state.waveLoading) "wave_loading" else "player_issue"))
            Box {
                ActionIcon(UiIcon.MORE, "Дополнительные действия", { if (taste != null && state.current?.source == Source.YANDEX) details = true else menu = true }, Modifier.testTag("player_more"))
                DropdownMenu(menu, { menu = false }) {
                    if (taste != null && state.current?.source == Source.YANDEX) DropdownMenuItem({ Text("Исполнители и альбом") }, { menu = false; details = true }, Modifier.testTag("player_artist_actions"))
                    DropdownMenuItem({ Text("Источник воспроизведения") }, {}, enabled = false)
                    (listOf<Source?>(null) + Source.entries.filter { state.profileId != "guest" && demo || it != Source.YANDEX }).forEach { source ->
                        DropdownMenuItem({ Text(source?.label ?: "Вся коллекция") }, { menu = false; player.chooseSource(source) }, Modifier.testTag("playback_source_${source?.name ?: "ALL"}"))
                    }
                    if (!demo) DropdownMenuItem({ Text("Папки с музыкой") }, { menu = false; folders() }, Modifier.testTag("manage_folders"))
                    DropdownMenuItem({ Text("Выбрать эквалайзер / DSP") }, { menu = false; equalizer(true) })
                }
            }
            Spacer(Modifier.weight(1f))
            if (state.wave) SkinIcon(UiIcon.WAVE, "Моя волна", Modifier.size(28.dp).testTag("wave_mode"))
            else if (state.supportsQueueOrdering) PlaybackModes(state, player)
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize().testTag("player_viewport").padding(horizontal = 16.dp, vertical = 4.dp)) {
        val landscape = maxWidth >= 520.dp && maxWidth > maxHeight * 1.25f
        val compact = maxHeight < 440.dp || LocalDensity.current.fontScale > 1.3f
        val veryShort = maxHeight < 300.dp
        val heading: @Composable () -> Unit = { TrackHeading(state.current, artist = artist, compact = compact,
            status = message ?: if (state.waveLoading) "Подбираем следующий трек…" else if (state.buffering) "Подготовка аудио…" else null,
            error = message != null, showStatus = { issue = true }) }
        if (landscape) Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Column(Modifier.weight(.7f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    TrackArtwork(state.current, Modifier.size(minOf(maxWidth, maxHeight).coerceAtLeast(0.dp)))
                }
                heading()
            }
            Column(Modifier.weight(1.3f).fillMaxHeight(), verticalArrangement = Arrangement.SpaceEvenly) {
                toolbar(); actions(); PlayerProgress(state, player, inlineTime = veryShort); TransportControls(state, player, compact = veryShort)
            }
        } else Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            toolbar()
            if (!compact) BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                TrackArtwork(state.current, Modifier.size(minOf(maxWidth, maxHeight).coerceAtLeast(0.dp)))
            } else Spacer(Modifier.weight(1f))
            if (compact) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TrackArtwork(state.current, Modifier.size(56.dp))
                Box(Modifier.weight(1f)) { heading() }
            } else heading()
            actions()
            PlayerProgress(state, player)
            TransportControls(state, player, compact = compact)
        }
    }
}

@Composable private fun PlayerProgress(state: PlaybackState, player: PlaybackController, inlineTime: Boolean = false) {
    if (inlineTime) Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(secondsLabel(state.positionSeconds), style = MaterialTheme.typography.labelMedium, modifier = Modifier.testTag("position"))
        Slider(state.positionSeconds.toFloat(), { player.seek(it.toInt()) }, Modifier.weight(1f).height(40.dp).testTag("progress"), enabled = state.current != null,
            valueRange = 0f..(state.current?.durationSeconds ?: 1).coerceAtLeast(1).toFloat())
        Text(secondsLabel(state.current?.durationSeconds ?: 0), style = MaterialTheme.typography.labelMedium)
    } else
    Column(Modifier.fillMaxWidth()) {
        Slider(state.positionSeconds.toFloat(), { player.seek(it.toInt()) }, Modifier.fillMaxWidth().height(40.dp).testTag("progress"), enabled = state.current != null,
            valueRange = 0f..(state.current?.durationSeconds ?: 1).coerceAtLeast(1).toFloat())
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(secondsLabel(state.positionSeconds), style = MaterialTheme.typography.labelMedium, modifier = Modifier.testTag("position"))
            Text(secondsLabel(state.current?.durationSeconds ?: 0), style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable private fun TransportControls(state: PlaybackState, player: PlaybackController, compact: Boolean) {
    val focus = remember { FocusRequester() }
    val isTv = LocalConfiguration.current.uiMode and android.content.res.Configuration.UI_MODE_TYPE_MASK == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
    LaunchedEffect(state.current?.available) { if (isTv && state.current?.available == true) focus.requestFocus() }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        @Composable fun button(icon: UiIcon, label: String, click: () -> Unit, tag: String, primary: Boolean = false) {
            val size = if (primary) if (compact) 80.dp else 96.dp else 64.dp
            FilledIconButton(click, Modifier.size(size).prismFocus().testTag(tag).then(if (primary) Modifier.focusRequester(focus) else Modifier),
                enabled = !primary || state.current?.available == true || state.wave,
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = if (primary) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = if (primary) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)) {
                SkinIcon(icon, label, Modifier.size(if (primary) 48.dp else 36.dp))
            }
        }
        button(UiIcon.PREVIOUS, "Предыдущий трек", { player.skip(-1) }, "player_previous")
        button(if (state.playing) UiIcon.PAUSE else UiIcon.PLAY, if (state.playing) "Пауза" else "Воспроизвести", player::toggle, "player_play", true)
        button(UiIcon.NEXT, "Следующий трек", { player.skip(1) }, "player_next")
        button(UiIcon.STOP, "Остановить", player::stop, "player_stop")
    }
}

@Composable private fun TrackHeading(track: Track?, artist: (ArtistRef) -> Unit, compact: Boolean, status: String?, error: Boolean, showStatus: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text(track?.title ?: "Очередь пуста", style = if (compact) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold, maxLines = if (compact) 1 else 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("current_title"))
        ArtistNames(track, artist, "player")
        Text(status ?: track?.let { if (it.source == Source.YANDEX) "Яндекс Музыка" else "${it.source.label} · ${if (it.available) "На устройстве" else "Недоступен"}" }
            ?: "Выберите музыку в медиатеке", Modifier.testTag("player_status").then(if (status != null) Modifier.clickable(onClick = showStatus) else Modifier),
            maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall,
            color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
    }
}

@Composable internal fun ArtistNames(track: Track?, open: (ArtistRef) -> Unit, location: String) {
    val artists = track?.artists.orEmpty().distinctBy(ArtistRef::id)
    if (track?.source != Source.YANDEX || artists.isEmpty()) Text(track?.artist ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis,
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        artists.forEachIndexed { index, item ->
            if (index > 0) Text(", ", color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton({ open(item) }, Modifier.weight(item.name.length.toFloat().coerceAtLeast(1f), fill = false).heightIn(min = 40.dp).prismFocus().testTag("${location}_artist_${item.id}"), contentPadding = PaddingValues(0.dp)) {
                Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable private fun PlaybackModes(state: PlaybackState, player: PlaybackController) {
    val repeat = when (state.repeatMode) { RepeatMode.OFF -> "Повтор выключен"; RepeatMode.ALL -> "Повтор очереди"; RepeatMode.ONE -> "Повтор трека" }
    IconToggleButton(state.repeatMode != RepeatMode.OFF, { player.setRepeatMode(state.repeatMode.next()) }, Modifier.size(48.dp).prismFocus().testTag("repeat_mode").semantics { stateDescription = repeat }) {
        SkinIcon(if (state.repeatMode == RepeatMode.ONE) UiIcon.REPEAT_ONE else UiIcon.REPEAT, repeat, Modifier.size(28.dp))
    }
    IconToggleButton(state.shuffle, { player.setShuffle(!state.shuffle) }, Modifier.size(48.dp).prismFocus().testTag("shuffle_mode").semantics { stateDescription = if (state.shuffle) "В случайном порядке" else "По порядку" }) {
        SkinIcon(UiIcon.SHUFFLE, "Случайный порядок", Modifier.size(28.dp))
    }
}

@Composable internal fun QueueScreen(state: PlaybackState, player: PlaybackController, taste: MusicTaste? = null, artist: (ArtistRef) -> Unit = {}) {
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
                    TrackRow(track, state.current?.id == track.id, { player.select(track.id) }, taste = taste, location = "queue", artist = artist)
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
    taste: MusicTaste? = null, location: String = "catalog", artist: (ArtistRef) -> Unit = {}) {
    val onlineTaste = taste?.takeIf { track.source == Source.YANDEX }
    var details by remember(track.id) { mutableStateOf(false) }
    if (details && onlineTaste != null) TrackTasteDialog(track, onlineTaste, artist = artist) { details = false }
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(onClick = play, enabled = track.available, modifier = Modifier.weight(1f).prismFocus().testTag("track_${track.id}"),
                shape = MaterialTheme.shapes.medium, color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TrackArtwork(track, Modifier.size(44.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(track.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                        if (onlineTaste == null) Text(track.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                        Text(if (track.available) "${track.source.label} · ${secondsLabel(track.durationSeconds)}" else "Трек недоступен", style = MaterialTheme.typography.labelSmall)
                    }
                    SkinIcon(if (selected) UiIcon.NOW_PLAYING else UiIcon.PLAY, if (selected) "Текущий трек" else "Воспроизвести трек", Modifier.size(24.dp))
                }
            }
            if (onlineTaste == null) {
                if (enqueue != null) ActionIcon(if (queued) UiIcon.CHECK else UiIcon.ADD_QUEUE, "В очередь: ${track.title}", enqueue, Modifier.testTag("enqueue_${track.id}"), enabled = track.available && !queued)
                if (more != null) ActionIcon(UiIcon.MORE, "Действия: ${track.title}", more, Modifier.testTag("track_more_${track.id}"))
            }
        }
        if (onlineTaste != null) {
            ArtistNames(track, artist, "${location}_${track.id}")
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TasteIcons(onlineTaste, track.tasteTarget(), location)
                Spacer(Modifier.weight(1f))
                if (enqueue != null) ActionIcon(if (queued) UiIcon.CHECK else UiIcon.ADD_QUEUE, "В очередь: ${track.title}", enqueue, Modifier.testTag("enqueue_${track.id}"), enabled = track.available && !queued)
                ActionIcon(UiIcon.MORE, "Исполнители и альбом: ${track.title}", { if (more != null) more() else details = true }, Modifier.testTag("track_more_${track.id}"))
            }
            val shelf = onlineTaste.state.collectAsStateWithLifecycle().value.shelf(TasteKind.TRACK)
            if (shelf.issue != null) TextButton({ onlineTaste.refresh(TasteKind.TRACK) }, Modifier.prismFocus(), enabled = !shelf.busy) { Text("Обновить отметки трека", color = MaterialTheme.colorScheme.error) }
        }
    }
}
