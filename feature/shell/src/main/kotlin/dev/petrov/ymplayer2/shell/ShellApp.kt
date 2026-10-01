package dev.petrov.ymplayer2.shell

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.delay
import dev.petrov.ymplayer2.core.*
import dev.petrov.ymplayer2.designsystem.*
import dev.petrov.ymplayer2.designsystem.skin.*

private data class Destination(val route: String, val label: String, val icon: UiIcon)
private val destinations = listOf(
    Destination("player", "Плеер", UiIcon.PLAYER),
    Destination("library", "Медиатека", UiIcon.LIBRARY),
    Destination("search", "Поиск", UiIcon.SEARCH),
    Destination("clips", "Клипы", UiIcon.CLIPS),
)

@Composable fun ShellApp(model: ShellModel, version: String, addFolder: (Source) -> Unit = {}, folderIssue: String? = null, skin: AppSkin = PrismSkin, onExit: () -> Unit = {}, equalizer: (Boolean) -> Unit = {}, syncOffline: () -> Unit = { model.offline?.sync() }, diagnostics: DiagnosticsAccess? = null, openClips: (() -> Unit)? = null, sideBar: SideBarAccess? = null, updates: UpdateAccess? = null) {
    val playback by model.player.state.collectAsStateWithLifecycle()
    val library by model.library.collectAsStateWithLifecycle()
    val offlineState = model.offline?.state?.collectAsStateWithLifecycle()?.value
    val profileOfflineState = offlineState?.takeIf { it.owner?.profileId == playback.profileId }
    val cachedTracks = profileOfflineState?.tracks.orEmpty()
    val demo = model.local == null
    var route by rememberSaveable { mutableStateOf("player") }
    var artistFrom by rememberSaveable { mutableStateOf("player") }
    var exitAt by remember { mutableStateOf<Long?>(null) }
    var libraryUpRequest by rememberSaveable { mutableIntStateOf(0) }
    var bottomBarHeight by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    var theme by rememberSaveable { mutableStateOf("dark") }
    var catalogState by rememberSaveable { mutableStateOf(CatalogState.READY) }
    var collectionTrack by remember(playback.profileId) { mutableStateOf<Track?>(null) }
    var onlineSource by rememberSaveable(playback.profileId) { mutableStateOf(false) }
    var offlineSearch by rememberSaveable(playback.profileId) { mutableStateOf(false) }
    var searchQuery by rememberSaveable(playback.profileId) { mutableStateOf("") }
    val holder = rememberSaveableStateHolder()
    val navigationRoute = if (route in listOf("playlists", "favorites", "folders", "offline")) "library" else route
    val navigate: (String) -> Unit = {
        if (route == "account" && it != "account") model.accounts?.cancel()
        if (route == "artist" && it != "artist") model.online?.closeArtistCard()
        exitAt = null
        if (it == "clips" && openClips != null) openClips() else route = it
    }
    val openArtist: (ArtistRef) -> Unit = { artist ->
        model.online?.let { music ->
            if (route != "artist") artistFrom = route
            music.openArtistCard(artist)
            navigate("artist")
        }
    }
    val closeArtist: () -> Unit = { navigate(artistFrom) }
    val dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, model.taste) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) exitAt = null
            if (event == Lifecycle.Event.ON_RESUME) model.taste?.let { taste ->
                // Pick up marks changed in Yandex while this app was in the background.
                if (taste.state.value.signedIn) TasteKind.entries.filter { !taste.state.value.shelf(it).busy }.forEach(taste::refresh)
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(exitAt) { if (exitAt != null) { delay(2000); exitAt = null } }
    BackHandler {
        when (route) {
            "quality", "diagnostics", "sidebar", "updates" -> navigate("settings")
            "account" -> navigate("profiles")
            "playlists", "favorites", "folders", "offline" -> { libraryUpRequest++; navigate("library") }
            "player" -> {
                val now = SystemClock.elapsedRealtime()
                if (exitAt?.let { now - it in 0..1999 } == true) { exitAt = null; onExit() }
                else exitAt = now
            }
            else -> navigate("player")
        }
    }
    PrismTheme(theme, skin) {
        BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding()) {
            val rail = maxWidth >= 600.dp
            val widePlayer = maxWidth >= 960.dp && maxHeight >= 500.dp
            val short = maxHeight < 480.dp
            // Keep destinations readable at large text sizes without using a tall icon-only rail.
            val compactRail = maxHeight < 380.dp || density.fontScale > 1.3f
            val tinyRail = maxHeight < 320.dp
            // Give text entry room on short windows; playback continues while its bar is hidden.
            val typingInShortWindow = short && WindowInsets.ime.getBottom(density) > 0
            Row(Modifier.fillMaxSize()) {
                if (rail) NavigationRail(Modifier.fillMaxHeight().then(if (compactRail && !tinyRail) Modifier.width(112.dp) else Modifier)
                    .then(if (route != "player") Modifier.verticalScroll(rememberScrollState()) else Modifier),
                    containerColor = MaterialTheme.colorScheme.background) {
                    if (!compactRail) {
                        Spacer(Modifier.height(12.dp))
                        SkinIcon(UiIcon.BRAND, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(if (short) 12.dp else 24.dp))
                    }
                    destinations.forEach { item ->
                        NavigationRailItem(navigationRoute == item.route, { navigate(item.route) },
                            { SkinIcon(item.icon, item.label) }, Modifier.height(if (tinyRail) 56.dp else if (compactRail) 76.dp else if (short) 72.dp else 80.dp).testTag("nav_${item.route}").prismFocus(),
                            label = if (tinyRail) null else ({ Text(item.label,
                                fontSize = if (compactRail) 10.sp else 12.sp,
                                maxLines = 2, textAlign = TextAlign.Center) }))
                    }
                }
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    if (!typingInShortWindow) Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (route != "player") ActionIcon(UiIcon.BACK, "Назад", { dispatcher?.onBackPressed() }, Modifier.testTag("navigate_up"))
                        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                            Text("YMPlayer 2", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (route != "player") Text(if (demo) "Прототип · Без звука" else if (version.contains("beta", ignoreCase = true)) "Музыка рядом · Beta" else "Музыка рядом", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        ActionIcon(UiIcon.PROFILE, "Профили", { navigate("profiles") }, Modifier.testTag("profiles"))
                        ActionIcon(UiIcon.SETTINGS, "Настройки", { navigate("settings") }, Modifier.testTag("settings"))
                    }
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        // Each route/profile owns its scroll, filters, detail and text field state.
                        holder.SaveableStateProvider("${playback.profileId}:$route") {
                            when (route) {
                                "player" -> PlayerScreen(playback, model.player, widePlayer, short, { navigate("queue") }, demo, { navigate("folders") }, model.taste, { navigate("account") }, openArtist, equalizer, model.cloudPlaylists)
                                "artist" -> model.online?.let { OnlineScreen(it, model.player, false, { navigate("account") }, model.taste, artist = openArtist, standalone = true, closeArtist = closeArtist, playlists = model.cloudPlaylists) }
                                "library", "search" -> Column(Modifier.fillMaxSize()) {
                                    model.online?.let {
                                        if (!typingInShortWindow) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            FilterChip(!onlineSource && (route != "search" || !offlineSearch), { onlineSource = false; offlineSearch = false }, { Text("Общий каталог") }, Modifier.prismFocus().testTag("source_local"))
                                            FilterChip(onlineSource, { onlineSource = true; offlineSearch = false }, { Text("Яндекс Музыка") }, Modifier.prismFocus().testTag("source_yandex"))
                                            if (model.offline != null) FilterChip(route == "search" && offlineSearch, {
                                                if (route == "search") { onlineSource = false; offlineSearch = true }
                                                else navigate("offline")
                                            }, { Text("Офлайн") }, Modifier.prismFocus().testTag("open_offline"))
                                        }
                                    }
                                    val cacheOnly = route == "search" && offlineSearch
                                    if (onlineSource && model.online != null) OnlineScreen(model.online, model.player, route == "search", { navigate("account") }, model.taste, { navigate("player") }, artist = openArtist, playlists = model.cloudPlaylists,
                                        searchQuery = searchQuery.takeIf { route == "search" }, onSearchQueryChange = { searchQuery = it })
                                    else CatalogScreen(if (demo) model.catalog.tracks(playback.profileId) else emptyList(), model.player, route == "search", if (demo) catalogState else CatalogState.READY,
                                    demo = demo, folders = { navigate("folders") }, scanning = if (cacheOnly) profileOfflineState?.ready == false else library.scanning, issue = if (cacheOnly) profileOfflineState?.message else library.issue,
                                    collections = model.collections != null, playlists = { navigate("playlists") }, favorites = { navigate("favorites") },
                                    upRequest = if (route == "library") libraryUpRequest else 0,
                                    more = model.collections?.let { { track -> collectionTrack = track } },
                                    indexed = if (cacheOnly) null else model.local as? IndexedLocalLibrary,
                                    indexedSources = library.roots.mapTo(linkedSetOf(), LibraryRoot::source),
                                    noLocalRoots = library.roots.isEmpty(),
                                    music = model.online, taste = model.taste, cached = cachedTracks,
                                    artist = openArtist, cloudPlaylists = model.cloudPlaylists, signIn = { navigate("account") },
                                    onlineHome = { onlineSource = true }, waveStarted = { navigate("player") },
                                    cacheOnly = cacheOnly, searchQuery = searchQuery.takeIf { route == "search" }, onSearchQueryChange = { searchQuery = it },
                                    retry = { if (demo) catalogState = CatalogState.READY else model.refresh() })
                                }
                                "playlists", "favorites" -> model.collections?.let { CollectionsScreen(it, playback.profileId, if (demo) library.tracks else emptyList(), model.player, route == "favorites", model.local as? IndexedLocalLibrary) }
                                "queue" -> QueueScreen(playback, model.player, model.taste, openArtist)
                                "offline" -> model.offline?.let { OfflineScreen(it, model.player, syncOffline, { navigate("account") }) }
                                "profiles" -> ProfilesScreen(model.catalog.profiles, playback.profileId, model.accounts?.let { { navigate("account") } }) {
                                    model.player.switchProfile(it); navigate("player")
                                }
                                "account" -> model.accounts?.let { AccountScreen(it, model.catalog.profiles.first { profile -> profile.id == playback.profileId }) }
                                "quality" -> model.audioQuality?.let { AudioQualityScreen(it) }
                                "settings" -> SettingsScreen(version, theme, { theme = it }, catalogState, { catalogState = it }, demo, { navigate("folders") }, model.offline?.let { { navigate("offline") } }, model.audioQuality?.let { { navigate("quality") } }, diagnostics?.let { { navigate("diagnostics") } }, sideBar?.let { { navigate("sidebar") } }, updates?.let { { navigate("updates") } })
                                "diagnostics" -> diagnostics?.let { DiagnosticsScreen(it) }
                                "sidebar" -> sideBar?.let { SideBarScreen(it) }
                                "updates" -> updates?.let { UpdateScreen(version, it) }
                                "folders" -> FoldersScreen(library, addFolder, model::refresh, model::forgetFolder, folderIssue)
                                "clips" -> MessageScreen("Клипы", "Видеомодуль ещё разрабатывается", "Аудиоплеер продолжает работать при переходе между разделами.", UiIcon.CLIPS)
                            }
                        }
                    }
                    if (route != "player" && !typingInShortWindow) MiniPlayer(playback, model.player, { navigate("player") }, { navigate("queue") })
                    if (!rail && !typingInShortWindow) NavigationBar(Modifier.onSizeChanged { bottomBarHeight = it.height }, containerColor = MaterialTheme.colorScheme.background) {
                        destinations.forEach { item ->
                            NavigationBarItem(navigationRoute == item.route, { navigate(item.route) },
                                { SkinIcon(item.icon, item.label) }, Modifier.testTag("nav_${item.route}").prismFocus(),
                                label = { Text(item.label, maxLines = 1, overflow = TextOverflow.Ellipsis) })
                        }
                    }
                    collectionTrack?.let { track -> model.collections?.let { store ->
                        TrackCollectionDialog(track, store, playback.profileId, demo || model.local is IndexedLocalLibrary || library.tracks.any { it.id == track.id }, { collectionTrack = null })
                    } }
                    BackHandler(collectionTrack != null) { collectionTrack = null }
                }
            }
            model.cloudPlaylists?.let { CloudPlaylistDialog(it) }
            if (exitAt != null) Snackbar(Modifier.align(Alignment.BottomCenter).padding(start = 16.dp, end = 16.dp, bottom = 16.dp + if (rail) 0.dp else with(density) { bottomBarHeight.toDp() }).testTag("exit_hint")) {
                Text("Нажмите «Назад» ещё раз для выхода")
            }
        }
        if (updates != null) {
            val updateState by updates.state.collectAsStateWithLifecycle()
            val offer = updateState.offer
            if (updateState.prompt && offer != null) UpdatePrompt(updates, offer)
        }
    }
}

@Composable private fun MiniPlayer(state: PlaybackState, player: PlaybackController, open: () -> Unit, queue: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(open, Modifier.weight(1f).prismFocus().testTag("mini_open")) {
                Column(Modifier.fillMaxWidth()) {
                    Text(state.current?.title ?: "Очередь пуста", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${state.current?.artist.orEmpty()} · ${secondsLabel(state.positionSeconds)}", style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            ActionIcon(if (state.playing) UiIcon.PAUSE else UiIcon.PLAY, if (state.playing) "Пауза" else "Воспроизвести", player::toggle, Modifier.testTag("mini_play"), state.current?.available == true)
            ActionIcon(UiIcon.QUEUE, "Очередь", queue)
        }
    }
}
