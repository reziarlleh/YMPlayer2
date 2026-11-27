package dev.petrov.ymplayer2.shell

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.petrov.ymplayer2.core.OnlineMusic
import dev.petrov.ymplayer2.core.OnlineMusicState
import dev.petrov.ymplayer2.core.PlaybackController
import dev.petrov.ymplayer2.core.Track
import dev.petrov.ymplayer2.designsystem.ActionIcon
import dev.petrov.ymplayer2.designsystem.prismFocus
import dev.petrov.ymplayer2.designsystem.skin.UiIcon
import dev.petrov.ymplayer2.localization.Msg
import dev.petrov.ymplayer2.localization.tr
import kotlinx.coroutines.flow.first

@Stable internal class OnlineLibrarySelection(val tracks: TrackSelection) {
    var loadingAll by mutableStateOf(false)
    var range by mutableStateOf(false)
        private set
    var rangeStart by mutableStateOf<Track?>(null)
        private set

    fun beginRange() { range = true; rangeStart = null }

    private fun endRange() { range = false; rangeStart = null }

    fun toggle(track: Track, shown: List<Track>) {
        if (loadingAll) return
        if (!range) {
            tracks.toggle(track)
            return
        }
        val start = rangeStart
        if (start == null) {
            rangeStart = track
            return
        }
        val from = shown.indexOfFirst { it.id == start.id }
        val to = shown.indexOfFirst { it.id == track.id }
        if (from >= 0 && to >= 0) tracks.add(shown.subList(minOf(from, to), maxOf(from, to) + 1))
        endRange()
    }

    fun add(shown: List<Track>) { endRange(); tracks.add(shown) }

    fun clear() { endRange(); tracks.clearChecks() }

    fun exit() { loadingAll = false; endRange(); tracks.clear() }

    fun back() { if (range) endRange() else exit() }
}

@Composable internal fun OnlineLibrarySelectionBar(selection: OnlineLibrarySelection, shown: List<Track>,
    state: OnlineMusicState, music: OnlineMusic, player: PlaybackController, filtersReady: Boolean,
    allows: (Track) -> Boolean, play: (List<String>) -> Unit) {
    val focus = LocalFocusManager.current
    val include by rememberUpdatedState(allows)
    var menuOpen by remember { mutableStateOf(false) }
    val canPlay = filtersReady && selection.tracks.tracks.any(Track::available)
    val loading = selection.loadingAll
    fun exit() {
        focus.clearFocus(force = true)
        selection.exit()
    }
    BackHandler { if (selection.range) selection.back() else exit() }
    LaunchedEffect(selection, loading) {
        if (!loading) return@LaunchedEffect
        val complete = music.readRemainingTracks()
        if (complete != null) selection.add(complete.filter(include))
        selection.loadingAll = false
    }
    Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.background) {
        Column {
            HorizontalDivider()
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("online_selection_loading"))
            Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(tr(Msg.bulk_count, selection.tracks.tracks.size), Modifier.testTag("bulk_count"),
                    style = MaterialTheme.typography.bodySmall)
                if (selection.range) Text(tr(if (selection.rangeStart == null) Msg.playlist_range_start else Msg.playlist_range_end),
                    Modifier.padding(start = 8.dp).testTag("online_range_hint"), style = MaterialTheme.typography.bodySmall)
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Button({
                    play(selection.tracks.tracks.filter(Track::available).map(Track::id))
                    exit()
                }, Modifier.weight(1f).prismFocus().testTag("online_play_selected"), enabled = canPlay && !loading,
                    contentPadding = PaddingValues(4.dp)) {
                    Text(tr(Msg.files_play_selected, selection.tracks.tracks.size), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                ActionIcon(UiIcon.ADD_QUEUE, tr(Msg.bulk_queue), {
                    player.enqueueMany(selection.tracks.tracks.filter(Track::available).map(Track::id))
                    exit()
                }, Modifier.testTag("bulk_enqueue"), enabled = canPlay && !loading)
                Box {
                    ActionIcon(UiIcon.MORE, tr(Msg.files_actions), { menuOpen = true },
                        Modifier.testTag("online_selection_menu"), enabled = !loading)
                    DropdownMenu(menuOpen, { menuOpen = false }) {
                        fun act(block: () -> Unit) { menuOpen = false; block() }
                        if (state.nextPage != null || state.failedPage != null) {
                            DropdownMenuItem({ Text(tr(Msg.online_select_all_tracks)) }, { act { selection.loadingAll = true } },
                                Modifier.prismFocus().testTag("online_select_all"), enabled = !state.loading)
                        }
                        DropdownMenuItem({ Text(tr(if (state.nextPage != null || state.failedPage != null) Msg.playlist_selected_all else Msg.files_select_all, shown.size)) },
                            { act { selection.add(shown) } }, Modifier.prismFocus().testTag("online_select_shown"), enabled = shown.isNotEmpty())
                        DropdownMenuItem({ Text(tr(Msg.files_range)) }, { act(selection::beginRange) },
                            Modifier.prismFocus().testTag("online_select_range"), enabled = shown.isNotEmpty() && (shown.size > 1 || state.nextPage != null))
                        DropdownMenuItem({ Text(tr(Msg.msg_8965271d3c97)) }, { act(selection::clear) },
                            Modifier.prismFocus().testTag("online_clear_selection"), enabled = selection.tracks.tracks.isNotEmpty() || selection.range)
                    }
                }
                ActionIcon(UiIcon.CLOSE, tr(Msg.msg_8fbe9b75cbdf), ::exit, Modifier.testTag("bulk_cancel"))
            }
        }
    }
}

/** Follow the same paging path as “Load more” and apply the selection only after the last page. */
private suspend fun OnlineMusic.readRemainingTracks(): List<Track>? {
    val initial = state.value
    fun sameList(current: OnlineMusicState) = current.signedIn &&
        current.profileId == initial.profileId && current.request == initial.request
    var mayRetry = true
    while (true) {
        val current = state.first { !it.loading || !sameList(it) }
        if (!sameList(current)) return null
        if (current.issue != null) {
            if (!mayRetry || current.failedPage == null) return null
            retry()
        } else {
            if (current.nextPage == null) return current.entries.mapNotNull { it.track }
            more()
        }
        mayRetry = false
    }
}
