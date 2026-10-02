package dev.petrov.ymplayer2.shell

import dev.petrov.ymplayer2.localization.*

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
        item { Text(tr(Msg.msg_c39959813ccd), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
        item { Text(tr(Msg.msg_031396ebc4da, profileName(profile)), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("account_profile")) }
        when (state.phase) {
            AuthPhase.LOADING, AuthPhase.REQUESTING, AuthPhase.VERIFYING -> {
                item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                item { Text(when (state.phase) { AuthPhase.REQUESTING -> tr(Msg.msg_62984b2595bb); AuthPhase.VERIFYING -> tr(Msg.msg_71ff8690cd03); else -> tr(Msg.msg_ecb4e3f4230f) }) }
            }
            AuthPhase.WAITING -> {
                item { Text(tr(Msg.msg_dcc07d613ee2)) }
                item { Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.large) {
                    SelectionContainer { Text(state.userCode.orEmpty(), Modifier.padding(20.dp).testTag("auth_code"), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold) }
                } }
                item { Text(tr(Msg.msg_004778f850b1), modifier = Modifier.testTag("auth_code_hint")) }
                item { Text(state.verificationUrl.orEmpty()) }
                item { Text(tr(Msg.msg_ee19d8c20fcb, secondsLabel(remaining.toInt())), modifier = Modifier.testTag("auth_countdown")) }
                item { OutlinedButton({
                    try { browser.openUri(state.verificationUrl!!); browserIssue = false }
                    catch (_: Exception) { browserIssue = true }
                }, Modifier.prismFocus().testTag("auth_browser")) { Text(tr(Msg.msg_e25ae5e8d071)) } }
                if (browserIssue) item { Text(tr(Msg.msg_c06f71ebf142), color = MaterialTheme.colorScheme.error) }
                item { Text(tr(Msg.msg_306af95e29b6)) }
            }
            AuthPhase.SIGNED_IN -> {
                item { Text(tr(Msg.msg_bb7c04530661), style = MaterialTheme.typography.titleLarge, modifier = Modifier.testTag("auth_connected")) }
                state.account?.let { account -> item { Text(account.name, fontWeight = FontWeight.Bold) } }
                item { Text(tr(Msg.msg_2d6b68932448)) }
                if (state.updatingAccount) item { Text(tr(Msg.msg_e21e6ad5c123)) }
                state.issue?.let { issue -> item { Text(trIssue(issue), modifier = Modifier.testTag("auth_account_retry_message")) } }
                if (state.account == null || state.issue != null) item {
                    OutlinedButton(auth::retryAccount, Modifier.prismFocus().testTag("auth_retry_account"), enabled = !state.updatingAccount) { Text(tr(Msg.msg_3f8a4e233c3e)) }
                }
                item { OutlinedButton({ confirmLogout = true }, Modifier.prismFocus().testTag("auth_logout")) { Text(tr(Msg.msg_3364af49d2ba)) } }
            }
            AuthPhase.GUEST -> item { Text(tr(Msg.msg_433852b8fe49), modifier = Modifier.testTag("auth_guest")) }
            AuthPhase.UNCONFIGURED -> item { Text(tr(Msg.msg_4cffda4eecf8), modifier = Modifier.testTag("auth_unconfigured")) }
            AuthPhase.SIGNED_OUT, AuthPhase.ERROR -> {
                item { Text(tr(Msg.msg_29f6f8d9dd3e)) }
                state.issue?.let { issue -> item { Text(trIssue(issue), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("auth_error")) } }
                item { Button(auth::start, Modifier.prismFocus().testTag("auth_start")) { Text(tr(Msg.msg_0a813814f149)) } }
                if (state.phase == AuthPhase.ERROR) item {
                    TextButton({ confirmLogout = true }, Modifier.prismFocus().testTag("auth_reset")) { Text(tr(Msg.msg_3628da5ba5a2)) }
                }
            }
        }
        if (state.phase == AuthPhase.WAITING) state.issue?.let { issue -> item { Text(trIssue(issue), modifier = Modifier.testTag("auth_network_wait")) } }
        state.diagnostic?.let { code -> item { Text(tr(Msg.msg_78ca838c8f88, code), style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("auth_diagnostic")) } }
        if (state.phase in setOf(AuthPhase.REQUESTING, AuthPhase.WAITING, AuthPhase.VERIFYING)) item {
            OutlinedButton(auth::cancel, Modifier.prismFocus().testTag("auth_cancel")) { Text(tr(Msg.msg_a4ee02a4c189)) }
        }
    }
    if (confirmLogout) AlertDialog(onDismissRequest = { confirmLogout = false }, title = { Text(tr(Msg.msg_350675f4e001)) },
        text = { Text(tr(Msg.msg_4e882842072f, profileName(profile))) },
        confirmButton = { TextButton({ confirmLogout = false; auth.signOut() }, Modifier.prismFocus().testTag("auth_logout_confirm")) { Text(tr(Msg.msg_5690c2e63b9d)) } },
        dismissButton = { TextButton({ confirmLogout = false }, Modifier.prismFocus()) { Text(tr(Msg.msg_8fbe9b75cbdf)) } })
}
