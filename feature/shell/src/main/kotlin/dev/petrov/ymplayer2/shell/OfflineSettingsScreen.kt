package dev.petrov.ymplayer2.shell

import dev.petrov.ymplayer2.localization.*

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
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

@Composable internal fun OfflineSettingsScreen(offline: OfflineMusic, start: () -> Unit, account: () -> Unit,
    location: CacheLocationAccess? = null, pickFolder: () -> Unit = {}, resetFolder: () -> Unit = {}, transferBusy: Boolean = false,
    verify: () -> Unit = offline::verifyAndRepair, confirmFolder: (String) -> Unit = { location?.confirm(it) },
    profileCache: Boolean = false, quality: AudioQualityPreferences? = null) {
    val state by offline.state.collectAsStateWithLifecycle()
    var confirmClear by remember(state.owner) { mutableStateOf(false) }
    val storage = location?.state?.collectAsStateWithLifecycle()?.value
    LazyColumn(Modifier.fillMaxSize().testTag(if (profileCache) "profile_cache_settings_list" else "offline_settings_list"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text(if (profileCache) trMessage("Офлайн-кэш профиля") else tr(Msg.msg_fd6e72f72d55), style = MaterialTheme.typography.headlineSmall) }
        item { Text(if (profileCache) tr(Msg.msg_d65fb9be6155) else trMessage("Эти параметры общие для всех профилей. Папка, синхронизация и очистка находятся в настройках профиля.")) }
        if (profileCache && !state.enabled) item { Text(trMessage("Офлайн-кэш выключен. Включите его в общих настройках."), Modifier.testTag("profile_cache_disabled")) }
        if (profileCache && storage != null) item {
            Text(trMessage("Папка офлайн-кэша"), style = MaterialTheme.typography.titleMedium)
            Text(trMessage(storage.label), Modifier.testTag("cache_location"))
            if (storage.moving) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("${storage.copiedBytes / 1048576} / ${storage.totalBytes / 1048576} MB")
                OutlinedButton({ location.cancel() }, Modifier.fillMaxWidth().prismFocus().testTag("cache_move_cancel")) { Text(trMessage("Отменить перенос")) }
            } else {
                OutlinedButton(pickFolder, Modifier.fillMaxWidth().prismFocus().testTag("cache_choose_folder"), enabled = state.enabled && state.owner != null && !transferBusy) { Text(trMessage("Выбрать папку кэша")) }
                if (storage.external) TextButton(resetFolder, Modifier.fillMaxWidth().prismFocus().testTag("cache_internal"), enabled = state.enabled && state.owner != null && !transferBusy) { Text(trMessage("Перенести во внутреннюю память")) }
            }
            Text(trMessage("Расположение отдельно для каждого профиля. Включение, Wi-Fi и качество общие."), style = MaterialTheme.typography.bodySmall)
            Text(trMessage("Перед переносом проверяется свободное место. Исходный кэш удаляется только после проверки копии."), style = MaterialTheme.typography.bodySmall)
            storage.message?.let { Text(trMessage(it), Modifier.testTag("cache_location_status")) }
        }
        if (!profileCache) item { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(tr(Msg.msg_074cc2ac4332), Modifier.weight(1f))
            Switch(state.enabled, offline::setEnabled, Modifier.prismFocus().testTag("offline_enabled"), enabled = !transferBusy)
        } }
        if (!profileCache) item { Text(tr(Msg.msg_17b7c023b762),
            style = MaterialTheme.typography.bodySmall) }
        if (!profileCache) item { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(tr(Msg.msg_729dc1568fdf), Modifier.weight(1f))
            Switch(state.wifiOnly, offline::setWifiOnly, Modifier.prismFocus().testTag("offline_wifi"), enabled = state.enabled)
        } }
        if (!profileCache && quality != null) item { AudioQualityPicker(quality, cache = true, enabled = state.enabled) }
        if (profileCache && state.owner == null) {
            item { Text(tr(Msg.msg_001dda282241)) }
            item { Button(account, Modifier.prismFocus()) { Text(tr(Msg.msg_612f724ec00f)) } }
        } else if (profileCache) {
            if (state.enabled) item { Text(tr(Msg.msg_5e4ea237ce6b, state.tracks.size, state.bytes / (1024 * 1024)), Modifier.testTag("offline_summary")) }
            item {
                if (state.running) OutlinedButton(offline::cancel, Modifier.fillMaxWidth().prismFocus().testTag("offline_cancel")) { Text(tr(Msg.msg_cb4cd68cb5f3)) }
                else Button(start, Modifier.fillMaxWidth().prismFocus().testTag("offline_sync"), enabled = state.enabled && state.ready && storage?.moving != true && !transferBusy) { Text(tr(Msg.msg_ca48a1a553cc)) }
            }
            if (!state.running) item {
                OutlinedButton(verify, Modifier.fillMaxWidth().prismFocus().testTag("offline_verify"),
                    enabled = state.enabled && state.ready && storage?.moving != true && !transferBusy) {
                    Text(tr(Msg.offline_verify))
                }
            }
            item { Text(tr(Msg.offline_sync_hint), style = MaterialTheme.typography.bodySmall) }
            if (state.enabled && (!state.ready || state.running)) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (!state.enabled) item { Text(tr(Msg.msg_94117e81a588), Modifier.testTag("offline_disabled")) }
            state.message?.let { message -> item { Text(trMessage(message), Modifier.testTag("offline_status")) } }
            if (state.audioFailures + state.coverFailures + state.noCover > 0) item {
                Text(tr(Msg.msg_7003237eb243, state.audioFailures, state.coverFailures, state.noCover))
            }
            item { TextButton({ confirmClear = true }, Modifier.fillMaxWidth().prismFocus().testTag("offline_clear"), enabled = state.ready && storage?.moving != true && !transferBusy) { Text(tr(Msg.msg_5277d2953e7b)) } }

        }
    }
    storage?.selection?.takeIf { profileCache && state.enabled && it.options.isNotEmpty() }?.let { selection ->
        var selected by remember(selection) { mutableStateOf(selection.options.first().id) }
        val legacy = selection.options.firstOrNull { it.id == selected }?.legacy == true
        AlertDialog(onDismissRequest = { location.dismiss() }, title = { Text(trMessage("Найден офлайн-кэш")) }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(trMessage(if (legacy) "Старый кэш будет преобразован для этого профиля. Подойдут треки из «Мне нравится». Исправные файлы сохранятся; старое приложение новый формат не поддерживает."
                    else "Подключить кэш этого аккаунта и объединить с текущим? Исправные файлы не будут скачиваться заново."))
                LazyColumn(Modifier.heightIn(max = 220.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(selection.options.size) { index ->
                        val option = selection.options[index]
                        Row(Modifier.fillMaxWidth().selectable(selected == option.id, onClick = { selected = option.id }).prismFocus().testTag("cache_candidate_$index"), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected == option.id, null)
                            Column(Modifier.weight(1f)) { Text(if (option.label.isBlank()) trMessage("Пустой кэш") else option.label); Row { Text(trMessage("Треков в папке:")); Text(" ${option.tracks}") } }
                        }
                    }
                }
            }
        }, confirmButton = { TextButton({ confirmFolder(selected) }, Modifier.testTag("cache_connect_confirm")) { Text(trMessage("Подключить кэш")) } },
            dismissButton = { TextButton({ location.dismiss() }) { Text(trMessage("Отмена")) } })
    }
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false }, title = { Text(tr(Msg.msg_982867f75b81)) },
        text = { Text(tr(Msg.msg_0e04e96eb049)) },
        confirmButton = { TextButton({ confirmClear = false; offline.clear() }, Modifier.testTag("offline_clear_confirm")) { Text(tr(Msg.msg_254732178a10)) } },
        dismissButton = { TextButton({ confirmClear = false }) { Text(tr(Msg.msg_8fbe9b75cbdf)) } })
}
