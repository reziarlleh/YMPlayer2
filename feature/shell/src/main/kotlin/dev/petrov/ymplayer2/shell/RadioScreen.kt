package dev.petrov.ymplayer2.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.petrov.ymplayer2.core.*
import dev.petrov.ymplayer2.designsystem.*
import dev.petrov.ymplayer2.designsystem.skin.UiIcon
import dev.petrov.ymplayer2.localization.trMessage

@Composable internal fun RadioScreen(radio: RadioController, signIn: () -> Unit) {
    val catalog by radio.state.collectAsStateWithLifecycle()
    val playback by radio.playback.collectAsStateWithLifecycle()
    var citiesOpen by rememberSaveable { mutableStateOf(false) }
    val focusTargets = remember { mutableMapOf<String, FocusRequester>() }
    var returnTarget by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val openStation: (RadioStation, String) -> Unit = { station, target -> returnTarget = target; radio.openStation(station) }
    val closeStation: () -> Unit = {
        radio.closeStation()
        scope.launch {
            withFrameNanos { }; withFrameNanos { }
            returnTarget?.let { focusTargets[it] }?.let { runCatching { it.requestFocus() } }
        }
    }
    LaunchedEffect(radio) { radio.open() }
    BoxWithConstraints(Modifier.fillMaxSize().testTag("radio_screen")) {
        val wide = maxWidth >= 480.dp && maxWidth > maxHeight
        val short = maxHeight < 430.dp
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                RadioTab.entries.forEach { tab ->
                    val chosen = catalog.tab == tab
                    Surface(onClick = { radio.tab(tab); if (tab == RadioTab.CITIES) citiesOpen = true },
                        modifier = Modifier.weight(1f).heightIn(min = 40.dp).prismFocus().semantics { selected = chosen }.testTag("radio_tab_${tab.name}"),
                        color = if (chosen) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                        shape = MaterialTheme.shapes.small) {
                        Box(Modifier.padding(horizontal = 4.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
                            Text(trMessage(tab.label()), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            OutlinedTextField(catalog.query, radio::search, Modifier.fillMaxWidth().testTag("radio_search"), singleLine = true,
                placeholder = { Text(trMessage("Поиск по всем станциям"), fontSize = 14.sp) },
                leadingIcon = { SkinIcon(UiIcon.SEARCH, null) },
                trailingIcon = { if (catalog.query.isNotEmpty()) ActionIcon(UiIcon.CLOSE, trMessage("Очистить поиск"), { radio.search("") }) })
            val collection = catalog.tab == RadioTab.COLLECTION && catalog.query.isBlank()
            if (wide && collection) Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                RadioPlayerPanel(playback, catalog, radio, signIn, true, short, Modifier.width(if (short) 236.dp else 280.dp).fillMaxHeight())
                RadioCatalogPanel(catalog, playback, radio, Modifier.weight(1f).fillMaxHeight(), short, signIn, focusTargets, openStation) { citiesOpen = true }
            } else {
                RadioCatalogPanel(catalog, playback, radio, Modifier.weight(1f).fillMaxWidth(), short, signIn, focusTargets, openStation) { citiesOpen = true }
                if (collection) RadioPlayerPanel(playback, catalog, radio, signIn, false, short, Modifier.fillMaxWidth())
                else if (playback.station != null) RadioMiniPlayer(radio) { radio.playback.value.station?.let { openStation(it, "") } }
            }
        }
    }
    if (citiesOpen) RadioCityDialog(catalog.cities, catalog.citiesBusy, catalog.citiesIssue, radio::refresh, { citiesOpen = false }) { radio.filter(it); citiesOpen = false }
    catalog.detail?.let { station -> RadioStationDialog(station, catalog, radio, {
        closeStation(); signIn()
    }, closeStation) }
}

private fun RadioTab.label() = when (this) { RadioTab.COLLECTION -> "Коллекция"; RadioTab.CITIES -> "Города"; RadioTab.GENRES -> "Жанры"; RadioTab.ALL -> "Все станции" }
private fun RadioStation.identity() = "$slug:${streamSlug.orEmpty()}"

@Composable private fun RadioCatalogPanel(state: RadioCatalogState, playback: RadioPlaybackState, radio: RadioController,
    modifier: Modifier, short: Boolean, signIn: () -> Unit, targets: MutableMap<String, FocusRequester>,
    open: (RadioStation, String) -> Unit, chooseCity: () -> Unit) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.tab == RadioTab.CITIES) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(chooseCity, Modifier.prismFocus().testTag("radio_choose_city")) {
                Text(state.filter?.name ?: trMessage("Выбрать город"), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (state.filter != null) TextButton({ radio.filter(null) }, Modifier.prismFocus()) { Text(trMessage("Все города")) }
        }
        if (state.tab == RadioTab.GENRES) {
            if (state.genresBusy && state.genres.isEmpty()) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.genresIssue?.let { Text(trMessage(it.text()), color = MaterialTheme.colorScheme.error); TextButton(radio::refresh, Modifier.prismFocus()) { Text(trMessage("Повторить")) } }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.testTag("radio_genres")) {
                item { FilterChip(state.filter == null, { radio.filter(null) }, { Text(trMessage("Все жанры")) }, Modifier.prismFocus()) }
                items(state.genres, key = { it.slug }) { genre ->
                    FilterChip(state.filter?.slug == genre.slug, { radio.filter(genre) }, { Text(genre.name) }, Modifier.prismFocus().testTag("radio_genre_${genre.slug}"))
                }
            }
        }
        if (state.tab == RadioTab.COLLECTION && state.query.isBlank()) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).testTag("radio_collection"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(trMessage("Любимые станции"), style = MaterialTheme.typography.titleSmall)
                when {
                    !state.signedIn -> Text(trMessage("Войдите в Яндекс, чтобы сохранить любимые станции."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    state.favouritesBusy && state.favourites.isEmpty() -> LinearProgressIndicator(Modifier.fillMaxWidth())
                    state.favourites.isEmpty() && state.collectionIssue == null -> Text(trMessage("Любимых станций пока нет. Добавьте их сердечком в плеере."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else -> StationCarousel(state.favourites, playback, state, radio, signIn, "radio_favourites", short,
                        state.favouritesHaveNext && state.collectionIssue == null, { radio.refreshCollection(more = true) }, state.favouritesBusy, targets, open)
                }
                Text(trMessage("Все станции"), style = MaterialTheme.typography.titleSmall)
                StationCarousel(state.stations, playback, state, radio, signIn, "radio_stations", short,
                    state.hasNext && state.issue == null, radio::more, state.busy, targets, open)
                CatalogStatus(state, radio)
            }
        } else {
            Text(if (state.query.isNotBlank()) trMessage("Результаты поиска") else state.filter?.name ?: trMessage("Все станции"), style = MaterialTheme.typography.titleSmall)
            key(state.tab, state.query, state.filter?.slug) {
                val grid = rememberLazyGridState()
                LaunchedEffect(grid, state.stations.size, state.cursor, state.busy, state.issue, state.detail != null) {
                    if (state.hasNext && !state.busy && state.issue == null && state.detail == null) {
                        snapshotFlow { grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let { it >= state.stations.size - 6 } == true }
                            .distinctUntilChanged().collect { if (it) radio.more() }
                    }
                }
                LazyVerticalGrid(GridCells.Adaptive(140.dp), Modifier.weight(1f).fillMaxWidth().testTag("radio_grid"), state = grid,
                    horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(state.stations, key = { it.identity() }) { station ->
                        StationTile(station, playback, state, radio, signIn, "radio_grid", short, targets, open, Modifier.fillMaxWidth())
                    }
                    item(span = { GridItemSpan(maxLineSpan) }) { CatalogStatus(state, radio) }
                }
            }
        }
    }
}

@Composable private fun CatalogStatus(state: RadioCatalogState, radio: RadioController) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("radio_loading"))
        if (!state.busy && state.stations.isEmpty() && state.issue == null) Text(trMessage("Станции не найдены."), style = MaterialTheme.typography.bodySmall)
        state.issue?.let { Text(trMessage(it.text()), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        state.collectionIssue?.let { Text(trMessage(it.text()), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        if (state.hasNext) OutlinedButton({ if (state.issue != null) radio.retryMore() else radio.more() }, Modifier.prismFocus().testTag("radio_stations_more"), enabled = !state.busy) { Text(trMessage(if (state.issue != null) "Повторить" else "Ещё станции")) }
        else if (state.issue != null || state.collectionIssue != null) OutlinedButton(radio::refresh, Modifier.prismFocus().testTag("radio_retry")) { Text(trMessage("Повторить")) }
    }
}

@Composable private fun StationCarousel(stations: List<RadioStation>, playback: RadioPlaybackState, state: RadioCatalogState,
    radio: RadioController, signIn: () -> Unit, tag: String, short: Boolean, more: Boolean, loadMore: () -> Unit, busy: Boolean,
    targets: MutableMap<String, FocusRequester>, open: (RadioStation, String) -> Unit) {
    val list = rememberLazyListState()
    LaunchedEffect(list, stations.size, more, busy, state.detail != null) {
        if (more && !busy && state.detail == null) snapshotFlow { list.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let { it >= stations.size - 3 } == true }
            .distinctUntilChanged().collect { if (it) loadMore() }
    }
    LazyRow(Modifier.fillMaxWidth().testTag(tag), state = list, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(stations, key = { it.identity() }) { station -> StationTile(station, playback, state, radio, signIn, tag, short, targets, open, Modifier.width(136.dp)) }
        if (more) item { OutlinedButton(loadMore, Modifier.heightIn(min = 90.dp).prismFocus().testTag("${tag}_more"), enabled = !busy) { Text(trMessage("Ещё станции")) } }
    }
}

@Composable private fun StationTile(station: RadioStation, playback: RadioPlaybackState, state: RadioCatalogState,
    radio: RadioController, signIn: () -> Unit, tag: String, short: Boolean, targets: MutableMap<String, FocusRequester>,
    open: (RadioStation, String) -> Unit, modifier: Modifier) {
    val target = "$tag:${station.identity()}"
    val requester = remember(target) { FocusRequester() }
    DisposableEffect(target) { targets[target] = requester; onDispose { targets.remove(target) } }
    val selected = station.slug == playback.station?.slug && playback.ownsOutput
    Surface(modifier, shape = MaterialTheme.shapes.medium, color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Surface(onClick = { open(station, target) }, Modifier.fillMaxWidth().focusRequester(requester).prismFocus().testTag("radio_station_${station.slug}"),
                color = Color.Transparent, shape = MaterialTheme.shapes.medium) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(2.dp)) {
                    RadioArtwork(station, Modifier.size(if (short) 64.dp else 80.dp))
                    Text(station.name, fontSize = 12.sp, maxLines = 2, minLines = 2, lineHeight = 15.sp, overflow = TextOverflow.Ellipsis)
                    station.regionName?.let { Text(it, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                StationLike(station, state, radio, signIn, Modifier.testTag("radio_station_like_${station.slug}"))
                ActionIcon(UiIcon.PLAY, trMessage("Включить эфир"), { radio.play(station) }, Modifier.testTag("radio_station_play_${station.slug}"))
            }
        }
    }
}

@Composable private fun StationLike(station: RadioStation, state: RadioCatalogState, radio: RadioController, signIn: () -> Unit, modifier: Modifier) {
    val liked = station.slug in state.favouriteSlugs
    ActionIcon(if (liked) UiIcon.FAVORITE else UiIcon.FAVORITE_OFF, trMessage(if (liked) "Убрать из коллекции" else "В коллекцию"),
        { if (state.signedIn) radio.like(station) else signIn() }, modifier,
        enabled = station.slug !in state.pendingLikes && !state.favouritesBusy && (state.collectionIssue == null || !state.signedIn))
}

@Composable private fun RadioStationDialog(station: RadioStation, state: RadioCatalogState, radio: RadioController, signIn: () -> Unit, close: () -> Unit) {
    val playback by radio.playback.collectAsStateWithLifecycle()
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.widthIn(max = 680.dp).fillMaxWidth().fillMaxHeight(.9f).padding(16.dp).testTag("radio_station_detail"),
            shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ActionIcon(UiIcon.BACK, trMessage("Назад"), close, Modifier.testTag("radio_detail_back"))
                    Text(station.name, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    RadioArtwork(station, Modifier.size(160.dp))
                    station.regionName?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    if (state.detailBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    station.description?.let { Text(it, Modifier.fillMaxWidth().testTag("radio_station_description"), style = MaterialTheme.typography.bodyMedium) }
                    state.detailIssue?.let { Text(trMessage(it.text()), color = MaterialTheme.colorScheme.error) }
                    if (state.detailIssue != null) OutlinedButton({ radio.openStation(station) }, Modifier.prismFocus()) { Text(trMessage("Повторить")) }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterHorizontally)) {
                    StationLike(station, state, radio, signIn, Modifier.testTag("radio_detail_like"))
                    ActionIcon(UiIcon.PLAY, trMessage("Включить эфир"), { radio.play(station) }, Modifier.testTag("radio_detail_play"), primary = true)
                    ActionIcon(UiIcon.STOP, trMessage("Остановить эфир"), radio.audio::stop, Modifier.testTag("radio_detail_stop"),
                        enabled = playback.station?.slug == station.slug && playback.ownsOutput)
                }
            }
        }
    }
}

