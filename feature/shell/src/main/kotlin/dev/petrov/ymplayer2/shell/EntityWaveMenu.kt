package dev.petrov.ymplayer2.shell

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.petrov.ymplayer2.core.*
import dev.petrov.ymplayer2.designsystem.*
import dev.petrov.ymplayer2.designsystem.skin.*
import dev.petrov.ymplayer2.localization.*

@Composable internal fun WaveAction(request: WaveRequest, label: Msg, player: PlaybackController, started: () -> Unit) {
    TextButton({ player.playWave(request); started() }, Modifier.prismFocus().testTag("wave_by_${request.station}")) {
        SkinIcon(UiIcon.WAVE, null); Spacer(Modifier.width(8.dp)); Text(tr(label))
    }
}

@Composable internal fun EntityWaveMenu(entity: MusicEntity, player: PlaybackController, started: () -> Unit, location: String) {
    val request = entity.waveRequest() ?: return
    var open by remember(entity) { mutableStateOf(false) }
    val label = when (entity.kind) {
        MusicKind.TRACKS -> Msg.wave_by_track
        MusicKind.ARTISTS -> Msg.wave_by_artist
        MusicKind.ALBUMS -> Msg.wave_by_album
        MusicKind.PLAYLISTS -> Msg.wave_by_playlist
    }
    Box {
        ActionIcon(UiIcon.MORE, tr(Msg.msg_7075085d9334), { open = true }, Modifier.testTag("${location}_wave_menu_${entity.kind}_${entity.id}"))
        DropdownMenu(open, { open = false }) {
            DropdownMenuItem({ Text(tr(label)) }, { open = false; player.playWave(request); started() },
                Modifier.testTag("wave_by_${request.station}"))
        }
    }
}
