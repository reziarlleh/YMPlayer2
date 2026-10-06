package dev.petrov.ymplayer2.shell

import dev.petrov.ymplayer2.localization.*

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.petrov.ymplayer2.core.*
import dev.petrov.ymplayer2.designsystem.prismFocus

@Composable internal fun OfflineScreen(offline: OfflineMusic, player: PlaybackController) {
    val state by offline.state.collectAsStateWithLifecycle()
    LazyColumn(Modifier.fillMaxSize().testTag("offline_list"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text(tr(Msg.msg_35864bdf26cb), style = MaterialTheme.typography.headlineSmall) }
        if (state.tracks.isNotEmpty()) {
            item { Text(tr(Msg.msg_5e4ea237ce6b, state.tracks.size, state.bytes / (1024 * 1024)), Modifier.testTag("offline_summary")) }
            item { OutlinedButton({ player.playOffline() }, Modifier.fillMaxWidth().prismFocus().testTag("offline_play_all")) { Text(tr(Msg.msg_dc9712a122a9)) } }
            items(state.tracks, key = Track::id) { track ->
                Surface(onClick = { player.playList(state.tracks.map(Track::id), track.id, PlaybackOrigin(PlaybackSource.OFFLINE)) }, modifier = Modifier.fillMaxWidth().prismFocus().testTag("offline_track_${track.id}"), shape = MaterialTheme.shapes.medium) {
                    Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        TrackArtwork(track, Modifier.size(56.dp))
                        Column(Modifier.weight(1f)) {
                            Text(track.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(track.artist, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        Text(secondsLabel(track.durationSeconds), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        } else item {
            Text(when {
                !state.enabled -> tr(Msg.msg_0b9e2aeefccd)
                state.owner == null -> tr(Msg.msg_430c6faef007)
                !state.ready || state.running -> tr(Msg.msg_bb35de568dc0)
                else -> tr(Msg.msg_e2e513b12b0d)
            }, Modifier.testTag("offline_empty"))
        }
    }
}
