package dev.petrov.ymplayer2.shell

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.petrov.ymplayer2.core.*
import dev.petrov.ymplayer2.designsystem.prismFocus

@Composable internal fun OfflineSettingsScreen(offline: OfflineMusic, start: () -> Unit, account: () -> Unit) {
    val state by offline.state.collectAsStateWithLifecycle()
    var confirmClear by remember(state.owner) { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize().testTag("offline_settings_list"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Настройки офлайн-кэша", style = MaterialTheme.typography.headlineSmall) }
        item { Text("Сохраняются только понравившиеся треки и их обложки. Любимые исполнители и альбомы целиком не загружаются.") }
        item { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Включить офлайн-кэш", Modifier.weight(1f))
            Switch(state.enabled, offline::setEnabled, Modifier.prismFocus().testTag("offline_enabled"))
        } }
        item { Text("Настройка общая для устройства. При выключении синхронизация останавливается, сохранённая музыка не используется. Онлайн-музыка и предзагрузка волны продолжают работать.",
            style = MaterialTheme.typography.bodySmall) }
        if (state.owner == null) {
            item { Text("Войдите в Яндекс в этом профиле. Если вход уже сохранён, обновите сведения об аккаунте.") }
            item { Button(account, Modifier.prismFocus()) { Text("Открыть аккаунт") } }
        } else {
            if (state.enabled) item { Text("Сохранено: ${state.tracks.size} · ${state.bytes / (1024 * 1024)} МБ", Modifier.testTag("offline_summary")) }
            item { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Загружать только по Wi-Fi", Modifier.weight(1f))
                Switch(state.wifiOnly, offline::setWifiOnly, Modifier.prismFocus().testTag("offline_wifi"), enabled = state.enabled)
            } }
            item {
                if (state.running) OutlinedButton(offline::cancel, Modifier.fillMaxWidth().prismFocus().testTag("offline_cancel")) { Text("Остановить синхронизацию") }
                else Button(start, Modifier.fillMaxWidth().prismFocus().testTag("offline_sync"), enabled = state.enabled && state.ready) { Text("Синхронизировать «Мне нравится»") }
            }
            if (state.enabled && (!state.ready || state.running)) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (!state.enabled) item { Text("Офлайн-кэш выключен. Ранее скачанные файлы остаются до удаления кнопкой ниже.", Modifier.testTag("offline_disabled")) }
            state.message?.let { message -> item { Text(message, Modifier.testTag("offline_status")) } }
            if (state.audioFailures + state.coverFailures + state.noCover > 0) item {
                Text("Не загружено аудио: ${state.audioFailures}. Ошибки обложек: ${state.coverFailures}. Без обложки у источника: ${state.noCover}. Повторная синхронизация проверит и восстановит недостающее.")
            }
            item { TextButton({ confirmClear = true }, Modifier.fillMaxWidth().prismFocus().testTag("offline_clear"), enabled = state.ready) { Text("Удалить офлайн-файлы") } }

        }
    }
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false }, title = { Text("Удалить офлайн-файлы?") },
        text = { Text("Будут удалены аудио и обложки этого аккаунта в текущем профиле. Лайки в Яндексе сохранятся.") },
        confirmButton = { TextButton({ confirmClear = false; offline.clear() }, Modifier.testTag("offline_clear_confirm")) { Text("Удалить файлы") } },
        dismissButton = { TextButton({ confirmClear = false }) { Text("Отмена") } })
}
