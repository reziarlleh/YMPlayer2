package dev.petrov.ymplayer2.shell

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.petrov.ymplayer2.core.*
import dev.petrov.ymplayer2.designsystem.*
import dev.petrov.ymplayer2.designsystem.skin.UiIcon
import dev.petrov.ymplayer2.localization.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal suspend fun <T> readMediaFiles(block: suspend () -> T): Result<T> =
    try { Result.success(block()) } catch (e: CancellationException) { throw e }
    catch (e: Exception) {
        val frame = e.stackTrace.firstOrNull { it.className.startsWith("dev.petrov.") }
        android.util.Log.w("LocalMediaFiles", "${e.javaClass.simpleName} at ${frame?.className}.${frame?.methodName}:${frame?.lineNumber}")
        Result.failure(e)
    }

@Composable
internal fun LocalMusicBrowser(
    files: LocalMediaFiles, library: LocalLibrary, profileId: String, manageFolders: () -> Unit,
    play: (ids: List<String>, startId: String?, folder: MediaFileEntry?) -> Unit,
    enqueue: (ids: List<String>, next: Boolean) -> Unit,
    onAddToPlaylist: (List<String>) -> Unit,
    picker: Boolean = false, cancelPicker: () -> Unit = {}, canEnqueue: Boolean = true,
    canPlayNext: Boolean = canEnqueue, upRequest: Int = 0, pickerLabel: String? = null,
    confirmPicker: (suspend (List<String>) -> Boolean)? = null,
) {
    val model: LocalBrowserState = viewModel(key = "media-files/$profileId/$picker")
    val snapshot by library.state.collectAsState()
    val revision = (library as? IndexedLocalLibrary)?.indexRevision ?: 0L
    val current = model.path.lastOrNull()
    val folderKey = current?.uri.orEmpty()
    val selecting = picker || model.selecting
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var partial by remember { mutableStateOf<FileBatchResult?>(null) }
    var fileAction by remember { mutableStateOf<BrowserFileAction?>(null) }
    var toolbarMenu by remember { mutableStateOf(false) }
    var selectionMenu by remember { mutableStateOf(false) }
    var rowMenu by remember { mutableStateOf<String?>(null) }
    val matching = model.query.isNotBlank()
    val roots by produceState<Result<List<MediaFileEntry>>?>(null, files, snapshot.roots, snapshot.ready, refresh) {
        if (snapshot.ready || snapshot.roots.isNotEmpty()) value = readMediaFiles { files.roots() }
    }
    // Re-open provider entries from genuine parent listings after process recreation.
    LaunchedEffect(roots, snapshot.ready, snapshot.scanning) {
        val availableRoots = roots?.getOrNull() ?: return@LaunchedEffect
        if (model.path.isEmpty() && model.savedPath.isNotEmpty() && snapshot.ready && !snapshot.scanning) {
            val route = model.savedPath
            val found = mutableListOf<MediaFileEntry>()
            var level = availableRoots
            for ((i, uri) in route.withIndex()) {
                val entry = level.find { it.uri == uri && it.directory } ?: break
                found += entry
                if (i < route.lastIndex) level = readMediaFiles { files.children(entry) }.getOrNull() ?: break
            }
            model.navigate(found)
        }
        if (model.path.isNotEmpty() && snapshot.ready && !snapshot.scanning &&
            availableRoots.none { it.rootUri == model.path.first().rootUri }) model.navigate(emptyList())
    }
    val listing by key(folderKey, matching) {
        produceState<Result<List<MediaFileEntry>>?>(null, files, roots, revision, refresh) {
            val availableRoots = roots?.getOrNull() ?: return@produceState
            value = readMediaFiles {
                if (matching) files.audioFiles(listOfNotNull(current).ifEmpty { availableRoots }.filter { it.available })
                else current?.let { files.children(it) } ?: availableRoots
            }
        }
    }
    val listed = listing?.getOrNull().orEmpty()
    val rows by produceState(emptyList(), listed, model.query, model.order) {
        val q = model.query.trim()
        val order = model.order
        value = withContext(Dispatchers.Default) {
            val matches = if (q.isEmpty()) listed else listed.filter { it.name.contains(q, true) || it.path.contains(q, true) }
            matches.sortedWith(compareByDescending(MediaFileEntry::directory).then(order.comparator()))
        }
    }
    val folderCounts = remember(model.selected) { selectedFolderCounts(model.selected.values) }
    val availableRoots = roots?.getOrNull().orEmpty()
    val rootCounts = remember(snapshot.roots) { snapshot.roots.associate { it.uri to it.trackCount } }
    val selected = model.selected.values
    fun runAction(block: suspend () -> Unit) {
        if (busy) return
        busy = true; failed = false; partial = null
        // AndroidPlayback and its Media3 engine receive transport commands on the main looper.
        scope.launch(Dispatchers.Main.immediate) {
            try { if (readMediaFiles(block).isFailure) failed = true }
            finally { busy = false }
        }
    }
    fun all(entries: List<MediaFileEntry>, alwaysAdd: Boolean = false) {
        runAction {
            val order = model.order.comparator()
            val expanded = withContext(Dispatchers.Default) { files.audioFiles(entries.filter { it.available }, order) }
            if (entries.size == 1 && entries.first().directory)
                model.discoveredFiles[entries.first().fileKey] = expanded.mapTo(hashSetOf(), MediaFileEntry::fileKey)
            model.toggle(expanded, alwaysAdd)
        }
    }
    fun choose(entry: MediaFileEntry) {
        if (!model.range) { all(listOf(entry)); return }
        val start = model.rangeStart
        if (start == null) model.rangeStart = entry.uri
        else {
            val a = rows.indexOfFirst { it.uri == start }
            val b = rows.indexOfFirst { it.uri == entry.uri }
            if (a >= 0 && b >= 0) all(rows.subList(minOf(a,b), maxOf(a,b)+1), true)
            model.stopRange()
        }
    }
    fun useTracks(entries: List<MediaFileEntry>, action: suspend (List<String>) -> Unit) = runAction {
        val order = model.order.comparator()
        val ids = withContext(Dispatchers.Default) {
            val ordered = files.audioFiles(entries, order)
            files.tracks(ordered).filter(Track::available).map(Track::id)
        }
        if (ids.isEmpty()) failed = true else action(ids)
    }
    fun playFolder(folder: MediaFileEntry) = useTracks(listOf(folder)) { ids -> play(ids, null, folder) }
    fun back() {
        when {
            model.range -> model.stopRange()
            model.searching -> { model.query = ""; model.searching = false }
            current != null -> model.returnToParent()
            picker -> { model.clearSelection(); cancelPicker() }
            model.selecting -> model.clearSelection()
        }
    }
    BackHandler(model.range || model.searching || current != null || selecting) { back() }
    var handledUp by rememberSaveable { mutableIntStateOf(upRequest) }
    LaunchedEffect(upRequest) { if (handledUp != upRequest) { handledUp = upRequest; back() } }
    LaunchedEffect(folderKey) { rowMenu = null }
    LaunchedEffect(revision, refresh) { model.discoveredFiles.clear() }
    Column(Modifier.fillMaxSize().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (current != null) ActionIcon(UiIcon.BACK, tr(Msg.msg_1a9fb1f3cf8e), { model.returnToParent() }, Modifier.testTag("files_up"))
            Text(current?.name ?: tr(Msg.files_dirs), Modifier.weight(1f), maxLines = 1,
                overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
            if (!selecting) TextButton({ model.selecting = true }, Modifier.prismFocus().testTag("bulk_start")) { Text(tr(Msg.bulk_select)) }
            ActionIcon(UiIcon.SEARCH, tr(Msg.msg_180f58ab9753), {
                model.query = ""; model.searching = !model.searching; model.stopRange()
            }, Modifier.testTag("files_search_button"))
            Box {
                ActionIcon(UiIcon.MORE, tr(Msg.files_actions), { toolbarMenu = true })
                DropdownMenu(toolbarMenu, { toolbarMenu = false }) {
                    FileOrder.entries.forEach { sort ->
                        BrowserMenuItem(sort.label(), enabled = model.order != sort) { toolbarMenu = false; model.sort(sort) }
                    }
                    if (!picker && current?.canCreate == true) BrowserMenuItem(tr(Msg.files_create_folder)) {
                        toolbarMenu = false; fileAction = BrowserFileAction.Create(current)
                    }
                    BrowserMenuItem(tr(Msg.msg_8f458964546a)) { toolbarMenu = false; manageFolders() }
                }
            }
        }
        if (model.searching) OutlinedTextField(model.query, { model.query = it; model.stopRange() },
            Modifier.fillMaxWidth().padding(horizontal = 8.dp).testTag("local_file_search"), singleLine = true,
            label = { Text(tr(Msg.files_search_hint), maxLines = 1) })
        if (busy || (listing == null && roots?.isFailure != true)) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (failed || listing?.isFailure == true || roots?.isFailure == true) {
            Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(tr(Msg.files_issue), Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
                TextButton({ failed = false; refresh++ }, Modifier.prismFocus()) { Text(tr(Msg.msg_5189135a6110)) }
            }
        }
        partial?.let { result ->
            Text(tr(Msg.files_partial, result.completed, result.failed.take(5).joinToString()), Modifier.padding(horizontal = 12.dp),
                color = MaterialTheme.colorScheme.error)
        }
        if (model.range) Text(tr(if (model.rangeStart == null) Msg.files_range_start else Msg.files_range_end),
            Modifier.padding(horizontal = 12.dp), color = MaterialTheme.colorScheme.primary)
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            val showFolderRail = maxWidth >= 720.dp && current != null
            Row(Modifier.fillMaxSize()) {
                if (showFolderRail) {
                    LazyColumn(Modifier.widthIn(max = 240.dp).fillMaxWidth(.28f).fillMaxHeight()) {
                        items(availableRoots, key = MediaFileEntry::uri) { root ->
                            TextButton({ model.navigate(listOf(root)) }, Modifier.fillMaxWidth().prismFocus(), enabled = root.available) {
                                Text(root.name + " · " + trMessage(root.source.label))
                            }
                        }
                        items(model.path.drop(1), key = MediaFileEntry::uri) { sub ->
                            TextButton({ model.navigate(model.path.takeWhile { it.uri != sub.uri } + sub) },
                                Modifier.fillMaxWidth().prismFocus()) { Text(sub.name) }
                        }
                    }
                    VerticalDivider()
                }
                key(folderKey, matching) {
                    val previous = model.position[folderKey]
                    val state = rememberLazyListState(previous?.first ?: 0, previous?.second ?: 0)
                    val wanted = remember { model.focused ?: model.lastFocus[folderKey] }
                    val request = remember { FocusRequester() }
                    val keyboard = LocalInputModeManager.current.inputMode == InputMode.Keyboard
                    val showFolderAction = current != null && !matching && !picker
                    DisposableEffect(state) { onDispose { model.position[folderKey] = state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset } }
                    var restored by remember { mutableStateOf(false) }
                    LaunchedEffect(wanted, rows, keyboard) {
                        if (restored || !keyboard || wanted == null) return@LaunchedEffect
                        val index = rows.indexOfFirst { it.uri == wanted }
                        if (index < 0) return@LaunchedEffect
                        if (state.layoutInfo.visibleItemsInfo.none { it.key == wanted })
                            state.scrollToItem(index + if (showFolderAction) 1 else 0)
                        withFrameNanos { }
                        if (state.layoutInfo.visibleItemsInfo.any { it.key == wanted }) {
                            request.requestFocus(); restored = true
                        }
                    }
                    LazyColumn(Modifier.weight(1f).fillMaxHeight().testTag("local_file_list"), state = state, contentPadding = PaddingValues(6.dp)) {
                        if (showFolderAction) item("play-folder") {
                            TextButton({ playFolder(current) }, Modifier.prismFocus().testTag("play_folder"), enabled = !busy && current.available) {
                                SkinIcon(UiIcon.PLAY, null); Spacer(Modifier.width(6.dp)); Text(tr(Msg.files_play_tree))
                            }
                        }
                        if (rows.isEmpty() && listing?.isSuccess == true) item("empty") {
                            Text(tr(if (matching) Msg.files_no_matches else Msg.files_empty), Modifier.padding(12.dp))
                        }
                        items(rows, key = MediaFileEntry::uri) { entry ->
                            val members = model.discoveredFiles[entry.fileKey]
                            val count = members?.count(model.selected::containsKey) ?: folderCounts[entry.rootUri to entry.path] ?: 0
                            val total = members?.size ?: if (entry.parentUri == null) rootCounts[entry.rootUri] else null
                            val checked = when {
                                !entry.directory -> if (entry.fileKey in model.selected) ToggleableState.On else ToggleableState.Off
                                count == 0 -> ToggleableState.Off
                                count == total -> ToggleableState.On
                                else -> ToggleableState.Indeterminate
                            }
                            val hint = when {
                                !entry.available -> tr(Msg.files_unavailable)
                                entry.parentUri == null -> trMessage(entry.source.label)
                                matching -> entry.path.substringBeforeLast(" / ", "")
                                else -> ""
                            }
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                if (selecting) TriStateCheckbox(checked, { choose(entry) },
                                    Modifier.prismFocus().testTag("check_${entry.uri}"), enabled = !busy && entry.available)
                                Row(Modifier.weight(1f).heightIn(min = 48.dp)
                                    .then(if (wanted == entry.uri) Modifier.focusRequester(request) else Modifier)
                                    .onFocusChanged { if (it.isFocused) model.lastFocus[folderKey] = entry.uri }
                                    .prismFocus().clickable(enabled = entry.available && !busy) {
                                        if (model.range) choose(entry)
                                        else if (entry.directory) {
                                            model.lastFocus[folderKey] = entry.uri
                                            model.navigate(model.path + entry)
                                        } else if (selecting) choose(entry)
                                        else useTracks(rows.filter { !it.directory && it.available }) { ids ->
                                            if (entry.trackId in ids) play(ids, entry.trackId, current?.takeUnless { matching }) else failed = true
                                        }
                                    }.testTag("file_${entry.uri}").padding(horizontal = 6.dp, vertical = 5.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    SkinIcon(if (entry.directory) UiIcon.FOLDER else UiIcon.ARTWORK, null)
                                    Spacer(Modifier.width(8.dp))
                                    Column {
                                        Text(entry.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        if (hint.isNotEmpty()) Text(hint, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                                if (!picker) Box {
                                    ActionIcon(UiIcon.MORE, tr(Msg.files_actions), { rowMenu = entry.uri }, enabled = !busy && entry.available)
                                    if (rowMenu == entry.uri) DropdownMenu(true, { rowMenu = null }) {
                                        fun action(block: () -> Unit) { rowMenu = null; block() }
                                        if (entry.directory) BrowserMenuItem(tr(Msg.files_play_tree)) { action { playFolder(entry) } }
                                        if (!entry.directory && entry.trackId != null) {
                                            BrowserMenuItem(tr(Msg.bulk_playlist)) { action { useTracks(listOf(entry)) { onAddToPlaylist(it) } } }
                                            if (canPlayNext) BrowserMenuItem(tr(Msg.files_next)) { action { useTracks(listOf(entry)) { enqueue(it, true) } } }
                                            if (canEnqueue) BrowserMenuItem(tr(Msg.files_append)) { action { useTracks(listOf(entry)) { enqueue(it, false) } } }
                                        }
                                        FileMutationMenu(listOf(entry)) { next -> action { fileAction = next } }
                                    }
                                }
                            }
                        }
                        if (current == null && !matching) item("manage") {
                            OutlinedButton(manageFolders, Modifier.prismFocus().testTag("manage_folders")) { Text(tr(Msg.msg_8f458964546a)) }
                        }
                    }
                }
            }
        }
        if (selecting) {
            HorizontalDivider()
            BoxWithConstraints(Modifier.fillMaxWidth()) {
            val compact = maxWidth < 280.dp
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (!picker) Button({
                    useTracks(selected.toList()) { ids -> play(ids, null, null); model.clearSelection() }
                }, Modifier.weight(1f).prismFocus().testTag("files_play_selected"), contentPadding = PaddingValues(4.dp), enabled = !busy && selected.isNotEmpty()) {
                    Text(tr(Msg.files_play_selected, selected.size), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                if (picker || !compact) OutlinedButton({
                    useTracks(selected.toList()) { ids ->
                        if (picker && confirmPicker != null) {
                            if (confirmPicker(ids)) { model.clearSelection(); model.navigate(emptyList()) } else failed = true
                        } else onAddToPlaylist(ids)
                    }
                }, Modifier.weight(1f).prismFocus().testTag("bulk_playlist"), contentPadding = PaddingValues(4.dp), enabled = !busy && selected.isNotEmpty()) {
                    Text(if (picker && pickerLabel != null) tr(Msg.files_to_named, pickerLabel, selected.size) else tr(Msg.bulk_playlist),
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Box {
                    ActionIcon(UiIcon.MORE, tr(Msg.files_actions), { selectionMenu = true }, Modifier.testTag("files_selection_menu"), enabled = !busy)
                    DropdownMenu(selectionMenu, { selectionMenu = false }) {
                        fun close(block: () -> Unit) { selectionMenu = false; block() }
                        BrowserMenuItem(tr(if (matching) Msg.files_select_matches else Msg.files_select_all, rows.size), rows.isNotEmpty()) {
                            close { all(rows, true) }
                        }
                        BrowserMenuItem(tr(Msg.files_range), rows.size > 1) { close { model.range = true; model.rangeStart = null } }
                        BrowserMenuItem(tr(Msg.msg_8965271d3c97), selected.isNotEmpty()) { close { model.clearSelection(); model.selecting = true } }
                        if (!picker && selected.isNotEmpty()) {
                            if (compact) BrowserMenuItem(tr(Msg.bulk_playlist)) { close { useTracks(selected.toList()) { onAddToPlaylist(it) } } }
                            if (canPlayNext) BrowserMenuItem(tr(Msg.files_next)) { close { useTracks(selected.toList()) { enqueue(it, true); model.clearSelection() } } }
                            if (canEnqueue) BrowserMenuItem(tr(Msg.files_append)) { close { useTracks(selected.toList()) { enqueue(it, false); model.clearSelection() } } }
                            FileMutationMenu(selected.toList()) { next -> close { fileAction = next } }
                        }
                    }
                }
                ActionIcon(UiIcon.CLOSE, tr(Msg.msg_8fbe9b75cbdf), {
                    model.clearSelection(); if (picker) { model.navigate(emptyList()); cancelPicker() }
                })
            }
            }
        }
    }
    fileAction?.let { action ->
        LocalFileActionDialog(action, files, { fileAction = null }) { result ->
            fileAction = null; refresh++; model.discoveredFiles.clear()
            if (result?.failed?.isNotEmpty() == true) partial = result
            if (action is BrowserFileAction.Delete || action is BrowserFileAction.Transfer && action.move) model.clearSelection()
        }
    }
}

@Composable internal fun BrowserMenuItem(label: String, enabled: Boolean = true, action: () -> Unit) {
    DropdownMenuItem({ Text(label) }, action, Modifier.prismFocus(), enabled = enabled)
}

@Composable private fun FileOrder.label(): String = when (this) {
    FileOrder.NAME -> tr(Msg.files_sort_name) + " ↑"
    FileOrder.REVERSE_NAME -> tr(Msg.files_sort_name) + " ↓"
    FileOrder.NEWEST -> tr(Msg.files_sort_newest)
    FileOrder.OLDEST -> tr(Msg.files_sort_oldest)
}
