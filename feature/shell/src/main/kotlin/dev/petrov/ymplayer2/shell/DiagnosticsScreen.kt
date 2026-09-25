package dev.petrov.ymplayer2.shell

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.petrov.ymplayer2.designsystem.prismFocus
import kotlinx.coroutines.launch

/** The shell can show diagnostics without knowing Android storage or any account data. */
interface DiagnosticsAccess {
    suspend fun snapshot(): String
    suspend fun clear(): String
    suspend fun export(): String
}

@Composable internal fun DiagnosticsScreen(access: DiagnosticsAccess) {
    var contents by remember { mutableStateOf("Загрузка журнала…") }
    var status by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(access) { contents = access.snapshot() }
    LazyColumn(Modifier.fillMaxSize().testTag("diagnostics_list"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Диагностика", style = MaterialTheme.typography.headlineSmall) }
        item { Text("В журнале только коды событий. Токены, коды входа, названия треков и адреса запросов не записываются.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({ scope.launch { contents = access.snapshot(); status = "Журнал обновлён." } }, Modifier.prismFocus().testTag("diagnostics_refresh")) { Text("Обновить") }
            OutlinedButton({ scope.launch { status = access.clear(); contents = access.snapshot() } }, Modifier.prismFocus().testTag("diagnostics_clear")) { Text("Очистить") }
        } }
        item { OutlinedButton({ scope.launch { status = access.export() } }, Modifier.prismFocus().testTag("diagnostics_export")) { Text("Экспорт в Downloads") } }
        if (status != null) item { Text(status.orEmpty(), Modifier.testTag("diagnostics_status"), color = MaterialTheme.colorScheme.primary) }
        item { Text(contents, Modifier.fillMaxWidth().testTag("diagnostics_contents"), style = MaterialTheme.typography.bodySmall) }
    }
}
