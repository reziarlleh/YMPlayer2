package dev.petrov.ymplayer2.shell

import dev.petrov.ymplayer2.localization.*

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

private const val FAVORITES = "builtin:favorites"

@Composable internal fun CollectionsScreen(
    store: UserCollections, profileId: String, tracks: List<Track>, player: PlaybackController,
    favorites: Boolean, indexed: IndexedLocalLibrary? = null,
    pickTracks: @Composable (String, suspend (List<String>) -> Boolean, () -> Unit) -> Unit,
) {
    val state by store.state.collectAsState()
    val profile = state.profile(profileId)
    val scope = rememberCoroutineScope()
    var selectedId by rememberSaveable(profileId, favorites) { mutableStateOf(if (favorites) FAVORITES else null) }
    var creating by rememberSaveable { mutableStateOf(false) }
    var renameId by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteId by rememberSaveable { mutableStateOf<String?>(null) }
    var picking by rememberSaveable { mutableStateOf(false) }
    val selected = profile.playlists.firstOrNull { it.id == selectedId }
    val builtIn = selectedId == FAVORITES
    val title = if (builtIn) tr(Msg.player_source_favorites) else selected?.name.orEmpty()
    val entries = if (builtIn) profile.favorites else selected?.tracks.orEmpty()
    val opened = builtIn || selected != null
    val listStates = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    BackHandler(!favorites && selectedId != null && !picking) { selectedId = null }
    BackHandler(picking) { picking = false }

    if (picking && opened) {
        pickTracks(title, { ids ->
            val change = if (builtIn) CollectionEdit.FavoriteMany(ids) else CollectionEdit.AddMany(requireNotNull(selected).id, ids)
            store.edit(profileId, change).also { if (it) picking = false }
        }, { picking = false })
        return
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
        if (!state.ready) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.issue?.let {
            Text(trMessage(it), color = MaterialTheme.colorScheme.error)
            TextButton({ scope.launch { store.reload() } }, Modifier.prismFocus()) { Text(tr(Msg.msg_eb9946b15efd)) }
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val split = maxWidth >= 720.dp && !favorites
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (split || !opened) Column(Modifier.fillMaxHeight().then(if (split) Modifier.weight(.35f) else Modifier.fillMaxWidth())) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(tr(Msg.msg_2efea0f8794f), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                        ActionIcon(UiIcon.ADD, tr(Msg.msg_3315af3b27b8), { creating = true }, Modifier.testTag("playlist_create"), enabled = state.writable)
                    }
                    listStates.SaveableStateProvider("lists") {
                        LazyColumn(Modifier.fillMaxSize().testTag("playlists_list"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            item(key = FAVORITES) {
                                CollectionListItem(tr(Msg.player_source_favorites), profile.favorites.size, { selectedId = FAVORITES },
                                    selectedId == FAVORITES, "category_FAVORITES")
                            }
                            items(profile.playlists, key = LocalPlaylist::id) { list ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.weight(1f)) {
                                        CollectionListItem(list.name, list.tracks.size, { selectedId = list.id },
                                            selectedId == list.id, "playlist_${list.id}")
                                    }
                                    ActionIcon(UiIcon.EDIT, tr(Msg.msg_bcdc88d10948, list.name), { renameId = list.id },
                                        Modifier.testTag("playlist_rename_${list.id}"), enabled = state.writable)
                                    ActionIcon(UiIcon.REMOVE, tr(Msg.msg_ea16ed0e182d, list.name), { deleteId = list.id },
                                        Modifier.testTag("playlist_delete_${list.id}"), enabled = state.writable)
                                }
                            }
                        }
                    }
                }
                if (opened) Box(Modifier.weight(1f).fillMaxHeight()) {
                    listStates.SaveableStateProvider(requireNotNull(selectedId)) {
                        LocalPlaylistDetail(title, if (builtIn) null else selectedId, entries, store, profileId,
                            tracks, indexed, player, state.writable, if (favorites) null else ({ selectedId = null }), { picking = true })
                    }
                }
            }
        }
    }
    val renaming = profile.playlists.firstOrNull { it.id == renameId }
    if (creating || renaming != null) PlaylistNameDialog(renaming?.name.orEmpty(), state.issue,
        { creating = false; renameId = null }) { name ->
        val change = renaming?.let { CollectionEdit.Rename(it.id, name) } ?: CollectionEdit.Create(name)
        store.edit(profileId, change).also { ok ->
            if (ok) {
                if (creating) {
                    selectedId = store.state.value.profile(profileId).playlists.firstOrNull { it.name == name.trim() }?.id
                    picking = selectedId != null
                }
                creating = false
                renameId = null
            }
        }
    }
    profile.playlists.firstOrNull { it.id == deleteId }?.let { list ->
        StableAlertDialog(onDismissRequest = { deleteId = null }, title = { Text(tr(Msg.msg_f91135efb9aa, list.name)) },
            text = { Text(tr(Msg.msg_d7c74665073a)) },
            confirmButton = { TextButton({ scope.launch {
                if (store.edit(profileId, CollectionEdit.Delete(list.id))) {
                    if (selectedId == list.id) selectedId = null
                    listStates.removeState(list.id)
                    deleteId = null
                }
            } }, Modifier.prismFocus().testTag("playlist_delete_confirm")) { Text(tr(Msg.msg_be99b1361201)) } },
            dismissButton = { TextButton({ deleteId = null }, Modifier.prismFocus()) { Text(tr(Msg.msg_8fbe9b75cbdf)) } })
    }
}

