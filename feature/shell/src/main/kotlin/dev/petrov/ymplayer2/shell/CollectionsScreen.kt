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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable internal fun CollectionsScreen(store: UserCollections, profileId: String, tracks: List<Track>, player: PlaybackController,
    favorites: Boolean, indexed: IndexedLocalLibrary? = null) {
    val state by store.state.collectAsState()
    val data = state.profile(profileId)
    val legacyCatalog = if (indexed == null) remember(tracks) { tracks.associateBy(Track::id) } else emptyMap()
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
    val entries = if (favorites) data.favorites else selected?.tracks.orEmpty()
    val diskCatalog by key(indexed, indexed?.indexRevision, entries) { produceState<Result<Map<String, Track>>?>(null, indexed, indexed?.indexRevision, entries) {
        value = if (indexed == null) null else try { Result.success(indexed.tracksByIds(entries.map(SavedTrack::id))) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { Result.failure(error) }
    } }
    val catalog = if (indexed == null) legacyCatalog else diskCatalog?.getOrNull().orEmpty()
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
            Text(trMessage(it), color = MaterialTheme.colorScheme.error)
            TextButton({ scope.launch { store.reload() } }, Modifier.prismFocus()) { Text(tr(Msg.msg_eb9946b15efd)) }
        }
        if (diskCatalog?.isFailure == true) Text(tr(Msg.msg_b5c15a505a62), color = MaterialTheme.colorScheme.error)
        if (favorites || selected != null) {
            val resolved = remember(entries, catalog) { entries.map { it.resolve(catalog) } }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!favorites) ActionIcon(UiIcon.BACK, tr(Msg.msg_efb640146dae), { selectedId = null; editing = false })
                Text(if (favorites) tr(Msg.msg_e4c1d01c5c8f) else selected!!.name, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (!favorites) {
                    ActionIcon(UiIcon.ADD, tr(Msg.msg_78cdeec91c73), { adding = true }, Modifier.testTag("playlist_add_tracks"), enabled = state.writable)
                    ActionIcon(if (editing) UiIcon.CHECK else UiIcon.EDIT, tr(Msg.msg_6b5623eafb06), { editing = !editing }, Modifier.testTag("playlist_edit"), enabled = state.writable)
                }
            }
            Text(tr(Msg.msg_bf6c36d1e481, entries.size, resolved.count { it.available }), style = MaterialTheme.typography.bodySmall)
            Button({ player.playQueue(entries.map { it.id }) }, Modifier.heightIn(min = 48.dp).prismFocus().testTag("collection_play"), enabled = resolved.any { it.available } && playback.connected && (indexed == null || diskCatalog?.isSuccess == true)) {
                SkinIcon(UiIcon.PLAY, null); Spacer(Modifier.width(8.dp)); Text(tr(Msg.msg_a033d47afec5))
            }
            if (entries.isEmpty()) Text(if (favorites) tr(Msg.msg_c25691d01a87) else tr(Msg.msg_8cd421f138ac), Modifier.padding(vertical = 16.dp))
            LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("collection_tracks"), contentPadding = PaddingValues(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(resolved.size, key = { resolved[it].id }) { index ->
                    val track = resolved[index]
                    Column {
                        TrackRow(track, playback.current?.id == track.id, { player.playQueue(entries.map { it.id }, track.id) }, more = { actionTrack = track })
                        if (editing && selected != null) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            ActionIcon(UiIcon.UP, tr(Msg.msg_9fcd67f23799, track.title), { scope.launch { store.edit(profileId, CollectionEdit.Move(selected.id, track.id, index - 1)) } }, Modifier.testTag("playlist_up_${track.id}"), enabled = index > 0 && state.writable)
                            ActionIcon(UiIcon.DOWN, tr(Msg.msg_48f1c04f18bc, track.title), { scope.launch { store.edit(profileId, CollectionEdit.Move(selected.id, track.id, index + 1)) } }, Modifier.testTag("playlist_down_${track.id}"), enabled = index < entries.lastIndex && state.writable)
                            ActionIcon(UiIcon.REMOVE, tr(Msg.msg_ce1456ccd84b, track.title), { scope.launch { store.edit(profileId, CollectionEdit.Remove(selected.id, track.id)) } }, Modifier.testTag("playlist_remove_${track.id}"), enabled = state.writable)
                        }
                    }
                }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tr(Msg.msg_2efea0f8794f), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                ActionIcon(UiIcon.ADD, tr(Msg.msg_3315af3b27b8), { rename = null; naming = true }, Modifier.testTag("playlist_create"), enabled = state.writable)
            }
            Text(tr(Msg.msg_a6d6c9941570), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (data.playlists.isEmpty()) Text(tr(Msg.msg_35619086cdfe), Modifier.padding(vertical = 16.dp))
            LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("playlists_list"), contentPadding = PaddingValues(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(data.playlists, key = { it.id }) { list ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(onClick = { selectedId = list.id }, modifier = Modifier.weight(1f).prismFocus().testTag("playlist_${list.id}"), shape = MaterialTheme.shapes.medium) {
                            Column(Modifier.padding(12.dp)) {
                                Text(list.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(tr(Msg.msg_12e3d1e9d948, list.tracks.size), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        ActionIcon(UiIcon.EDIT, tr(Msg.msg_bcdc88d10948, list.name), { rename = list; naming = true }, Modifier.testTag("playlist_rename_${list.id}"), enabled = state.writable)
                        ActionIcon(UiIcon.REMOVE, tr(Msg.msg_ea16ed0e182d, list.name), { deleting = list }, Modifier.testTag("playlist_delete_${list.id}"), enabled = state.writable)
                    }
                }
            }
        }
    }
    if (naming) PlaylistNameDialog(rename?.name.orEmpty(), state.issue, { naming = false }) { name ->
        store.edit(profileId, rename?.let { CollectionEdit.Rename(it.id, name) } ?: CollectionEdit.Create(name)).also { if (it) naming = false }
    }
    deleting?.let { list ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text(tr(Msg.msg_f91135efb9aa, list.name)) },
            text = { Text(tr(Msg.msg_d7c74665073a)) },
            confirmButton = { TextButton({ scope.launch { if (store.edit(profileId, CollectionEdit.Delete(list.id))) deleting = null } }, Modifier.prismFocus().testTag("playlist_delete_confirm")) { Text(tr(Msg.msg_be99b1361201)) } },
            dismissButton = { TextButton({ deleting = null }, Modifier.prismFocus()) { Text(tr(Msg.msg_8fbe9b75cbdf)) } })
    }
    if (adding && selected != null) AddTracksDialog(selected, tracks, state, { adding = false }, indexed) { store.edit(profileId, CollectionEdit.Add(selected.id, it)) }
    actionTrack?.let { track -> TrackCollectionDialog(track, store, profileId, catalog.containsKey(track.id), { actionTrack = null }) }
}

