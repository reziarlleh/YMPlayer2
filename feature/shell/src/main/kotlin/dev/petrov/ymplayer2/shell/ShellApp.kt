package dev.petrov.ymplayer2.shell

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.petrov.ymplayer2.core.*
import dev.petrov.ymplayer2.designsystem.*

private data class Destination(val route: String, val label: String, val icon: ImageVector)
private val destinations = listOf(
    Destination("player", "Плеер", Icons.Default.PlayCircle),
    Destination("library", "Медиатека", Icons.Default.LibraryMusic),
    Destination("search", "Поиск", Icons.Default.Search),
    Destination("clips", "Клипы", Icons.Default.SmartDisplay),
)

@Composable fun ShellApp(model: ShellModel, version: String) {
    val playback by model.player.state.collectAsStateWithLifecycle()
    var history by rememberSaveable { mutableStateOf(listOf("player")) }
    var theme by rememberSaveable { mutableStateOf("dark") }
    var catalogState by rememberSaveable { mutableStateOf(CatalogState.READY) }
    val holder = rememberSaveableStateHolder()
    val route = history.last()
    val navigate: (String) -> Unit = { if (route != it) history = history + it }
    val back: () -> Unit = { if (history.size > 1) history = history.dropLast(1) }
    BackHandler(history.size > 1, back)
    PrismTheme(theme) {
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
                            { Icon(item.icon, item.label) }, Modifier.testTag("nav_${item.route}").prismFocus(),
                            label = { Text(item.label) })
                    }
                }
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (history.size > 1) ActionIcon(Icons.AutoMirrored.Filled.ArrowBack, "Назад", back)
                        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                            Text("YMPlayer 2", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("Прототип · Без звука", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        ActionIcon(Icons.Default.AccountCircle, "Профили", { navigate("profiles") }, Modifier.testTag("profiles"))
                        ActionIcon(Icons.Default.Settings, "Настройки", { navigate("settings") }, Modifier.testTag("settings"))
                    }
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        // Each route/profile owns its scroll, filters, detail and text field state.
                        holder.SaveableStateProvider("${playback.profileId}:$route") {
                            when (route) {
                                "player" -> PlayerScreen(playback, model.player, widePlayer, short, { navigate("queue") })
                                "library" -> CatalogScreen(model.catalog.tracks(playback.profileId), model.player, false, catalogState) { catalogState = CatalogState.READY }
                                "search" -> CatalogScreen(model.catalog.tracks(playback.profileId), model.player, true, catalogState) { catalogState = CatalogState.READY }
                                "queue" -> QueueScreen(playback, model.player)
                                "profiles" -> ProfilesScreen(model.catalog.profiles, playback.profileId) {
                                    model.player.switchProfile(it); history = listOf("player")
                                }
                                "settings" -> SettingsScreen(version, theme, { theme = it }, catalogState, { catalogState = it })
                                "clips" -> MessageScreen("Клипы", "Модуль появится на следующем этапе", "В прототипе нет видео, авторизации и сетевых запросов.", Icons.Default.SmartDisplay)
                            }
                        }
                    }
                    if (route != "player") MiniPlayer(playback, model.player, { navigate("player") }, { navigate("queue") })
                    if (!rail) NavigationBar(containerColor = MaterialTheme.colorScheme.background) {
                        destinations.forEach { item ->
                            NavigationBarItem(route == item.route, { navigate(item.route) },
                                { Icon(item.icon, item.label) }, Modifier.testTag("nav_${item.route}").prismFocus(),
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
            ActionIcon(if (state.playing) Icons.Default.Pause else Icons.Default.PlayArrow, if (state.playing) "Пауза" else "Воспроизвести", player::toggle, Modifier.testTag("mini_play"), state.current?.available == true)
            ActionIcon(Icons.AutoMirrored.Filled.QueueMusic, "Очередь", queue)
        }
    }
}
