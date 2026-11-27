package dev.petrov.ymplayer2.shell

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.petrov.ymplayer2.core.*
import dev.petrov.ymplayer2.designsystem.*
import dev.petrov.ymplayer2.designsystem.skin.UiIcon
import dev.petrov.ymplayer2.localization.*

@Composable internal fun LibraryWorkspace(model: ShellModel, playback: PlaybackState, files: LocalMediaFiles?,
    cloud: Boolean, chooseSource: (Boolean) -> Unit, navigate: (String) -> Unit,
    openArtist: (ArtistRef) -> Unit, exporter: AudioExportAccess?) {
    val holder = rememberSaveableStateHolder()
    var playlists by rememberSaveable { mutableStateOf(false) }
    var more by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            ChoiceRow(if (cloud) "yandex" else "local", Modifier.weight(1f)) { choice ->
                FilterChip(!cloud, { chooseSource(false) }, { Text(tr(Msg.library_local_files)) },
                    choice("local").prismFocus().testTag("source_local"))
                if (model.online != null) FilterChip(cloud, { chooseSource(true) }, { Text(tr(Msg.msg_c39959813ccd)) },
                    choice("yandex").prismFocus().testTag("source_yandex"))
            }
            Box {
                if (!cloud && model.local is IndexedLocalLibrary || model.history != null) ActionIcon(UiIcon.MORE, tr(Msg.msg_7075085d9334),
                    { more = true }, Modifier.testTag("library_more"))
                DropdownMenu(more, { more = false }) {
                    if (!cloud && model.local is IndexedLocalLibrary) DropdownMenuItem({ Text(tr(Msg.recent_title)) },
                        { more = false; navigate("recent") }, modifier = Modifier.testTag("open_recent"))
                    if (model.history != null) DropdownMenuItem({ Text(tr(Msg.history_title)) },
                        { more = false; navigate("history") }, modifier = Modifier.testTag("open_history"))
                }
            }
        }
        if (!cloud && model.collections != null) ChoiceRow(if (playlists) "lists" else "files",
            Modifier.fillMaxWidth().padding(horizontal = 12.dp)) { choice ->
            FilterChip(!playlists, { playlists = false }, { Text(tr(Msg.files_dirs)) },
                choice("files").prismFocus().testTag("library_folders"))
            FilterChip(playlists, { playlists = true }, { Text(tr(Msg.msg_2efea0f8794f)) },
                choice("lists").prismFocus().testTag("category_PLAYLISTS"))
        }
        when {
            cloud && model.online != null -> holder.SaveableStateProvider("cloud") {
                OnlineScreen(model.online, model.player, false, { navigate("account") }, model.taste, { navigate("player") },
                    artist = openArtist, playlists = model.cloudPlaylists, exporter = exporter, librarySelection = true)
            }
            playlists -> holder.SaveableStateProvider("playlists") {
                LocalPlaylistWorkspace(model, playback, files, false, navigate)
            }
            files != null && model.local != null -> holder.SaveableStateProvider("files") {
                var pending by remember { mutableStateOf<List<String>>(emptyList()) }
                val radio = model.radio?.playback?.collectAsState()?.value
                val editableQueue = playback.connected && radio?.ownsOutput != true && !playback.wave && playback.origin.source !in
                    setOf(PlaybackSource.OFFLINE, PlaybackSource.MY_WAVE, PlaybackSource.OBJECT_WAVE)
                LocalMusicBrowser(files, requireNotNull(model.local), playback.profileId,
                    manageFolders = { navigate("folders") },
                    play = { ids, start, folder -> model.player.playList(ids, start,
                        folder?.let { LocalListTarget.folder(it).origin() } ?: PlaybackOrigin(PlaybackSource.LIST)) },
                    enqueue = { ids, next -> if (next) model.player.enqueueNext(ids) else model.player.enqueueMany(ids) },
                    onAddToPlaylist = { pending = it },
                    canEnqueue = editableQueue,
                    canPlayNext = editableQueue && playback.queueCount > 0 && !playback.shuffle && playback.repeatMode != RepeatMode.ONE)
                if (pending.isNotEmpty() && model.collections != null) SaveTracksToPlaylistDialog(model.collections,
                    playback.profileId, pending, dismiss = { pending = emptyList() }, saved = { pending = emptyList() })
            }
            else -> CatalogScreen(if (model.local == null) model.catalog.tracks(playback.profileId) else emptyList(),
                model.player, false, CatalogState.READY, demo = model.local == null, folders = { navigate("folders") },
                indexed = model.local as? IndexedLocalLibrary, retry = model::refresh)
        }
    }
}

@Composable internal fun LocalPlaylistWorkspace(model: ShellModel, playback: PlaybackState, files: LocalMediaFiles?,
    favorites: Boolean, navigate: (String) -> Unit) {
    val store = model.collections ?: return
    CollectionsScreen(store, playback.profileId, if (model.local == null) model.catalog.tracks(playback.profileId) else emptyList(),
        model.player, favorites, model.local as? IndexedLocalLibrary) { name, confirm, cancel ->
        if (files != null && model.local != null) LocalMusicBrowser(files, requireNotNull(model.local), playback.profileId,
            manageFolders = { navigate("folders") }, play = { _, _, _ -> }, enqueue = { _, _ -> }, onAddToPlaylist = {},
            picker = true, cancelPicker = cancel, canEnqueue = false, pickerLabel = name, confirmPicker = confirm)
        else TextButton(cancel, Modifier.prismFocus()) { Text(trMessage("Назад")) }
    }
}
