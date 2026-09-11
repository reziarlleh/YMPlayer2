package dev.petrov.ymplayer2.shell

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.petrov.ymplayer2.core.*
import dev.petrov.ymplayer2.designsystem.prismFocus
import kotlinx.coroutines.delay

@Composable internal fun AccountScreen(auth: AccountAuth, profile: Profile) {
    val current by auth.state.collectAsStateWithLifecycle()
    val state = current.takeIf { it.profileId == profile.id } ?: AccountAuthState(profile.id)
    val browser = LocalUriHandler.current
    var browserIssue by remember(state.userCode) { mutableStateOf(false) }
    var confirmLogout by remember(profile.id) { mutableStateOf(false) }
    var remaining by remember(state.expiresAtMillis) { mutableLongStateOf(0) }
    LaunchedEffect(state.expiresAtMillis) {
        val deadline = state.expiresAtMillis ?: return@LaunchedEffect
        while (true) { remaining = ((deadline - System.currentTimeMillis()).coerceAtLeast(0) + 999) / 1000; delay(1000) }
    }
    LazyColumn(Modifier.fillMaxSize().testTag("account_screen"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Text("Яндекс Музыка", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
        item { Text("Профиль: ${profile.name}", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("account_profile")) }
        when (state.phase) {
            AuthPhase.LOADING, AuthPhase.REQUESTING, AuthPhase.VERIFYING -> {
                item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                item { Text(when (state.phase) { AuthPhase.REQUESTING -> "Получаем код…"; AuthPhase.VERIFYING -> "Проверяем аккаунт Музыки…"; else -> "Загружаем состояние входа…" }) }
            }
            AuthPhase.WAITING -> {
                item { Text("Введите код на другом устройстве или откройте страницу Яндекса здесь.") }
                item { Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.large) {
                    SelectionContainer { Text(state.userCode.orEmpty(), Modifier.padding(20.dp).testTag("auth_code"), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold) }
                } }
                item { Text("Запишите код перед переходом в браузер: на странице Яндекса не всегда можно вставить его из буфера обмена.", modifier = Modifier.testTag("auth_code_hint")) }
                item { Text(state.verificationUrl.orEmpty()) }
                item { Text("Код действует ещё ${secondsLabel(remaining.toInt())}", modifier = Modifier.testTag("auth_countdown")) }
                item { OutlinedButton({
                    try { browser.openUri(state.verificationUrl!!); browserIssue = false }
                    catch (_: Exception) { browserIssue = true }
                }, Modifier.prismFocus().testTag("auth_browser")) { Text("Открыть Яндекс") } }
                if (browserIssue) item { Text("Браузер недоступен. Откройте указанный адрес на телефоне и введите код.", color = MaterialTheme.colorScheme.error) }
                item { Text("Ожидаем подтверждения…") }
            }
            AuthPhase.SIGNED_IN -> {
                item { Text("Вход выполнен", style = MaterialTheme.typography.titleLarge, modifier = Modifier.testTag("auth_connected")) }
                item { Text(state.account?.name.orEmpty(), fontWeight = FontWeight.Bold) }
                item { Text("Аккаунт подключён к этому профилю. Онлайн-каталог и воспроизведение Яндекса появятся на следующем этапе.") }
                item { OutlinedButton({ confirmLogout = true }, Modifier.prismFocus().testTag("auth_logout")) { Text("Выйти из аккаунта") } }
            }
            AuthPhase.GUEST -> item { Text("Гость слушает локальную музыку без аккаунта. Для входа выберите другой профиль.", modifier = Modifier.testTag("auth_guest")) }
            AuthPhase.UNCONFIGURED -> item { Text("Вход в Яндекс недоступен в этой сборке. Локальная музыка продолжает работать.", modifier = Modifier.testTag("auth_unconfigured")) }
            AuthPhase.SIGNED_OUT, AuthPhase.ERROR -> {
                item { Text("Вход по коду без ввода пароля в плеере. Аккаунты разных профилей не смешиваются.") }
                state.issue?.let { issue -> item { Text(issue, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("auth_error")) } }
                item { Button(auth::start, Modifier.prismFocus().testTag("auth_start")) { Text("Получить код входа") } }
                if (state.phase == AuthPhase.ERROR) item {
                    TextButton({ confirmLogout = true }, Modifier.prismFocus().testTag("auth_reset")) { Text("Удалить сохранённый вход") }
                }
            }
        }
        if (state.phase in setOf(AuthPhase.REQUESTING, AuthPhase.WAITING, AuthPhase.VERIFYING)) item {
            OutlinedButton(auth::cancel, Modifier.prismFocus().testTag("auth_cancel")) { Text("Отменить вход") }
        }
    }
    if (confirmLogout) AlertDialog(onDismissRequest = { confirmLogout = false }, title = { Text("Выйти из Яндекса?") },
        text = { Text("Сохранённый вход будет удалён только из профиля «${profile.name}» на этом устройстве. Локальные файлы, очередь и плейлисты останутся.") },
        confirmButton = { TextButton({ confirmLogout = false; auth.signOut() }, Modifier.prismFocus().testTag("auth_logout_confirm")) { Text("Выйти") } },
        dismissButton = { TextButton({ confirmLogout = false }, Modifier.prismFocus()) { Text("Отмена") } })
}
