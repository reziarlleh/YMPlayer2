package dev.petrov.ymplayer2.shell

import dev.petrov.ymplayer2.localization.*

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.petrov.ymplayer2.core.*
import dev.petrov.ymplayer2.designsystem.prismFocus
import dev.petrov.ymplayer2.designsystem.SkinIcon
import dev.petrov.ymplayer2.designsystem.skin.*

@Composable internal fun AudioQualityScreen(preferences: AudioQualityPreferences) {
    val state by preferences.state.collectAsStateWithLifecycle()
    var editingCache by remember { mutableStateOf<Boolean?>(null) }
    LazyColumn(Modifier.fillMaxSize().testTag("quality_list"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Text(tr(Msg.msg_995011f87d4b), style = MaterialTheme.typography.headlineSmall) }
        item { Text(tr(Msg.msg_3e38544b4537)) }
        item { Text(tr(Msg.msg_01fc82460086), style = MaterialTheme.typography.titleMedium) }
        item { OutlinedButton({ editingCache = false }, Modifier.fillMaxWidth().prismFocus().testTag("quality_stream")) { Text(trMessage(state.stream.label)) } }
        item { Text(tr(Msg.msg_36cf98277e5f), style = MaterialTheme.typography.bodyMedium) }
        item { Text(tr(Msg.msg_e30fc8a5eb01), style = MaterialTheme.typography.titleMedium) }
        item { OutlinedButton({ editingCache = true }, Modifier.fillMaxWidth().prismFocus().testTag("quality_cache")) { Text(trMessage(state.cache.label)) } }
        item { Text(tr(Msg.msg_9887b0bfa666), style = MaterialTheme.typography.bodyMedium) }
        item { Text(tr(Msg.msg_69294d8ce6a4)) }
        item { Text(tr(Msg.msg_c90dd531a092)) }
    }
    editingCache?.let { cache ->
        val current = if (cache) state.cache else state.stream
        AlertDialog(onDismissRequest = { editingCache = null }, title = { Text(if (cache) tr(Msg.msg_2718e917ffb3) else tr(Msg.msg_a5e1aeda4ccc)) },
            text = {
                LazyColumn(Modifier.heightIn(max = 320.dp).testTag("quality_options"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(AudioQuality.entries, key = AudioQuality::key) { option ->
                        Surface(onClick = {
                            if (cache) preferences.setCache(option) else preferences.setStream(option)
                            editingCache = null
                        }, modifier = Modifier.fillMaxWidth().prismFocus().testTag("quality_option_${option.name}").semantics {
                            selected = option == current; role = Role.RadioButton
                        }, shape = MaterialTheme.shapes.medium,
                            color = if (option == current) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface) {
                            Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                SkinIcon(if (option == current) UiIcon.CHOICE_ON else UiIcon.CHOICE_OFF, null)
                                Text(trMessage(option.label), Modifier.weight(1f))
                            }
                        }
                    }
                }
            }, confirmButton = { TextButton({ editingCache = null }) { Text(tr(Msg.msg_a7a4033657e8)) } })
    }
}