@Composable private fun RadioArtwork(station: RadioStation?, modifier: Modifier) {
    val color = station?.logoColor?.let { runCatching { Color(android.graphics.Color.parseColor(it)) }.getOrNull() } ?: MaterialTheme.colorScheme.surfaceVariant
    Box(modifier.clip(MaterialTheme.shapes.medium).background(color), contentAlignment = Alignment.Center) {
        PublicArtwork(station?.logoUri, station?.name.orEmpty(), Modifier.fillMaxSize().padding(4.dp), ContentScale.Fit, "radio_artwork") {
            SkinIcon(UiIcon.RADIO, null, Modifier.size(40.dp))
        }
    }
}

@Composable private fun RadioPlayerPanel(playback: RadioPlaybackState, catalog: RadioCatalogState, radio: RadioController,
    signIn: () -> Unit, wide: Boolean, short: Boolean, modifier: Modifier) {
    Surface(modifier.testTag("radio_player"), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(if (short) 8.dp else 14.dp), verticalArrangement = Arrangement.spacedBy(if (short) 4.dp else 10.dp)) {
            if (wide && !short) RadioArtwork(playback.station, Modifier.size(136.dp).align(Alignment.CenterHorizontally))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (!wide) RadioArtwork(playback.station, Modifier.size(48.dp))
                Column(Modifier.weight(1f)) {
                    Text(trMessage(when { playback.reconnecting -> "Переподключение…"; playback.buffering -> "Подключение…"; playback.playing -> "Прямой эфир"; else -> "Радио" }),
                        color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall, modifier = Modifier.testTag("radio_playback_status"))
                    Text(playback.station?.name ?: trMessage("Выберите станцию"), fontSize = if (short) 18.sp else 21.sp, lineHeight = if (short) 22.sp else 26.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("radio_current_station"))
                    playback.station?.regionName?.takeIf { it.isNotBlank() && !short }?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, lineHeight = 14.sp) }
                }
            }
            if (playback.issue == null) Column(Modifier.testTag("radio_on_air"), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(trMessage("Сейчас в эфире"), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, lineHeight = 14.sp)
                Text(playback.onAir.title.ifBlank { trMessage("Информация об эфире недоступна") }, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = if (short) 13.sp else 16.sp, lineHeight = if (short) 16.sp else 20.sp)
                if (playback.onAir.artist.isNotBlank()) Text(playback.onAir.artist, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp, lineHeight = 16.sp)
            }
            if (wide) Spacer(Modifier.weight(1f))
            if (playback.issue != null) Text(trMessage(playback.issue!!.text()), color = MaterialTheme.colorScheme.error, fontSize = 11.sp, maxLines = if (short) 1 else 2, overflow = TextOverflow.Ellipsis)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(22.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
                val station = playback.station
                val liked = station?.slug in catalog.favouriteSlugs
                ActionIcon(if (liked) UiIcon.FAVORITE else UiIcon.FAVORITE_OFF,
                    trMessage(if (liked) "Убрать из коллекции" else "В коллекцию"),
                    { if (!catalog.signedIn) signIn() else station?.let(radio::like) }, Modifier.testTag("radio_like"),
                    enabled = station != null && station.slug !in catalog.pendingLikes && !catalog.favouritesBusy && (catalog.collectionIssue == null || !catalog.signedIn))
                ActionIcon(UiIcon.PLAY, trMessage("Включить эфир"), { radio.play() }, Modifier.testTag("radio_play"),
                    enabled = station != null && !playback.playing && !playback.buffering && !playback.reconnecting, primary = !short)
                ActionIcon(UiIcon.STOP, trMessage("Остановить эфир"), radio.audio::stop, Modifier.testTag("radio_stop"),
                    enabled = playback.ownsOutput && (playback.playing || playback.buffering || playback.reconnecting))
            }
        }
    }
}

