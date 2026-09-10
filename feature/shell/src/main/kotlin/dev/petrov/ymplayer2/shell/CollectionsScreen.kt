package dev.petrov.ymplayer2.shell

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.petrov.ymplayer2.core.*
import dev.petrov.ymplayer2.designsystem.*
import dev.petrov.ymplayer2.designsystem.skin.UiIcon
import kotlinx.coroutines.launch

@Composable internal fun CollectionsScreen(store: UserCollections, profileId: String, tracks: List<Track>, player: PlaybackController, favorites: Boolean) {
    val state by store.state.collectAsState()
    val data = state.profile(profileId)
    val catalog = remember(tracks) { tracks.associateBy(Track::id) }
    val playback by player.state.collectAsState()
    val scope = rememberCoroutineScope()
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var naming by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf<LocalPlaylist?>(null) }
    var deleting by remember { mutableStateOf<LocalPlaylist?>(null) }
    var adding by remember { mutableStateOf(false) }
    var editing by rememberSaveable { mutableStateOf(false) }
    var actionTrack by remember { mutableStateOf<Track?>(null) }
    val selected = data.playlists.find { it.id == selectedId }
    BackHandler(selectedId != null || adding || naming || deleting != null || actionTrack != null) {
        when {
            adding -> adding = false
            naming -> naming = false
            deleting != null -> deleting = null
            actionTrack != null -> actionTrack = null
            else -> { selectedId = null; editing = false }
        }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        if (!state.ready) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.issue?.let {
            Text(it, color = MaterialTheme.colorScheme.error)
            TextButton({ scope.launch { store.reload() } }, Modifier.prismFocus()) { Text("Повторить чтение") }
        }
        if (favorites || selected != null) {
            val entries = if (favorites) data.favorites else selected!!.tracks
            val resolved = remember(entries, catalog) { entries.map { it.resolve(catalog) } }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!favorites) ActionIcon(UiIcon.BACK, "К плейлистам", { selectedId = null; editing = false })
                Text(if (favorites) "Избранное" else selected!!.name, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (!favorites) {
                    ActionIcon(UiIcon.ADD, "Добавить треки", { adding = true }, Modifier.testTag("playlist_add_tracks"), enabled = state.writable)
                    ActionIcon(if (editing) UiIcon.CHECK else UiIcon.EDIT, "Изменить список", { editing = !editing }, Modifier.testTag("playlist_edit"), enabled = state.writable)
                }
            }
            Text("${entries.size} треков · ${resolved.count { it.available }} доступно", style = MaterialTheme.typography.bodySmall)
            Button({ player.playQueue(entries.map { it.id }) }, Modifier.heightIn(min = 48.dp).prismFocus().testTag("collection_play"), enabled = resolved.any { it.available } && playback.connected) {
                SkinIcon(UiIcon.PLAY, null); Spacer(Modifier.width(8.dp)); Text("Воспроизвести список")
            }
            if (entries.isEmpty()) Text(if (favorites) "Добавляйте треки в избранное через меню рядом с треком." else "Добавьте музыку кнопкой «Добавить треки».", Modifier.padding(vertical = 16.dp))
            LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("collection_tracks"), contentPadding = PaddingValues(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(resolved.size, key = { resolved[it].id }) { index ->
                    val track = resolved[index]
                    Column {
                        TrackRow(track, playback.current?.id == track.id, { player.playQueue(entries.map { it.id }, track.id) }, more = { actionTrack = track })
                        if (editing && selected != null) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            ActionIcon(UiIcon.UP, "Выше: ${track.title}", { scope.launch { store.edit(profileId, CollectionEdit.Move(selected.id, track.id, index - 1)) } }, Modifier.testTag("playlist_up_${track.id}"), enabled = index > 0 && state.writable)
                            ActionIcon(UiIcon.DOWN, "Ниже: ${track.title}", { scope.launch { store.edit(profileId, CollectionEdit.Move(selected.id, track.id, index + 1)) } }, Modifier.testTag("playlist_down_${track.id}"), enabled = index < entries.lastIndex && state.writable)
                            ActionIcon(UiIcon.REMOVE, "Убрать из плейлиста: ${track.title}", { scope.launch { store.edit(profileId, CollectionEdit.Remove(selected.id, track.id)) } }, Modifier.testTag("playlist_remove_${track.id}"), enabled = state.writable)
                        }
                    }
                }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Плейлисты", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                ActionIcon(UiIcon.ADD, "Создать плейлист", { rename = null; naming = true }, Modifier.testTag("playlist_create"), enabled = state.writable)
            }
            Text("Только для текущего профиля", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (data.playlists.isEmpty()) Text("Создайте первый плейлист кнопкой «+».", Modifier.padding(vertical = 16.dp))
            LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("playlists_list"), contentPadding = PaddingValues(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(data.playlists, key = { it.id }) { list ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(onClick = { selectedId = list.id }, modifier = Modifier.weight(1f).prismFocus().testTag("playlist_${list.id}"), shape = MaterialTheme.shapes.medium) {
                            Column(Modifier.padding(12.dp)) {
                                Text(list.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text("${list.tracks.size} треков", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        ActionIcon(UiIcon.EDIT, "Переименовать: ${list.name}", { rename = list; naming = true }, Modifier.testTag("playlist_rename_${list.id}"), enabled = state.writable)
                        ActionIcon(UiIcon.REMOVE, "Удалить плейлист: ${list.name}", { deleting = list }, Modifier.testTag("playlist_delete_${list.id}"), enabled = state.writable)
                    }
                }
            }
        }
    }
    if (naming) PlaylistNameDialog(rename?.name.orEmpty(), state.issue, { naming = false }) { name ->
        store.edit(profileId, rename?.let { CollectionEdit.Rename(it.id, name) } ?: CollectionEdit.Create(name)).also { if (it) naming = false }
    }
    deleting?.let { list ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Удалить «${list.name}»?") },
            text = { Text("Удалится только плейлист. Файлы и текущая очередь сохранятся.") },
            confirmButton = { TextButton({ scope.launch { if (store.edit(profileId, CollectionEdit.Delete(list.id))) deleting = null } }, Modifier.prismFocus().testTag("playlist_delete_confirm")) { Text("Удалить") } },
            dismissButton = { TextButton({ deleting = null }, Modifier.prismFocus()) { Text("Отмена") } })
    }
    if (adding && selected != null) AddTracksDialog(selected, tracks, state, { adding = false }) { store.edit(profileId, CollectionEdit.Add(selected.id, it)) }
    actionTrack?.let { track -> TrackCollectionDialog(track, store, profileId, catalog.containsKey(track.id), { actionTrack = null }) }
}

