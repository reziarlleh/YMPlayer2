package dev.petrov.ymplayer2.shell

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.petrov.ymplayer2.core.*
import dev.petrov.ymplayer2.designsystem.prismFocus

@Composable internal fun AddToCloudPlaylist(track: Track, playlists: CloudPlaylists, dismiss: () -> Unit) {
    val state by playlists.state.collectAsStateWithLifecycle()
    if (state.owner != null && track.cloudTrackKey() != null) TextButton({ dismiss(); playlists.choose(track) },
        Modifier.prismFocus().testTag("cloud_add_track"), enabled = !state.busy) { Text("Добавить в плейлист Яндекса") }
}

@Composable internal fun CloudPlaylistDialog(playlists: CloudPlaylists) {
    val state by playlists.state.collectAsStateWithLifecycle()
    val mode = state.dialog ?: return
    var title by rememberSaveable(state.owner, mode) { mutableStateOf("") }
    Dialog(playlists::dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val focus = LocalFocusManager.current
        val keyboard = LocalSoftwareKeyboardController.current
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().imePadding(), contentAlignment = Alignment.Center) {
            Surface(Modifier.widthIn(max = 640.dp).fillMaxWidth().heightIn(max = minOf(maxHeight, 640.dp)).padding(12.dp), shape = MaterialTheme.shapes.large) {
                Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(when (mode) {
                        PlaylistDialog.CHOOSE -> "Плейлисты Яндекса"
                        PlaylistDialog.CREATE -> "Новый плейлист Яндекса"
                        PlaylistDialog.DELETE -> "Удалить плейлист?"
                        PlaylistDialog.RESULT -> "Плейлисты Яндекса"
                    }, style = MaterialTheme.typography.titleLarge)
                    LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("cloud_dialog_list"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        state.track?.let { item { Text("Трек: ${it.title}") } }
                        if (state.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Выполняем…") }
                        state.message?.let { item { Text(it, Modifier.testTag("cloud_message")) } }
                        state.issue?.let { item { Text(it, Modifier.testTag("cloud_issue"), color = MaterialTheme.colorScheme.error) } }
                        when (mode) {
                            PlaylistDialog.CREATE -> {
                                item { Text("Будет создан приватный плейлист в текущем аккаунте Яндекса.") }
                                item { OutlinedTextField(title, { if (it.length <= 200) title = it }, Modifier.fillMaxWidth().prismFocus().testTag("cloud_title").onPreviewKeyEvent {
                                        it.type == KeyEventType.KeyDown && it.key == Key.DirectionDown && focus.moveFocus(FocusDirection.Down)
                                    },
                                    label = { Text("Название") }, singleLine = true, enabled = !state.busy,
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { keyboard?.hide(); focus.moveFocus(FocusDirection.Next) })) }
                                item { Button({ playlists.create(title) }, Modifier.fillMaxWidth().prismFocus().testTag("cloud_create_confirm"), enabled = title.isNotBlank() && !state.busy) {
                                    Text(if (state.track == null) "Создать" else "Создать и добавить трек")
                                } }
                            }
                            PlaylistDialog.CHOOSE -> {
                                item { OutlinedButton({ playlists.newPlaylist() }, Modifier.fillMaxWidth().prismFocus().testTag("cloud_new"), enabled = !state.busy) { Text("Создать новый плейлист") } }
                                item { TextButton(playlists::refresh, Modifier.prismFocus().testTag("cloud_refresh"), enabled = !state.busy) { Text("Обновить плейлисты") } }
                                if (state.loaded && state.playlists.isEmpty()) item { Text("Плейлистов пока нет.") }
                                if (state.loaded) items(state.playlists, key = { "${it.ownerId}:${it.id}" }) { playlist ->
                                    OutlinedButton({ playlists.add(playlist) }, Modifier.fillMaxWidth().prismFocus().testTag("cloud_playlist_${playlist.id}"), enabled = !state.busy && state.track != null) {
                                        Text("${playlist.title} · ${playlist.trackCount} треков")
                                    }
                                }
                            }
                            PlaylistDialog.DELETE -> {
                                item { Text("Плейлист «${state.target?.title}» будет удалён из вашего аккаунта Яндекса на всех устройствах. Текущая очередь и отметки «Мне нравится» сохранятся.") }
                                item { Button(playlists::delete, Modifier.fillMaxWidth().prismFocus().testTag("cloud_delete_confirm"), enabled = !state.busy) { Text("Удалить плейлист") } }
                            }
                            PlaylistDialog.RESULT -> Unit
                        }
                    }
                    TextButton(playlists::dismiss, Modifier.align(Alignment.End).prismFocus().testTag("cloud_close"), enabled = !state.busy) { Text("Закрыть") }
                }
            }
        }
    }
}
