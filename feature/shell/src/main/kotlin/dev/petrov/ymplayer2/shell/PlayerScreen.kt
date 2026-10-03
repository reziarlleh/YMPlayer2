package dev.petrov.ymplayer2.shell

import dev.petrov.ymplayer2.localization.*

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.petrov.ymplayer2.core.*
import dev.petrov.ymplayer2.designsystem.*
import dev.petrov.ymplayer2.designsystem.skin.*
import kotlinx.coroutines.launch

/** Only artwork is flexible. Transport and actions never live in a scroll container. */
@Composable internal fun PlayerScreen(state: PlaybackState, player: PlaybackController, wide: Boolean, short: Boolean, queue: () -> Unit, demo: Boolean = true, folders: () -> Unit = {},
    taste: MusicTaste? = null, signIn: () -> Unit = {}, artist: (ArtistRef) -> Unit = {}, equalizer: (Boolean) -> Unit = {}, playlists: CloudPlaylists? = null) {
    var details by remember(state.current?.id, state.profileId) { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var issue by remember { mutableStateOf(false) }
    if (details && taste != null) state.current?.let { TrackTasteDialog(it, taste, artist = artist, extra = {
        playlists?.let { lists -> AddToCloudPlaylist(state.current!!, lists) { details = false } }
        HorizontalDivider()
        TextButton({ details = false; menu = true }, Modifier.prismFocus().testTag("player_sources")) { Text(tr(Msg.msg_d8987908d58b)) }
        TextButton({ details = false; equalizer(true) }, Modifier.prismFocus()) { Text(tr(Msg.msg_6c2b14d954bb)) }
    }) { details = false } }
    val message = (state.waveIssue ?: state.error)?.let(::trIssue)
    if (issue) AlertDialog(onDismissRequest = { issue = false }, title = { Text(tr(Msg.msg_5c7cfc9e8bc2)) },
        text = { Text(message ?: if (state.waveLoading) tr(Msg.msg_c989e98d6470) else tr(Msg.msg_2f4f45e427e6)) },
        confirmButton = { TextButton({ issue = false }) { Text(tr(Msg.msg_a7a4033657e8)) } })
    val account = taste?.state?.collectAsStateWithLifecycle()?.value
    val toolbar: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (taste != null) Button({ if (account?.signedIn == true) player.playMyWave() else signIn() },
                Modifier.weight(1f).height(48.dp).prismFocus().testTag("my_wave"), enabled = !state.waveLoading,
                contentPadding = PaddingValues(horizontal = 10.dp)) {
                SkinIcon(UiIcon.WAVE, null); Spacer(Modifier.width(6.dp))
                Text(tr(Msg.msg_de1ea8c09caa), maxLines = 1, overflow = TextOverflow.Ellipsis)
            } else Text(tr(Msg.msg_a94943f157d5), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 1)
            ActionIcon(UiIcon.EQUALIZER, tr(Msg.msg_fdc54db3a2b0), { equalizer(false) }, Modifier.testTag("player_equalizer"))
            ActionIcon(UiIcon.QUEUE, tr(Msg.msg_cb297d129add), queue, Modifier.testTag("player_queue"))
        }
    }
    val actions: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            if (taste != null && state.current?.source == Source.YANDEX) TasteIcons(taste, state.current!!.tasteTarget(), "player")
            val shelf = account?.shelf(TasteKind.TRACK)
            if (shelf?.issue != null && state.current?.source == Source.YANDEX) ActionIcon(UiIcon.REFRESH, tr(Msg.msg_fd21b337444d), { taste?.refresh(TasteKind.TRACK) },
                Modifier.testTag("player_taste_TRACK_${state.current!!.tasteTarget().key}_retry"), enabled = !shelf.busy)
            else if (message != null || state.waveLoading || state.buffering) ActionIcon(if (message != null) UiIcon.REFRESH else UiIcon.UNKNOWN,
                message ?: tr(Msg.msg_61b578bca0ca), { if (state.waveIssue != null) player.retryWave() else issue = true },
                Modifier.testTag(if (state.waveIssue != null) "wave_retry" else if (state.waveLoading) "wave_loading" else "player_issue"))
            Box {
                ActionIcon(UiIcon.MORE, tr(Msg.msg_7075085d9334), { if (taste != null && state.current?.source == Source.YANDEX) details = true else menu = true }, Modifier.testTag("player_more"))
                DropdownMenu(menu, { menu = false }) {
                    if (taste != null && state.current?.source == Source.YANDEX) DropdownMenuItem({ Text(tr(Msg.msg_0a17ec91a15b)) }, { menu = false; details = true }, Modifier.testTag("player_artist_actions"))
                    DropdownMenuItem({ Text(tr(Msg.msg_c8dbba7583c2)) }, {}, enabled = false)
                    (listOf<Source?>(null) + Source.entries.filter { state.profileId != "guest" && demo || it != Source.YANDEX }).forEach { source ->
                        DropdownMenuItem({ Text(source?.label?.let(::trMessage) ?: tr(Msg.msg_db66cedc69fc)) }, { menu = false; player.chooseSource(source) }, Modifier.testTag("playback_source_${source?.name ?: "ALL"}"))
                    }
                    if (!demo) DropdownMenuItem({ Text(tr(Msg.msg_8f458964546a)) }, { menu = false; folders() }, Modifier.testTag("manage_folders"))
                    DropdownMenuItem({ Text(tr(Msg.msg_6c2b14d954bb)) }, { menu = false; equalizer(true) })
                }
            }
            Spacer(Modifier.weight(1f))
            if (state.wave) SkinIcon(UiIcon.WAVE, tr(Msg.msg_de1ea8c09caa), Modifier.size(28.dp).testTag("wave_mode"))
            else if (state.supportsQueueOrdering) PlaybackModes(state, player)
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize().testTag("player_viewport").padding(horizontal = 16.dp, vertical = 4.dp)) {
        val landscape = maxWidth >= 520.dp && maxWidth > maxHeight * 1.25f
        val compact = maxHeight < 440.dp || LocalDensity.current.fontScale > 1.3f
        val veryShort = maxHeight < 300.dp
        val heading: @Composable () -> Unit = { TrackHeading(state.current, artist = artist, compact = compact,
            status = message ?: if (state.waveLoading) tr(Msg.msg_c989e98d6470) else if (state.buffering) tr(Msg.msg_2f4f45e427e6) else null,
            error = message != null, showStatus = { issue = true }) }
        if (landscape) Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Column(Modifier.weight(.7f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    TrackArtwork(state.current, Modifier.size(minOf(maxWidth, maxHeight).coerceAtLeast(0.dp)))
                }
                heading()
            }
            Column(Modifier.weight(1.3f).fillMaxHeight(), verticalArrangement = Arrangement.SpaceEvenly) {
                toolbar(); actions(); PlayerProgress(state, player, inlineTime = veryShort); TransportControls(state, player, compact = veryShort)
            }
        } else Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            toolbar()
            if (!compact) BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                TrackArtwork(state.current, Modifier.size(minOf(maxWidth, maxHeight).coerceAtLeast(0.dp)))
            } else Spacer(Modifier.weight(1f))
            if (compact) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TrackArtwork(state.current, Modifier.size(56.dp))
                Box(Modifier.weight(1f)) { heading() }
            } else heading()
            actions()
            PlayerProgress(state, player)
            TransportControls(state, player, compact = compact)
        }
    }
}

