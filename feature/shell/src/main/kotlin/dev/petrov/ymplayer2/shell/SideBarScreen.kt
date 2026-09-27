package dev.petrov.ymplayer2.shell

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
        item { Text("Боковая панель", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
        item { Text("Потяните цветной маркер коротким свайпом от левого, правого или нижнего края к центру. Маркеры занимают лишь небольшую часть края; панель работает поверх других приложений после вашего разрешения.") }
        if (!state.permitted) item {
            OutlinedButton(access::requestPermission, Modifier.fillMaxWidth().prismFocus().testTag("sidebar_permission")) {
                Text("Разрешить показ поверх приложений")
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Включить панель", Modifier.weight(1f))
                Switch(state.enabled, access::setEnabled, enabled = state.permitted, modifier = Modifier.testTag("sidebar_enabled"))
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Сворачивать через 8 секунд", Modifier.weight(1f))
                Switch(state.autoHide, access::setAutoHide, modifier = Modifier.testTag("sidebar_autohide"))
            }
        }
        item { HorizontalDivider(); Text("Кнопки панели", style = MaterialTheme.typography.titleMedium) }
        items(state.options, key = SideBarOption::id) { option ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(option.title, Modifier.weight(1f))
                Switch(option.selected, { access.setButton(option.id, it) }, modifier = Modifier.testTag("sidebar_button_${option.id}"))
            }
        }
        item { Text("Свернуть — всегда последняя кнопка и не отключается.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (state.enabled) item {
            OutlinedButton(access::toggle, Modifier.fillMaxWidth().prismFocus().testTag("sidebar_toggle")) { Text("Показать / скрыть панель") }
        }
        item { Text("Назад, сон, перезагрузка и DSP появятся после проверки адаптера магнитолы. Команды устройства не отправляются без подтверждённой совместимости.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        state.message?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.primary, modifier = Modifier.testTag("sidebar_message")) } }
    }
}