@Composable private fun RadioCityDialog(cities: List<RadioFilter>, busy: Boolean, issue: RadioIssue?, retry: () -> Unit, close: () -> Unit, select: (RadioFilter) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    AlertDialog(onDismissRequest = close, title = { Text(trMessage("Выбрать город")) }, confirmButton = { TextButton(close, Modifier.prismFocus()) { Text(trMessage("Закрыть")) } },
        text = { Column(Modifier.heightIn(max = 440.dp)) {
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().testTag("radio_city_search"), singleLine = true,
                placeholder = { Text(trMessage("Найти город")) })
            if (busy && cities.isEmpty()) LinearProgressIndicator(Modifier.fillMaxWidth())
            else if (cities.isEmpty()) Text(trMessage("Список городов недоступен. Закройте окно и повторите запрос."))
            if (issue != null) TextButton(retry, Modifier.prismFocus().testTag("radio_cities_retry")) { Text(trMessage("Повторить")) }
            LazyColumn(Modifier.testTag("radio_cities_list")) {
                items(cities.filter { it.name.contains(query, ignoreCase = true) }, key = { it.slug }) { city ->
                    TextButton({ select(city) }, Modifier.fillMaxWidth().prismFocus().testTag("radio_city_${city.slug}")) {
                        Text(city.name, Modifier.fillMaxWidth())
                    }
                }
            }
        } })
}

