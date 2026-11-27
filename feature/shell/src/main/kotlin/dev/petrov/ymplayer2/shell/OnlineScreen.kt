package dev.petrov.ymplayer2.shell

import dev.petrov.ymplayer2.localization.*

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.petrov.ymplayer2.core.*
import dev.petrov.ymplayer2.designsystem.*
import dev.petrov.ymplayer2.designsystem.skin.*

@Composable internal fun OnlineScreen(music: OnlineMusic, player: PlaybackController, search: Boolean, signIn: () -> Unit, taste: MusicTaste? = null, waveStarted: () -> Unit = {},
    artist: (ArtistRef) -> Unit = { music.open(MusicEntity(it.id, it.name, MusicKind.ARTISTS)) }, standalone: Boolean = false, closeArtist: () -> Unit = {}, playlists: CloudPlaylists? = null,
    searchQuery: String? = null, onSearchQueryChange: (String) -> Unit = {}, exporter: AudioExportAccess? = null,
    searchSources: @Composable () -> Unit = {}, librarySelection: Boolean = false) {
    val state by music.state.collectAsStateWithLifecycle()
    val playback by player.state.collectAsStateWithLifecycle()
    val cloudState = playlists?.state?.collectAsStateWithLifecycle()?.value
    val keyboard = LocalSoftwareKeyboardController.current
    val request = state.request
    val query = searchQuery ?: request.query
    val detail = request.entity
    val topSelected = search && (request.topSearch || request.collection)
    val matchesQuery = !search || detail != null ||
        query.isNotBlank() && !request.collection && request.entity == null && request.query == query.take(200)
    fun submitSearch(value: String) { if (topSelected) music.searchTop(value) else music.search(value, request.kind) }
    fun changeQuery(value: String) { onSearchQueryChange(value); submitSearch(value) }
    val selection = rememberTrackSelection(playback.profileId, state.profileId, state.signedIn, request, query, search)
    fun up() { if (standalone && detail?.kind == MusicKind.ARTISTS) closeArtist() else music.up() }
    BackHandler(detail != null) { up() }
    LaunchedEffect(search, searchQuery, state.profileId, state.signedIn) {
        if (!standalone && detail == null) {
            if (search && (request.collection || request.query != query.take(200))) submitSearch(query)
            if (!search && state.signedIn && (!request.collection || !state.loaded && !state.loading && state.issue == null)) music.collection()
        }
    }
    val preferences = taste?.state?.collectAsStateWithLifecycle()?.value
    var actions by remember(state.profileId) { mutableStateOf<Track?>(null) }
    if (taste != null) actions?.let { track -> TrackTasteDialog(track, taste, artist = artist, player = player, started = waveStarted,
        extra = { playlists?.let { AddToCloudPlaylist(track, it) { actions = null } } }) { actions = null } }
    val entries = if (request.recommended) state.entries.filter { entry -> entry.track?.let { preferences?.allows(it) != false } ?: true } else state.entries
    val shownTracks = entries.mapNotNull(MusicEntry::track)
    val librarySelectionMode = remember(selection) { OnlineLibrarySelection(selection) }
    val filtersReady = !request.recommended || preferences?.let { it.shelf(TasteKind.TRACK).ready && it.shelf(TasteKind.ARTIST).ready } != false
    fun play(trackId: String? = null) {
        if (!filtersReady) return
        if (request.recommended) player.playRecommendedQueue(shownTracks.map(Track::id), trackId, request.playbackOrigin(music.accounts.state.value.account?.id))
        else player.playList(shownTracks.map(Track::id), trackId, request.playbackOrigin(music.accounts.state.value.account?.id))
        keyboard?.hide()
    }
    val queued = playback.explicitQueueIds ?: playback.queue.mapTo(hashSetOf(), Track::id)
    Column(Modifier.fillMaxSize()) {
    if (!librarySelection && matchesQuery && selection.active) SelectionToolbar(selection, player)
    LazyColumn(Modifier.weight(1f).fillMaxWidth().imePadding().testTag("online_list"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (search && detail == null) {
            item(key = "search_input") { SearchInput(query, ::changeQuery, "online_query") }
            item(key = "search_sources") { searchSources() }
        }
        if (!search || detail != null) item {
            if (detail != null) TextButton({ if (librarySelection && selection.active) librarySelectionMode.back() else up() }, Modifier.prismFocus().testTag("online_up")) { Text(tr(Msg.msg_1478970270d1)) }
            val title: @Composable () -> Unit = { Text(detail?.title ?: if (search) tr(Msg.msg_fede8859c53f) else tr(Msg.msg_5fbef357e55e), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
            val target = detail?.tasteTarget()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    if (state.signedIn && taste != null && target != null) Column { TasteLabel(taste, target, "detail", title) } else title()
                }
                if (state.signedIn && detail != null) EntityWaveMenu(detail, player, waveStarted, "detail", exporter?.let { e -> { entity -> e.ask(AudioExportRequest(state.profileId, entity.title, entity)) } })
            }
        }
        if (!state.signedIn) {
            item {
                Text(tr(Msg.msg_741ca2439e4d))
                OutlinedButton(signIn, Modifier.prismFocus().testTag("online_sign_in")) { Text(tr(Msg.msg_612f724ec00f)) }
            }
        } else {
            if (cloudState?.owner != null && playlists != null && detail != null && playlists.editable(detail)) item {
                OutlinedButton({ playlists.openEditor(detail) }, Modifier.prismFocus().testTag("cloud_edit")) { Text(tr(Msg.msg_b0ad5bf65466)) }
                OutlinedButton({ playlists.askDelete(detail) }, Modifier.prismFocus().testTag("cloud_delete")) { Text(tr(Msg.msg_48b5898b7140)) }
            }
            if (detail?.kind == MusicKind.ARTISTS) item {
                ChoiceRow(request.kind.name, Modifier.fillMaxWidth()) { choice ->
                    listOf(MusicKind.TRACKS to tr(Msg.msg_3c99d6538c1d), MusicKind.ALBUMS to tr(Msg.msg_90c141192bd7)).forEach { (kind, label) ->
                        FilterChip(request.kind == kind, { music.artistSection(kind) }, { Text(label) }, choice(kind.name).prismFocus().testTag("artist_section_${kind.name}"))
                    }
                }
            }
            if (detail == null) {
                item {
                    val selected = if (topSelected) "TOP" else if (request.recommended) "recommended" else request.kind.name
                    ChoiceRow(selected, Modifier.fillMaxWidth()) { choice ->
                        if (search) FilterChip(topSelected, { music.searchTop(query, debounce = false) }, { Text(tr(Msg.search_top)) },
                            choice("TOP").prismFocus().testTag("online_kind_TOP"))
                        MusicKind.entries.forEach { kind ->
                            FilterChip(!topSelected && request.kind == kind && !request.recommended, { if (search) music.search(query, kind, false) else music.collection(kind) },
                                { Text(if (search) trMessage(kind.label) else when (kind) {
                                    MusicKind.TRACKS -> tr(Msg.msg_840370e3fad2)
                                    MusicKind.ARTISTS -> tr(Msg.msg_263e20096cc0)
                                    MusicKind.ALBUMS -> tr(Msg.msg_b86ab3d74350)
                                    MusicKind.PLAYLISTS -> tr(Msg.msg_44d8126b8321)
                                }) }, choice(kind.name).prismFocus().testTag("online_kind_${kind.name}"))
                        }
                        if (!search) FilterChip(request.recommended, music::recommendations, { Text(tr(Msg.msg_fb7d156c5bfe)) }, choice("recommended").prismFocus().testTag("online_recommendations"))
                    }
                }
                if (!search && request.recommended) item { Text(tr(Msg.msg_999770a38a9d)) }
                if (!search && !request.recommended && request.kind == MusicKind.PLAYLISTS && playlists != null) item {
                    OutlinedButton({ playlists.newPlaylist(null) }, Modifier.prismFocus().testTag("cloud_create"), enabled = cloudState?.owner != null && !cloudState.busy) { Text(tr(Msg.msg_14b10e3e0473)) }
                }
            }
            if (search && detail == null && query.isBlank()) {
                item { Text(tr(Msg.search_prompt), Modifier.testTag("online_empty"), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                return@LazyColumn
            }
            if (!matchesQuery) return@LazyColumn
            if (state.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth().testTag("online_loading")); Text(tr(Msg.msg_22a5d9112486)) }
            if (state.issue != null) item {
                Text(trIssue(state.issue!!), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("online_issue"))
                OutlinedButton(music::retry, Modifier.prismFocus().testTag("online_retry"), enabled = !state.loading) { Text(tr(Msg.msg_5189135a6110)) }
            }
            if (!state.loading && state.issue == null && entries.isEmpty()) item {
                Text(if (state.loaded) tr(Msg.msg_2f9654f4d1a1) else tr(Msg.msg_39cef1f5fa63), Modifier.testTag("online_empty"))
            }
            if (shownTracks.isNotEmpty() && (!librarySelection || !selection.active)) item(key = "result_actions") {
                if (!filtersReady) {
                    Text(tr(Msg.msg_a3e2c7d263a6))
                    TextButton({ taste?.refresh(TasteKind.TRACK); taste?.refresh(TasteKind.ARTIST) }, Modifier.prismFocus()) { Text(tr(Msg.msg_68cc12d616b8)) }
                }
                val playShown: @Composable () -> Unit = {
                    if (shownTracks.any { it.available }) OutlinedButton({ play() }, Modifier.prismFocus().testTag("online_play_all"), enabled = filtersReady) {
                        Text(tr(Msg.msg_9cbbe230cb66))
                    }
                }
                if (search && detail == null) ResultActions {
                    playShown()
                    if (!selection.active) BeginTrackSelection(selection)
                } else {
                    playShown()
                    if (!selection.active) {
                        if (librarySelection) BeginTrackSelection(selection) else SelectionToolbar(selection, player)
                    }
                }
            }
            items(entries, key = MusicEntry::id) { entry ->
                val track = entry.track
                val wave = entry.wave
                if (wave != null) GenreWaveSearchRow(entry, wave) {
                    keyboard?.hide(); player.playWave(wave.request); waveStarted()
                }
                else if (track != null) TrackRow(track,
                    play = { play(track.id) },
                    enqueue = { player.enqueue(track.id) }, queued = track.id in queued, more = if (taste != null) ({ actions = track }) else null, taste = taste, artist = artist,
                    checked = if (selection.active) selection.contains(track.id) else null,
                    toggleSelection = if (selection.active) ({
                        if (librarySelection) librarySelectionMode.toggle(track, shownTracks) else selection.toggle(track)
                    }) else null)
                else entry.entity?.let { entity ->
                    val label: @Composable () -> Unit = { Surface(onClick = { keyboard?.hide(); music.open(entity) }, modifier = Modifier.fillMaxWidth().prismFocus().testTag("online_entity_${entity.id}"), shape = MaterialTheme.shapes.medium) {
                        Column(Modifier.padding(16.dp)) {
                            Text(entry.title, fontWeight = FontWeight.Bold)
                            Text(entry.subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } }
                    val target = entity.tasteTarget()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) { if (taste != null && target != null) Column { TasteLabel(taste, target, "catalog", label) } else label() }
                        EntityWaveMenu(entity, player, waveStarted, "catalog", exporter?.let { e -> { entity -> e.ask(AudioExportRequest(state.profileId, entity.title, entity)) } })
                    }
                }
            }
            if (state.nextPage != null && state.issue == null) item {
                OutlinedButton(music::more, Modifier.prismFocus().testTag("online_more"), enabled = !state.loading) { Text(tr(Msg.msg_a137dd0ef761)) }
            }
        }
    }
    if (librarySelection && selection.active) OnlineLibrarySelectionBar(
        librarySelectionMode, shownTracks, state, music, player, filtersReady,
        allows = { !request.recommended || preferences?.allows(it) != false },
        play = { tracks -> player.playList(tracks, origin = request.playbackOrigin(music.accounts.state.value.account?.id)) },
    )
    }
}

@Composable private fun GenreWaveSearchRow(entry: MusicEntry, wave: WaveSearchResult, start: () -> Unit) {
    Surface(onClick = start, modifier = Modifier.fillMaxWidth().prismFocus().testTag("online_${entry.id}"), shape = MaterialTheme.shapes.medium) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val artwork = Modifier.size(48.dp).clip(MaterialTheme.shapes.small)
            PublicArtwork(wave.artworkUri, "", artwork, ContentScale.Crop, "online_wave_artwork") {
                Box(artwork, contentAlignment = Alignment.Center) { SkinIcon(UiIcon.WAVE, null) }
            }
            Column(Modifier.weight(1f)) {
                Text(entry.title, fontWeight = FontWeight.Bold)
                Text(tr(Msg.search_genre_wave), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
