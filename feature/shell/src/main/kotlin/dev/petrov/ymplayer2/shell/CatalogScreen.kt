package dev.petrov.ymplayer2.shell

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

@Composable internal fun CatalogScreen(tracks: List<Track>, player: PlaybackController, search: Boolean, state: CatalogState,
    demo: Boolean = true, folders: () -> Unit = {}, scanning: Boolean = false, issue: String? = null,
    collections: Boolean = false, playlists: () -> Unit = {}, favorites: () -> Unit = {}, more: ((Track) -> Unit)? = null, retry: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var source by rememberSaveable { mutableStateOf<Source?>(null) }
    var offline by rememberSaveable { mutableStateOf(false) }
    var category by rememberSaveable { mutableStateOf(Category.TRACKS) }
    var detail by rememberSaveable { mutableStateOf<String?>(null) }
    var descending by rememberSaveable { mutableStateOf(false) }
    val holder = rememberSaveableStateHolder()
    val keyboard = LocalSoftwareKeyboardController.current
    val playback by player.state.collectAsState()
    val queuedIds = remember(playback.queue) { playback.queue.mapTo(hashSetOf(), Track::id) }
    BackHandler(detail != null) { detail = null }
    val filtered = tracks.filter {
        (source == null || it.source == source) && (!offline || it.offline && it.available) &&
            (state != CatalogState.OFFLINE || it.offline && it.available) &&
            (!search || listOf(it.title, it.artist, it.album).any { text -> text.contains(query.trim(), true) })
    }.let { if (descending) it.sortedByDescending(Track::title) else it.sortedBy(Track::title) }
    holder.SaveableStateProvider(detail ?: "root") {
        LazyColumn(Modifier.fillMaxSize().imePadding().testTag("catalog_list"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (detail != null) ActionIcon(UiIcon.BACK, "К списку", { detail = null })
                    Text(detail ?: if (search) "Поиск" else "Медиатека", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                }
            }
            if (!demo && !search && detail == null) item {
                OutlinedButton(folders, Modifier.prismFocus().testTag("manage_folders")) { SkinIcon(UiIcon.FOLDER, null); Spacer(Modifier.width(8.dp)); Text("Папки с музыкой") }
            }
            if (scanning) item { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Обновляем каталог…") }
            if (issue != null) item { Text(issue, color = MaterialTheme.colorScheme.error) }
            if (detail == null) {
                if (search) item {
                    OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().testTag("search_input").prismFocus(),
                        label = { Text("Трек, исполнитель или альбом") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                        leadingIcon = { SkinIcon(UiIcon.SEARCH, null) },
                        trailingIcon = { if (query.isNotEmpty()) ActionIcon(UiIcon.CLOSE, "Очистить поиск", { query = "" }) })
                }
                if (!search) item {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Category.entries.filter { demo || collections || it != Category.PLAYLISTS }.forEach { item ->
                            FilterChip(category == item, { if (item == Category.PLAYLISTS && collections) playlists() else category = item }, { Text(item.label) }, Modifier.prismFocus().testTag("category_${item.name}"))
                        }
                        if (collections) AssistChip(favorites, { Text("Избранное") }, Modifier.prismFocus().testTag("category_FAVORITES"), leadingIcon = { SkinIcon(UiIcon.FAVORITE, null) })
                    }
                }
                item {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(source == null, { source = null }, { Text("Все источники") }, Modifier.prismFocus())
                        Source.entries.filter { candidate -> tracks.any { it.source == candidate } }.forEach { item ->
                            FilterChip(source == item, { source = item }, { Text(item.label) }, Modifier.prismFocus().testTag("filter_${item.name}"))
                        }
                    }
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(offline, { offline = !offline }, { Text("Доступно офлайн") }, Modifier.prismFocus().testTag("filter_offline"))
                        AssistChip({ descending = !descending }, { Text(if (descending) "Название ↓" else "Название ↑") }, Modifier.prismFocus())
                    }
                }
            }
            when {
                !demo && tracks.isEmpty() && !search -> item { CatalogMessage("Медиатека пока пуста", "Выберите папку с аудиофайлами на устройстве или USB.", folders, "Добавить музыку") }
                state == CatalogState.ERROR -> item { CatalogMessage("Не удалось загрузить медиатеку", "Демонстрация ошибки. Текущая очередь сохранена.", retry, "Повторить") }
                state == CatalogState.EMPTY -> item { CatalogMessage("Медиатека пока пуста", "Демонстрация первого запуска.", retry, "Показать демоданные") }
                filtered.isEmpty() -> item { CatalogMessage("Ничего не найдено", "Попробуйте другой запрос или сбросьте фильтры.", { query = ""; source = null; offline = false }, "Сбросить") }
                else -> {
                    if (state == CatalogState.OFFLINE) item { Text("Нет сети · показаны доступные офлайн треки", color = MaterialTheme.colorScheme.primary) }
                    if (detail != null || category == Category.TRACKS || search) {
                        val shown = if (detail == null) filtered else filtered.filter { it.group(category) == detail }
                        item { Text("${shown.size} треков" + if (demo) " · демонстрационный каталог" else "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        items(shown, key = Track::id) { track -> TrackRow(track, play = { player.select(track.id) }, enqueue = { player.enqueue(track.id) }, queued = track.id in queuedIds, more = more?.let { action -> { action(track) } }) }
                    } else {
                        items(filtered.groupBy { it.group(category) }.entries.toList(), key = { it.key }) { group ->
                            Surface(onClick = { detail = group.key }, modifier = Modifier.fillMaxWidth().prismFocus(), shape = MaterialTheme.shapes.medium) {
                                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                    DemoArtwork(group.value.first().tint, Modifier.size(64.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(group.key, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        Text("${group.value.size} треков", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    SkinIcon(UiIcon.FORWARD, "Открыть")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun CatalogMessage(title: String, description: String, action: () -> Unit, label: String) {
    Column(Modifier.padding(vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(action, Modifier.prismFocus()) { Text(label) }
    }
}
