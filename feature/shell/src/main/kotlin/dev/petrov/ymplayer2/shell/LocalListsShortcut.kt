package dev.petrov.ymplayer2.shell

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.petrov.ymplayer2.core.*
import dev.petrov.ymplayer2.designsystem.prismFocus
import dev.petrov.ymplayer2.designsystem.SkinIcon
import dev.petrov.ymplayer2.designsystem.skin.*
import dev.petrov.ymplayer2.localization.*

@Composable internal fun localListLabel(list: LocalListTarget): String = when {
    list == LocalListTarget.favorites -> tr(Msg.player_source_favorites)
    list.selection -> tr(Msg.local_selection_name, list.title)
    else -> list.title
}

@Composable internal fun LocalListsShortcut(state: PlaybackState, saved: CollectionsState?,
    resume: (LocalListTarget, List<SavedTrack>?) -> Unit, pin: (LocalListTarget, Boolean) -> Unit) {
    var menu by remember(state.profileId) { mutableStateOf(false) }
    val profile = saved?.takeIf { it.ready }?.profile(state.profileId)
    fun named(target: LocalListTarget): LocalListTarget {
        val id = target.playlistId ?: return target
        return profile?.playlists?.find { it.id == id }?.let { target.copy(title = it.name) } ?: target
    }
    val fallback = LocalListTarget.favorites.takeUnless { profile?.favorites.isNullOrEmpty() }
    val choices = remember(state.localLists, profile) {
        state.localLists.menu().map(::named).ifEmpty { listOfNotNull(fallback) }
    }
    fun choose(target: LocalListTarget) {
        menu = false
        val members = if (target == LocalListTarget.favorites) profile?.favorites
            else target.playlistId?.let { id -> profile?.playlists?.find { it.id == id }?.tracks }
        resume(target, members)
    }
    val first = state.localLists.recent.firstOrNull()?.let(::named) ?: fallback
    val holdMillis = LocalViewConfiguration.current.longPressTimeoutMillis
    var remoteDown by remember(state.profileId) { mutableStateOf<Long?>(null) }
    var remoteLongPress by remember { mutableStateOf(false) }
    val active = first != null && state.pendingSource == null && state.origin.localList?.key == first.key
    val colors = MaterialTheme.colorScheme
    val tint = when {
        first == null -> colors.onSurface.copy(alpha = 0.38f)
        active -> colors.onPrimary
        else -> colors.onSurfaceVariant
    }
    val background = when {
        first == null -> colors.onSurface.copy(alpha = 0.12f)
        active -> colors.primary
        else -> colors.surfaceContainerHighest
    }
    Box(Modifier.size(48.dp).prismFocus().clip(CircleShape).background(background)
        .onFocusChanged { if (!it.hasFocus) remoteDown = null }
        .onPreviewKeyEvent {
            val event = it.nativeKeyEvent
            if (first == null || event.keyCode !in remoteSelectKeys) return@onPreviewKeyEvent false
            when (event.action) {
                KeyEvent.ACTION_DOWN -> {
                    if (event.repeatCount == 0) {
                        remoteDown = event.eventTime
                        remoteLongPress = false
                    }
                    if (event.isLongPress) remoteLongPress = true
                }
                KeyEvent.ACTION_UP -> {
                    val start = remoteDown
                    remoteDown = null
                    if (start != null && !event.isCanceled) {
                        if (remoteLongPress || event.eventTime - start >= holdMillis) menu = true
                        else choose(first)
                    }
                }
                else -> return@onPreviewKeyEvent false
            }
            true
        }
        .combinedClickable(enabled = first != null, role = Role.Button, onLongClickLabel = tr(Msg.local_list_history),
            onLongClick = { menu = true }, onClick = { first?.let(::choose) })
        .semantics { selected = active }.testTag("player_source_list"), contentAlignment = Alignment.Center) {
        CompositionLocalProvider(LocalContentColor provides tint) {
            SkinIcon(UiIcon.PLAYLIST, localListLabel(first ?: LocalListTarget.favorites), Modifier.size(28.dp))
        }
    }
    if (menu) AlertDialog(onDismissRequest = { menu = false }, title = { Text(tr(Msg.local_list_history)) },
        text = {
            LazyColumn(Modifier.testTag("local_list_history")) {
                items(choices, key = LocalListTarget::key) { target ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton({ choose(target) }, Modifier.weight(1f).prismFocus().testTag("local_history_${target.key}")) {
                            Column(Modifier.fillMaxWidth()) {
                                Text(localListLabel(target), maxLines = 2, overflow = TextOverflow.Ellipsis)
                                if (target.folderPath.isNotBlank() && target.folderPath != target.title)
                                    Text(target.folderPath, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        val pinned = target.key in state.localLists.pinned
                        FilledTonalIconToggleButton(pinned, { pin(target, it) }, Modifier.prismFocus().testTag("local_pin_${target.key}")) {
                            Icon(painterResource(R.drawable.local_push_pin),
                                tr(if (pinned) Msg.local_unpin else Msg.local_pin, localListLabel(target)))
                        }
                    }
                }
            }
        }, confirmButton = { TextButton({ menu = false }, Modifier.prismFocus()) { Text(tr(Msg.msg_8fbe9b75cbdf)) } })
}

private val remoteSelectKeys = setOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
    KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BUTTON_A)
