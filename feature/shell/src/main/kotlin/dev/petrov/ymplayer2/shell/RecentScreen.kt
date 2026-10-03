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
import dev.petrov.ymplayer2.designsystem.*
import dev.petrov.ymplayer2.localization.*
import kotlinx.coroutines.CancellationException

@Composable internal fun RecentScreen(model: ShellModel, more: (Track) -> Unit) {
    val indexed = model.local as? IndexedLocalLibrary ?: return
    val library by model.library.collectAsStateWithLifecycle()
    val playback by model.player.state.collectAsStateWithLifecycle()
    var source by remember(playback.profileId) { mutableStateOf<Source?>(null) }
    var count by remember(playback.profileId, source) { mutableIntStateOf(80) }
    var retry by remember { mutableIntStateOf(0) }
    val filter = CatalogFilter(source = source)
    val batches = remember(indexed.indexRevision, library, filter, retry) { mutableMapOf<Int, CatalogPage<Track>>() }
    val result by key(batches, count) { produceState<Result<CatalogPage<Track>>?>(null, batches, count) {
        value = try {
            for (offset in 0 until count step 80) {
                if (offset !in batches) batches[offset] = indexed.pageRecentTracks(filter, offset, 80)
                if (!batches.getValue(offset).hasMore) break
            }
            Result.success(CatalogPage(batches.toSortedMap().values.flatMap { it.items }, batches[0]?.total ?: 0, 0))
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { Result.failure(e) }
    } }
    val page = result?.getOrNull()
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Text(tr(Msg.recent_title), style = MaterialTheme.typography.titleLarge)
        Text(tr(Msg.recent_description), style = MaterialTheme.typography.bodySmall)
        ChoiceRow(source?.name ?: "all", Modifier.fillMaxWidth()) { choice ->
            FilterChip(source == null, { source = null }, { Text(trMessage("Общий каталог")) }, choice("all").prismFocus().testTag("recent_all"))
            listOf(Source.LOCAL, Source.USB).forEach { item ->
                FilterChip(source == item, { source = item }, { Text(trMessage(item.label)) }, choice(item.name).prismFocus().testTag("recent_${item.name}"))
            }
        }
        if (result == null) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (result?.isFailure == true) TextButton({ retry++ }, Modifier.prismFocus()) { Text(tr(Msg.recent_failure), color = MaterialTheme.colorScheme.error) }
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("recent_list"), contentPadding = PaddingValues(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (page?.total == 0) item { Text(tr(Msg.recent_empty), Modifier.testTag("recent_empty")) }
            items(page?.items.orEmpty(), key = Track::id) { track ->
                TrackRow(track, playback.current?.id == track.id, { model.player.playQueue(listOf(track.id)) },
                    enqueue = { model.player.enqueue(track.id) }, queued = playback.explicitQueueIds?.contains(track.id) == true, more = { more(track) })
            }
            if (page?.hasMore == true) item { OutlinedButton({ count += 80 }, Modifier.fillMaxWidth().prismFocus().testTag("recent_more")) { Text(trMessage("Загрузить ещё")) } }
        }
    }
}
