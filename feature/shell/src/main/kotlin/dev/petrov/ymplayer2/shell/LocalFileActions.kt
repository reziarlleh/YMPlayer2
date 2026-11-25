package dev.petrov.ymplayer2.shell

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.petrov.ymplayer2.core.*
import dev.petrov.ymplayer2.designsystem.*
import dev.petrov.ymplayer2.designsystem.skin.UiIcon
import dev.petrov.ymplayer2.localization.*
import kotlinx.coroutines.launch

internal sealed interface BrowserFileAction {
    data class Create(val folder: MediaFileEntry) : BrowserFileAction
    data class Rename(val item: MediaFileEntry) : BrowserFileAction
    data class Delete(val entries: List<MediaFileEntry>) : BrowserFileAction
    data class Transfer(val entries: List<MediaFileEntry>, val move: Boolean) : BrowserFileAction
}

@Composable internal fun FileMutationMenu(entries: List<MediaFileEntry>, command: (BrowserFileAction) -> Unit) {
    val first = entries.singleOrNull()
    if (first?.canRename == true) BrowserMenuItem(tr(Msg.msg_194306e20807)) { command(BrowserFileAction.Rename(first)) }
    if (entries.isNotEmpty() && entries.all { it.available }) {
        BrowserMenuItem(tr(Msg.files_copy)) { command(BrowserFileAction.Transfer(entries, false)) }
        if (entries.all { it.canDelete }) {
            BrowserMenuItem(tr(Msg.msg_af45ec8c56b7)) { command(BrowserFileAction.Transfer(entries, true)) }
            BrowserMenuItem(tr(Msg.msg_be99b1361201)) { command(BrowserFileAction.Delete(entries)) }
        }
    }
}

@Composable internal fun LocalFileActionDialog(action: BrowserFileAction, files: LocalMediaFiles,
    dismiss: () -> Unit, success: (FileBatchResult?) -> Unit) {
    var name by rememberSaveable(action) { mutableStateOf((action as? BrowserFileAction.Rename)?.item?.name.orEmpty()) }
    var destination by remember(action) { mutableStateOf(emptyList<MediaFileEntry>()) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val folder = destination.lastOrNull()
    val safeName = name.isNotBlank() && name.trim() !in setOf(".", "..") && name.none { it == '/' || it == '\\' || it.isISOControl() }
    val valid = when (action) {
        is BrowserFileAction.Create -> safeName
        is BrowserFileAction.Rename -> safeName && name.trim() != action.item.name
        is BrowserFileAction.Delete -> action.entries.isNotEmpty()
        is BrowserFileAction.Transfer -> folder?.canCreate == true && action.entries.none { entry ->
            entry.rootUri == folder.rootUri && (entry.uri == folder.uri || entry.parentUri == folder.uri ||
                entry.directory && folder.path.startsWith(entry.path + " / "))
        }
    }
    val title = when (action) {
        is BrowserFileAction.Create -> tr(Msg.files_create_folder)
        is BrowserFileAction.Rename -> tr(Msg.msg_194306e20807)
        is BrowserFileAction.Transfer -> tr(Msg.files_destination)
        is BrowserFileAction.Delete -> action.entries.singleOrNull()?.let { tr(Msg.files_delete_named, it.name) }
            ?: tr(Msg.files_delete_many, action.entries.size)
    }
    val confirm = when (action) {
        is BrowserFileAction.Create -> tr(Msg.files_create_folder)
        is BrowserFileAction.Rename -> tr(Msg.msg_194306e20807)
        is BrowserFileAction.Delete -> tr(Msg.msg_be99b1361201)
        is BrowserFileAction.Transfer -> tr(if (action.move) Msg.msg_af45ec8c56b7 else Msg.files_copy)
    }
    AlertDialog(onDismissRequest = { if (!busy) dismiss() }, title = { Text(title) }, text = {
        Column {
            when (action) {
                is BrowserFileAction.Create, is BrowserFileAction.Rename -> OutlinedTextField(name,
                    { name = it; failed = false }, Modifier.fillMaxWidth(), label = { Text(tr(Msg.msg_0918b4ba9268)) },
                    enabled = !busy, singleLine = true)
                is BrowserFileAction.Delete -> if (action.entries.any(MediaFileEntry::directory)) Text(tr(Msg.files_directory_delete_warning)) else Unit
                is BrowserFileAction.Transfer -> {
                    val children by key(folder?.uri) { produceState<Result<List<MediaFileEntry>>?>(null, files) {
                        value = readMediaFiles {
                            (if (folder == null) files.roots() else files.children(folder)).filter { it.directory && it.available }
                                .sortedWith(compareBy(mediaFileNameOrder, MediaFileEntry::name))
                        }
                    } }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (folder != null) ActionIcon(UiIcon.BACK, tr(Msg.msg_1a9fb1f3cf8e), { destination = destination.dropLast(1) }, enabled = !busy)
                        Text(folder?.path ?: tr(Msg.files_dirs), Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    if (children == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (children?.isFailure == true) Text(tr(Msg.files_issue), color = MaterialTheme.colorScheme.error)
                    LazyColumn(Modifier.heightIn(max = 280.dp)) { items(children?.getOrNull().orEmpty(), key = MediaFileEntry::uri) { child ->
                        TextButton({ destination = destination + child; failed = false }, Modifier.fillMaxWidth().prismFocus(),
                            enabled = !busy && action.entries.none { it.uri == child.uri && it.directory }) {
                            SkinIcon(UiIcon.FOLDER, null); Spacer(Modifier.width(8.dp))
                            Text(child.name, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    } }
                }
            }
            if (failed) Text(tr(Msg.files_issue), color = MaterialTheme.colorScheme.error)
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }, confirmButton = {
        TextButton({
            if (!busy) {
                busy = true
                scope.launch {
                    try {
                        readMediaFiles {
                            when (action) {
                                is BrowserFileAction.Create -> { files.createFolder(action.folder, name.trim()); null }
                                is BrowserFileAction.Rename -> { files.rename(action.item, name.trim()); null }
                                is BrowserFileAction.Delete -> files.delete(action.entries)
                                is BrowserFileAction.Transfer -> files.transfer(action.entries, requireNotNull(folder), action.move)
                            }
                        }.onSuccess(success).onFailure { failed = true }
                    } finally { busy = false }
                }
            }
        }, Modifier.prismFocus(), enabled = valid && !busy) { Text(confirm) }
    }, dismissButton = { TextButton(dismiss, Modifier.prismFocus(), enabled = !busy) { Text(tr(Msg.msg_8fbe9b75cbdf)) } })
}
