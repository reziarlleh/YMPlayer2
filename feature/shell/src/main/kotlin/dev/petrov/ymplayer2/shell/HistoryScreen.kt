package dev.petrov.ymplayer2.shell

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.petrov.ymplayer2.core.*
import dev.petrov.ymplayer2.designsystem.prismFocus
import dev.petrov.ymplayer2.localization.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@Composable internal fun HistoryScreen(model: ShellModel, artist: (ArtistRef) -> Unit) {
    val history = model.history ?: return
    val playback by model.player.state.collectAsStateWithLifecycle()
    val log by history.state.collectAsStateWithLifecycle()
    val library by model.library.collectAsStateWithLifecycle()
    val online by (model.online?.catalog ?: remember { kotlinx.coroutines.flow.MutableStateFlow(OnlineCatalogState()) }).collectAsStateWithLifecycle()
    val cache by (model.offline?.state ?: remember { kotlinx.coroutines.flow.MutableStateFlow(OfflineState()) }).collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var count by remember(playback.profileId) { mutableIntStateOf(80) }
    var retry by remember { mutableIntStateOf(0) }
    var confirming by remember(playback.profileId) { mutableStateOf(false) }
    var result by remember(playback.profileId) { mutableStateOf<Result<CatalogPage<ListeningEntry>>?>(null) }
    LaunchedEffect(playback.profileId, log.revision, count, retry, library, online.enabled, online.profileId, cache) {
        result = try {
            val page = history.page(playback.profileId, limit = count)
            val locals = (model.local as? IndexedLocalLibrary)?.tracksByIds(page.items.filter { it.track.source != Source.YANDEX }.map { it.track.id }).orEmpty()
            val cached = cache.tracks.takeIf { cache.enabled && cache.owner?.profileId == playback.profileId }.orEmpty().associateBy(Track::id)
            Result.success(page.copy(items = page.items.map { entry ->
                val saved = entry.track
                entry.copy(track = if (saved.source == Source.YANDEX) cached[saved.id] ?: saved.copy(available = online.profileId == playback.profileId && online.enabled)
                    else locals[saved.id] ?: saved.copy(available = false))
            }))
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { Result.failure(e) }
    }
    val page = result?.getOrNull()
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Text(tr(Msg.history_title), style = MaterialTheme.typography.titleLarge)
        Text(tr(Msg.history_description), style = MaterialTheme.typography.bodySmall)
        if (page != null && page.total > 0) OutlinedButton({ confirming = true }, Modifier.prismFocus().testTag("history_clear")) { Text(trMessage("Очистить")) }
        if (result == null) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (result?.isFailure == true || log.failed) TextButton({ retry++ }, Modifier.prismFocus()) { Text(tr(Msg.history_failure), color = MaterialTheme.colorScheme.error) }
        if (page?.total == 0) Text(tr(Msg.history_empty), Modifier.padding(vertical = 16.dp).testTag("history_empty"))
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("history_list"), contentPadding = PaddingValues(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(page?.items.orEmpty(), key = { it.track.id }) { entry ->
                val track = entry.track
                fun prepare(): Boolean = track.source != Source.YANDEX || track.offline || model.online?.restoreForPlayback(playback.profileId, track) == true
                TrackRow(track, playback.current?.id == track.id, { if (prepare()) model.player.playQueue(listOf(track.id)) },
                    enqueue = { if (prepare()) model.player.enqueue(track.id) }, queued = playback.explicitQueueIds?.contains(track.id) == true,
                    taste = model.taste, location = "history", artist = artist,
                    note = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(entry.playedAtMillis)))
            }
            if (page?.hasMore == true) item { OutlinedButton({ count = (count + 80).coerceAtMost(500) }, Modifier.fillMaxWidth().prismFocus().testTag("history_more")) { Text(trMessage("Загрузить ещё")) } }
        }
    }
    if (confirming) AlertDialog(onDismissRequest = { confirming = false }, title = { Text(tr(Msg.history_clear_title)) },
        text = { Text(tr(Msg.history_clear_description)) },
        confirmButton = { TextButton({ confirming = false; val profile = playback.profileId; scope.launch {
            try { history.clear(profile) } catch (e: CancellationException) { throw e } catch (e: Exception) { result = Result.failure(e) }
        } }, Modifier.prismFocus().testTag("history_clear_confirm")) { Text(trMessage("Очистить")) } },
        dismissButton = { TextButton({ confirming = false }, Modifier.prismFocus()) { Text(trMessage("Отмена")) } })
}
