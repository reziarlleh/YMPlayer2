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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.petrov.ymplayer2.core.*
import dev.petrov.ymplayer2.designsystem.*
import dev.petrov.ymplayer2.designsystem.skin.*

@Composable internal fun OnlineScreen(music: OnlineMusic, player: PlaybackController, search: Boolean, signIn: () -> Unit, taste: MusicTaste? = null, waveStarted: () -> Unit = {},
    artist: (ArtistRef) -> Unit = { music.open(MusicEntity(it.id, it.name, MusicKind.ARTISTS)) }, standalone: Boolean = false, closeArtist: () -> Unit = {}) {
    val state by music.state.collectAsStateWithLifecycle()
    val playback by player.state.collectAsStateWithLifecycle()
    val keyboard = LocalSoftwareKeyboardController.current
    val request = state.request
    val detail = request.entity
    fun up() { if (standalone && detail?.kind == MusicKind.ARTISTS) closeArtist() else music.up() }
    BackHandler(detail != null) { up() }
    LaunchedEffect(search, state.profileId, state.signedIn) {
        if (!standalone && detail == null) {
            if (search && request.collection) music.search("")
            if (!search && state.signedIn && (!request.collection || !state.loaded && !state.loading && state.issue == null)) music.collection()
        }
    }
    val preferences = taste?.state?.collectAsStateWithLifecycle()?.value
    var actions by remember(state.profileId) { mutableStateOf<Track?>(null) }
    if (taste != null) actions?.let { TrackTasteDialog(it, taste, artist = artist) { actions = null } }
    val entries = if (request.recommended) state.entries.filter { entry -> entry.track?.let { preferences?.allows(it) != false } ?: true } else state.entries
    val shownTracks = entries.mapNotNull(MusicEntry::track)
    val filtersReady = !request.recommended || preferences?.let { it.shelf(TasteKind.TRACK).ready && it.shelf(TasteKind.ARTIST).ready } != false
    fun play(trackId: String? = null) {
        if (!filtersReady) return
        if (request.recommended) player.playRecommendedQueue(shownTracks.map(Track::id), trackId) else player.playQueue(shownTracks.map(Track::id), trackId)
        keyboard?.hide()
    }
    val queued = playback.queue.mapTo(hashSetOf(), Track::id)
    LazyColumn(Modifier.fillMaxSize().imePadding().testTag("online_list"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            if (detail != null) TextButton({ up() }, Modifier.prismFocus().testTag("online_up")) { Text("На уровень выше") }
            val title: @Composable () -> Unit = { Text(detail?.title ?: if (search) "Поиск в Яндекс Музыке" else "Моя музыка в Яндексе", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
            val target = detail?.tasteTarget()
            if (state.signedIn && taste != null && target != null) Column { TasteLabel(taste, target, "detail", title) } else title()
        }
        if (!state.signedIn) {
            item {
                Text("Для онлайн-музыки войдите в Яндекс в этом профиле. Локальная музыка доступна без входа.")
                OutlinedButton(signIn, Modifier.prismFocus().testTag("online_sign_in")) { Text("Открыть аккаунт") }
            }
        } else {
            if (detail?.kind == MusicKind.ARTISTS) item {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(MusicKind.TRACKS to "Популярные треки", MusicKind.ALBUMS to "Альбомы").forEach { (kind, label) ->
                        FilterChip(request.kind == kind, { music.artistSection(kind) }, { Text(label) }, Modifier.prismFocus().testTag("artist_section_${kind.name}"))
                    }
                }
            }
            if (detail == null) {
                if (!search && taste != null) item { WaveButton(player, state.signedIn, signIn, waveStarted) }
                if (search) item {
                    OutlinedTextField(request.query, { music.search(it) }, Modifier.fillMaxWidth().prismFocus().testTag("online_query"),
                        label = { Text("Название") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                        trailingIcon = { if (request.query.isNotEmpty()) ActionIcon(UiIcon.CLOSE, "Очистить поиск", { music.search("") }) })
                }
                item {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MusicKind.entries.forEach { kind ->
                            FilterChip(request.kind == kind && !request.recommended, { if (search) music.search(request.query, kind, false) else music.collection(kind) },
                                { Text(if (search) kind.label else when (kind) {
                                    MusicKind.TRACKS -> "Мне нравится"
                                    MusicKind.ARTISTS -> "Любимые исполнители"
                                    MusicKind.ALBUMS -> "Любимые альбомы"
                                    MusicKind.PLAYLISTS -> "Мои плейлисты"
                                }) }, Modifier.prismFocus().testTag("online_kind_${kind.name}"))
                        }
                        if (!search) FilterChip(request.recommended, music::recommendations, { Text("Рекомендации") }, Modifier.prismFocus().testTag("online_recommendations"))
                    }
                }
                if (!search && request.recommended) item { Text("Плейлисты, подобранные Яндексом для вашего аккаунта.") }
            }
            if (state.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth().testTag("online_loading")); Text("Загружаем…") }
            if (state.issue != null) item {
                Text(state.issue!!, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("online_issue"))
                OutlinedButton(music::retry, Modifier.prismFocus().testTag("online_retry"), enabled = !state.loading) { Text("Повторить") }
            }
            if (!state.loading && state.issue == null && entries.isEmpty()) item {
                Text(if (search && request.query.isBlank() && detail == null) "Введите название и выберите тип результатов."
                    else if (state.loaded) "Здесь пока ничего нет. Попробуйте другой запрос или раздел." else "Выберите раздел.", Modifier.testTag("online_empty"))
            }
            if (shownTracks.any { it.available }) item {
                if (!filtersReady) {
                    Text("Для рекомендаций нужно проверить запрещённые треки и исполнителей.")
                    TextButton({ taste?.refresh(TasteKind.TRACK); taste?.refresh(TasteKind.ARTIST) }, Modifier.prismFocus()) { Text("Обновить отметки") }
                }
                OutlinedButton({ play() }, Modifier.prismFocus().testTag("online_play_all"), enabled = filtersReady) { Text("Слушать показанные треки") }
            }
            items(entries, key = MusicEntry::id) { entry ->
                val track = entry.track
                if (track != null) TrackRow(track,
                    play = { play(track.id) },
                    enqueue = { player.enqueue(track.id) }, queued = track.id in queued, more = if (taste != null) ({ actions = track }) else null, taste = taste, artist = artist)
                else entry.entity?.let { entity ->
                    val label: @Composable () -> Unit = { Surface(onClick = { keyboard?.hide(); music.open(entity) }, modifier = Modifier.fillMaxWidth().prismFocus().testTag("online_entity_${entity.id}"), shape = MaterialTheme.shapes.medium) {
                        Column(Modifier.padding(16.dp)) {
                            Text(entry.title, fontWeight = FontWeight.Bold)
                            Text(entry.subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } }
                    val target = entity.tasteTarget()
                    if (taste != null && target != null) Column { TasteLabel(taste, target, "catalog", label) } else label()
                }
            }
            if (state.nextPage != null && state.issue == null) item {
                OutlinedButton(music::more, Modifier.prismFocus().testTag("online_more"), enabled = !state.loading) { Text("Загрузить ещё") }
            }
        }
    }
}
