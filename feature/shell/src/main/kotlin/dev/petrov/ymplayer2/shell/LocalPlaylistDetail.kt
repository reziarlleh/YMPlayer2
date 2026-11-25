package dev.petrov.ymplayer2.shell

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.petrov.ymplayer2.core.*
import dev.petrov.ymplayer2.designsystem.*
import dev.petrov.ymplayer2.designsystem.skin.UiIcon
import dev.petrov.ymplayer2.localization.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

internal class PlaylistEditState : ViewModel() {
    val selection = mutableStateOf<List<String>>(emptyList())
    val moveGroup = mutableStateOf<Set<String>>(emptySet())
    val originalOrder = mutableStateOf<List<String>>(emptyList())
    val lastReorder = mutableStateOf<Pair<List<String>, List<String>>?>(null)
}

@Composable internal fun LocalPlaylistDetail(
    title: String, playlistId: String?, entries: List<SavedTrack>, store: UserCollections, profileId: String,
    tracks: List<Track>, indexed: IndexedLocalLibrary?, player: PlaybackController, writable: Boolean,
    back: (() -> Unit)?, add: () -> Unit,
) {
    val ids = remember(entries) { entries.map(SavedTrack::id) }
    val members = remember(ids) { ids.toSet() }
    val legacy = remember(tracks, indexed) { if (indexed == null) tracks.associateBy(Track::id) else emptyMap() }
    // Sorting only changes order; it must not requery the catalog or briefly disable playback.
    val resolved by key(indexed, members) {
        produceState<Result<Map<String, Track>>?>(null, legacy) {
            if (indexed == null) value = Result.success(legacy)
            else indexed.state.map { indexed.indexRevision }.distinctUntilChanged().collectLatest {
                value = try { Result.success(indexed.tracksByIds(members)) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { Result.failure(error) }
            }
        }
    }
    val catalog = if (indexed == null) legacy else resolved?.getOrNull().orEmpty()
    val all = remember(entries, catalog) { entries.map { it.resolve(catalog) } }
    val playback by player.state.collectAsState()
    val origin = (if (playlistId == null) LocalListTarget.favorites else LocalListTarget.playlist(playlistId, title)).origin()
    val scope = rememberCoroutineScope()
    var edit by rememberSaveable { mutableStateOf(false) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val edits: PlaylistEditState = viewModel(key = "playlist/$profileId/$playlistId")
    var selected by edits.selection
    val checked = remember(selected) { selected.toSet() }
    var range by rememberSaveable { mutableStateOf(false) }
    var rangeStart by rememberSaveable { mutableStateOf<String?>(null) }
    var sorting by rememberSaveable { mutableStateOf(false) }
    var moving by rememberSaveable { mutableStateOf(false) }
    var movingIds by edits.moveGroup
    var moveBase by edits.originalOrder
    var undo by edits.lastReorder
    var pending by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var addingElsewhere by remember { mutableStateOf(false) }
    var moreTrack by remember { mutableStateOf<Track?>(null) }
    var dragOver by remember { mutableStateOf<String?>(null) }
    val shown = remember(all, query) { all.filter { trackMatches(it, query) } }
    val listState = rememberLazyListState()

    LaunchedEffect(members) {
        if (selected.any { it !in members }) selected = ArrayList(selected.filter(members::contains))
    }
    fun clearChecks() { selected = arrayListOf(); range = false; rangeStart = null }
    fun select(id: String) {
        if (!range) {
            selected = ArrayList(selected).apply { if (!remove(id)) add(id) }
        } else if (rangeStart == null) {
            rangeStart = id
        } else {
            val a = shown.indexOfFirst { it.id == rangeStart }; val b = shown.indexOfFirst { it.id == id }
            if (a >= 0 && b >= 0) selected = ArrayList(checked + shown.subList(minOf(a,b), maxOf(a,b) + 1).map(Track::id))
            range = false; rangeStart = null
        }
    }
    fun applyOrder(expected: List<String>, order: List<String>, recordUndo: Boolean = true) {
        if (pending || expected == order) return
        pending = true
        scope.launch {
            try {
                if (store.edit(profileId, CollectionEdit.Reorder(playlistId, expected, order))) {
                    undo = if (recordUndo) order to expected else null
                    clearChecks()
                }
            } finally { pending = false }
        }
    }
    fun move(group: Set<String>) {
        movingIds = group
        moveBase = ids
        moving = true
    }
    BackHandler(edit || searching) {
        when {
            edit -> { clearChecks(); edit = false }
            else -> { query = ""; searching = false }
        }
    }
    val playable = playback.connected && (indexed == null || resolved?.isSuccess == true) && all.any(Track::available)
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (back != null) ActionIcon(UiIcon.BACK, tr(Msg.msg_efb640146dae), back)
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            ActionIcon(UiIcon.PLAY, tr(Msg.msg_a033d47afec5), { player.playList(ids, origin = origin) },
                Modifier.testTag("collection_play"), enabled = playable)
            ActionIcon(UiIcon.ADD, tr(Msg.msg_3048598439e0), add, Modifier.testTag("playlist_add_tracks"), enabled = writable)
            ActionIcon(if (edit) UiIcon.CHECK else UiIcon.EDIT, tr(if (edit) Msg.msg_ef05d57959cf else Msg.msg_b0ad5bf65466), {
                edit = !edit; clearChecks()
            }, Modifier.testTag("playlist_edit"), enabled = writable && entries.isNotEmpty())
            ActionIcon(UiIcon.SEARCH, tr(Msg.msg_95a0b91751fc), { searching = !searching; query = ""; rangeStart = null },
                Modifier.testTag("playlist_search"))
        }
        if (searching) OutlinedTextField(query, { query = it; rangeStart = null }, Modifier.fillMaxWidth().testTag("playlist_query"),
            singleLine = true, label = { Text(tr(Msg.msg_95a0b91751fc)) })
        if (indexed != null && resolved == null) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (resolved?.isFailure == true) Text(tr(Msg.msg_b5c15a505a62), color = MaterialTheme.colorScheme.error)
        if (edit) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            Text(tr(Msg.bulk_count, checked.size), Modifier.padding(horizontal = 4.dp))
            TextButton({ sorting = true }, Modifier.prismFocus().testTag("playlist_sort"), enabled = !pending && ids.size > 1) { Text(tr(Msg.playlist_order_action)) }
            TextButton({ move(checked) }, Modifier.prismFocus().testTag("playlist_move"), enabled = !pending && checked.isNotEmpty() && checked.size < ids.size) { Text(tr(Msg.msg_af45ec8c56b7)) }
            Box {
                ActionIcon(UiIcon.MORE, tr(Msg.msg_7e6258d504ba, title), { menu = true }, Modifier.testTag("playlist_selection_menu"))
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem({ Text(tr(Msg.files_range)) }, { menu = false; range = true; rangeStart = null }, modifier = Modifier.prismFocus().testTag("playlist_range"))
                    DropdownMenuItem({ Text(tr(Msg.playlist_selected_all, shown.size)) }, { menu = false; selected = ArrayList(checked + shown.map(Track::id)) }, modifier = Modifier.prismFocus())
                    DropdownMenuItem({ Text(tr(Msg.msg_a033d47afec5)) }, { menu = false; player.playList(ids.filter(checked::contains), origin = PlaybackOrigin(PlaybackSource.LIST)) },
                        enabled = playable && all.any { it.available && it.id in checked }, modifier = Modifier.prismFocus())
                    DropdownMenuItem({ Text(tr(Msg.msg_9a529d376ca3)) }, { menu = false; addingElsewhere = true }, enabled = checked.isNotEmpty(), modifier = Modifier.prismFocus())
                    DropdownMenuItem({ Text(tr(if (playlistId == null) Msg.msg_334c3299a899 else Msg.playlist_remove_selected)) }, {
                        menu = false
                        val remove = checked
                        scope.launch { if (store.edit(profileId, CollectionEdit.RemoveMany(playlistId, remove))) clearChecks() }
                    }, enabled = !pending && checked.isNotEmpty(), modifier = Modifier.prismFocus().testTag("playlist_remove_selection"))
                }
            }
            undo?.takeIf { it.first == ids }?.let { (expected, original) ->
                TextButton({ applyOrder(expected, original, recordUndo = false) }, Modifier.prismFocus().testTag("playlist_undo_order"), enabled = !pending) {
                    Text(tr(Msg.playlist_undo_order))
                }
            }
        }
        if (range) Text(tr(if (rangeStart == null) Msg.playlist_range_start else Msg.playlist_range_end))
        if (entries.isEmpty()) Text(tr(if (playlistId == null) Msg.msg_c25691d01a87 else Msg.msg_8cd421f138ac), Modifier.padding(8.dp))
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("collection_tracks"), state = listState,
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(shown, key = Track::id) { track ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        TrackRow(track, playback.current?.id == track.id || dragOver == track.id,
                            { player.playList(ids, track.id, origin) }, more = { moreTrack = track },
                            note = if (!track.available && track.source == Source.USB && resolved != null) tr(Msg.files_unavailable) else null,
                            checked = if (edit) track.id in checked else null,
                            toggleSelection = if (edit) ({ select(track.id) }) else null)
                    }
                    if (edit && ids.size > 1) PlaylistDragHandle(track, listState, ids, writable && !pending,
                        hover = { dragOver = it }, choose = { move(setOf(track.id)) },
                        drop = { target ->
                            val end = ids.indexOf(target)
                            val insertBefore = if (end < ids.indexOf(track.id)) target else ids.getOrNull(end + 1)
                            applyOrder(ids, movePlaylistGroup(ids, setOf(track.id), insertBefore))
                        })
                }
            }
        }
    }
    if (sorting) key(playlistId) {
        val snapshot = remember { entries }
        PlaylistSortDialog(snapshot, catalog, { sorting = false }) { sorted ->
            sorting = false
            applyOrder(snapshot.map(SavedTrack::id), sorted)
        }
    }
    if (moving && movingIds.isNotEmpty()) PlaylistMoveDialog(all.filterNot { it.id in movingIds }, { moving = false }) { before ->
        moving = false
        applyOrder(moveBase, movePlaylistGroup(moveBase, movingIds, before))
    }
    if (addingElsewhere) SaveTracksToPlaylistDialog(store, profileId, ids.filter(checked::contains),
        dismiss = { addingElsewhere = false }, saved = { addingElsewhere = false; clearChecks() })
    moreTrack?.let { track -> TrackCollectionDialog(track, store, profileId, track.id in catalog, { moreTrack = null }) }
}

