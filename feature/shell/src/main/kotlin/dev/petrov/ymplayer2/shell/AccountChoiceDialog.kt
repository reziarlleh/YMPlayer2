package dev.petrov.ymplayer2.shell

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.petrov.ymplayer2.core.Profile
import dev.petrov.ymplayer2.core.UserProfiles
import dev.petrov.ymplayer2.designsystem.prismFocus
import dev.petrov.ymplayer2.localization.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Opening the offer neither creates a profile nor starts OAuth. Both need an explicit action. */
@Composable internal fun AccountChoiceDialog(profiles: List<Profile>, users: UserProfiles?,
    dismiss: () -> Unit, select: (String) -> Unit, created: (String) -> Unit) {
    val accounts = profiles.filter { it.canSignIn && it.accountConnected }
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val working = busy || users?.busy?.collectAsState()?.value == true || users?.accountsLoading?.collectAsState()?.value == true
    AlertDialog(onDismissRequest = { if (!working) dismiss() },
        modifier = Modifier.testTag("account_choice"),
        title = { Text(tr(if (accounts.isEmpty()) Msg.account_connect_title else Msg.account_choose_title)) },
        text = { Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(tr(if (accounts.isEmpty()) Msg.account_connect_offer else Msg.account_choose_hint))
            accounts.forEach { profile ->
                OutlinedButton({ select(profile.id) }, Modifier.fillMaxWidth().prismFocus().testTag("account_choice_${profile.id}"), enabled = !working) {
                    Text(profileName(profile))
                }
            }
            if (working) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (failed) Text(tr(Msg.profiles_save_failed), color = MaterialTheme.colorScheme.error)
        } },
        confirmButton = { TextButton({
            if (!busy && users?.busy?.value != true && users?.writable == true) {
                busy = true; failed = false
                scope.launch(Dispatchers.Main.immediate) {
                    try { created(users.createYandex().id) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { failed = true }
                    finally { busy = false }
                }
            }
        }, Modifier.prismFocus().testTag("account_choice_connect"), enabled = !working && users?.writable == true) {
            Text(tr(Msg.account_connect))
        } },
        dismissButton = { TextButton(dismiss, Modifier.prismFocus().testTag("account_choice_cancel"), enabled = !working) { Text(trMessage("Отмена")) } })
}
