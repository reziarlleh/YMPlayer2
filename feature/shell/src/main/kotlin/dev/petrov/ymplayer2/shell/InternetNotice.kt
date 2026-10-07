package dev.petrov.ymplayer2.shell

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import dev.petrov.ymplayer2.core.*
import dev.petrov.ymplayer2.designsystem.prismFocus
import dev.petrov.ymplayer2.localization.trMessage

@Composable internal fun InternetNotice(connection: InternetConnection?, onReconnect: (Boolean) -> Unit) {
    if (connection == null) return
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val reconnect by rememberUpdatedState(onReconnect)
    val check = remember(connection, lifecycle) { InternetCheck(connection, scope, {
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) reconnect(it)
    }) }
    DisposableEffect(check) { onDispose(check::close) }
    val status by check.state.collectAsStateWithLifecycle()
    if (status != InternetStatus.CONNECTED) Column(Modifier.fillMaxWidth().padding(12.dp).testTag("internet_notice")) {
        if (status == InternetStatus.WAITING) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("internet_waiting"))
        else {
            Text(trMessage("Отсутствует интернет"), color = MaterialTheme.colorScheme.error)
            OutlinedButton(check::retry, Modifier.prismFocus().testTag("internet_retry")) { Text(trMessage("Повторить подключение")) }
        }
    }
}