private fun trackMatches(track: Track, query: String) = query.isBlank() || track.title.contains(query, true) ||
    track.artist.contains(query, true) || track.fileName?.contains(query, true) == true

/** Grab the handle directly. The separate click path also opens the precise move dialog for D-pad users. */
@Composable private fun PlaylistDragHandle(track: Track, state: LazyListState, ids: List<String>, enabled: Boolean,
    hover: (String?) -> Unit, choose: () -> Unit, drop: (String) -> Unit) {
    val onDrop by rememberUpdatedState(drop)
    val onHover by rememberUpdatedState(hover)
    ActionIcon(UiIcon.DRAG_HANDLE, tr(Msg.msg_98699315d011), choose,
        Modifier.testTag("playlist_handle_${track.id}").pointerInput(track.id, ids, enabled) {
            if (!enabled) return@pointerInput
            var y = 0f
            var target: String? = null
            detectVerticalDragGestures(
                onDragStart = {
                    val item = state.layoutInfo.visibleItemsInfo.firstOrNull { it.key == track.id }
                    y = (item?.offset ?: 0) + (item?.size ?: 0) / 2f
                    target = null
                },
                onVerticalDrag = { change, delta ->
                    change.consume(); y += delta
                    val rows = state.layoutInfo.visibleItemsInfo
                    if (rows.isNotEmpty()) {
                        val hit = rows.firstOrNull { y < it.offset + it.size } ?: rows.last()
                        target = (hit.key as? String)?.takeUnless { it == track.id }; onHover(target)
                    }
                },
                onDragCancel = { target = null; onHover(null) },
                onDragEnd = { target?.let(onDrop); onHover(null) })
        }, enabled = enabled)
}

