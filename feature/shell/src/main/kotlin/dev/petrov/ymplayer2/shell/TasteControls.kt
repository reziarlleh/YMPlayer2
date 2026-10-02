package dev.petrov.ymplayer2.shell

import dev.petrov.ymplayer2.localization.*

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.petrov.ymplayer2.core.*
import dev.petrov.ymplayer2.designsystem.*
import dev.petrov.ymplayer2.designsystem.skin.*

/** Unknown is distinct from unchecked; only a confirmed server read supplies the checked state. */
@Composable internal fun TasteIcons(taste: MusicTaste, target: TasteTarget, location: String) {
    val state by taste.state.collectAsStateWithLifecycle()
    val shelf = state.shelf(target.kind)
    val known = state.signedIn && shelf.ready
    val enabled = known && !shelf.busy
    val tag = "${location}_taste_${target.kind.name}_${target.key}"
    val colors = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        listOf(false, true).filter { !it || target.kind != TasteKind.ALBUM }.forEach { block ->
            val marked = known && target.key in if (block) shelf.list.blocked else shelf.list.liked
            val action = if (block) {
                if (marked) TasteAction.UNBLOCK else TasteAction.BLOCK
            } else if (marked) TasteAction.UNLIKE else TasteAction.LIKE
            val description = when {
                !known -> if (state.signedIn) tr(Msg.msg_922c5fae2ec8) else tr(Msg.msg_be8789acfb99)
                block -> if (marked) tr(Msg.msg_3b840443abf7) else tr(Msg.msg_cdb3ca001d96)
                else -> if (marked) tr(Msg.msg_0fd3fe573609) else tr(Msg.msg_09008d3dafef)
            } + if (shelf.busy) tr(Msg.msg_372ccd2e8254) else ""
            val label = when (action) {
                TasteAction.LIKE -> tr(Msg.msg_2a6b5f7278c0)
                TasteAction.UNLIKE -> tr(Msg.msg_334c3299a899)
                TasteAction.BLOCK -> tr(Msg.msg_3b840443abf7)
                TasteAction.UNBLOCK -> tr(Msg.msg_b0acd0c9bfb1)
            } + ": ${trMessage(target.kind.label).lowercase()} «${target.title}»"
            FilledIconToggleButton(marked, { taste.react(target, action) },
                Modifier.size(48.dp).prismFocus().testTag("${tag}_${if (block) "block" else "like"}").semantics {
                    contentDescription = label
                    stateDescription = description
                    // A failed/unfinished read must not masquerade as an unmarked item to TalkBack.
                    this.toggleableState = if (!known) ToggleableState.Indeterminate else if (marked) ToggleableState.On else ToggleableState.Off
                }, enabled = enabled,
                colors = IconButtonDefaults.filledIconToggleButtonColors(
                    containerColor = colors.surfaceContainerHigh, contentColor = colors.onSurfaceVariant,
                    checkedContainerColor = if (block) colors.errorContainer else colors.primaryContainer,
                    checkedContentColor = if (block) colors.onErrorContainer else colors.onPrimaryContainer,
                    disabledContainerColor = colors.surfaceContainerHigh,
                    disabledContentColor = colors.onSurfaceVariant.copy(alpha = .6f))) {
                if (shelf.busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else SkinIcon(when {
                    !known -> UiIcon.UNKNOWN
                    block -> if (marked) UiIcon.DISLIKE else UiIcon.DISLIKE_OFF
                    else -> if (marked) UiIcon.FAVORITE else UiIcon.FAVORITE_OFF
                }, null)
            }
        }
    }
}

/** Keeps each pair attached to its own name, including collaborative artists and large fonts. */
@Composable internal fun TasteLabel(taste: MusicTaste, target: TasteTarget, location: String, label: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth < 280.dp || LocalDensity.current.fontScale > 1.3f) Column {
            label()
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) { TasteIcons(taste, target, location) }
        } else Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { label() }
            TasteIcons(taste, target, location)
        }
    }
    val shelf = taste.state.collectAsStateWithLifecycle().value.shelf(target.kind)
    if (shelf.issue != null) TextButton({ taste.refresh(target.kind) }, Modifier.prismFocus().testTag("${location}_taste_${target.kind.name}_${target.key}_retry"), enabled = !shelf.busy) {
        SkinIcon(UiIcon.REFRESH, null); Spacer(Modifier.width(8.dp))
        Text(tr(Msg.msg_62243b1fd0d8), color = MaterialTheme.colorScheme.error)
    }
}

