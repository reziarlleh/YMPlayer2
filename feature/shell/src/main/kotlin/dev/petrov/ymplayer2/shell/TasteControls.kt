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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.petrov.ymplayer2.core.*
import dev.petrov.ymplayer2.designsystem.*
import dev.petrov.ymplayer2.designsystem.skin.*

@Composable internal fun TasteControls(taste: MusicTaste, target: TasteTarget) {
    val state by taste.state.collectAsStateWithLifecycle()
    val shelf = state.shelf(target.kind)
    val liked = target.key in shelf.list.liked
    val blocked = target.key in shelf.list.blocked
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
            SkinIcon(UiIcon.BLOCK, null); Spacer(Modifier.width(8.dp))
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

@Composable internal fun TrackTasteDialog(track: Track, taste: MusicTaste, dismiss: () -> Unit) {
    val content: @Composable () -> Unit = {
            TasteControls(taste, track.tasteTarget())
            HorizontalDivider()
            track.artists.forEach { TasteControls(taste, TasteTarget(TasteKind.ARTIST, it.id, it.name)) }
            track.albumId?.let { TasteControls(taste, TasteTarget(TasteKind.ALBUM, it, track.album)) }
            if (track.artists.isEmpty()) Text("Идентификаторы исполнителей отсутствуют. Откройте трек заново из поиска или медиатеки.")
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

@Composable internal fun WaveButton(player: PlaybackController, signedIn: Boolean, signIn: () -> Unit, started: () -> Unit = {}) {
    val state by player.state.collectAsStateWithLifecycle()
    Button({ if (signedIn) { player.playMyWave(); started() } else signIn() }, Modifier.prismFocus().testTag("my_wave"), enabled = !state.waveLoading) {
        SkinIcon(UiIcon.WAVE, null); Spacer(Modifier.width(8.dp))
        Text(if (state.waveLoading) "Моя волна · загружаем…" else "Моя волна")
    }
}
