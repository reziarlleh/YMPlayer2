package dev.petrov.ymplayer2.shell

import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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

@Composable fun ShellApp(model: ShellModel, version: String, addFolder: (Source) -> Unit = {}, folderIssue: String? = null, skin: AppSkin = PrismSkin) {
    val playback by model.player.state.collectAsStateWithLifecycle()
    val library by model.library.collectAsStateWithLifecycle()
    val demo = model.local == null
    var history by rememberSaveable { mutableStateOf(listOf("player")) }
    var theme by rememberSaveable { mutableStateOf("dark") }
    var catalogState by rememberSaveable { mutableStateOf(CatalogState.READY) }
    val holder = rememberSaveableStateHolder()
    val route = history.last()
    val navigate: (String) -> Unit = { if (route != it) history = history + it }
    val back: () -> Unit = { if (history.size > 1) history = history.dropLast(1) }
    BackHandler(history.size > 1, back)
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
                        NavigationRailItem(route == item.route, { navigate(item.route) },
                            { SkinIcon(item.icon, item.label) }, Modifier.testTag("nav_${item.route}").prismFocus(),
                            label = { Text(item.label) })
                    }
                }
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (history.size > 1) ActionIcon(UiIcon.BACK, "Назад", back)
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
                                    retry = { if (demo) catalogState = CatalogState.READY else model.refresh() })
                                "queue" -> QueueScreen(playback, model.player)
                                "profiles" -> ProfilesScreen(model.catalog.profiles, playback.profileId) {
                                    model.player.switchProfile(it); history = listOf("player")
                                }
                                "settings" -> SettingsScreen(version, theme, { theme = it }, catalogState, { catalogState = it }, demo, { navigate("folders") })
                                "folders" -> FoldersScreen(library, addFolder, model::refresh, model::forgetFolder, folderIssue)
                                "clips" -> MessageScreen("Клипы", "Модуль появится на следующем этапе", "В прототипе нет видео, авторизации и сетевых запросов.", UiIcon.CLIPS)
                            }
                        }
                    }
                    if (route != "player") MiniPlayer(playback, model.player, { navigate("player") }, { navigate("queue") })
                    if (!rail) NavigationBar(containerColor = MaterialTheme.colorScheme.background) {
                        destinations.forEach { item ->
                            NavigationBarItem(route == item.route, { navigate(item.route) },
                                { SkinIcon(item.icon, item.label) }, Modifier.testTag("nav_${item.route}").prismFocus(),
                                label = { Text(item.label, maxLines = 1, overflow = TextOverflow.Ellipsis) })
                        }
                    }
                }
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
