package dev.petrov.ymplayer2.shell

import dev.petrov.ymplayer2.localization.*

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.petrov.ymplayer2.core.*
import dev.petrov.ymplayer2.designsystem.prismFocus

@Composable internal fun OfflineSettingsScreen(offline: OfflineMusic, start: () -> Unit, account: () -> Unit) {
    val state by offline.state.collectAsStateWithLifecycle()
    var confirmClear by remember(state.owner) { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize().testTag("offline_settings_list"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text(tr(Msg.msg_fd6e72f72d55), style = MaterialTheme.typography.headlineSmall) }
        item { Text(tr(Msg.msg_d65fb9be6155)) }
        item { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(tr(Msg.msg_074cc2ac4332), Modifier.weight(1f))
            Switch(state.enabled, offline::setEnabled, Modifier.prismFocus().testTag("offline_enabled"))
        } }
        item { Text(tr(Msg.msg_17b7c023b762),
            style = MaterialTheme.typography.bodySmall) }
        if (state.owner == null) {
            item { Text(tr(Msg.msg_001dda282241)) }
            item { Button(account, Modifier.prismFocus()) { Text(tr(Msg.msg_612f724ec00f)) } }
        } else {
            if (state.enabled) item { Text(tr(Msg.msg_5e4ea237ce6b, state.tracks.size, state.bytes / (1024 * 1024)), Modifier.testTag("offline_summary")) }
            item { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(tr(Msg.msg_729dc1568fdf), Modifier.weight(1f))
                Switch(state.wifiOnly, offline::setWifiOnly, Modifier.prismFocus().testTag("offline_wifi"), enabled = state.enabled)
            } }
            item {
                if (state.running) OutlinedButton(offline::cancel, Modifier.fillMaxWidth().prismFocus().testTag("offline_cancel")) { Text(tr(Msg.msg_cb4cd68cb5f3)) }
                else Button(start, Modifier.fillMaxWidth().prismFocus().testTag("offline_sync"), enabled = state.enabled && state.ready) { Text(tr(Msg.msg_ca48a1a553cc)) }
            }
            if (state.enabled && (!state.ready || state.running)) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (!state.enabled) item { Text(tr(Msg.msg_94117e81a588), Modifier.testTag("offline_disabled")) }
            state.message?.let { message -> item { Text(trMessage(message), Modifier.testTag("offline_status")) } }
            if (state.audioFailures + state.coverFailures + state.noCover > 0) item {
                Text(tr(Msg.msg_7003237eb243, state.audioFailures, state.coverFailures, state.noCover))
            }
            item { TextButton({ confirmClear = true }, Modifier.fillMaxWidth().prismFocus().testTag("offline_clear"), enabled = state.ready) { Text(tr(Msg.msg_5277d2953e7b)) } }

        }
    }
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false }, title = { Text(tr(Msg.msg_982867f75b81)) },
        text = { Text(tr(Msg.msg_0e04e96eb049)) },
        confirmButton = { TextButton({ confirmClear = false; offline.clear() }, Modifier.testTag("offline_clear_confirm")) { Text(tr(Msg.msg_254732178a10)) } },
        dismissButton = { TextButton({ confirmClear = false }) { Text(tr(Msg.msg_8fbe9b75cbdf)) } })
}
