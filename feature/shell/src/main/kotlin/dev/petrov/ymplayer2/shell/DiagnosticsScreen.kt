package dev.petrov.ymplayer2.shell

import dev.petrov.ymplayer2.localization.*

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
    var contents by remember { mutableStateOf(tr(Msg.msg_aed87524dfde)) }
    var status by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(access) { contents = access.snapshot() }
    LazyColumn(Modifier.fillMaxSize().testTag("diagnostics_list"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text(tr(Msg.msg_da4da4e3527c), style = MaterialTheme.typography.headlineSmall) }
        item { Text(tr(Msg.msg_d6e12cca8a0f), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({ scope.launch { contents = access.snapshot(); status = tr(Msg.msg_1a5f7efbec65) } }, Modifier.prismFocus().testTag("diagnostics_refresh")) { Text(tr(Msg.msg_603e460bf59c)) }
            OutlinedButton({ scope.launch { status = access.clear(); contents = access.snapshot() } }, Modifier.prismFocus().testTag("diagnostics_clear")) { Text(tr(Msg.msg_8965271d3c97)) }
        } }
        item { OutlinedButton({ scope.launch { status = access.export() } }, Modifier.prismFocus().testTag("diagnostics_export")) { Text(tr(Msg.msg_4195b215e9f0)) } }
        if (status != null) item { Text(trMessage(status.orEmpty()), Modifier.testTag("diagnostics_status"), color = MaterialTheme.colorScheme.primary) }
        item { Text(contents, Modifier.fillMaxWidth().testTag("diagnostics_contents"), style = MaterialTheme.typography.bodySmall) }
    }
}
