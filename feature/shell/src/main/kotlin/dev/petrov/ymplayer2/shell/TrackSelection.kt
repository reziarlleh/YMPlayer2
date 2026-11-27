package dev.petrov.ymplayer2.shell

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import dev.petrov.ymplayer2.core.*
import dev.petrov.ymplayer2.designsystem.prismFocus
import dev.petrov.ymplayer2.localization.*

/** Only explicit choices, in the order chosen; loading a page never replaces this list. */
@Stable internal class TrackSelection {
    var active by mutableStateOf(false)
    val tracks = mutableStateListOf<Track>()
    private val ids by derivedStateOf { tracks.mapTo(hashSetOf(), Track::id) }
    fun toggle(track: Track) {
        val index = tracks.indexOfFirst { it.id == track.id }
        if (index >= 0) tracks.removeAt(index) else tracks.add(track)
    }
    fun contains(id: String) = id in ids
    fun add(candidates: List<Track>) {
        val seen = ids.toMutableSet()
        tracks.addAll(candidates.filter { seen.add(it.id) })
    }
    fun clearChecks() = tracks.clear()
    fun clear() { clearChecks(); active = false }
}

@Composable internal fun rememberTrackSelection(vararg section: Any?): TrackSelection = remember(*section) { TrackSelection() }

@Composable internal fun SelectionToolbar(selection: TrackSelection, player: PlaybackController, store: UserCollections? = null,
    prepare: (Track) -> Boolean = { true }) {
    val profile = player.state.collectAsState().value.profileId
    val collections = store?.state?.collectAsState()?.value
    var choosing by remember(selection) { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val view = LocalView.current
    fun finish() { focus.clearFocus(force = true); selection.clear() }
    BackHandler(selection.active && !choosing) { finish() }
    val localOnly = selection.tracks.isNotEmpty() && selection.tracks.all { it.source != Source.YANDEX }
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxWidth()) {
            if (!selection.active) BeginTrackSelection(selection)
            else {
                Text(tr(Msg.bulk_count, selection.tracks.size), Modifier.testTag("bulk_count"), style = MaterialTheme.typography.bodySmall)
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton({ player.enqueueMany(selection.tracks.filter(Track::available).filter(prepare).map(Track::id)); finish() },
                        Modifier.prismFocus().testTag("bulk_enqueue"), enabled = selection.tracks.any(Track::available)) { Text(tr(Msg.bulk_queue)) }
                    if (store != null) OutlinedButton({ choosing = true }, Modifier.prismFocus().testTag("bulk_playlist"), enabled = localOnly && collections?.writable == true) { Text(tr(Msg.bulk_playlist)) }
                    TextButton({ finish() }, Modifier.prismFocus().testTag("bulk_cancel")) { Text(trMessage("Отмена")) }
                }
                if (store != null && selection.tracks.any { it.source == Source.YANDEX }) Text(tr(Msg.bulk_local_only), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    if (choosing && store != null) SaveTracksToPlaylistDialog(store, profile, selection.tracks.map(Track::id),
        dismiss = { choosing = false }, saved = {
            // A repository write can complete through an IO dispatcher. Focus belongs to the view thread.
            view.post { choosing = false; finish() }
        })
}

@Composable internal fun BeginTrackSelection(selection: TrackSelection) {
    OutlinedButton({
        selection.active = true
    }, Modifier.prismFocus().testTag("bulk_start")) { Text(tr(Msg.bulk_select)) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun ResultActions(content: @Composable FlowRowScope.() -> Unit) {
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), content = content)
}
