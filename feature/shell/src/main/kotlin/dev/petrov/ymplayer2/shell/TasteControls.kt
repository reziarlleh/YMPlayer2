package dev.petrov.ymplayer2.shell

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
                !known -> if (state.signedIn) "Состояние не получено" else "Нужен вход в Яндекс"
                block -> if (marked) "Никогда не предлагать" else "Запрет не установлен"
                else -> if (marked) "В избранном" else "Не в избранном"
            } + if (shelf.busy) "; обновляем…" else ""
            val label = when (action) {
                TasteAction.LIKE -> "Добавить в избранное"
                TasteAction.UNLIKE -> "Убрать из избранного"
                TasteAction.BLOCK -> "Никогда не предлагать"
                TasteAction.UNBLOCK -> "Снова предлагать"
            } + ": ${target.kind.label.lowercase()} «${target.title}»"
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
        Text("Не удалось обновить отметки. Повторить", color = MaterialTheme.colorScheme.error)
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
        Text("${target.kind.label} · ${target.title}", fontWeight = FontWeight.Medium)
        if (!shelf.ready && shelf.issue == null) Text("Проверяем отметки…", style = MaterialTheme.typography.bodySmall)
        OutlinedButton({ taste.react(target, if (liked) TasteAction.UNLIKE else TasteAction.LIKE) },
            Modifier.prismFocus().testTag("${tag}_like"), enabled = enabled) {
            SkinIcon(if (liked) UiIcon.FAVORITE else UiIcon.FAVORITE_OFF, null)
            Spacer(Modifier.width(8.dp))
            Text(when (target.kind) {
                TasteKind.TRACK -> if (liked) "Убрать из «Мне нравится»" else "В «Мне нравится»"
                TasteKind.ARTIST -> if (liked) "Убрать из любимых исполнителей" else "В любимые исполнители"
                TasteKind.ALBUM -> if (liked) "Убрать из любимых альбомов" else "В любимые альбомы"
            })
        }
        if (target.kind != TasteKind.ALBUM) OutlinedButton({ taste.react(target, if (blocked) TasteAction.UNBLOCK else TasteAction.BLOCK) },
            Modifier.prismFocus().testTag("${tag}_block"), enabled = enabled) {
            SkinIcon(if (blocked) UiIcon.DISLIKE else UiIcon.DISLIKE_OFF, null); Spacer(Modifier.width(8.dp))
            Text(if (blocked) "Снова предлагать ${if (target.kind == TasteKind.TRACK) "трек" else "исполнителя"}"
                else "Никогда не предлагать ${if (target.kind == TasteKind.TRACK) "трек" else "исполнителя"}")
        }
        if (shelf.busy) Text("Обновляем отметки…", style = MaterialTheme.typography.bodySmall)
        shelf.issue?.let {
            Text(it, color = MaterialTheme.colorScheme.error)
            TextButton({ taste.refresh(target.kind) }, Modifier.prismFocus(), enabled = !shelf.busy) { Text("Проверить отметки") }
        }
    }
}

@Composable internal fun TrackTasteDialog(track: Track, taste: MusicTaste, artist: (ArtistRef) -> Unit = {}, extra: @Composable () -> Unit = {}, dismiss: () -> Unit) {
    val content: @Composable () -> Unit = {
            track.artists.distinctBy(ArtistRef::id).forEach {
                TextButton({ dismiss(); artist(it) }, Modifier.prismFocus().testTag("actions_artist_${it.id}")) { Text("Открыть: ${it.name}") }
                TasteControls(taste, TasteTarget(TasteKind.ARTIST, it.id, it.name))
            }
            track.albumId?.let { TasteControls(taste, TasteTarget(TasteKind.ALBUM, it, track.album)) }
            if (track.artists.isEmpty()) Text("Идентификаторы исполнителей отсутствуют. Откройте трек заново из поиска или медиатеки.")
            extra()
    }
    if (LocalConfiguration.current.screenHeightDp < 480 || LocalDensity.current.fontScale > 1.3f) {
        Dialog(dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.Center) {
                Surface(Modifier.widthIn(max = 880.dp).fillMaxSize(), shape = MaterialTheme.shapes.large) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Отметки", style = MaterialTheme.typography.titleMedium)
                        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) { content() }
                        TextButton(dismiss, Modifier.align(Alignment.End).prismFocus()) { Text("Готово") }
                    }
                }
            }
        }
    } else AlertDialog(onDismissRequest = dismiss, title = { Text("Отметки в Яндекс Музыке") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) { content() } },
        confirmButton = { TextButton(dismiss, Modifier.prismFocus()) { Text("Готово") } })
}

@Composable internal fun WaveButton(player: PlaybackController, signedIn: Boolean, signIn: () -> Unit, started: () -> Unit = {}, modifier: Modifier = Modifier) {
    val state by player.state.collectAsStateWithLifecycle()
    Button({ if (signedIn) { player.playMyWave(); started() } else signIn() }, modifier.prismFocus().testTag("my_wave"), enabled = !state.waveLoading) {
        SkinIcon(UiIcon.WAVE, null); Spacer(Modifier.width(8.dp))
        Text(if (state.waveLoading) "Моя волна · загружаем…" else "Моя волна")
    }
}