@Composable internal fun RadioMiniPlayer(radio: RadioController, open: () -> Unit) {
    val playback by radio.playback.collectAsStateWithLifecycle()
    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(open, Modifier.weight(1f).prismFocus().testTag("mini_radio_open")) {
                Column(Modifier.fillMaxWidth()) {
                    Text(playback.station?.name ?: trMessage("Радио"), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(playback.onAir.title.ifBlank { trMessage("Прямой эфир") }, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            ActionIcon(if (playback.playing || playback.buffering || playback.reconnecting) UiIcon.STOP else UiIcon.PLAY,
                trMessage(if (playback.playing || playback.buffering || playback.reconnecting) "Остановить эфир" else "Включить эфир"),
                { if (playback.playing || playback.buffering || playback.reconnecting) radio.audio.stop() else radio.play() }, Modifier.testTag("mini_radio_toggle"))
        }
    }
}
private fun RadioIssue.text() = when (this) {
    RadioIssue.NETWORK -> "Нет связи с Радио. Проверьте сеть и повторите."
    RadioIssue.ACCESS -> "Нет доступа к коллекции Радио. Проверьте вход в текущем профиле."
    RadioIssue.UNAVAILABLE -> "Эфир этой станции сейчас недоступен."
    RadioIssue.RESPONSE -> "Не удалось прочитать ответ Радио. Повторите запрос."
}
