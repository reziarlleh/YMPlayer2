package dev.petrov.ymplayer2.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
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
            if (wide) Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                RadioPlayerPanel(playback, catalog, radio, signIn, true, short, Modifier.width(if (short) 236.dp else 280.dp).fillMaxHeight())
                RadioCatalogPanel(catalog, playback, radio, Modifier.weight(1f).fillMaxHeight(), short) { citiesOpen = true }
            } else {
                RadioCatalogPanel(catalog, playback, radio, Modifier.weight(1f).fillMaxWidth(), short) { citiesOpen = true }
                RadioPlayerPanel(playback, catalog, radio, signIn, false, short, Modifier.fillMaxWidth())
            }
        }
    }
    if (citiesOpen) RadioCityDialog(catalog.cities, catalog.citiesBusy, { citiesOpen = false }) { radio.filter(it); citiesOpen = false }
}

private fun RadioTab.label() = when (this) { RadioTab.COLLECTION -> "Коллекция"; RadioTab.CITIES -> "Города"; RadioTab.GENRES -> "Жанры"; RadioTab.ALL -> "Все станции" }

@Composable private fun RadioCatalogPanel(state: RadioCatalogState, playback: RadioPlaybackState, radio: RadioController, modifier: Modifier, short: Boolean, chooseCity: () -> Unit) {
    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (state.tab == RadioTab.CITIES) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton({ radio.tab(RadioTab.CITIES); chooseCity() }, Modifier.prismFocus().testTag("radio_choose_city")) {
                    Text(state.filter?.name ?: trMessage("Выбрать город"), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (state.filter != null) TextButton({ radio.filter(null) }, Modifier.prismFocus()) { Text(trMessage("Все города")) }
            }
            if (state.tab == RadioTab.GENRES) LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.testTag("radio_genres")) {
                item { FilterChip(state.filter == null, { radio.filter(null) }, { Text(trMessage("Все жанры")) }, Modifier.prismFocus()) }
                items(state.genres, key = { it.slug }) { genre ->
                    FilterChip(state.filter?.slug == genre.slug, { radio.filter(genre) }, { Text(genre.name) }, Modifier.prismFocus().testTag("radio_genre_${genre.slug}"))
                }
            }
        if (state.tab == RadioTab.COLLECTION && state.query.isBlank()) {
            Text(trMessage("Любимые станции"), style = MaterialTheme.typography.titleSmall)
            when {
                !state.signedIn -> Text(trMessage("Войдите в Яндекс, чтобы сохранить любимые станции."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                state.favouritesBusy && state.favourites.isEmpty() -> LinearProgressIndicator(Modifier.fillMaxWidth())
                state.favourites.isEmpty() && state.collectionIssue == null -> Text(trMessage("Любимых станций пока нет. Добавьте их сердечком в плеере."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> StationCarousel(state.favourites, playback, { radio.play(it) }, "radio_favourites", short,
                    state.favouritesHaveNext, { radio.refreshCollection(more = true) }, state.favouritesBusy)
            }
        }
        if (state.collectionIssue != null) Text(trMessage(state.collectionIssue!!.text()), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        Text(if (state.query.isNotBlank()) trMessage("Результаты поиска") else state.filter?.name ?: trMessage("Все станции"), style = MaterialTheme.typography.titleSmall)
        if (state.busy && state.stations.isEmpty()) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (state.stations.isNotEmpty()) StationCarousel(state.stations, playback, { radio.play(it) }, "radio_stations", short,
            state.hasNext, radio::more, state.busy)
        if (!state.busy && state.stations.isEmpty() && state.issue == null) Text(trMessage("Станции не найдены."), style = MaterialTheme.typography.bodySmall)
        state.issue?.let { Text(trMessage(it.text()), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        if (state.issue != null || state.collectionIssue != null) OutlinedButton(radio::refresh, Modifier.prismFocus().testTag("radio_retry")) { Text(trMessage("Повторить")) }
    }
}

@Composable private fun StationCarousel(stations: List<RadioStation>, playback: RadioPlaybackState, play: (RadioStation) -> Unit,
    tag: String, short: Boolean, more: Boolean, loadMore: () -> Unit, busy: Boolean) {
    LazyRow(Modifier.fillMaxWidth().testTag(tag), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(stations, key = { "${it.slug}:${it.streamSlug}" }) { station ->
            val selected = station.slug == playback.station?.slug && (station.streamSlug == playback.station?.streamSlug || station.streamSlug == "${station.slug}-${playback.station?.streamSlug}" || station.streamSlug == null)
            Surface(onClick = { play(station) }, Modifier.width(if (short) 104.dp else 114.dp).prismFocus()
                .semantics { this.selected = selected }.testTag("radio_station_${station.slug}"), shape = MaterialTheme.shapes.medium,
                color = if (selected && playback.ownsOutput) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface) {
                Column(Modifier.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    RadioArtwork(station, Modifier.size(if (short) 64.dp else 76.dp))
                    Text(station.name, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    station.regionName?.let { Text(it, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
        }
        if (more) item { OutlinedButton(loadMore, Modifier.heightIn(min = 90.dp).prismFocus().testTag("${tag}_more"), enabled = !busy) { Text(trMessage("Ещё станции")) } }
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

@Composable private fun RadioCityDialog(cities: List<RadioFilter>, busy: Boolean, close: () -> Unit, select: (RadioFilter) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    AlertDialog(onDismissRequest = close, title = { Text(trMessage("Выбрать город")) }, confirmButton = { TextButton(close, Modifier.prismFocus()) { Text(trMessage("Закрыть")) } },
        text = { Column(Modifier.heightIn(max = 440.dp)) {
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().testTag("radio_city_search"), singleLine = true,
                placeholder = { Text(trMessage("Найти город")) })
            if (busy && cities.isEmpty()) LinearProgressIndicator(Modifier.fillMaxWidth())
            else if (cities.isEmpty()) Text(trMessage("Список городов недоступен. Закройте окно и повторите запрос."))
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
