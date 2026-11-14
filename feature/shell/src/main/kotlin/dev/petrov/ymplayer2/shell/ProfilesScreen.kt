package dev.petrov.ymplayer2.shell

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.petrov.ymplayer2.core.Profile
import dev.petrov.ymplayer2.core.UserProfiles
import dev.petrov.ymplayer2.designsystem.prismFocus
import dev.petrov.ymplayer2.designsystem.SkinIcon
import dev.petrov.ymplayer2.designsystem.skin.UiIcon
import dev.petrov.ymplayer2.localization.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable internal fun ProfilesScreen(profiles: List<Profile>, current: String, account: (() -> Unit)? = null,
    users: UserProfiles? = null, created: ((String) -> Unit)? = null, select: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    val managerBusy = users?.busy?.collectAsState()?.value == true
    val working = busy || managerBusy
    var issue by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Profile?>(null) }
    val writable = users?.writable == true
    fun mutate(action: suspend () -> Unit) {
        if (busy || users?.busy?.value == true) return
        busy = true; issue = false
        scope.launch(Dispatchers.Main.immediate) {
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                // Types and source locations only: never names, account data or exception messages.
                android.util.Log.e("YM2Profiles", "Profile operation failed: ${error.javaClass.simpleName}; ${error.stackTrace.take(8).joinToString()}")
                issue = true
            }
            finally { busy = false }
        }
    }
    LazyColumn(Modifier.fillMaxSize().testTag("profiles_list"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text(tr(Msg.msg_30c61037264b), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
        item { Text(tr(Msg.profiles_people_hint), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (users != null && !writable) item { Text(tr(Msg.profiles_read_failed), color = MaterialTheme.colorScheme.error) }
        if (working) item { LinearProgressIndicator(Modifier.fillMaxWidth().testTag("profile_busy")) }
        if (issue) item { Text(tr(Msg.profiles_save_failed), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("profile_error")) }
        if (account != null && profiles.firstOrNull { it.id == current }?.let { !it.isLocal && !it.provisional } == true) item {
            OutlinedButton(account, Modifier.fillMaxWidth().prismFocus().testTag("account_open"), enabled = !working) {
                Text(tr(Msg.msg_43b07d243705, profiles.firstOrNull { it.id == current }?.let(::profileName).orEmpty()))
            }
        }
        items(profiles.filterNot(Profile::provisional), key = Profile::id) { profile ->
            var menu by remember(profile.id) { mutableStateOf(false) }
            Surface(onClick = { select(profile.id) }, Modifier.fillMaxWidth().prismFocus().testTag("profile_${profile.id}"), enabled = !working,
                color = if (current == profile.id) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                shape = MaterialTheme.shapes.medium) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SkinIcon(UiIcon.PROFILE, null)
                    Text(profileName(profile), Modifier.weight(1f), fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (current == profile.id) SkinIcon(UiIcon.CHECK, tr(Msg.msg_954fea171639))
                    if (users != null && !profile.isLocal) Box {
                        IconButton({ menu = true }, Modifier.prismFocus().testTag("profile_menu_${profile.id}"), enabled = writable && !working) {
                            SkinIcon(UiIcon.MORE, tr(Msg.profile_options))
                        }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem(text = { Text(tr(Msg.profile_delete)) }, enabled = profiles.size > 1, onClick = {
                                menu = false; deleting = profile
                            }, modifier = Modifier.prismFocus().testTag("profile_delete_${profile.id}"))
                        }
                    }
                }
            }
        }
        if (users != null) item {
            Button({ mutate {
                val profile = users.createYandex()
                (created ?: select)(profile.id)
            } }, Modifier.fillMaxWidth().prismFocus().testTag("profile_add"), enabled = writable && !working) {
                SkinIcon(UiIcon.ADD, null); Spacer(Modifier.width(8.dp)); Text(tr(Msg.profile_add))
            }
        }
        item { Text(tr(Msg.profile_pause_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
    deleting?.let { profile -> AlertDialog(onDismissRequest = { if (!working) deleting = null },
        title = { Text(tr(Msg.profile_delete_title, profileName(profile))) },
        text = { Column { Text(tr(Msg.profile_delete_hint)); if (issue) Text(tr(Msg.profiles_save_failed), color = MaterialTheme.colorScheme.error) } },
        confirmButton = { TextButton({ mutate { users!!.remove(profile.id); deleting = null } },
            Modifier.prismFocus().testTag("profile_delete_confirm"), enabled = !working) { Text(trMessage("Удалить")) } },
        dismissButton = { TextButton({ deleting = null }, Modifier.prismFocus(), enabled = !working) { Text(trMessage("Отмена")) } }) }
}
