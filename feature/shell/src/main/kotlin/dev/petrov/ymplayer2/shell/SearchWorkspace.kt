package dev.petrov.ymplayer2.shell

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import dev.petrov.ymplayer2.core.*
import dev.petrov.ymplayer2.designsystem.ChoiceRow
import dev.petrov.ymplayer2.designsystem.prismFocus
import dev.petrov.ymplayer2.designsystem.ActionIcon
import dev.petrov.ymplayer2.designsystem.SkinIcon
import dev.petrov.ymplayer2.designsystem.skin.UiIcon
import dev.petrov.ymplayer2.localization.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

@Composable internal fun SearchWorkspace(model: ShellModel, library: LibrarySnapshot, yandexSelected: Boolean,
    query: String, onQueryChange: (String) -> Unit, showSources: Boolean,
    chooseYandex: (Boolean) -> Unit, signIn: () -> Unit, waveStarted: () -> Unit,
    openArtist: (ArtistRef) -> Unit, showTrackActions: (Track) -> Unit, exporter: AudioExportAccess?) {
    val holder = rememberSaveableStateHolder()
    val yandex = model.yandexSearch
    val online = yandexSelected && yandex != null
    val sources: @Composable () -> Unit = {
        if (showSources && yandex != null) ChoiceRow(if (online) "yandex" else "local", Modifier.fillMaxWidth()) { choice ->
            FilterChip(!online, { chooseYandex(false) }, { Text(tr(Msg.library_local_files)) },
                choice("local").prismFocus().testTag("source_local"))
            FilterChip(online, { chooseYandex(true) }, { Text(tr(Msg.msg_c39959813ccd)) },
                choice("yandex").prismFocus().testTag("source_yandex"))
        }
    }
    holder.SaveableStateProvider(if (online) "yandex" else "local") {
        if (online) {
            OnlineScreen(yandex, model.player, true, signIn, model.taste, waveStarted, artist = openArtist,
                playlists = model.cloudPlaylists, searchQuery = query, onSearchQueryChange = onQueryChange,
                exporter = exporter, searchSources = sources)
        } else LocalSearchScreen(model, library, query, onQueryChange, sources, showTrackActions)
    }
}

@Composable internal fun SearchInput(query: String, onQueryChange: (String) -> Unit, tag: String) {
    val keyboard = LocalSoftwareKeyboardController.current
    OutlinedTextField(query, { onQueryChange(it.take(200)) }, Modifier.fillMaxWidth().testTag(tag).prismFocus(),
        label = { Text(tr(Msg.msg_180f58ab9753)) },
        placeholder = { Text(tr(Msg.msg_1d74ce16d44e)) }, singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
        leadingIcon = { SkinIcon(UiIcon.SEARCH, null) },
        trailingIcon = { if (query.isNotEmpty()) ActionIcon(UiIcon.CLOSE, tr(Msg.msg_6e1f7baa472f), { onQueryChange("") }) })
}

@Composable private fun LocalSearchScreen(model: ShellModel, library: LibrarySnapshot, query: String,
    onQueryChange: (String) -> Unit, searchSources: @Composable () -> Unit, showTrackActions: (Track) -> Unit) {
    val playback by model.player.state.collectAsState()
    val keyboard = LocalSoftwareKeyboardController.current
    val selection = rememberTrackSelection(playback.profileId, query)
    val indexed = model.local as? IndexedLocalLibrary
    val revision = indexed?.indexRevision
    var limit by rememberSaveable(query) { mutableIntStateOf(80) }
    var retry by remember { mutableIntStateOf(0) }
    val result by key(query, revision, library, retry) {
        produceState<Result<CatalogPage<Track>>?>(null, limit) {
            if (query.isBlank()) return@produceState
            val previous = value?.getOrNull()
            if (previous == null) delay(180)
            val filter = CatalogFilter(query = query)
            value = try {
                val page = if (indexed != null) {
                    val start = previous?.items?.size ?: 0
                    val next = indexed.pageTracks(filter, offset = start, limit = limit - start)
                    CatalogPage(previous?.items.orEmpty() + next.items, next.total, 0)
                } else {
                    val localTracks = if (model.local == null) model.catalog.tracks(playback.profileId) else library.tracks
                    CatalogQueries.tracks(localTracks.filter { it.source != Source.YANDEX }, filter, limit = limit)
                }
                Result.success(page)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }
    val page = result?.getOrNull()
    val tracks = page?.items.orEmpty()
    val ids = remember(tracks) { tracks.filter(Track::available).map(Track::id) }
    val queued = playback.explicitQueueIds ?: remember(playback.queue) { playback.queue.mapTo(hashSetOf(), Track::id) }

    fun play(start: String? = null) {
        model.player.playList(ids, start, PlaybackOrigin(PlaybackSource.LIST))
        keyboard?.hide()
    }

    Column(Modifier.fillMaxSize()) {
        if (selection.active) SelectionToolbar(selection, model.player, model.collections)
        LazyColumn(Modifier.weight(1f).fillMaxWidth().imePadding().testTag("catalog_list"),
            contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item(key = "search_input") { SearchInput(query, onQueryChange, "search_input") }
            item(key = "search_sources") { searchSources() }
            when {
                query.isBlank() -> item {
                    Text(tr(Msg.search_prompt), Modifier.testTag("search_prompt"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                result?.isFailure == true -> item {
                    Text(tr(Msg.msg_293a0bf80248), color = MaterialTheme.colorScheme.error)
                    OutlinedButton({ retry++ }, Modifier.prismFocus()) { Text(tr(Msg.msg_5189135a6110)) }
                }
                page == null || !library.ready && tracks.isEmpty() && model.local != null -> item {
                    LinearProgressIndicator(Modifier.fillMaxWidth().testTag("local_search_loading"))
                }
                tracks.isEmpty() -> item { Text(tr(Msg.msg_480fae1c4bc9), Modifier.testTag("local_search_empty")) }
                else -> {
                    item(key = "result_actions") {
                        ResultActions {
                            if (ids.isNotEmpty()) OutlinedButton({ play() }, Modifier.prismFocus().testTag("catalog_play_all")) {
                                Text(tr(Msg.msg_9cbbe230cb66))
                            }
                            if (!selection.active) BeginTrackSelection(selection)
                        }
                    }
                    items(tracks, key = Track::id) { track ->
                        TrackRow(track, play = { play(track.id) }, enqueue = { model.player.enqueue(track.id) },
                            queued = track.id in queued, more = if (model.collections != null) ({ showTrackActions(track) }) else null,
                            checked = if (selection.active) selection.contains(track.id) else null,
                            toggleSelection = if (selection.active) ({ selection.toggle(track) }) else null)
                    }
                    if (page.hasMore && tracks.size == limit && (indexed != null || limit < 50_000)) item {
                        OutlinedButton({ limit = if (indexed != null) limit + 80 else (limit + 80).coerceAtMost(50_000) },
                            Modifier.prismFocus().testTag("catalog_more")) { Text(tr(Msg.msg_fc0f3616f567)) }
                    }
                }
            }
        }
    }
}
