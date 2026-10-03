package dev.petrov.ymplayer2.shell

import dev.petrov.ymplayer2.localization.*

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.petrov.ymplayer2.core.*
import dev.petrov.ymplayer2.designsystem.*
import dev.petrov.ymplayer2.designsystem.skin.*
import kotlinx.coroutines.CancellationException

@Composable internal fun CatalogScreen(tracks: List<Track>, player: PlaybackController, search: Boolean, state: CatalogState,
    demo: Boolean = true, folders: () -> Unit = {}, scanning: Boolean = false, issue: String? = null,
    collections: Boolean = false, playlists: () -> Unit = {}, favorites: () -> Unit = {}, more: ((Track) -> Unit)? = null, upRequest: Int = 0,
    indexed: IndexedLocalLibrary? = null, indexedSources: Set<Source> = emptySet(), noLocalRoots: Boolean = false,
    music: OnlineMusic? = null, taste: MusicTaste? = null, cached: List<Track> = emptyList(),
    artist: (ArtistRef) -> Unit = {}, cloudPlaylists: CloudPlaylists? = null, signIn: () -> Unit = {},
    onlineHome: () -> Unit = {}, waveStarted: () -> Unit = {}, cacheOnly: Boolean = false,
    searchQuery: String? = null, onSearchQueryChange: (String) -> Unit = {}, collectionStore: UserCollections? = null, retry: () -> Unit) {
    var localQuery by rememberSaveable { mutableStateOf("") }
    val query = searchQuery ?: localQuery
    val changeQuery: (String) -> Unit = { localQuery = it; onSearchQueryChange(it) }
    var source by rememberSaveable { mutableStateOf<Source?>(null) }
    var offline by rememberSaveable { mutableStateOf(false) }
    var availableOnly by rememberSaveable { mutableStateOf(false) }
    var category by rememberSaveable { mutableStateOf(Category.TRACKS) }
    var detail by rememberSaveable { mutableStateOf<String?>(null) }
    var handledUpRequest by rememberSaveable { mutableIntStateOf(upRequest) }
    LaunchedEffect(upRequest) { if (handledUpRequest != upRequest) { detail = null; handledUpRequest = upRequest } }
    var descending by rememberSaveable { mutableStateOf(false) }
    val holder = rememberSaveableStateHolder()
    val keyboard = LocalSoftwareKeyboardController.current
    val playback by player.state.collectAsState()
    val online = music?.state?.collectAsState()?.value?.takeIf { it.profileId == playback.profileId }
    var trackActions by remember(playback.profileId) { mutableStateOf<Track?>(null) }
    if (taste != null) trackActions?.let { track -> TrackTasteDialog(track, taste, artist = artist,
        extra = { cloudPlaylists?.let { AddToCloudPlaylist(track, it) { trackActions = null } } }) { trackActions = null } }
    if (!cacheOnly && online?.request?.entity != null) {
        OnlineScreen(music, player, search, signIn, taste, waveStarted, artist, playlists = cloudPlaylists,
            searchQuery = searchQuery, onSearchQueryChange = changeQuery)
        return
    }
    val queuedIds = playback.explicitQueueIds ?: remember(playback.queue) { playback.queue.mapTo(hashSetOf(), Track::id) }
    BackHandler(!cacheOnly && detail != null) { detail = null }
    val sources = (if (indexed != null) indexedSources else remember(tracks) { tracks.mapTo(linkedSetOf(), Track::source) }) +
        if (music != null) setOf(Source.YANDEX) else emptySet()
    val filter = CatalogFilter(if (cacheOnly) Source.YANDEX else source, cacheOnly || offline || state == CatalogState.OFFLINE, availableOnly, if (search) query else "")
    val currentDetail = if (cacheOnly) null else detail
    val currentCategory = if (cacheOnly) Category.TRACKS else category
    val grouped = currentDetail == null && currentCategory != Category.TRACKS && (!search || music != null)
    val dimension = currentCategory.dimension()
    val selection = rememberTrackSelection(playback.profileId, filter, currentCategory, currentDetail, descending, search, upRequest)
    val remoteKind = when (currentCategory) {
        Category.TRACKS -> MusicKind.TRACKS
        Category.ALBUMS -> MusicKind.ALBUMS
        Category.ARTISTS -> MusicKind.ARTISTS
        else -> null
    }
    val remoteRequested = music != null && currentDetail == null && !cacheOnly && !offline && remoteKind != null &&
        (source == null || source == Source.YANDEX)
    val remoteRequest = remoteKind?.let { MusicRequest(if (search) query.take(200) else "", it, collection = !search) }
    LaunchedEffect(remoteRequested, remoteRequest, online?.profileId, online?.signedIn) {
        if (remoteRequested && remoteRequest != null && online != null) {
            val unchanged = online.request == remoteRequest && (online.loaded || online.loading || online.issue != null)
            if (!unchanged) {
                if (search) music.search(remoteRequest.query, remoteRequest.kind)
                else if (online.signedIn) music.collection(remoteRequest.kind)
            }
        }
    }
    val remote = online?.takeIf { remoteRequested && it.request == remoteRequest }
    // Only already downloaded likes join the offline view. No collection-wide audio transfer.
    val cachedById = remember(cached) { cached.associateBy(Track::id) }
    val remoteTracks = if (!grouped && currentDetail == null && (cacheOnly || source == null || source == Source.YANDEX)) {
        (remote?.entries?.mapNotNull(MusicEntry::track).orEmpty() + cached.filter { track ->
            !search || query.isBlank() || track.title.contains(query, true) || track.artist.contains(query, true) || track.album.contains(query, true) })
            .distinctBy(Track::id).map { track -> cachedById[track.id] ?: track }
            .filter { track -> (!(cacheOnly || offline) || track.offline && track.available) && (!availableOnly || track.available) }
    } else emptyList()
    val remoteEntities = if (grouped) remote?.entries?.filter { it.entity != null }.orEmpty() else emptyList()
    var visibleCount by rememberSaveable(filter, currentCategory, currentDetail, descending, search) { mutableIntStateOf(80) }
    val groupBatches = remember(indexed, indexed?.indexRevision, filter, dimension, descending) {
        mutableMapOf<Int, CatalogPage<CatalogGroup>>()
    }
    val trackBatches = remember(indexed, indexed?.indexRevision, filter, dimension, currentDetail, descending) {
        mutableMapOf<Int, CatalogPage<Track>>()
    }
    val diskGroups by key(indexed, indexed?.indexRevision, filter, dimension, descending, visibleCount, grouped) { produceState<Result<CatalogPage<CatalogGroup>>?>(null,
        indexed, indexed?.indexRevision, filter, dimension, descending, visibleCount, grouped) {
        value = if (indexed != null && grouped) catalogResult {
            for (offset in 0 until visibleCount step 80) {
                if (offset !in groupBatches) groupBatches[offset] = indexed.pageGroups(filter, dimension, descending, offset, 80)
                if (!groupBatches.getValue(offset).hasMore) break
            }
            CatalogPage(groupBatches.toSortedMap().values.flatMap { it.items }, groupBatches[0]?.total ?: 0, 0)
        } else null
    } }
    val diskPage by key(indexed, indexed?.indexRevision, filter, dimension, currentDetail, descending, visibleCount, grouped) { produceState<Result<CatalogPage<Track>>?>(null,
        indexed, indexed?.indexRevision, filter, dimension, currentDetail, descending, visibleCount, grouped) {
        value = if (indexed != null && !grouped) catalogResult {
            for (offset in 0 until visibleCount step 80) {
                if (offset !in trackBatches) trackBatches[offset] = indexed.pageTracks(filter, descending, currentDetail, dimension, offset, 80)
                if (!trackBatches.getValue(offset).hasMore) break
            }
            CatalogPage(trackBatches.toSortedMap().values.flatMap { it.items }, trackBatches[0]?.total ?: 0, 0)
        } else null
    } }
    val groups = if (indexed != null) diskGroups?.getOrNull() else if (grouped) remember(tracks, filter, currentCategory, descending, visibleCount) {
        CatalogQueries.groups(tracks, filter, { it.group(currentCategory) }, descending, limit = visibleCount)
    } else null
    val page = if (indexed != null) diskPage?.getOrNull() else if (!grouped) remember(tracks, filter, currentCategory, currentDetail, descending, visibleCount) {
        CatalogQueries.tracks(tracks, filter, descending, currentDetail, { it.group(currentCategory) }, limit = visibleCount)
    } else null
    val indexLoading = indexed != null && (if (grouped) diskGroups == null else diskPage == null)
    val indexFailed = indexed != null && (if (grouped) diskGroups?.isFailure == true else diskPage?.isFailure == true)
    val resultCount = groups?.total ?: page?.total ?: 0
    val shownTracks = (page?.items.orEmpty() + remoteTracks).sortedWith(
        compareBy<Track> { it.title.lowercase() }.thenBy(Track::id).let { if (descending) it.reversed() else it })
    val shownGroups = (groups?.items.orEmpty().map { CatalogGroupRow(local = it) } +
        remoteEntities.map { CatalogGroupRow(remote = it) }).sortedWith(
        compareBy<CatalogGroupRow> { it.title.lowercase() }.thenBy { it.key }.let { if (descending) it.reversed() else it })
    val loadMore = { visibleCount = if (indexed == null) (visibleCount + 80).coerceAtMost(50_000) else visibleCount + 80 }
    Column(Modifier.fillMaxSize()) {
    if (selection.active) SelectionToolbar(selection, player, collectionStore)
    holder.SaveableStateProvider(currentDetail ?: "root") {
        LazyColumn(Modifier.weight(1f).fillMaxWidth().imePadding().testTag("catalog_list"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (currentDetail != null) ActionIcon(UiIcon.BACK, tr(Msg.msg_b0f0ab86be80), { detail = null })
                    Text(currentDetail ?: if (search) tr(Msg.msg_180f58ab9753) else tr(Msg.msg_0a20ddc9928f), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    if (!grouped && !selection.active) OutlinedButton({ selection.active = true }, Modifier.prismFocus().testTag("bulk_start")) { Text(tr(Msg.bulk_select)) }
                }
            }
            if (!demo && !search && currentDetail == null) item {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(folders, Modifier.prismFocus().testTag("manage_folders")) { SkinIcon(UiIcon.FOLDER, null); Spacer(Modifier.width(8.dp)); Text(tr(Msg.msg_8f458964546a)) }
                if (music != null) {
                    OutlinedButton(onlineHome, Modifier.prismFocus().testTag("online_home")) { Text(tr(Msg.msg_6d6a9bb2179b)) }
                    if (taste != null) WaveButton(player, online?.signedIn == true, signIn, waveStarted)
                }
                }
            }
            if (scanning) item { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(tr(Msg.msg_4a87a9c8e472)) }
            if (issue != null) item { Text(trIssue(issue), color = MaterialTheme.colorScheme.error) }
            if (currentDetail == null) {
                if (search) item {
                    OutlinedTextField(query, changeQuery, Modifier.fillMaxWidth().testTag("search_input").prismFocus(),
                        label = { Text(tr(Msg.msg_1d74ce16d44e)) }, singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                        leadingIcon = { SkinIcon(UiIcon.SEARCH, null) },
                        trailingIcon = { if (query.isNotEmpty()) ActionIcon(UiIcon.CLOSE, tr(Msg.msg_6e1f7baa472f), { changeQuery("") }) })
                }
                if (!cacheOnly && (!search || music != null)) item {
                    ChoiceRow(currentCategory.name, Modifier.fillMaxWidth()) { choice ->
                        Category.entries.filter { demo || collections || it != Category.PLAYLISTS }.forEach { item ->
                            FilterChip(currentCategory == item, { if (item == Category.PLAYLISTS && collections) playlists() else category = item }, { Text(trMessage(item.label)) }, choice(item.name).prismFocus().testTag("category_${item.name}"))
                        }
                        if (collections) AssistChip(favorites, { Text(tr(Msg.msg_e4c1d01c5c8f)) }, choice("FAVORITES").prismFocus().testTag("category_FAVORITES"), leadingIcon = { SkinIcon(UiIcon.FAVORITE, null) })
                    }
                }
                if (!cacheOnly) item {
                    ChoiceRow(source?.name ?: "ALL", Modifier.fillMaxWidth()) { choice ->
                        FilterChip(source == null, { source = null }, { Text(tr(Msg.msg_71e214e1477f)) }, choice("ALL").prismFocus().testTag("filter_all"))
                        Source.entries.filter { it in sources }.forEach { item ->
                            FilterChip(source == item, { source = item }, { Text(trMessage(item.label)) }, choice(item.name).prismFocus().testTag("filter_${item.name}"))
                        }
                    }
                }
                if (!cacheOnly) item {
                    ChoiceRow(if (offline) "offline" else if (availableOnly) "available" else "offline", Modifier.fillMaxWidth()) { choice ->
                        FilterChip(offline, { offline = !offline }, { Text(tr(Msg.msg_8514965e4ae0)) }, choice("offline").prismFocus().testTag("filter_offline"))
                        FilterChip(availableOnly, { availableOnly = !availableOnly }, { Text(tr(Msg.msg_20c3a85f6767)) }, choice("available").prismFocus().testTag("filter_available"))
                        AssistChip({ descending = !descending }, { Text(if (descending) tr(Msg.msg_cb3d171cdbfd) else tr(Msg.msg_fa58ba03d4c5)) }, choice("sort").prismFocus())
                    }
                }
            }
            if (remoteRequested && online?.signedIn == false) item {
                Text(tr(Msg.msg_a55fc5d0f320))
                OutlinedButton(signIn, Modifier.prismFocus().testTag("catalog_sign_in")) { Text(tr(Msg.msg_612f724ec00f)) }
            }
            if (remote?.loading == true) item { LinearProgressIndicator(Modifier.fillMaxWidth().testTag("catalog_online_loading")) }
            if (remote?.issue != null) item {
                Text(trIssue(remote.issue!!), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("catalog_online_issue"))
                OutlinedButton({ music.retry() }, Modifier.prismFocus().testTag("catalog_online_retry"), enabled = !remote.loading) { Text(tr(Msg.msg_be28ea14a37e)) }
            }
            if (music != null && currentDetail == null && offline && grouped && source != Source.LOCAL && source != Source.USB) item {
                Text(tr(Msg.msg_3d7762ba837e))
            }
            if (music != null && currentDetail == null && remoteKind == null) item {
                Text(tr(Msg.msg_29ff5117d5b1))
            }
            if (indexLoading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (indexFailed) item { CatalogMessage(tr(Msg.msg_293a0bf80248), tr(Msg.msg_c6087f7e1289), retry, tr(Msg.msg_603e460bf59c)) }
            when {
                !demo && noLocalRoots && !search && !indexLoading && resultCount == 0 && shownTracks.isEmpty() && remoteEntities.isEmpty() && remote?.loading != true -> item { CatalogMessage(tr(Msg.msg_f8480d481387), tr(Msg.msg_55d6dc96a9df), folders, tr(Msg.msg_3048598439e0)) }
                state == CatalogState.ERROR -> item { CatalogMessage(tr(Msg.msg_f7be0cbd0b8a), tr(Msg.msg_e3c30e30adb9), retry, tr(Msg.msg_5189135a6110)) }
                state == CatalogState.EMPTY -> item { CatalogMessage(tr(Msg.msg_f8480d481387), tr(Msg.msg_7e57673a5d4c), retry, tr(Msg.msg_ee9181d5fe7d)) }
                resultCount == 0 && shownTracks.isEmpty() && remoteEntities.isEmpty() && remote?.loading != true && !indexLoading && !indexFailed -> item { CatalogMessage(tr(Msg.msg_480fae1c4bc9), tr(Msg.msg_d555eba48a2d), { changeQuery(""); source = null; offline = false; availableOnly = false }, tr(Msg.msg_407f871778d3)) }
                else -> {
                    if (state == CatalogState.OFFLINE) item { Text(tr(Msg.msg_ded8b330175a), color = MaterialTheme.colorScheme.primary) }
                    if (!grouped) {
                        val shown = page
                        item {
                            Column {
                            Text(if (cacheOnly) tr(Msg.msg_5d5f78c5790d, remoteTracks.size) else if (music != null) tr(Msg.msg_350c510572ad, shown?.total ?: 0, remoteTracks.size)
                                else tr(Msg.msg_12e3d1e9d948, shown?.total ?: 0) + if (demo) tr(Msg.msg_73e0d3beed31) else "",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (music != null && shownTracks.any { it.available }) OutlinedButton({ player.playQueue(shownTracks.map(Track::id)); keyboard?.hide() }, Modifier.prismFocus().testTag("catalog_play_all")) { Text(tr(Msg.msg_9cbbe230cb66)) }
                            }
                        }
                        items(shownTracks, key = Track::id) { track -> TrackRow(track, play = {
                            if (music != null && track.source == Source.YANDEX) player.playQueue(shownTracks.map(Track::id), track.id) else player.select(track.id)
                            keyboard?.hide()
                        }, enqueue = { player.enqueue(track.id) },
                            queued = track.id in queuedIds || playback.automaticLocal && track.source != Source.YANDEX &&
                                (playback.automaticSource == null || playback.automaticSource == track.source),
                            more = if (track.source == Source.YANDEX && taste != null) ({ trackActions = track }) else more?.let { action -> { action(track) } },
                            taste = taste?.takeIf { track.source == Source.YANDEX }, artist = artist,
                            checked = if (selection.active) selection.contains(track.id) else null,
                            toggleSelection = if (selection.active) ({ selection.toggle(track) }) else null) }
                        if (shown?.hasMore == true && (indexed == null || visibleCount <= Int.MAX_VALUE - 80)) item { OutlinedButton(loadMore, Modifier.fillMaxWidth().prismFocus().testTag("catalog_more")) { Text(tr(Msg.msg_fc0f3616f567)) } }
                    } else {
                        val shown = groups
                        items(shownGroups, key = CatalogGroupRow::key) { row ->
                            Surface(onClick = { if (row.local != null) detail = row.local.name else { keyboard?.hide(); row.remote?.entity?.let { music?.open(it) } } },
                                modifier = Modifier.fillMaxWidth().prismFocus().then(row.remote?.let { Modifier.testTag("catalog_entity_${it.id}") } ?: Modifier), shape = MaterialTheme.shapes.medium) {
                                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                    row.local?.let { TrackArtwork(it.sample, Modifier.size(64.dp)) }
                                    Column(Modifier.weight(1f)) {
                                        Text(row.title, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        Text(row.local?.let { tr(Msg.msg_29c90888994f, it.count) } ?: tr(Msg.msg_43b07d243705, row.remote?.subtitle.orEmpty()), color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    SkinIcon(UiIcon.FORWARD, tr(Msg.msg_ea1f40824607))
                                }
                            }
                        }
                        if (shown?.hasMore == true && (indexed == null || visibleCount <= Int.MAX_VALUE - 80)) item { OutlinedButton(loadMore, Modifier.fillMaxWidth().prismFocus().testTag("catalog_more")) { Text(tr(Msg.msg_fc0f3616f567)) } }
                    }
                }
            }
            if (remote?.nextPage != null && remote.issue == null) item {
                OutlinedButton({ music.more() }, Modifier.fillMaxWidth().prismFocus().testTag("catalog_online_more"), enabled = !remote.loading) { Text(tr(Msg.msg_61c83c0e0569)) }
            }
        }
    }
}

}

private data class CatalogGroupRow(val local: CatalogGroup? = null, val remote: MusicEntry? = null) {
    val title get() = local?.name ?: remote!!.title
    val key get() = local?.let { "local:${it.name}" } ?: "yandex:${remote!!.id}"
}

private suspend fun <T> catalogResult(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Exception) {
    Result.failure(error)
}

private fun Category.dimension() = when (this) {
    Category.ALBUMS -> CatalogDimension.ALBUMS
    Category.ARTISTS -> CatalogDimension.ARTISTS
    Category.GENRES -> CatalogDimension.GENRES
    Category.FOLDERS -> CatalogDimension.FOLDERS
    Category.TRACKS, Category.PLAYLISTS -> CatalogDimension.TRACKS
}

@Composable private fun CatalogMessage(title: String, description: String, action: () -> Unit, label: String) {
    Column(Modifier.padding(vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(action, Modifier.prismFocus()) { Text(label) }
    }
}