@Composable private fun CollectionListItem(name: String, count: Int, open: () -> Unit, selected: Boolean, tag: String) {
    Surface(onClick = open, modifier = Modifier.fillMaxWidth().prismFocus().testTag(tag), shape = MaterialTheme.shapes.medium,
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(12.dp)) {
            Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(tr(Msg.msg_12e3d1e9d948, count), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable internal fun SaveTracksToPlaylistDialog(store: UserCollections, profileId: String, trackIds: List<String>,
    dismiss: () -> Unit, saved: () -> Unit) {
    val state by store.state.collectAsState()
    val scope = rememberCoroutineScope()
    var creating by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    fun add(change: CollectionEdit) {
        if (busy) return
        busy = true
        scope.launch { try { if (store.edit(profileId, change)) saved() } finally { busy = false } }
    }
    if (creating) PlaylistNameDialog("", state.issue, { creating = false }) { name ->
        store.edit(profileId, CollectionEdit.CreateMany(name, trackIds)).also { if (it) saved() }
    } else StableAlertDialog(onDismissRequest = { if (!busy) dismiss() }, title = { Text(tr(Msg.msg_9a529d376ca3)) }, text = {
        Column {
            state.issue?.let { Text(trIssue(it), color = MaterialTheme.colorScheme.error) }
            LazyColumn(Modifier.heightIn(max = 340.dp)) {
                item { TextButton({ creating = true }, Modifier.fillMaxWidth().prismFocus().testTag("bulk_new_playlist"),
                    enabled = state.writable && !busy) { Text(tr(Msg.msg_38aa13198a8b)) } }
                item { TextButton({ add(CollectionEdit.FavoriteMany(trackIds)) }, Modifier.fillMaxWidth().prismFocus().testTag("bulk_favorites"),
                    enabled = state.writable && !busy) { Text(tr(Msg.player_source_favorites)) } }
                items(state.profile(profileId).playlists, key = LocalPlaylist::id) { list ->
                    TextButton({ add(CollectionEdit.AddMany(list.id, trackIds)) }, Modifier.fillMaxWidth().prismFocus().testTag("bulk_playlist_${list.id}"),
                        enabled = state.writable && !busy) { Text(list.name) }
                }
            }
        }
    }, confirmButton = { TextButton(dismiss, Modifier.prismFocus(), enabled = !busy) { Text(tr(Msg.msg_8fbe9b75cbdf)) } })
}

@Composable internal fun PlaylistNameDialog(initial: String, issue: String?, dismiss: () -> Unit, save: suspend (String) -> Boolean) {
    var name by rememberSaveable { mutableStateOf(initial) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    StableAlertDialog(onDismissRequest = { if (!busy) dismiss() }, title = { Text(if (initial.isEmpty()) tr(Msg.msg_38aa13198a8b) else tr(Msg.msg_717012565e0a)) },
        text = { Column {
            OutlinedTextField(name, { name = it.take(80) }, Modifier.fillMaxWidth().testTag("playlist_name"), label = { Text(tr(Msg.msg_0918b4ba9268)) }, singleLine = true)
            issue?.let { Text(trMessage(it), color = MaterialTheme.colorScheme.error) }
        } }, confirmButton = { TextButton({ scope.launch { busy = true; try { save(name) } finally { busy = false } } }, Modifier.prismFocus().testTag("playlist_save"), enabled = !busy && name.isNotBlank()) { Text(tr(Msg.msg_b4d30cae5289)) } },
        dismissButton = { TextButton(dismiss, Modifier.prismFocus(), enabled = !busy) { Text(tr(Msg.msg_8fbe9b75cbdf)) } })
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
    else StableAlertDialog(onDismissRequest = dismiss, title = { Text(track.title, maxLines = 2, overflow = TextOverflow.Ellipsis) }, text = {
        Column {
            state.issue?.let { Text(trMessage(it), color = MaterialTheme.colorScheme.error) }
            if (!choosing) {
                TextButton({ edit(CollectionEdit.Favorite(track.id, !favorite)) }, Modifier.fillMaxWidth().heightIn(min = 48.dp).prismFocus().testTag("favorite_toggle"), enabled = state.writable && !busy && (known || favorite)) {
                    SkinIcon(if (favorite) UiIcon.FAVORITE else UiIcon.FAVORITE_OFF, null); Spacer(Modifier.width(8.dp)); Text(if (favorite) tr(Msg.msg_334c3299a899) else tr(Msg.msg_05f7734c7fbc))
                }
                TextButton({ choosing = true }, Modifier.fillMaxWidth().heightIn(min = 48.dp).prismFocus().testTag("add_to_playlist"), enabled = state.writable && known) { SkinIcon(UiIcon.PLAYLIST, null); Spacer(Modifier.width(8.dp)); Text(tr(Msg.msg_9a529d376ca3)) }
            } else {
                TextButton({ creating = true }, Modifier.heightIn(min = 48.dp).prismFocus().testTag("track_new_playlist")) { Text(tr(Msg.msg_38aa13198a8b)) }
                LazyColumn(Modifier.heightIn(max = 300.dp)) {
                    items(data.playlists, key = { it.id }) { list ->
                        TextButton({ edit(CollectionEdit.Add(list.id, track.id)) }, Modifier.fillMaxWidth().heightIn(min = 48.dp).prismFocus().testTag("track_playlist_${list.id}"), enabled = !busy && state.writable && list.tracks.none { it.id == track.id }) {
                            Text(list.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (list.tracks.any { it.id == track.id }) SkinIcon(UiIcon.CHECK, tr(Msg.msg_758aa155871b))
                        }
                    }
                }
            }
        }
    }, confirmButton = { TextButton(dismiss, Modifier.prismFocus()) { Text(tr(Msg.msg_a7a4033657e8)) } })
}
