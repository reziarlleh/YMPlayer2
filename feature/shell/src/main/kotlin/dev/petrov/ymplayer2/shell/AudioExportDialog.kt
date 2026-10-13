package dev.petrov.ymplayer2.shell

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.petrov.ymplayer2.core.*
import dev.petrov.ymplayer2.designsystem.prismFocus
import dev.petrov.ymplayer2.localization.trMessage

@Composable internal fun AudioExportDialog(exporter: AudioExportAccess, selectFolder: () -> Unit) {
    val state by exporter.state.collectAsStateWithLifecycle()
    val request = state.request ?: return
    var help by remember { mutableStateOf(false) }
    val actionFocus = remember { FocusRequester() }
    val valid = ExportFileName.valid(state.template)
    val sample = request.tracks?.firstOrNull() ?: Track("yandex:123", trMessage("Название трека"), trMessage("Исполнитель"),
        request.entity?.title ?: trMessage("Альбом"), Source.YANDEX, 180, false)
    Dialog(onDismissRequest = { if (help) help = false else if (!state.running) exporter.dismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false)) {
        // Stable full-size platform window, matching AboutDialog. Only its bounded card scrolls.
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp), contentAlignment = Alignment.Center) {
          Surface(Modifier.widthIn(max = 560.dp).fillMaxWidth().heightIn(max = maxHeight), shape = MaterialTheme.shapes.large) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
              if (help) {
                Text(trMessage("Переменные шаблона"), style = MaterialTheme.typography.titleLarge)
                Text(trMessage("{number} — номер в списке\n{artist} — исполнитель\n{title} — название трека\n{album} — альбом\n{id} — ID трека\n\nМожно добавлять свой текст между переменными. Например: {number}. {artist} - {title}"))
                TextButton({ help = false }, Modifier.focusRequester(actionFocus).prismFocus().testTag("export_help_close")) { Text(trMessage("Понятно")) }
              } else {
                Text(trMessage("Экспорт MP3"), style = MaterialTheme.typography.titleLarge)
                Text(request.title)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(state.template, exporter::template, Modifier.weight(1f).testTag("export_template"),
                        label = { Text(trMessage("Шаблон имени файла")) }, enabled = !state.running, isError = !valid, singleLine = true)
                    TextButton({ help = true }, Modifier.prismFocus().testTag("export_template_help")) { Text("?") }
                }
                if (valid) Text(ExportFileName.render(state.template, sample, 0, request.tracks?.size ?: 10), Modifier.testTag("export_name_preview"), style = MaterialTheme.typography.bodySmall)
                else Text(trMessage("Используйте известные переменные и непустой шаблон."), color = MaterialTheme.colorScheme.error)
                Text(trMessage("Расширение .mp3 добавляется автоматически. Существующие файлы не перезаписываются."), style = MaterialTheme.typography.bodySmall)
                if (state.running) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (state.total > 0) Text(trMessage("Готово: ${state.completed} из ${state.total}. Ошибок: ${state.failed}."), Modifier.testTag("export_summary"))
                if (state.current.isNotBlank()) Text(state.current)
                state.message?.let { Text(trMessage(it), Modifier.testTag("export_message")) }
                if (state.running) TextButton(exporter::cancel, Modifier.focusRequester(actionFocus).prismFocus().testTag("export_cancel")) { Text(trMessage("Остановить экспорт")) }
                else {
                    TextButton(selectFolder, Modifier.prismFocus().testTag("export_choose_folder"), enabled = valid) { Text(trMessage("Выбрать папку и экспортировать")) }
                    TextButton(exporter::dismiss, Modifier.focusRequester(actionFocus).prismFocus()) { Text(trMessage("Закрыть")) }
                }
              }
            }
          }
        }
        val windowFocused = LocalWindowInfo.current.isWindowFocused
        LaunchedEffect(windowFocused, help, state.running) { if (windowFocused) actionFocus.requestFocus() }
    }
}