@Composable private fun PlaylistNameDialog(initial: String, issue: String?, dismiss: () -> Unit, save: suspend (String) -> Boolean) {
    var name by rememberSaveable { mutableStateOf(initial) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(onDismissRequest = { if (!busy) dismiss() }, title = { Text(if (initial.isEmpty()) "Новый плейлист" else "Название плейлиста") },
        text = { Column {
            OutlinedTextField(name, { name = it.take(80) }, Modifier.fillMaxWidth().testTag("playlist_name"), label = { Text("Название") }, singleLine = true)
            issue?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } }, confirmButton = { TextButton({ scope.launch { busy = true; try { save(name) } finally { busy = false } } }, Modifier.prismFocus().testTag("playlist_save"), enabled = !busy && name.isNotBlank()) { Text("Сохранить") } },
        dismissButton = { TextButton(dismiss, Modifier.prismFocus(), enabled = !busy) { Text("Отмена") } })
}

@Composable private fun AddTracksDialog(list: LocalPlaylist, tracks: List<Track>, state: CollectionsState, dismiss: () -> Unit, add: suspend (String) -> Boolean) {
    var query by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val ids = remember(list.tracks) { list.tracks.mapTo(hashSetOf()) { it.id } }
    AlertDialog(onDismissRequest = dismiss, title = { Text("Добавить в «${list.name}»") }, text = { Column {
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().testTag("playlist_track_search"), label = { Text("Поиск треков") }, singleLine = true)
        state.issue?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        LazyColumn(Modifier.heightIn(max = 320.dp).testTag("playlist_picker")) {
            items(tracks.filter { it.source != Source.YANDEX && (it.title.contains(query, true) || it.artist.contains(query, true)) }, key = Track::id) { track ->
                TextButton({ scope.launch { busy = true; try { add(track.id) } finally { busy = false } } }, Modifier.fillMaxWidth().heightIn(min = 48.dp).prismFocus().testTag("playlist_pick_${track.id}"), enabled = !busy && state.writable && track.id !in ids) {
                    Column(Modifier.weight(1f)) { Text(track.title, maxLines = 2, overflow = TextOverflow.Ellipsis); Text(track.artist, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    SkinIcon(if (track.id in ids) UiIcon.CHECK else UiIcon.ADD, if (track.id in ids) "Добавлен" else "Добавить")
                }
            }
        }
    } }, confirmButton = { TextButton(dismiss, Modifier.prismFocus().testTag("playlist_picker_done")) { Text("Готово") } })
}

@Composable internal fun TrackCollectionDialog(track: Track, store: UserCollections, profileId: String, known: Boolean, dismiss: () -> Unit) {
    val state by store.state.collectAsState()
    val data = state.profile(profileId)
    val favorite = data.favorites.any { it.id == track.id }
    var choosing by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val edit: (CollectionEdit) -> Unit = { change -> scope.launch { busy = true; try { if (store.edit(profileId, change)) dismiss() } finally { busy = false } } }
    if (creating) PlaylistNameDialog("", state.issue, { creating = false }) { name -> store.edit(profileId, CollectionEdit.Create(name, track.id)).also { if (it) dismiss() } }
    else AlertDialog(onDismissRequest = dismiss, title = { Text(track.title, maxLines = 2, overflow = TextOverflow.Ellipsis) }, text = {
        Column {
            state.issue?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (!choosing) {
                TextButton({ edit(CollectionEdit.Favorite(track.id, !favorite)) }, Modifier.fillMaxWidth().heightIn(min = 48.dp).prismFocus().testTag("favorite_toggle"), enabled = state.writable && !busy && (known || favorite)) {
                    SkinIcon(if (favorite) UiIcon.FAVORITE else UiIcon.FAVORITE_OFF, null); Spacer(Modifier.width(8.dp)); Text(if (favorite) "Убрать из избранного" else "В избранное")
                }
                TextButton({ choosing = true }, Modifier.fillMaxWidth().heightIn(min = 48.dp).prismFocus().testTag("add_to_playlist"), enabled = state.writable && known) { SkinIcon(UiIcon.PLAYLIST, null); Spacer(Modifier.width(8.dp)); Text("В плейлист") }
            } else {
                TextButton({ creating = true }, Modifier.heightIn(min = 48.dp).prismFocus().testTag("track_new_playlist")) { Text("Новый плейлист") }
                LazyColumn(Modifier.heightIn(max = 300.dp)) {
                    items(data.playlists, key = { it.id }) { list ->
                        TextButton({ edit(CollectionEdit.Add(list.id, track.id)) }, Modifier.fillMaxWidth().heightIn(min = 48.dp).prismFocus().testTag("track_playlist_${list.id}"), enabled = !busy && state.writable && list.tracks.none { it.id == track.id }) {
                            Text(list.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (list.tracks.any { it.id == track.id }) SkinIcon(UiIcon.CHECK, "Добавлен")
                        }
                    }
                }
            }
        }
    }, confirmButton = { TextButton(dismiss, Modifier.prismFocus()) { Text("Закрыть") } })
}
