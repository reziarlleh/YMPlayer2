package dev.petrov.ymplayer2

import android.content.Context
import android.os.Environment
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.petrov.ymplayer2.localization.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Optional read-only folder choice for legacy head units with an incomplete SAF provider. */
@Composable internal fun MountedFolderPicker(close: () -> Unit, systemPicker: () -> Unit, choose: (File) -> Unit) {
    val context = LocalContext.current
    var selected by remember { mutableStateOf<File?>(null) }
    var boundary by remember { mutableStateOf<File?>(null) }
    var folders by remember { mutableStateOf<List<File>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var unavailable by remember { mutableStateOf(false) }
    LaunchedEffect(selected) {
        loading = true
        val listing = withContext(Dispatchers.IO) {
            runCatching {
                selected?.listFiles()?.filter { it.isDirectory && it.canonicalFile.toPath().startsWith(boundary!!.toPath()) }
                    ?: if (selected == null) mountedRoots(context) else error("Unreadable folder")
            }.getOrNull()
        }
        folders = listing.orEmpty().sortedBy { it.name.lowercase() }
        unavailable = listing == null
        loading = false
    }
    AlertDialog(onDismissRequest = close, title = { Text(trMessage("Папка на накопителе")) }, text = {
        Column {
            Text(selected?.path ?: trMessage("Выберите накопитель"))
            if (loading) CircularProgressIndicator()
            else if (unavailable || selected == null && folders.isEmpty()) Text(trMessage("Накопитель недоступен. Подключите его или используйте системный выбор папки."))
            LazyColumn(Modifier.heightIn(max = 300.dp)) {
                if (selected != null) item {
                    TextButton(onClick = { selected = if (selected == boundary) null else selected!!.parentFile }) { Text(trMessage("На уровень вверх")) }
                }
                items(folders, key = { it.path }) { folder ->
                    TextButton(modifier = Modifier.fillMaxWidth().testTag("mounted_folder_${folder.name}"), onClick = {
                        if (selected == null) boundary = folder.canonicalFile
                        selected = folder.canonicalFile
                    }) { Text(folder.name) }
                }
            }
            TextButton(onClick = systemPicker) { Text(trMessage("Системный выбор папки")) }
        }
    }, confirmButton = {
        TextButton(enabled = selected != null && !loading && !unavailable, onClick = { selected?.let(choose) }, modifier = Modifier.testTag("mounted_folder_select")) {
            Text(trMessage("Выбрать эту папку"))
        }
    }, dismissButton = { TextButton(onClick = close) { Text(trMessage("Отмена")) } })
}

@Suppress("DEPRECATION")
internal fun mountedRoots(context: Context): List<File> {
    val candidates = mutableListOf(Environment.getExternalStorageDirectory())
    context.getExternalFilesDirs(null).filterNotNull().forEach { directory ->
        val path = directory.path.substringBefore("/Android/")
        candidates.add(File(path))
    }
    // Public mount aliases also cover volumes not returned by getExternalFilesDirs on some HU.
    candidates.addAll(File("/storage").listFiles().orEmpty().filter { it.name !in setOf("emulated", "self") })
    candidates.addAll(File("/mnt").listFiles().orEmpty().filter { it.name.startsWith("usb", ignoreCase = true) })
    return candidates.filter { it.isDirectory && it.canRead() }.map { it.canonicalFile }.distinctBy { it.path }
}