@Composable private fun PlaylistMoveDialog(destinations: List<Track>, dismiss: () -> Unit, insert: (String?) -> Unit) {
    var searchBefore by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    StableAlertDialog(onDismissRequest = dismiss, title = { Text(tr(if (searchBefore) Msg.playlist_move_before else Msg.msg_af45ec8c56b7)) },
        text = {
            Column {
                if (!searchBefore) {
                    TextButton({ insert(destinations.firstOrNull()?.id) }, Modifier.fillMaxWidth().prismFocus().testTag("playlist_move_start")) { Text(tr(Msg.playlist_move_first)) }
                    TextButton({ insert(null) }, Modifier.fillMaxWidth().prismFocus().testTag("playlist_move_end")) { Text(tr(Msg.playlist_move_last)) }
                    TextButton({ searchBefore = true }, Modifier.fillMaxWidth().prismFocus().testTag("playlist_move_before")) { Text(tr(Msg.playlist_move_before)) }
                } else {
                    OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().testTag("playlist_find_destination"), singleLine = true, label = { Text(tr(Msg.msg_95a0b91751fc)) })
                    val matches = remember(query, destinations) { destinations.filter { trackMatches(it, query) } }
                    LazyColumn(Modifier.heightIn(max = 260.dp)) {
                        items(matches, key = Track::id) { row ->
                            TextButton({ insert(row.id) }, Modifier.fillMaxWidth().prismFocus().testTag("playlist_before_${row.id}")) {
                                Column(Modifier.fillMaxWidth()) {
                                    Text(row.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text(row.artistLabel(), style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
        }, confirmButton = { TextButton(dismiss, Modifier.prismFocus()) { Text(tr(Msg.msg_8fbe9b75cbdf)) } })
}

@Composable private fun PlaylistSortDialog(rows: List<SavedTrack>, catalog: Map<String, Track>, dismiss: () -> Unit, apply: (List<String>) -> Unit) {
    var order by rememberSaveable { mutableStateOf(PlaylistOrder.TITLE) }
    var options by remember { mutableStateOf(false) }
    fun label(value: PlaylistOrder) = when (value) {
        PlaylistOrder.TITLE -> Msg.msg_0918b4ba9268
        PlaylistOrder.ARTIST -> Msg.msg_037174d58809
        PlaylistOrder.ALBUM -> Msg.msg_0928b8d4c742
        PlaylistOrder.FILE -> Msg.files_sort_name
        PlaylistOrder.ADDED -> Msg.playlist_added_order
    }
    val preview = remember(rows, catalog, order) { orderedPlaylist(rows, catalog, order) }
    val byId = remember(rows) { rows.associateBy(SavedTrack::id) }
    StableAlertDialog(onDismissRequest = dismiss, title = { Text(tr(Msg.playlist_order_action)) }, text = {
        Column {
            Box {
                OutlinedButton({ options = true }, Modifier.fillMaxWidth().prismFocus().testTag("playlist_sort_options")) {
                    Text(tr(label(order))); SkinIcon(UiIcon.DOWN, null)
                }
                DropdownMenu(options, { options = false }) {
                    PlaylistOrder.entries.forEach { key ->
                        DropdownMenuItem({ Text(tr(label(key))) }, { order = key; options = false }, Modifier.prismFocus().testTag("playlist_sort_${key.name}"))
                    }
                }
            }
            key(order) {
                LazyColumn(Modifier.heightIn(max = 270.dp).testTag("playlist_preview")) {
                    itemsIndexed(preview, key = { _, id -> id }) { index, id ->
                        val row = byId.getValue(id).resolve(catalog)
                        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Text("${index + 1}. ${row.title}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(when (order) {
                                PlaylistOrder.ALBUM -> listOfNotNull(row.album.takeIf(String::isNotBlank), row.discNumber?.toString(), row.trackNumber?.toString()).joinToString(" · ")
                                PlaylistOrder.FILE -> row.fileName ?: row.title
                                else -> row.artistLabel()
                            }, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }, confirmButton = { TextButton({ apply(preview) }, Modifier.prismFocus().testTag("playlist_sort_apply")) { Text(tr(Msg.msg_e15f960d93eb)) } },
        dismissButton = { TextButton(dismiss, Modifier.prismFocus()) { Text(tr(Msg.msg_8fbe9b75cbdf)) } })
}