@Composable internal fun TasteControls(taste: MusicTaste, target: TasteTarget) {
    val state by taste.state.collectAsStateWithLifecycle()
    val shelf = state.shelf(target.kind)
    val liked = shelf.ready && target.key in shelf.list.liked
    val blocked = shelf.ready && target.key in shelf.list.blocked
    val enabled = state.signedIn && shelf.ready && !shelf.busy
    val tag = "taste_${target.kind.name}_${target.key}"
    Column(Modifier.fillMaxWidth().testTag(tag), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("${trMessage(target.kind.label)} · ${target.title}", fontWeight = FontWeight.Medium)
        if (!shelf.ready && shelf.issue == null) Text(tr(Msg.msg_a9bb9fcb7d94), style = MaterialTheme.typography.bodySmall)
        OutlinedButton({ taste.react(target, if (liked) TasteAction.UNLIKE else TasteAction.LIKE) },
            Modifier.prismFocus().testTag("${tag}_like"), enabled = enabled) {
            SkinIcon(if (liked) UiIcon.FAVORITE else UiIcon.FAVORITE_OFF, null)
            Spacer(Modifier.width(8.dp))
            Text(when (target.kind) {
                TasteKind.TRACK -> if (liked) tr(Msg.msg_e4e4b9b0d2df) else tr(Msg.msg_3e1506ad1141)
                TasteKind.ARTIST -> if (liked) tr(Msg.msg_57d8de34aad1) else tr(Msg.msg_3c959ac71187)
                TasteKind.ALBUM -> if (liked) tr(Msg.msg_06a1b25395b9) else tr(Msg.msg_b011fc656a0f)
            })
        }
        if (target.kind != TasteKind.ALBUM) OutlinedButton({ taste.react(target, if (blocked) TasteAction.UNBLOCK else TasteAction.BLOCK) },
            Modifier.prismFocus().testTag("${tag}_block"), enabled = enabled) {
            SkinIcon(if (blocked) UiIcon.DISLIKE else UiIcon.DISLIKE_OFF, null); Spacer(Modifier.width(8.dp))
            Text(if (blocked) tr(Msg.msg_9fe9ae8fbc17, if (target.kind == TasteKind.TRACK) tr(Msg.msg_cd70228cba41) else tr(Msg.msg_f413b50444c7))
                else tr(Msg.msg_346750dab787, if (target.kind == TasteKind.TRACK) tr(Msg.msg_cd70228cba41) else tr(Msg.msg_f413b50444c7)))
        }
        if (shelf.busy) Text(tr(Msg.msg_ef5631741cc5), style = MaterialTheme.typography.bodySmall)
        shelf.issue?.let {
            Text(trMessage(it), color = MaterialTheme.colorScheme.error)
            TextButton({ taste.refresh(target.kind) }, Modifier.prismFocus(), enabled = !shelf.busy) { Text(tr(Msg.msg_79e35d4d8b5b)) }
        }
    }
}

@Composable internal fun TrackTasteDialog(track: Track, taste: MusicTaste, artist: (ArtistRef) -> Unit = {}, extra: @Composable () -> Unit = {}, dismiss: () -> Unit) {
    val content: @Composable () -> Unit = {
            track.artists.distinctBy(ArtistRef::id).forEach {
                TextButton({ dismiss(); artist(it) }, Modifier.prismFocus().testTag("actions_artist_${it.id}")) { Text(tr(Msg.msg_0df38a907fd2, it.name)) }
                TasteControls(taste, TasteTarget(TasteKind.ARTIST, it.id, it.name))
            }
            track.albumId?.let { TasteControls(taste, TasteTarget(TasteKind.ALBUM, it, track.album)) }
            if (track.artists.isEmpty()) Text(tr(Msg.msg_aa59e4f43e97))
            extra()
    }
    if (LocalConfiguration.current.screenHeightDp < 480 || LocalDensity.current.fontScale > 1.3f) {
        Dialog(dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.Center) {
                Surface(Modifier.widthIn(max = 880.dp).fillMaxSize(), shape = MaterialTheme.shapes.large) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(tr(Msg.msg_03222b95f9b4), style = MaterialTheme.typography.titleMedium)
                        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) { content() }
                        TextButton(dismiss, Modifier.align(Alignment.End).prismFocus()) { Text(tr(Msg.msg_ef05d57959cf)) }
                    }
                }
            }
        }
    } else AlertDialog(onDismissRequest = dismiss, title = { Text(tr(Msg.msg_d585770173b3)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) { content() } },
        confirmButton = { TextButton(dismiss, Modifier.prismFocus()) { Text(tr(Msg.msg_ef05d57959cf)) } })
}

@Composable internal fun WaveButton(player: PlaybackController, signedIn: Boolean, signIn: () -> Unit, started: () -> Unit = {}, modifier: Modifier = Modifier) {
    val state by player.state.collectAsStateWithLifecycle()
    Button({ if (signedIn) { player.playMyWave(); started() } else signIn() }, modifier.prismFocus().testTag("my_wave"), enabled = !state.waveLoading) {
        SkinIcon(UiIcon.WAVE, null); Spacer(Modifier.width(8.dp))
        Text(if (state.waveLoading) tr(Msg.msg_414a326c7fbe) else tr(Msg.msg_de1ea8c09caa))
    }
}
