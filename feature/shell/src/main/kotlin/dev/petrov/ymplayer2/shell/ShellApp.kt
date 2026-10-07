package dev.petrov.ymplayer2.shell

import dev.petrov.ymplayer2.localization.*

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.Role
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.delay
import dev.petrov.ymplayer2.core.*
import dev.petrov.ymplayer2.designsystem.*
import dev.petrov.ymplayer2.designsystem.skin.*

private data class Destination(val route: String, val label: String, val icon: UiIcon)
private val destinations get() = listOf(
    Destination("player", tr(Msg.msg_dff16f6f70a1), UiIcon.PLAYER),
    Destination("library", tr(Msg.msg_0a20ddc9928f), UiIcon.LIBRARY),
    Destination("search", tr(Msg.msg_180f58ab9753), UiIcon.SEARCH),
    Destination("radio", trMessage("Радио"), UiIcon.RADIO),
    Destination("clips", tr(Msg.msg_6daecbeea4b6), UiIcon.CLIPS),
)

@Composable fun ShellApp(model: ShellModel, version: String, addFolder: (Source) -> Unit = {}, folderIssue: String? = null, skin: AppSkin = PrismSkin, onExit: () -> Unit = {}, equalizer: (Boolean) -> Unit = {}, syncOffline: () -> Unit = { model.offline?.sync() }, diagnostics: DiagnosticsAccess? = null, openClips: (() -> Unit)? = null, sideBar: SideBarAccess? = null, updates: UpdateAccess? = null, skins: SkinRepository? = null, importSkin: () -> Unit = {}, diagnosticScreen: (String, Boolean, Int, Int) -> Unit = { _, _, _, _ -> }, initialRoute: String = "player", onRouteChanged: (String) -> Unit = {}, initialOnlineSource: Boolean = false, initialOfflineSearch: Boolean = false, saveSources: (Boolean, Boolean) -> Unit = { _, _ -> }) {
    val playback by model.player.state.collectAsStateWithLifecycle()
    val radioPlayback = model.radio?.playback?.collectAsStateWithLifecycle()?.value
    val library by model.library.collectAsStateWithLifecycle()
    val offlineState = model.offline?.state?.collectAsStateWithLifecycle()?.value
    val profileOfflineState = offlineState?.takeIf { it.owner?.profileId == playback.profileId }
    val cachedTracks = profileOfflineState?.tracks.orEmpty()
    val demo = model.local == null
    var route by rememberSaveable { mutableStateOf(initialRoute) }
    var aboutOpen by rememberSaveable { mutableStateOf(false) }
    var artistFrom by rememberSaveable { mutableStateOf("player") }
    var exitAt by remember { mutableStateOf<Long?>(null) }
    var libraryUpRequest by rememberSaveable { mutableIntStateOf(0) }
    var bottomBarHeight by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    var theme by rememberSaveable { mutableStateOf("dark") }
    var catalogState by rememberSaveable { mutableStateOf(CatalogState.READY) }
    var collectionTrack by remember(playback.profileId) { mutableStateOf<Track?>(null) }
    var onlineSource by rememberSaveable(playback.profileId) { mutableStateOf(initialOnlineSource) }
    var offlineSearch by rememberSaveable(playback.profileId) { mutableStateOf(initialOfflineSearch) }
    LaunchedEffect(onlineSource, offlineSearch) { saveSources(onlineSource, offlineSearch) }
    var searchQuery by rememberSaveable(playback.profileId) { mutableStateOf("") }
    val holder = rememberSaveableStateHolder()
    val navigationRoute = if (route in listOf("playlists", "favorites", "folders", "offline", "history", "recent")) "library" else route
    val navigate: (String) -> Unit = {
        if (route == "account" && it != "account") model.accounts?.cancel()
        if (route == "artist" && it != "artist") model.online?.closeArtistCard()
        exitAt = null
        route = it
        onRouteChanged(if (it == "artist") artistFrom else it)
        if (it == "clips" && openClips != null) openClips()
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
    val activeRoute by rememberUpdatedState(route)
    DisposableEffect(lifecycle, model.taste, model.radio) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) exitAt = null
            if (event == Lifecycle.Event.ON_RESUME && activeRoute == "radio") model.radio?.refreshCollection()
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
            "quality", "diagnostics", "sidebar", "updates", "skins", "offline_settings", "language" -> navigate("settings")
            "account" -> navigate("profiles")
            "playlists", "favorites", "folders", "offline", "history", "recent" -> { libraryUpRequest++; navigate("library") }
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
            // Give text entry room on short windows; playback continues while its bar is hidden.
            val typingInShortWindow = short && WindowInsets.ime.getBottom(density) > 0
            val imeVisible = WindowInsets.ime.getBottom(density) > 0
            SideEffect { diagnosticScreen(route, imeVisible, maxWidth.value.toInt(), maxHeight.value.toInt()) }
            Column(Modifier.fillMaxSize().testTag("shell_layout")) {
                if (!typingInShortWindow) Row(Modifier.fillMaxWidth().testTag("shell_header").padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (route != "player") ActionIcon(UiIcon.BACK, tr(Msg.msg_1a9fb1f3cf8e), { dispatcher?.onBackPressed() }, Modifier.testTag("navigate_up"))
                    Column(Modifier.weight(1f).heightIn(min = 48.dp).prismFocus().clickable(onClickLabel = tr(Msg.msg_eca626bab07b), role = Role.Button) { aboutOpen = true }.testTag("about_logo").padding(vertical = 6.dp)) {
                        WideBrandLogo(Modifier.widthIn(max = 188.dp).fillMaxWidth().height(30.dp), "YMPlayer2")
                        if (route != "player") Text(if (demo) tr(Msg.msg_bbb42f6a25b6) else if (version.contains("beta", ignoreCase = true)) tr(Msg.msg_5e66b30bc941) else tr(Msg.msg_f4d6290e6f4b), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    ActionIcon(UiIcon.PROFILE, tr(Msg.msg_30c61037264b), { navigate("profiles") }, Modifier.testTag("profiles"))
                    ActionIcon(UiIcon.SETTINGS, tr(Msg.msg_985b5e0f2ccf), { navigate("settings") }, Modifier.testTag("settings"))
                }
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                    // Rail sizing uses the space remaining below the common header.
                    val compactRail = maxHeight < 380.dp || density.fontScale > 1.3f
                    val tinyRail = maxHeight < 320.dp
                    val railItemHeight = (maxHeight / destinations.size).coerceIn(48.dp, if (compactRail) 76.dp else 80.dp)
                    Row(Modifier.fillMaxSize()) {
                        if (rail) NavigationRail(Modifier.fillMaxHeight().testTag("shell_rail").then(if (compactRail && !tinyRail) Modifier.width(112.dp) else Modifier)
                            .then(if (route != "player") Modifier.verticalScroll(rememberScrollState()) else Modifier),
                            containerColor = MaterialTheme.colorScheme.background) {
                            destinations.forEach { item ->
                                NavigationRailItem(navigationRoute == item.route, { navigate(item.route) },
                                    { SkinIcon(item.icon, trMessage(item.label)) }, Modifier.height(railItemHeight).testTag("nav_${item.route}").prismFocus(),
                                    label = if (tinyRail) null else ({ Text(trMessage(item.label),
                                        fontSize = if (compactRail) 10.sp else 12.sp,
                                        maxLines = 2, textAlign = TextAlign.Center) }))
                            }
                        }
                        Column(Modifier.weight(1f).fillMaxHeight()) {
                            Box(Modifier.weight(1f).fillMaxWidth()) {
                                // Each route/profile owns its scroll, filters, detail and text field state.
                                holder.SaveableStateProvider("${playback.profileId}:$route") {
                                    when (route) {
                                        "player" -> PlayerScreen(playback, model.player, widePlayer, short, { navigate("queue") }, demo, { navigate("folders") }, model.taste, { navigate("account") }, openArtist, equalizer, model.cloudPlaylists, model.collections, model.offline)
                                        "artist" -> model.online?.let { OnlineScreen(it, model.player, false, { navigate("account") }, model.taste, artist = openArtist, standalone = true, closeArtist = closeArtist, playlists = model.cloudPlaylists) }
                                        "library", "search" -> Column(Modifier.fillMaxSize()) {
                                            if (route == "library") Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                if (model.history != null) OutlinedButton({ navigate("history") }, Modifier.prismFocus().testTag("open_history")) { Text(tr(Msg.history_title)) }
                                                if (model.local is IndexedLocalLibrary) OutlinedButton({ navigate("recent") }, Modifier.prismFocus().testTag("open_recent")) { Text(tr(Msg.recent_title)) }
                                            }
                                            model.online?.let {
                                                if (!typingInShortWindow) ChoiceRow(if (onlineSource) "yandex" else if (route == "search" && offlineSearch) "offline" else "local", Modifier.fillMaxWidth().padding(horizontal = 16.dp)) { choice ->
                                                    FilterChip(!onlineSource && (route != "search" || !offlineSearch), { onlineSource = false; offlineSearch = false }, { Text(tr(Msg.msg_00231affc201)) }, choice("local").prismFocus().testTag("source_local"))
                                                    FilterChip(onlineSource, { onlineSource = true; offlineSearch = false }, { Text(tr(Msg.msg_c39959813ccd)) }, choice("yandex").prismFocus().testTag("source_yandex"))
                                                    if (model.offline != null) FilterChip(route == "search" && offlineSearch, {
                                                        if (route == "search") { onlineSource = false; offlineSearch = true }
                                                        else navigate("offline")
                                                    }, { Text(tr(Msg.msg_aa07f084155c)) }, choice("offline").prismFocus().testTag("open_offline"))
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
                                            collectionStore = model.collections,
                                            retry = { if (demo) catalogState = CatalogState.READY else model.refresh() })
                                        }
                                        "playlists", "favorites" -> model.collections?.let { CollectionsScreen(it, playback.profileId, if (demo) library.tracks else emptyList(), model.player, route == "favorites", model.local as? IndexedLocalLibrary) }
                                        "queue" -> QueueScreen(playback, model.player, model.taste, openArtist)
                                        "offline" -> model.offline?.let { OfflineScreen(it, model.player) }
                                        "history" -> HistoryScreen(model, openArtist)
                                        "recent" -> RecentScreen(model) { collectionTrack = it }
                                        "profiles" -> ProfilesScreen(model.catalog.profiles, playback.profileId, model.accounts?.let { { navigate("account") } }) {
                                            model.player.switchProfile(it); navigate("player")
                                        }
                                        "account" -> model.accounts?.let { AccountScreen(it, model.catalog.profiles.first { profile -> profile.id == playback.profileId }) }
                                        "quality" -> model.audioQuality?.let { AudioQualityScreen(it) }
                                        "offline_settings" -> model.offline?.let { OfflineSettingsScreen(it, syncOffline, { navigate("account") }) }
                                        "settings" -> SettingsScreen(version, theme, { theme = it }, catalogState, { catalogState = it }, demo, model.offline?.let { { navigate("offline_settings") } }, model.audioQuality?.let { { navigate("quality") } }, diagnostics?.let { { navigate("diagnostics") } }, sideBar?.let { { navigate("sidebar") } }, updates?.let { { navigate("updates") } }, skins?.let { { navigate("skins") } }, about = { aboutOpen = true }, language = { navigate("language") })
                                        "language" -> LanguageScreen()
                                        "skins" -> skins?.let { SkinsScreen(it, importSkin) }
                                        "diagnostics" -> diagnostics?.let { DiagnosticsScreen(it) }
                                        "sidebar" -> sideBar?.let { SideBarScreen(it) }
                                        "updates" -> updates?.let { UpdateScreen(version, it) }
                                        "folders" -> FoldersScreen(library, addFolder, model::refresh, model::forgetFolder, folderIssue)
                                        "radio" -> model.radio?.let { RadioScreen(it, { navigate("account") }) }
                                        "clips" -> MessageScreen(tr(Msg.msg_6daecbeea4b6), tr(Msg.msg_f8ac3be6be11), tr(Msg.msg_0a0ebb200d14), UiIcon.CLIPS)
                                    }
                                }
                            }
                            if (route != "radio" && !typingInShortWindow && radioPlayback?.ownsOutput == true) model.radio?.let { RadioMiniPlayer(it) { navigate("radio") } }
                            else if (route !in listOf("player", "radio") && !typingInShortWindow) MiniPlayer(playback, model.player, { navigate("player") }, { navigate("queue") })
                            if (!rail && !typingInShortWindow) NavigationBar(Modifier.onSizeChanged { bottomBarHeight = it.height }, containerColor = MaterialTheme.colorScheme.background) {
                                destinations.forEach { item ->
                                    NavigationBarItem(navigationRoute == item.route, { navigate(item.route) },
                                        { SkinIcon(item.icon, trMessage(item.label)) }, Modifier.testTag("nav_${item.route}").prismFocus(),
                                        label = { Text(trMessage(item.label), maxLines = 1, overflow = TextOverflow.Ellipsis) })
                                }
                            }
                            collectionTrack?.let { track -> model.collections?.let { store ->
                                TrackCollectionDialog(track, store, playback.profileId, demo || model.local is IndexedLocalLibrary || library.tracks.any { it.id == track.id }, { collectionTrack = null })
                            } }
                            BackHandler(collectionTrack != null) { collectionTrack = null }
                        }
                    }
                }
            }
            model.cloudPlaylists?.let { CloudPlaylistDialog(it) }
            if (exitAt != null) Snackbar(Modifier.align(Alignment.BottomCenter).padding(start = 16.dp, end = 16.dp, bottom = 16.dp + if (rail) 0.dp else with(density) { bottomBarHeight.toDp() }).testTag("exit_hint")) {
                Text(tr(Msg.msg_5779b6589ef5))
            }
        }
        if (aboutOpen) AboutDialog(version) { aboutOpen = false }
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
                    Text(state.current?.title ?: tr(Msg.msg_39c3c9622cdc), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${state.current?.artist.orEmpty()} · ${secondsLabel(state.positionSeconds)}", style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            ActionIcon(if (state.playing) UiIcon.PAUSE else UiIcon.PLAY, if (state.playing) tr(Msg.msg_65530fd463ea) else tr(Msg.msg_bdd37eb21746), player::toggle, Modifier.testTag("mini_play"), state.current?.available == true)
            ActionIcon(UiIcon.QUEUE, tr(Msg.msg_cb297d129add), queue)
        }
    }
}
