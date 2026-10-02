package dev.petrov.ymplayer2.shell

import dev.petrov.ymplayer2.localization.*

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.petrov.ymplayer2.designsystem.prismFocus
import kotlinx.coroutines.flow.StateFlow

data class SideBarOption(val id: String, val title: String, val selected: Boolean)
data class SideBarUiState(
    val permitted: Boolean = false,
    val enabled: Boolean = false,
    val autoHide: Boolean = true,
    val options: List<SideBarOption> = emptyList(),
    val message: String? = null,
)

/** The shell only knows user-visible state and commands, never WindowManager or vendor APIs. */
interface SideBarAccess {
    val state: StateFlow<SideBarUiState>
    fun requestPermission()
    fun setEnabled(enabled: Boolean)
    fun setAutoHide(enabled: Boolean)
    fun setButton(id: String, enabled: Boolean)
    fun toggle()
}

@Composable internal fun SideBarScreen(access: SideBarAccess) {
    val state by access.state.collectAsStateWithLifecycle()
    LazyColumn(Modifier.fillMaxSize().testTag("sidebar_settings"),
        contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text(tr(Msg.msg_43fb606c5b6f), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
        item { Text(tr(Msg.msg_af72c5eb8791)) }
        if (!state.permitted) item {
            OutlinedButton(access::requestPermission, Modifier.fillMaxWidth().prismFocus().testTag("sidebar_permission")) {
                Text(tr(Msg.msg_b3be276ac9d7))
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(tr(Msg.msg_6807424b3688), Modifier.weight(1f))
                Switch(state.enabled, access::setEnabled, enabled = state.permitted && state.options.any { it.selected }, modifier = Modifier.testTag("sidebar_enabled"))
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(tr(Msg.msg_7435626dd379), Modifier.weight(1f))
                Switch(state.autoHide, access::setAutoHide, modifier = Modifier.testTag("sidebar_autohide"))
            }
        }
        item { HorizontalDivider(); Text(tr(Msg.msg_25dc72086a44), style = MaterialTheme.typography.titleMedium) }
        items(state.options, key = SideBarOption::id) { option ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(trMessage(option.title), Modifier.weight(1f))
                Switch(option.selected, { access.setButton(option.id, it) }, modifier = Modifier.testTag("sidebar_button_${option.id}"))
            }
        }
        item { Text(tr(Msg.msg_f8ddc90b1078), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (state.enabled) item {
            OutlinedButton(access::toggle, Modifier.fillMaxWidth().prismFocus().testTag("sidebar_toggle")) { Text(tr(Msg.msg_dc2b5a858155)) }
        }
        item { Text(tr(Msg.msg_8208dc91519d), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        state.message?.let { message -> item { Text(trMessage(message), color = MaterialTheme.colorScheme.primary, modifier = Modifier.testTag("sidebar_message")) } }
    }
}
