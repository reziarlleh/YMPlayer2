package dev.petrov.ymplayer2.shell

import dev.petrov.ymplayer2.localization.*

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.petrov.ymplayer2.designsystem.WideBrandLogo
import dev.petrov.ymplayer2.designsystem.prismFocus

internal const val DONATION_URL = "https://donate.stream/donate_6a60559cd9e35"
internal const val PROJECT_URL = "https://github.com/reziarlleh/YMPlayer2"

@Composable internal fun AboutDialog(version: String, dismiss: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    var browserIssue by remember { mutableStateOf(false) }
    val closeFocus = remember { FocusRequester() }
    fun open(url: String) { browserIssue = runCatching { uriHandler.openUri(url) }.isFailure }
    Dialog(dismiss, DialogProperties(usePlatformDefaultWidth = false)) {
        // Keep the platform window size stable; only its inner card adapts to the content.
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp), contentAlignment = Alignment.Center) {
            Surface(Modifier.widthIn(max = 560.dp).fillMaxWidth().heightIn(max = maxHeight).testTag("about_dialog"), shape = MaterialTheme.shapes.large) {
                BoxWithConstraints {
                    val wide = maxWidth >= 480.dp
                    Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(tr(Msg.msg_eca626bab07b), style = MaterialTheme.typography.headlineSmall)
                        WideBrandLogo(Modifier.width(188.dp).height(35.dp), "YMPlayer2")
                        Text(version, style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("about_version"))
                        val details: @Composable () -> Unit = {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(tr(Msg.msg_0c985ad12096))
                                Text(tr(Msg.msg_4a59253d0fb2), color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(DONATION_URL, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        val qr: @Composable () -> Unit = {
                            Image(painterResource(R.drawable.donate_qr), tr(Msg.msg_ea183e546bb0), Modifier.size(176.dp).testTag("about_qr"))
                        }
                        if (wide) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                            Box(Modifier.weight(1f)) { details() }; qr()
                        } else { details(); Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { qr() } }
                        if (browserIssue) Text(tr(Msg.msg_9debd4812638), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("about_browser_issue"))
                        Button({ open(DONATION_URL) }, Modifier.fillMaxWidth().prismFocus().testTag("about_donate")) { Text(tr(Msg.msg_3b024cce3b59)) }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            OutlinedButton({ open(PROJECT_URL) }, Modifier.prismFocus().testTag("about_github")) { Text("GitHub") }
                            TextButton(dismiss, Modifier.focusRequester(closeFocus).prismFocus().testTag("about_close")) { Text(tr(Msg.msg_a7a4033657e8)) }
                        }
                    }
                }
            }
        }
        val windowFocused = LocalWindowInfo.current.isWindowFocused
        LaunchedEffect(windowFocused) { if (windowFocused) closeFocus.requestFocus() }
    }
}
