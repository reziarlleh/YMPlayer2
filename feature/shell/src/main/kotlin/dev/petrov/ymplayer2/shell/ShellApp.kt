package dev.petrov.ymplayer2.shell

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import android.os.SystemClock
import androidx.compose.foundation.background
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
import androidx.compose.ui.unit.dp
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

@Composable fun ShellApp(model: ShellModel, version: String, addFolder: (Source) -> Unit = {}, folderIssue: String? = null, skin: AppSkin = PrismSkin, onExit: () -> Unit = {}) {
    val playback by model.player.state.collectAsStateWithLifecycle()
    val library by model.library.collectAsStateWithLifecycle()
    val demo = model.local == null
    var route by rememberSaveable { mutableStateOf("player") }
    var exitAt by remember { mutableStateOf<Long?>(null) }
    var libraryUpRequest by rememberSaveable { mutableIntStateOf(0) }
    var bottomBarHeight by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    var theme by rememberSaveable { mutableStateOf("dark") }
    var catalogState by rememberSaveable { mutableStateOf(CatalogState.READY) }
    var collectionTrack by remember(playback.profileId) { mutableStateOf<Track?>(null) }
    val holder = rememberSaveableStateHolder()
    val navigationRoute = if (route in listOf("playlists", "favorites", "folders")) "library" else route
    val navigate: (String) -> Unit = {
        if (route == "account" && it != "account") model.accounts?.cancel()
        exitAt = null; route = it
    }
    val dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) exitAt = null }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(exitAt) { if (exitAt != null) { delay(2000); exitAt = null } }
    BackHandler {
        when (route) {
            "account" -> navigate("profiles")
            "playlists", "favorites", "folders" -> { libraryUpRequest++; navigate("library") }
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
            Row(Modifier.fillMaxSize()) {
                if (rail) NavigationRail(Modifier.fillMaxHeight().verticalScroll(rememberScrollState()), containerColor = MaterialTheme.colorScheme.background) {
                    Spacer(Modifier.height(12.dp))
                    Text("YM", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
                    Spacer(Modifier.height(24.dp))
                    destinations.forEach { item ->
                        NavigationRailItem(navigationRoute == item.route, { navigate(item.route) },
                            { SkinIcon(item.icon, item.label) }, Modifier.testTag("nav_${item.route}").prismFocus(),
                            label = { Text(item.label) })
                    }
                }
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (route != "player") ActionIcon(UiIcon.BACK, "Назад", { dispatcher?.onBackPressed() }, Modifier.testTag("navigate_up"))
                        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                            Text("YMPlayer 2", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(if (demo) "Прототип · Без звука" else "Локальная музыка · Beta", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        ActionIcon(UiIcon.PROFILE, "Профили", { navigate("profiles") }, Modifier.testTag("profiles"))
                        ActionIcon(UiIcon.SETTINGS, "Настройки", { navigate("settings") }, Modifier.testTag("settings"))
                    }
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        // Each route/profile owns its scroll, filters, detail and text field state.
                        holder.SaveableStateProvider("${playback.profileId}:$route") {
                            when (route) {
                                "player" -> PlayerScreen(playback, model.player, widePlayer, short, { navigate("queue") }, demo, { navigate("folders") })
                                "library", "search" -> CatalogScreen(if (demo) model.catalog.tracks(playback.profileId) else library.tracks, model.player, route == "search", if (demo) catalogState else CatalogState.READY,
                                    demo = demo, folders = { navigate("folders") }, scanning = library.scanning, issue = library.issue,
                                    collections = model.collections != null, playlists = { navigate("playlists") }, favorites = { navigate("favorites") },
                                    upRequest = if (route == "library") libraryUpRequest else 0,
                                    more = model.collections?.let { { track -> collectionTrack = track } },
                                    retry = { if (demo) catalogState = CatalogState.READY else model.refresh() })
                                "playlists", "favorites" -> model.collections?.let { CollectionsScreen(it, playback.profileId, library.tracks, model.player, route == "favorites") }
                                "queue" -> QueueScreen(playback, model.player)
                                "profiles" -> ProfilesScreen(model.catalog.profiles, playback.profileId, model.accounts?.let { { navigate("account") } }) {
                                    model.player.switchProfile(it); navigate("player")
                                }
                                "account" -> model.accounts?.let { AccountScreen(it, model.catalog.profiles.first { profile -> profile.id == playback.profileId }) }
                                "settings" -> SettingsScreen(version, theme, { theme = it }, catalogState, { catalogState = it }, demo, { navigate("folders") })
                                "folders" -> FoldersScreen(library, addFolder, model::refresh, model::forgetFolder, folderIssue)
                                "clips" -> MessageScreen("Клипы", "Модуль появится на следующем этапе", "В прототипе нет видео, авторизации и сетевых запросов.", UiIcon.CLIPS)
                            }
                        }
                    }
                    if (route != "player") MiniPlayer(playback, model.player, { navigate("player") }, { navigate("queue") })
                    if (!rail) NavigationBar(Modifier.onSizeChanged { bottomBarHeight = it.height }, containerColor = MaterialTheme.colorScheme.background) {
                        destinations.forEach { item ->
                            NavigationBarItem(navigationRoute == item.route, { navigate(item.route) },
                                { SkinIcon(item.icon, item.label) }, Modifier.testTag("nav_${item.route}").prismFocus(),
                                label = { Text(item.label, maxLines = 1, overflow = TextOverflow.Ellipsis) })
                        }
                    }
                    collectionTrack?.let { track -> model.collections?.let { store ->
                        TrackCollectionDialog(track, store, playback.profileId, library.tracks.any { it.id == track.id }, { collectionTrack = null })
                    } }
                    BackHandler(collectionTrack != null) { collectionTrack = null }
                }
            }
            if (exitAt != null) Snackbar(Modifier.align(Alignment.BottomCenter).padding(start = 16.dp, end = 16.dp, bottom = 16.dp + if (rail) 0.dp else with(density) { bottomBarHeight.toDp() }).testTag("exit_hint")) {
                Text("Нажмите «Назад» ещё раз для выхода")
            }
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