@Composable private fun PlayerProgress(state: PlaybackState, player: PlaybackController, inlineTime: Boolean = false) {
    val duration = (state.current?.durationSeconds ?: 1).coerceAtLeast(1)
    val navigation = Modifier.prismFocus().horizontalSliderNavigation { direction ->
        // Slider's fractional 1% step would otherwise round to zero on short tracks.
        player.seek((state.positionSeconds + direction * (duration / 100).coerceAtLeast(1)).coerceIn(0, duration))
    }
    if (inlineTime) Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(secondsLabel(state.positionSeconds), style = MaterialTheme.typography.labelMedium, modifier = Modifier.testTag("position"))
        Slider(state.positionSeconds.toFloat(), { player.seek(it.toInt()) }, Modifier.weight(1f).height(40.dp).testTag("progress").then(navigation), enabled = state.current != null,
            valueRange = 0f..(state.current?.durationSeconds ?: 1).coerceAtLeast(1).toFloat())
        Text(secondsLabel(state.current?.durationSeconds ?: 0), style = MaterialTheme.typography.labelMedium)
    } else
    Column(Modifier.fillMaxWidth()) {
        Slider(state.positionSeconds.toFloat(), { player.seek(it.toInt()) }, Modifier.fillMaxWidth().height(40.dp).testTag("progress").then(navigation), enabled = state.current != null,
            valueRange = 0f..(state.current?.durationSeconds ?: 1).coerceAtLeast(1).toFloat())
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(secondsLabel(state.positionSeconds), style = MaterialTheme.typography.labelMedium, modifier = Modifier.testTag("position"))
            Text(secondsLabel(state.current?.durationSeconds ?: 0), style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable private fun TransportControls(state: PlaybackState, player: PlaybackController, compact: Boolean) {
    val focus = remember { FocusRequester() }
    val isTv = LocalConfiguration.current.uiMode and android.content.res.Configuration.UI_MODE_TYPE_MASK == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
    LaunchedEffect(state.current?.available) { if (isTv && state.current?.available == true) focus.requestFocus() }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        @Composable fun button(icon: UiIcon, label: String, click: () -> Unit, tag: String, primary: Boolean = false) {
            val size = if (primary) if (compact) 80.dp else 96.dp else 64.dp
            FilledIconButton(click, Modifier.size(size).prismFocus().testTag(tag).then(if (primary) Modifier.focusRequester(focus) else Modifier),
                enabled = !primary || state.current?.available == true || state.wave,
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = if (primary) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = if (primary) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)) {
                SkinIcon(icon, label, Modifier.size(if (primary) 48.dp else 36.dp))
            }
        }
        button(UiIcon.PREVIOUS, tr(Msg.msg_3fbf9e4ea1c1), { player.skip(-1) }, "player_previous")
        button(if (state.playing) UiIcon.PAUSE else UiIcon.PLAY, if (state.playing) tr(Msg.msg_65530fd463ea) else tr(Msg.msg_bdd37eb21746), player::toggle, "player_play", true)
        button(UiIcon.NEXT, tr(Msg.msg_46da7285528f), { player.skip(1) }, "player_next")
        button(UiIcon.STOP, tr(Msg.msg_3396400da0c4), player::stop, "player_stop")
    }
}

