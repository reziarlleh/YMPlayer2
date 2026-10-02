package dev.petrov.ymplayer2.shell

import dev.petrov.ymplayer2.localization.*

import android.content.res.Configuration
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.petrov.ymplayer2.core.*
import dev.petrov.ymplayer2.designsystem.prismFocus
import dev.petrov.ymplayer2.designsystem.ActionIcon
import dev.petrov.ymplayer2.designsystem.SkinIcon
import dev.petrov.ymplayer2.designsystem.skin.UiIcon

@Composable internal fun AddToCloudPlaylist(track: Track, playlists: CloudPlaylists, dismiss: () -> Unit) {
    val state by playlists.state.collectAsStateWithLifecycle()
    if (state.owner != null && track.cloudTrackKey() != null) TextButton({ dismiss(); playlists.choose(track) },
        Modifier.prismFocus().testTag("cloud_add_track"), enabled = !state.busy) { Text(tr(Msg.msg_767475640343)) }
}

@Composable internal fun CloudPlaylistDialog(playlists: CloudPlaylists) {
    val observed = playlists.state.collectAsStateWithLifecycle()
    val state = observed.value
    val mode = state.dialog ?: return
    var title by rememberSaveable(state.owner, mode, state.target?.id) { mutableStateOf(if (mode == PlaylistDialog.RENAME) state.target?.title.orEmpty() else "") }
    var position by rememberSaveable(state.owner, mode, state.selectedIndex) { mutableStateOf(((state.selectedIndex ?: 0) + 1).toString()) }
    val subEditor = mode in setOf(PlaylistDialog.RENAME, PlaylistDialog.REMOVE, PlaylistDialog.MOVE)
    val close = { if (subEditor) playlists.backToEditor() else playlists.dismiss() }
    val editorSnapshot = state.snapshot.takeIf { mode == PlaylistDialog.EDIT }
    val order = remember(editorSnapshot) { editorSnapshot?.tracks?.indices?.toList()?.toMutableStateList() ?: mutableStateListOf<Int>() }
    val listState = rememberLazyListState()
    val handleBounds = remember(editorSnapshot) { mutableMapOf<Int, Rect>() }
    var listCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var overlayCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var dragOrigin by remember(editorSnapshot) { mutableIntStateOf(-1) }
    var dragY by remember(editorSnapshot) { mutableFloatStateOf(0f) }
    fun previewDrag() {
        val original = dragOrigin
        if (original !in order) return
        val others = order.filter { it != original }
        val visible = listState.layoutInfo.visibleItemsInfo.mapNotNull { item ->
            val index = (item.key as? String)?.removePrefix("cloud_editor_item_")?.toIntOrNull() ?: return@mapNotNull null
            val inOthers = others.indexOf(index)
            if (inOthers < 0) null else item to inOthers
        }.sortedBy { it.first.offset }
        if (visible.isEmpty()) return
        var destination = visible.first().second
        for ((item, index) in visible) if (dragY >= item.offset + item.size / 2f) destination = index + 1
        destination = destination.coerceIn(0, others.size)
        if (order.indexOf(original) != destination) {
            order.remove(original)
            order.add(destination, original)
        }
    }
    val edge = with(LocalDensity.current) { 64.dp.toPx() }
    val touchReorder = LocalConfiguration.current.uiMode and Configuration.UI_MODE_TYPE_MASK != Configuration.UI_MODE_TYPE_TELEVISION
    // A fixed overlay above the handle column owns the gesture even when the
    // dragged lazy item scrolls off screen; the list itself handles ordinary scroll.
    val reorderGesture = remember(editorSnapshot, state.busy, touchReorder) {
        if (mode == PlaylistDialog.EDIT && touchReorder && editorSnapshot != null) Modifier.pointerInput(editorSnapshot, state.busy) {
            if (!state.busy) detectDragGesturesAfterLongPress(
                onDragStart = { offset ->
                    val yInList = listCoordinates?.let { list -> overlayCoordinates?.let { list.localPositionOf(it, offset) } }
                    val original = yInList?.let { touch -> handleBounds.entries.firstOrNull { it.value.contains(touch) }?.key }
                    if (original != null && yInList != null && original in order) { dragOrigin = original; dragY = yInList.y }
                },
                onDragEnd = {
                    val from = dragOrigin
                    val to = order.indexOf(from)
                    dragOrigin = -1
                    if (from >= 0 && to >= 0 && from != to) playlists.dragMove(from, to)
                },
                onDragCancel = {
                    dragOrigin = -1; order.clear(); order.addAll(editorSnapshot.tracks.indices.toList())
                },
                onDrag = { change, amount ->
                    if (dragOrigin >= 0) { change.consume(); dragY += amount.y; previewDrag() }
                })
        } else Modifier
    }
    LaunchedEffect(dragOrigin, state.busy) {
        while (dragOrigin >= 0 && !state.busy) {
            val info = listState.layoutInfo
            val speed = when {
                dragY < info.viewportStartOffset + edge -> -18f
                dragY > info.viewportEndOffset - edge -> 18f
                else -> 0f
            }
            if (speed != 0f) { listState.scrollBy(speed); previewDrag() }
            kotlinx.coroutines.delay(16)
        }
    }
    Dialog(close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val focus = LocalFocusManager.current
        val keyboard = LocalSoftwareKeyboardController.current
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().imePadding(), contentAlignment = Alignment.Center) {
            Surface(Modifier.widthIn(max = 640.dp).fillMaxWidth().heightIn(max = minOf(maxHeight, 640.dp)).padding(12.dp), shape = MaterialTheme.shapes.large) {
                Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(when (mode) {
                        PlaylistDialog.CHOOSE -> tr(Msg.msg_cfde40242cd9)
                        PlaylistDialog.CREATE -> tr(Msg.msg_556fab910a7b)
                        PlaylistDialog.DELETE -> tr(Msg.msg_4f41558e776b)
                        PlaylistDialog.RESULT -> tr(Msg.msg_cfde40242cd9)
                        PlaylistDialog.EDIT -> tr(Msg.msg_417875482e57)
                        PlaylistDialog.RENAME -> tr(Msg.msg_717012565e0a)
                        PlaylistDialog.REMOVE -> tr(Msg.msg_c011a7b7c5c5)
                        PlaylistDialog.MOVE -> tr(Msg.msg_98699315d011)
                    }, style = MaterialTheme.typography.titleLarge)
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                    LazyColumn(Modifier.fillMaxSize().onGloballyPositioned { listCoordinates = it }
                        .testTag("cloud_dialog_list"), state = listState,
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        // Lazy content can run before parent recomposition. Read mode and selection
                        // from one fresh snapshot, including when the account closes the dialog.
                        val state = observed.value
                        val mode = state.dialog
                        state.track?.let { item { Text(tr(Msg.msg_6bd804f5b776, it.title)) } }
                        if (state.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(tr(Msg.msg_5651a421a70e)) }
                        state.message?.let { item { Text(trMessage(it), Modifier.testTag("cloud_message")) } }
                        state.issue?.let { item { Text(trMessage(it), Modifier.testTag("cloud_issue"), color = MaterialTheme.colorScheme.error) } }
                        when (mode) {
                            PlaylistDialog.CREATE -> {
                                item { Text(tr(Msg.msg_c78b1aee4241)) }
                                item { OutlinedTextField(title, { if (it.length <= 200) title = it }, Modifier.fillMaxWidth().prismFocus().testTag("cloud_title").onPreviewKeyEvent {
                                        it.type == KeyEventType.KeyDown && it.key == Key.DirectionDown && focus.moveFocus(FocusDirection.Down)
                                    },
                                    label = { Text(tr(Msg.msg_0918b4ba9268)) }, singleLine = true, enabled = !state.busy,
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { keyboard?.hide(); focus.moveFocus(FocusDirection.Next) })) }
                                item { Button({ playlists.create(title) }, Modifier.fillMaxWidth().prismFocus().testTag("cloud_create_confirm"), enabled = title.isNotBlank() && !state.busy) {
                                    Text(if (state.track == null) tr(Msg.msg_e2be34974ae4) else tr(Msg.msg_6aadca8eb39a))
                                } }
                            }
                            PlaylistDialog.CHOOSE -> {
                                item { OutlinedButton({ playlists.newPlaylist() }, Modifier.fillMaxWidth().prismFocus().testTag("cloud_new"), enabled = !state.busy) { Text(tr(Msg.msg_5710ad29b999)) } }
                                item { TextButton(playlists::refresh, Modifier.prismFocus().testTag("cloud_refresh"), enabled = !state.busy) { Text(tr(Msg.msg_85a21c8d0f56)) } }
                                if (state.loaded && state.playlists.isEmpty()) item { Text(tr(Msg.msg_272bf8adef84)) }
                                if (state.loaded) items(state.playlists, key = { "${it.ownerId}:${it.id}" }) { playlist ->
                                    OutlinedButton({ playlists.add(playlist) }, Modifier.fillMaxWidth().prismFocus().testTag("cloud_playlist_${playlist.id}"), enabled = !state.busy && state.track != null) {
                                        Text(tr(Msg.msg_60bbcc5198aa, playlist.title, playlist.trackCount))
                                    }
                                }
                            }
                            PlaylistDialog.DELETE -> {
                                item { Text(tr(Msg.msg_589983337a78, state.target?.title)) }
                                item { Button(playlists::delete, Modifier.fillMaxWidth().prismFocus().testTag("cloud_delete_confirm"), enabled = !state.busy) { Text(tr(Msg.msg_1dd7755458c1)) } }
                            }
                            PlaylistDialog.RESULT, null -> Unit
                            PlaylistDialog.EDIT -> {
                                item { Text(state.target?.title.orEmpty(), style = MaterialTheme.typography.titleMedium) }
                                item { OutlinedButton({ playlists.editorAction(PlaylistDialog.RENAME) }, Modifier.prismFocus().testTag("cloud_rename"), enabled = state.loaded && !state.busy) { Text(tr(Msg.msg_194306e20807)) } }
                                item { TextButton(playlists::refreshEditor, Modifier.prismFocus().testTag("cloud_editor_refresh"), enabled = !state.busy) { Text(tr(Msg.msg_442f1380374f)) } }
                                item { Text(if (touchReorder)
                                    tr(Msg.msg_53c57c59cc24)
                                    else tr(Msg.msg_b8ad90801037)) }
                                if (state.loaded && editorSnapshot?.tracks?.isEmpty() == true) item { Text(tr(Msg.msg_55870b7a873d)) }
                                if (state.loaded && editorSnapshot != null) itemsIndexed(order, key = { _, original -> "cloud_editor_item_$original" }) { displayIndex, original ->
                                    DisposableEffect(original) { onDispose { handleBounds.remove(original) } }
                                    // The lazy item can compose before its parent after a readback. Keep
                                    // the displayed order and its source snapshot from the same frame.
                                    val entry = editorSnapshot.tracks[original]
                                    Surface(Modifier.fillMaxWidth().testTag("cloud_editor_row_$original"), shape = MaterialTheme.shapes.medium,
                                        color = MaterialTheme.colorScheme.surfaceContainerLow) {
                                        Column(Modifier.fillMaxWidth()) {
                                            Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 2.dp),
                                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                                TrackArtwork(null, Modifier.size(44.dp))
                                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                                    Text(entry.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                                                    if (entry.artist.isNotBlank()) Text(entry.artist, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                                        style = MaterialTheme.typography.bodySmall)
                                                    Text(tr(Msg.msg_9a3adc7135e4, displayIndex + 1), style = MaterialTheme.typography.labelSmall)
                                                }
                                            }
                                            Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 4.dp, bottom = 4.dp),
                                                verticalAlignment = Alignment.CenterVertically) {
                                                if (!entry.movable) Text(tr(Msg.msg_0fad384ca45b), Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
                                                else Spacer(Modifier.weight(1f))
                                                TextButton({ playlists.editorAction(PlaylistDialog.MOVE, original) },
                                                    Modifier.prismFocus().testTag("cloud_move_$original"), enabled = !state.busy && dragOrigin < 0 && entry.movable && editorSnapshot.tracks.size > 1,
                                                    contentPadding = PaddingValues(horizontal = 8.dp)) { Text(tr(Msg.msg_e09846033f94)) }
                                                ActionIcon(UiIcon.REMOVE, tr(Msg.msg_ce1456ccd84b, entry.title),
                                                    { playlists.editorAction(PlaylistDialog.REMOVE, original) }, Modifier.testTag("cloud_remove_$original"),
                                                    enabled = !state.busy && dragOrigin < 0)
                                                if (touchReorder && entry.movable && editorSnapshot.tracks.size > 1) Box(
                                                    Modifier.size(48.dp).testTag("cloud_drag_$original").semantics {
                                                        contentDescription = tr(Msg.msg_9641a49f03aa, entry.title)
                                                    }.onGloballyPositioned { coordinates ->
                                                        val parent = listCoordinates?.boundsInRoot() ?: return@onGloballyPositioned
                                                        val bounds = coordinates.boundsInRoot()
                                                        handleBounds[original] = Rect(bounds.left - parent.left, bounds.top - parent.top,
                                                            bounds.right - parent.left, bounds.bottom - parent.top)
                                                    }, contentAlignment = Alignment.Center) {
                                                    SkinIcon(UiIcon.DRAG_HANDLE, null)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            PlaylistDialog.RENAME -> {
                                item { OutlinedTextField(title, { if (it.length <= 200) title = it }, Modifier.fillMaxWidth().prismFocus().testTag("cloud_rename_title").onPreviewKeyEvent {
                                    it.type == KeyEventType.KeyDown && it.key == Key.DirectionDown && focus.moveFocus(FocusDirection.Down)
                                }, label = { Text(tr(Msg.msg_0918b4ba9268)) }, singleLine = true, enabled = !state.busy,
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { keyboard?.hide(); focus.moveFocus(FocusDirection.Next) })) }
                                item { Button({ playlists.rename(title) }, Modifier.prismFocus().testTag("cloud_rename_confirm"), enabled = !state.busy && title.isNotBlank()) { Text(tr(Msg.msg_620bc932f669)) } }
                            }
                            PlaylistDialog.REMOVE -> {
                                val index = state.selectedIndex!!; val entry = state.snapshot!!.tracks[index]
                                item { Text(tr(Msg.msg_4e50f6bdc764, entry.title, index + 1, state.target?.title)) }
                                item { Button(playlists::removeTrack, Modifier.prismFocus().testTag("cloud_remove_confirm"), enabled = !state.busy) { Text(tr(Msg.msg_414209446572)) } }
                            }
                            PlaylistDialog.MOVE -> {
                                val snapshot = state.snapshot!!; val index = state.selectedIndex!!
                                val to = position.toIntOrNull()?.minus(1)
                                item { Text("${index + 1}. ${snapshot.tracks[index].title}") }
                                item { OutlinedTextField(position, { if (it.length <= 7 && it.all { c -> c in '0'..'9' }) position = it }, Modifier.fillMaxWidth().prismFocus().testTag("cloud_move_position").onPreviewKeyEvent {
                                    it.type == KeyEventType.KeyDown && it.key == Key.DirectionDown && focus.moveFocus(FocusDirection.Down)
                                }, label = { Text(tr(Msg.msg_601de107e288, snapshot.tracks.size)) }, singleLine = true, enabled = !state.busy,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { keyboard?.hide(); focus.moveFocus(FocusDirection.Next) })) }
                                item { Button({ to?.let(playlists::moveTrack) }, Modifier.prismFocus().testTag("cloud_move_confirm"), enabled = !state.busy && to != null && to in snapshot.tracks.indices && to != index) { Text(tr(Msg.msg_af45ec8c56b7)) } }
                            }
                        }
                    }
                    if (mode == PlaylistDialog.EDIT && touchReorder && editorSnapshot != null) Box(
                        Modifier.align(Alignment.CenterEnd).width(56.dp).fillMaxHeight()
                            .onGloballyPositioned { overlayCoordinates = it }.then(reorderGesture))
                    }
                    TextButton(close, Modifier.align(Alignment.End).prismFocus().testTag("cloud_close"), enabled = !state.busy) { Text(if (subEditor) tr(Msg.msg_8fbe9b75cbdf) else tr(Msg.msg_a7a4033657e8)) }
                }
            }
        }
    }
}
