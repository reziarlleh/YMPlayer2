package dev.petrov.ymplayer2

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract as Docs
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.provider.DocumentsContractCompat
import dev.petrov.ymplayer2.designsystem.prismFocus
import dev.petrov.ymplayer2.localization.trMessage

internal data class AllowedFolder(val uri: String, val name: String, val connected: Boolean, val recent: Boolean)

internal fun Context.rememberExportFolderAccess(uri: Uri) {
    val grant = contentResolver.persistedUriPermissions.firstOrNull { it.uri == uri }
    if (grant?.isReadPermission != true || grant.isWritePermission != true)
        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
}

/** Query the live provider as well as the saved grant. Removed USB volumes drop out of the list. */
internal fun Context.allowedFolders(writable: Boolean, connected: Set<String>, recent: String?): List<AllowedFolder> =
    contentResolver.persistedUriPermissions.mapNotNull { grant ->
        if (!grant.isReadPermission || writable && !grant.isWritePermission || !DocumentsContractCompat.isTreeUri(grant.uri))
            return@mapNotNull null
        val tree = grant.uri.toString()
        runCatching {
            val id = Docs.getTreeDocumentId(grant.uri)
            val document = Docs.buildDocumentUriUsingTree(grant.uri, id)
            contentResolver.query(document, arrayOf(Docs.Document.COLUMN_DISPLAY_NAME, Docs.Document.COLUMN_MIME_TYPE,
                Docs.Document.COLUMN_FLAGS), null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst() || cursor.getString(1) != Docs.Document.MIME_TYPE_DIR ||
                    writable && (cursor.getInt(2) and Docs.Document.FLAG_DIR_SUPPORTS_CREATE) == 0) return@use null
                val name = cursor.getString(0)?.takeIf { it.isNotBlank() } ?: id
                val label = if (grant.uri.authority == "com.android.externalstorage.documents" && id != name)
                    "$name ($id)" else name
                AllowedFolder(tree, label, !writable && tree in connected, tree == recent)
            }
        }.getOrNull()
    }.distinctBy(AllowedFolder::uri).sortedWith(compareByDescending(AllowedFolder::recent).thenBy { it.name.lowercase() })

@Composable internal fun AllowedFolderDialog(folders: List<AllowedFolder>, select: (String) -> Unit,
    another: () -> Unit, dismiss: () -> Unit) {
    AlertDialog(onDismissRequest = dismiss, modifier = Modifier.testTag("allowed_folders"),
        title = { Text(trMessage("Разрешённые папки")) },
        text = {
            LazyColumn(Modifier.heightIn(max = 260.dp)) {
                items(folders, key = AllowedFolder::uri) { folder ->
                    TextButton({ if (folder.connected) dismiss() else select(folder.uri) },
                        Modifier.fillMaxWidth().prismFocus().testTag("allowed_folder_choice")) {
                        Column {
                            Text(folder.name)
                            if (folder.connected) Text(trMessage("Уже подключена"), style = MaterialTheme.typography.bodySmall)
                            if (folder.recent) Text(trMessage("Последняя папка экспорта"), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(another, Modifier.prismFocus().testTag("another_folder")) { Text(trMessage("Другая папка")) } },
        dismissButton = { TextButton(dismiss, Modifier.prismFocus()) { Text(trMessage("Закрыть")) } })
}
