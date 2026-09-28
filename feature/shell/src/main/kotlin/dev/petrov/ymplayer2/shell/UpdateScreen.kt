package dev.petrov.ymplayer2.shell

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.petrov.ymplayer2.designsystem.prismFocus
import kotlinx.coroutines.flow.StateFlow

data class UpdateOffer(val versionName: String, val notes: String, val hasAlternative: Boolean)
data class UpdateUiState(
    val autoCheck: Boolean = true,
    val checking: Boolean = false,
    val downloading: Boolean = false,
    val progress: Int = 0,
    val source: String = "",
    val status: String = "",
    val offer: UpdateOffer? = null,
    val ready: Boolean = false,
    val prompt: Boolean = false,
)

interface UpdateAccess {
    val state: StateFlow<UpdateUiState>
    fun setAutoCheck(enabled: Boolean)
    fun check(manual: Boolean = true)
    fun download(preferAlternative: Boolean = false)
    fun install()
    fun dismissPrompt()
}

@Composable internal fun UpdateScreen(version: String, update: UpdateAccess) {
    val state = update.state.collectAsState().value
    LazyColumn(Modifier.fillMaxSize().testTag("updates_list"), contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Text("Обновление приложения", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
        item { Text("Установлено: $version", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Проверять при запуске", Modifier.weight(1f))
                Switch(state.autoCheck, update::setAutoCheck, Modifier.testTag("updates_auto"))
            }
        }
        item { Text("Автоматическая проверка выполняется не чаще раза в сутки. При наличии новой версии загрузка и системная установка требуют вашего действия.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item { OutlinedButton({ update.check() }, Modifier.fillMaxWidth().prismFocus().testTag("updates_check"), enabled = !state.checking && !state.downloading) {
            Text(if (state.checking) "Проверяем…" else "Проверить обновления")
        } }
        if (state.status.isNotBlank()) item { Text(state.status, color = MaterialTheme.colorScheme.primary, modifier = Modifier.testTag("updates_status")) }
        if (state.downloading) item {
            Column { Text("Загрузка: ${state.progress}% · ${state.source}"); LinearProgressIndicator({ state.progress / 100f }, Modifier.fillMaxWidth()) }
        }
        state.offer?.let { offer ->
            item { Text("Доступна ${offer.versionName}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
            if (offer.notes.isNotBlank()) item { Text(offer.notes) }
            if (!state.ready) {
                item { Button({ update.download() }, Modifier.fillMaxWidth().prismFocus().testTag("updates_download"), enabled = !state.downloading) { Text("Скачать и установить") } }
                if (offer.hasAlternative) item { OutlinedButton({ update.download(true) }, Modifier.fillMaxWidth().prismFocus().testTag("updates_alternative"), enabled = !state.downloading) { Text("Скачать через резервный источник") } }
            } else item { Button(update::install, Modifier.fillMaxWidth().prismFocus().testTag("updates_install")) { Text("Открыть установку") } }
        }
        item { Text("Источники: GitHub и jsDelivr. APK проверяется по размеру, SHA-256, имени пакета, версии и подписи. Android попросит подтвердить установку.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable internal fun UpdatePrompt(update: UpdateAccess, offer: UpdateOffer) {
    AlertDialog(onDismissRequest = update::dismissPrompt, title = { Text("Обновление YMPlayer 2") },
        text = { Column(Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Доступна версия ${offer.versionName}.")
            if (offer.notes.isNotBlank()) Text(offer.notes)
        } },
        confirmButton = { TextButton({ update.dismissPrompt(); update.download() }, Modifier.testTag("updates_prompt_download")) { Text("Скачать") } },
        dismissButton = { TextButton(update::dismissPrompt, Modifier.testTag("updates_prompt_later")) { Text("Позже") } })
}
