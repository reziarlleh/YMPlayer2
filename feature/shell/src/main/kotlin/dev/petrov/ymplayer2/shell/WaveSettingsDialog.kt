package dev.petrov.ymplayer2.shell

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.petrov.ymplayer2.core.*
import dev.petrov.ymplayer2.designsystem.prismFocus
import dev.petrov.ymplayer2.localization.*

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun WaveSettingsDialog(settings: WaveSettings, player: PlaybackController, dismiss: () -> Unit) {
    val state by settings.state.collectAsStateWithLifecycle()
    val configuration = LocalConfiguration.current
    val language = configuration.locales[0]?.language ?: "ru"
    LaunchedEffect(state.profileId, state.signedIn, language) { if (state.signedIn) settings.load(language) }
    Dialog(dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        // Keep the native window stable while asynchronous options change the card's size.
        // The same layout is used by AboutDialog for older Android TV compositors.
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().padding(12.dp), contentAlignment = Alignment.Center) {
        Surface(Modifier.widthIn(max = 760.dp).fillMaxWidth()
            .heightIn(max = maxHeight * .85f).testTag("wave_settings_dialog"),
            shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tr(Msg.wave_settings_title), style = MaterialTheme.typography.titleLarge)
                Column(Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(tr(Msg.wave_settings_hint), style = MaterialTheme.typography.bodySmall)
                    if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("wave_settings_loading"))
                    state.issue?.let { Text(trIssue(it), color = MaterialTheme.colorScheme.error)
                        TextButton({ settings.load(language) }, Modifier.prismFocus().testTag("wave_settings_retry")) { Text(tr(Msg.msg_79e35d4d8b5b)) }
                    }
                    state.options?.groups?.forEach { group ->
                        Text(trMessage(group.title), style = MaterialTheme.typography.titleMedium)
                        val selected = state.selected[group.key] ?: group.values.firstOrNull(WaveOption::unspecified)?.seed
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            group.values.forEach { option ->
                                FilterChip(selected == option.seed, { settings.select(group.key, option.seed) },
                                    label = { Text(trMessage(option.title)) },
                                    modifier = Modifier.prismFocus().testTag("wave_setting_${group.key}_${option.seed}"),
                                    enabled = state.signedIn && !state.loading)
                            }
                        }
                    }
                }
                FlowRow(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton({ settings.reset() }, Modifier.prismFocus().testTag("wave_settings_reset"),
                        enabled = state.signedIn && !state.loading) { Text(tr(Msg.wave_settings_reset)) }
                    TextButton(dismiss, Modifier.prismFocus().testTag("wave_settings_close")) { Text(tr(Msg.msg_ef05d57959cf)) }
                    Button({ player.playMyWave(); dismiss() }, Modifier.prismFocus().testTag("wave_settings_play"),
                        enabled = state.signedIn && state.options != null && !state.loading && state.issue == null) { Text(tr(Msg.wave_settings_play)) }
                }
            }
        }
        }
    }
}