@Composable private fun PlaylistNameDialog(initial: String, issue: String?, dismiss: () -> Unit, save: suspend (String) -> Boolean) {
    var name by rememberSaveable { mutableStateOf(initial) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(onDismissRequest = { if (!busy) dismiss() }, title = { Text(if (initial.isEmpty()) tr(Msg.msg_38aa13198a8b) else tr(Msg.msg_717012565e0a)) },
        text = { Column {
            OutlinedTextField(name, { name = it.take(80) }, Modifier.fillMaxWidth().testTag("playlist_name"), label = { Text(tr(Msg.msg_0918b4ba9268)) }, singleLine = true)
            issue?.let { Text(trMessage(it), color = MaterialTheme.colorScheme.error) }
        } }, confirmButton = { TextButton({ scope.launch { busy = true; try { save(name) } finally { busy = false } } }, Modifier.prismFocus().testTag("playlist_save"), enabled = !busy && name.isNotBlank()) { Text(tr(Msg.msg_b4d30cae5289)) } },
        dismissButton = { TextButton(dismiss, Modifier.prismFocus(), enabled = !busy) { Text(tr(Msg.msg_8fbe9b75cbdf)) } })
}

@Composable private fun AddTracksDialog(list: LocalPlaylist, tracks: List<Track>, state: CollectionsState, dismiss: () -> Unit,
    indexed: IndexedLocalLibrary?, add: suspend (String) -> Boolean) {
    var query by rememberSaveable { mutableStateOf("") }
    var visibleCount by rememberSaveable(query) { mutableIntStateOf(80) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val ids = remember(list.tracks) { list.tracks.mapTo(hashSetOf()) { it.id } }
    val batches = remember(indexed, indexed?.indexRevision, query) { mutableMapOf<Int, CatalogPage<Track>>() }
    val diskPage by key(indexed, indexed?.indexRevision, query, visibleCount) { produceState<Result<CatalogPage<Track>>?>(null, indexed, indexed?.indexRevision, query, visibleCount) {
        value = if (indexed == null) null else try {
            for (offset in 0 until visibleCount step 80) {
                if (offset !in batches) batches[offset] = indexed.pageTracks(CatalogFilter(query = query), offset = offset, limit = 80)
                if (!batches.getValue(offset).hasMore) break
            }
            Result.success(CatalogPage(batches.toSortedMap().values.flatMap { it.items }, batches[0]?.total ?: 0, 0))
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { Result.failure(error) }
    } }
    val choices = if (indexed == null) tracks.filter { it.source != Source.YANDEX && (it.title.contains(query, true) || it.artist.contains(query, true)) }
        else diskPage?.getOrNull()?.items.orEmpty()
    AlertDialog(onDismissRequest = dismiss, title = { Text(tr(Msg.msg_7aa5b98239e2, list.name)) }, text = { Column {
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().testTag("playlist_track_search"), label = { Text(tr(Msg.msg_95a0b91751fc)) }, singleLine = true)
        state.issue?.let { Text(trMessage(it), color = MaterialTheme.colorScheme.error) }
        LazyColumn(Modifier.heightIn(max = 320.dp).testTag("playlist_picker")) {
            if (indexed != null && diskPage == null) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (diskPage?.isFailure == true) item { Text(tr(Msg.msg_502d3a6bd090), color = MaterialTheme.colorScheme.error) }
            items(choices, key = Track::id) { track ->
                TextButton({ scope.launch { busy = true; try { add(track.id) } finally { busy = false } } }, Modifier.fillMaxWidth().heightIn(min = 48.dp).prismFocus().testTag("playlist_pick_${track.id}"), enabled = !busy && state.writable && track.id !in ids) {
                    Column(Modifier.weight(1f)) { Text(track.title, maxLines = 2, overflow = TextOverflow.Ellipsis); Text(track.artist, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    SkinIcon(if (track.id in ids) UiIcon.CHECK else UiIcon.ADD, if (track.id in ids) tr(Msg.msg_758aa155871b) else tr(Msg.msg_71038c53bbc4))
                }
            }
            if (diskPage?.getOrNull()?.hasMore == true && visibleCount <= Int.MAX_VALUE - 80) item {
                TextButton({ visibleCount += 80 }, Modifier.fillMaxWidth().prismFocus().testTag("playlist_picker_more")) { Text(tr(Msg.msg_c6826c783794)) }
            }
        }
    } }, confirmButton = { TextButton(dismiss, Modifier.prismFocus().testTag("playlist_picker_done")) { Text(tr(Msg.msg_ef05d57959cf)) } })
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
