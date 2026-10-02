package dev.petrov.ymplayer2.shell

import dev.petrov.ymplayer2.localization.*

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.petrov.ymplayer2.core.*
import dev.petrov.ymplayer2.designsystem.prismFocus

@Composable internal fun FoldersScreen(state: LibrarySnapshot, add: (Source) -> Unit, refresh: () -> Unit, forget: (String) -> Unit, pickerIssue: String?) {
    LazyColumn(Modifier.fillMaxSize().testTag("folders_list"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Text(tr(Msg.msg_8f458964546a), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
        item { Text(tr(Msg.msg_af9d1711a669)) }
        item { Button({ add(Source.LOCAL) }, Modifier.prismFocus().testTag("add_local"), enabled = !state.scanning) { Text(tr(Msg.msg_ae407aec2e14)) } }
        item { OutlinedButton({ add(Source.USB) }, Modifier.prismFocus().testTag("add_usb"), enabled = !state.scanning) { Text(tr(Msg.msg_b583c0b5f77e)) } }
        if (state.scanning || !state.ready) item { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(tr(Msg.msg_c722f8b4c671)) }
        (pickerIssue ?: state.issue)?.let { issue -> item { Text(issue, color = MaterialTheme.colorScheme.error) } }
        if (state.roots.isNotEmpty()) item { OutlinedButton(refresh, Modifier.prismFocus().testTag("refresh_folders"), enabled = !state.scanning) { Text(tr(Msg.msg_9216ace8c045)) } }
        items(state.roots, key = LibraryRoot::uri) { root ->
            Surface(shape = MaterialTheme.shapes.medium) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(root.name, fontWeight = FontWeight.Bold)
                    Text(tr(Msg.msg_60bbcc5198aa, trMessage(root.source.label), if (state.tracks.isEmpty()) root.trackCount else state.tracks.count { it.rootId == root.uri }), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    root.issue?.let { Text(trMessage(it), color = MaterialTheme.colorScheme.error) }
                    TextButton({ forget(root.uri) }, Modifier.prismFocus(), enabled = !state.scanning) { Text(tr(Msg.msg_aba2a8a3d61d)) }
                }
            }
        }
        item { Text(tr(Msg.msg_70c88673f5a8), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
