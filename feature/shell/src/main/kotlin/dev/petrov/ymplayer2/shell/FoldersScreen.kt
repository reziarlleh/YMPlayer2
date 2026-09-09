package dev.petrov.ymplayer2.shell

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
        item { Text("Папки с музыкой", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
        item { Text("Выберите папку в системном окне. Музыка остаётся на своём месте, приложение получает только доступ к чтению.") }
        item { Button({ add(Source.LOCAL) }, Modifier.prismFocus().testTag("add_local"), enabled = !state.scanning) { Text("Добавить с устройства") } }
        item { OutlinedButton({ add(Source.USB) }, Modifier.prismFocus().testTag("add_usb"), enabled = !state.scanning) { Text("Добавить с USB / SD") } }
        if (state.scanning || !state.ready) item { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Читаем аудиофайлы…") }
        (pickerIssue ?: state.issue)?.let { issue -> item { Text(issue, color = MaterialTheme.colorScheme.error) } }
        if (state.roots.isNotEmpty()) item { OutlinedButton(refresh, Modifier.prismFocus().testTag("refresh_folders"), enabled = !state.scanning) { Text("Обновить каталог") } }
        items(state.roots, key = LibraryRoot::uri) { root ->
            Surface(shape = MaterialTheme.shapes.medium) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(root.name, fontWeight = FontWeight.Bold)
                    Text("${root.source.label} · ${state.tracks.count { it.rootId == root.uri }} треков", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    root.issue?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    TextButton({ forget(root.uri) }, Modifier.prismFocus(), enabled = !state.scanning) { Text("Убрать из медиатеки") }
                }
            }
        }
        item { Text("Удаление папки из медиатеки не удаляет файлы. Локальный каталог доступен всем профилям.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