@Composable private fun TrackHeading(track: Track?, artist: (ArtistRef) -> Unit, compact: Boolean, status: String?, error: Boolean, showStatus: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text(track?.title ?: tr(Msg.msg_39c3c9622cdc), style = if (compact) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold, maxLines = if (compact) 1 else 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("current_title"))
        ArtistNames(track, artist, "player")
        Text(status ?: track?.let { if (it.source == Source.YANDEX) tr(Msg.msg_c39959813ccd) else "${trMessage(it.source.label)} · ${if (it.available) tr(Msg.msg_e92912b778ee) else tr(Msg.msg_53ba89acf405)}" }
            ?: tr(Msg.msg_eabc559b5eaa), Modifier.testTag("player_status").then(if (status != null) Modifier.clickable(onClick = showStatus) else Modifier),
            maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall,
            color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
    }
}

@Composable internal fun ArtistNames(track: Track?, open: (ArtistRef) -> Unit, location: String, compact: Boolean = false) {
    val artists = track?.artists.orEmpty().distinctBy(ArtistRef::id)
    if (track?.source != Source.YANDEX || artists.isEmpty()) Text(track?.artist ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis,
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        artists.forEachIndexed { index, item ->
            if (index > 0) Text(", ", color = MaterialTheme.colorScheme.onSurfaceVariant)
            val modifier = Modifier.weight(item.name.length.toFloat().coerceAtLeast(1f), fill = false)
                .prismFocus().testTag("${location}_artist_${item.id}")
            if (compact) Box(modifier.heightIn(min = 32.dp).clickable { open(item) }, contentAlignment = Alignment.CenterStart) {
                Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            } else TextButton({ open(item) }, modifier.heightIn(min = 40.dp), contentPadding = PaddingValues(0.dp)) {
                Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable private fun PlaybackModes(state: PlaybackState, player: PlaybackController) {
    val repeat = when (state.repeatMode) { RepeatMode.OFF -> tr(Msg.msg_ce1e647fd722); RepeatMode.ALL -> tr(Msg.msg_65e00ce0d8d2); RepeatMode.ONE -> tr(Msg.msg_f0a7fd0c1884) }
    IconToggleButton(state.repeatMode != RepeatMode.OFF, { player.setRepeatMode(state.repeatMode.next()) }, Modifier.size(48.dp).prismFocus().testTag("repeat_mode").semantics { stateDescription = repeat }) {
        SkinIcon(if (state.repeatMode == RepeatMode.ONE) UiIcon.REPEAT_ONE else UiIcon.REPEAT, repeat, Modifier.size(28.dp))
    }
    IconToggleButton(state.shuffle, { player.setShuffle(!state.shuffle) }, Modifier.size(48.dp).prismFocus().testTag("shuffle_mode").semantics { stateDescription = if (state.shuffle) tr(Msg.msg_58cb4ee024d5) else tr(Msg.msg_ffdf574befa9) }) {
        SkinIcon(UiIcon.SHUFFLE, tr(Msg.msg_8f88b95463e2), Modifier.size(28.dp))
    }
}

@Composable internal fun QueueScreen(state: PlaybackState, player: PlaybackController, taste: MusicTaste? = null, artist: (ArtistRef) -> Unit = {}) {
    var editing by rememberSaveable { mutableStateOf(false) }
    val pageKey = if (state.automaticLocal || state.referenceQueue) listOf(state.profileId, state.automaticSource, state.queueRevision, state.queueCount)
        else state.queue
    val pages = remember(pageKey) { mutableStateMapOf<Int, Result<CatalogPage<Track>>>() }
    val loading = remember(pageKey) { mutableSetOf<Int>() }
    val scope = rememberCoroutineScope()
    fun requestPage(number: Int) {
        if (number in pages || !loading.add(number)) return
        scope.launch {
            pages[number] = runCatching { player.queuePage(number * 80, 80) }
            loading.remove(number)
            // Scrolling through a long queue must not re-create a full metadata snapshot.
            pages.keys.sortedBy { kotlin.math.abs(it - number) }.drop(5).forEach(pages::remove)
        }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
        Text(tr(Msg.msg_33073b976515, state.queueCount), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (state.shuffle) tr(Msg.msg_dbfc1e54cdd9) else tr(Msg.msg_0c581306a3c6), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ActionIcon(if (editing) UiIcon.CHECK else UiIcon.EDIT, if (editing) tr(Msg.msg_fcd7691c84b2) else tr(Msg.msg_a2c053c748d0), { editing = !editing }, Modifier.testTag("queue_edit"), enabled = state.queueCount > 0)
        }
        if (editing && state.queueCount > 0) {
            Text(tr(Msg.msg_53ee3b3a9092), style = MaterialTheme.typography.bodySmall)
            TextButton({ player.clearQueue(); editing = false }, Modifier.heightIn(min = 48.dp).prismFocus().testTag("queue_clear")) {
                SkinIcon(UiIcon.CLEAR_QUEUE, null); Spacer(Modifier.width(8.dp)); Text(tr(Msg.msg_a5f630df4494))
            }
        }
        if (state.queueCount == 0) Text(tr(Msg.msg_857db5eccf13), Modifier.padding(vertical = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyColumn(Modifier.fillMaxWidth().weight(1f).testTag("queue_list"), contentPadding = PaddingValues(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(state.queueCount, key = { it }) { index ->
                val number = index / 80
                LaunchedEffect(number, pages) { requestPage(number) }
                val result = pages[number]
                val track = result?.getOrNull()?.items?.getOrNull(index % 80)
                if (track != null) Column {
                    TrackRow(track, state.current?.id == track.id, { player.select(track.id) }, taste = taste, location = "queue", artist = artist)
                    if (editing) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        ActionIcon(UiIcon.UP, tr(Msg.msg_9fcd67f23799, track.title), { player.moveInQueue(track.id, index - 1) }, Modifier.testTag("queue_up_${track.id}"), enabled = index > 0)
                        ActionIcon(UiIcon.DOWN, tr(Msg.msg_48f1c04f18bc, track.title), { player.moveInQueue(track.id, index + 1) }, Modifier.testTag("queue_down_${track.id}"), enabled = index < state.queueCount - 1)
                        ActionIcon(UiIcon.REMOVE, tr(Msg.msg_6edaf3285434, track.title), { player.removeFromQueue(track.id) }, Modifier.testTag("queue_remove_${track.id}"))
                    }
                } else if (result?.isFailure == true) TextButton({ pages.remove(number); requestPage(number) }) { Text(tr(Msg.msg_7ad2cca22536)) }
                else Text(tr(Msg.msg_22a5d9112486), Modifier.padding(12.dp))
            }
        }
    }
}

@Composable internal fun TrackRow(track: Track, selected: Boolean = false, play: () -> Unit, enqueue: (() -> Unit)? = null, queued: Boolean = false, more: (() -> Unit)? = null,
    taste: MusicTaste? = null, location: String = "catalog", artist: (ArtistRef) -> Unit = {}, note: String? = null,
    checked: Boolean? = null, toggleSelection: (() -> Unit)? = null) {
    val onlineTaste = taste?.takeIf { track.source == Source.YANDEX }
    val hasActions = checked == null && (onlineTaste != null || enqueue != null || more != null)
    val metadata = if (track.available) "${trMessage(track.source.label)} · ${secondsLabel(track.durationSeconds)}" else tr(Msg.msg_91cf891549ab)
    var details by remember(track.id) { mutableStateOf(false) }
    if (details && onlineTaste != null) TrackTasteDialog(track, onlineTaste, artist = artist) { details = false }
    Surface(Modifier.fillMaxWidth().testTag("track_card_${track.id}"), shape = MaterialTheme.shapes.medium,
        color = if (checked == true || selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().clickable(enabled = checked != null || track.available, onClick = toggleSelection ?: play).prismFocus().testTag("track_${track.id}")
                .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (checked != null) Checkbox(checked, { toggleSelection?.invoke() }, Modifier.testTag("select_${track.id}").prismFocus())
                TrackArtwork(track, Modifier.size(44.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                    Text(track.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                    if (checked == null) ArtistNames(track, artist, "${location}_${track.id}", compact = true)
                    else Text(track.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                    note?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    if (!hasActions) Text(metadata, style = MaterialTheme.typography.labelSmall)
                }
                if (checked == null) SkinIcon(if (selected) UiIcon.NOW_PLAYING else UiIcon.PLAY,
                    if (selected) tr(Msg.msg_653f03c94726) else tr(Msg.msg_2922b43474e0), Modifier.size(24.dp))
            }
            if (hasActions) {
                Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (onlineTaste != null) TasteIcons(onlineTaste, track.tasteTarget(), location)
                    Text(metadata, Modifier.weight(1f).testTag("track_meta_${track.id}"), maxLines = 1,
                        overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (enqueue != null) ActionIcon(if (queued) UiIcon.CHECK else UiIcon.ADD_QUEUE,
                        if (queued) tr(Msg.msg_ab78a1119f41, track.title) else tr(Msg.msg_0f0e734fb752, track.title), enqueue,
                        Modifier.testTag("enqueue_${track.id}"), enabled = track.available && !queued)
                    if (onlineTaste != null || more != null) ActionIcon(UiIcon.MORE,
                        tr(Msg.msg_7e6258d504ba, track.title), { if (more != null) more() else details = true },
                        Modifier.testTag("track_more_${track.id}"))
                }
            }
            if (onlineTaste != null && checked == null) {
                val shelf = onlineTaste.state.collectAsStateWithLifecycle().value.shelf(TasteKind.TRACK)
                if (shelf.issue != null) TextButton({ onlineTaste.refresh(TasteKind.TRACK) }, Modifier.prismFocus(), enabled = !shelf.busy) {
                    Text(tr(Msg.msg_fd21b337444d), color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}
