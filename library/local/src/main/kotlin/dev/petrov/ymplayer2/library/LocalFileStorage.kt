package dev.petrov.ymplayer2.library

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract as Documents
import android.provider.DocumentsContract.Document as Doc
import dev.petrov.ymplayer2.core.*
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** Low-level operations are restricted to registered, currently accessible music trees. */
internal class LocalFileStorage(private val resolver: ContentResolver, private val roots: () -> List<LibraryRoot>) {
    private val columns = arrayOf(Doc.COLUMN_DOCUMENT_ID, Doc.COLUMN_DISPLAY_NAME, Doc.COLUMN_MIME_TYPE,
        Doc.COLUMN_SIZE, Doc.COLUMN_LAST_MODIFIED, Doc.COLUMN_FLAGS)

    fun root(library: LibraryRoot): MediaFileEntry {
        val tree = Uri.parse(library.uri)
        val uri = if (tree.scheme == "file") Uri.fromFile(File(requireNotNull(tree.path)).canonicalFile)
            else Documents.buildDocumentUriUsingTree(tree, Documents.getTreeDocumentId(tree))
        val entry = MediaFileEntry(uri.toString(), library.uri, null, library.name, library.name, library.source, true,
            false, false, false, false, null, 0, 0)
        return try { stat(entry) } catch (_: Exception) { entry }
    }

    fun stat(entry: MediaFileEntry): MediaFileEntry {
        val root = roots().first { it.uri == entry.rootUri }
        val uri = Uri.parse(entry.uri)
        if (uri.scheme == "file") {
            val f = file(entry)
            check(f.exists() && f.canRead())
            return mounted(root, f, entry.parentUri, entry.path)
        }
        val tree = Uri.parse(root.uri)
        check(uri.authority == tree.authority && Documents.getTreeDocumentId(uri) == Documents.getTreeDocumentId(tree))
        val grant = resolver.persistedUriPermissions.first { it.uri == tree && it.isReadPermission }
        return resolver.query(uri, columns, null, null, null)?.use { c ->
            check(c.moveToFirst())
            document(c, root, grant.isWritePermission, entry.parentUri, entry.path.substringBeforeLast(" / ", ""))
        } ?: throw IOException("Document unavailable")
    }

    fun list(folder: MediaFileEntry, forMutation: Boolean = false): List<MediaFileEntry> {
        val root = roots().first { it.uri == folder.rootUri }
        check(folder.directory && folder.available)
        val uri = Uri.parse(folder.uri)
        if (uri.scheme == "file") {
            val dir = file(folder)
            val files = dir.listFiles() ?: throw IOException("Unreadable folder")
            return files.mapNotNull { child ->
                val protected = child.canonicalFile != child.absoluteFile ||
                    (dir.name == "Android" && child.name in setOf("data", "obb")) || (!child.isDirectory && !child.isFile)
                if (protected) {
                    check(!forMutation) { "Folder contains protected entries" }
                    null
                } else mounted(root, child, folder.uri, "${folder.path} / ${child.name}")
            }
        }
        val tree = Uri.parse(root.uri)
        check(uri.authority == tree.authority && Documents.getTreeDocumentId(uri) == Documents.getTreeDocumentId(tree))
        val grant = resolver.persistedUriPermissions.first { it.uri == tree && it.isReadPermission }
        val listing = Documents.buildChildDocumentsUriUsingTree(tree, Documents.getDocumentId(uri))
        return resolver.query(listing, columns, null, null, null)?.use { c ->
            buildList {
                while (c.moveToNext()) add(document(c, root, grant.isWritePermission, folder.uri, folder.path))
                check(!c.extras.getBoolean(Documents.EXTRA_LOADING, false) && c.extras.getString(Documents.EXTRA_ERROR) == null)
            }
        } ?: throw IOException("Unreadable folder")
    }

    private fun document(c: Cursor, root: LibraryRoot, write: Boolean, parent: String?, parentPath: String): MediaFileEntry {
        val uri = Documents.buildDocumentUriUsingTree(Uri.parse(root.uri), c.getString(0))
        val name = c.getString(1) ?: throw IOException("Missing document name")
        val mime = c.getString(2).orEmpty()
        val directory = mime == Doc.MIME_TYPE_DIR
        val flags = c.getInt(5)
        fun supports(flag: Int) = write && (flags and flag != 0)
        val entry = MediaFileEntry(uri.toString(), root.uri, parent, name, if (parent == null) root.name else "$parentPath / $name",
            root.source, directory, true, directory && supports(Doc.FLAG_DIR_SUPPORTS_CREATE),
            parent != null && supports(Doc.FLAG_SUPPORTS_RENAME), parent != null && supports(Doc.FLAG_SUPPORTS_DELETE),
            if (!directory && supportedLocalAudio(name, mime)) localTrackId(uri) else null,
            if (c.isNull(3)) -1 else c.getLong(3), c.getLong(4))
        return entry.copy(fileKey = identity(entry))
    }

    private fun mounted(root: LibraryRoot, f: File, parent: String?, path: String): MediaFileEntry {
        val uri = Uri.fromFile(f)
        val canChange = parent != null && f.parentFile?.canWrite() == true
        return MediaFileEntry(uri.toString(), root.uri, parent, if (parent == null) root.name else f.name, path,
            root.source, f.isDirectory, f.canRead(), f.isDirectory && f.canWrite(), canChange, canChange,
            if (f.isFile && supportedLocalAudio(f.name, "")) localTrackId(uri) else null, f.length(), f.lastModified(), f.path)
    }

    private fun file(entry: MediaFileEntry): File {
        val root = roots().first { it.uri == entry.rootUri }
        val rootUri = Uri.parse(root.uri)
        require(rootUri.scheme == "file")
        val boundary = File(requireNotNull(rootUri.path)).canonicalFile
        val candidate = File(requireNotNull(Uri.parse(entry.uri).path)).absoluteFile
        require(candidate.canonicalFile == candidate && insideMusicRoot(candidate, boundary))
        return candidate
    }

    fun physicalPath(entry: MediaFileEntry): File? {
        val uri = Uri.parse(entry.uri)
        if (uri.scheme == "file") return file(entry)
        if (uri.authority != "com.android.externalstorage.documents") return null
        val id = Documents.getDocumentId(uri)
        if (':' !in id) return null
        val volume = id.substringBefore(':')
        val base = if (volume.equals("primary", true)) Environment.getExternalStorageDirectory() else File("/storage", volume)
        return File(base, id.substringAfter(':')).canonicalFile
    }

    fun identity(entry: MediaFileEntry): String {
        physicalPath(entry)?.let { return it.path }
        val uri = Uri.parse(entry.uri)
        return uri.authority + ":" + Documents.getDocumentId(uri)
    }

    fun parent(entry: MediaFileEntry): MediaFileEntry = stat(entry.copy(uri = requireNotNull(entry.parentUri),
        path = entry.path.substringBeforeLast(" / "), parentUri = null))

    data class ChildrenInUse(val names: MutableSet<String>, val uris: MutableSet<String>)

    fun childrenInUse(folder: MediaFileEntry): ChildrenInUse {
        val entries = list(folder)
        return ChildrenInUse(entries.mapTo(mutableSetOf()) { it.name.lowercase() }, entries.mapTo(mutableSetOf()) { it.uri })
    }

    fun create(parent: MediaFileEntry, name: String, mime: String, inUse: ChildrenInUse? = null): MediaFileEntry {
        validateName(name)
        val dir = stat(parent)
        check(dir.canCreate)
        val siblings = inUse ?: childrenInUse(dir)
        check(name.lowercase() !in siblings.names) { "Name already exists" }
        val uri = if (Uri.parse(dir.uri).scheme == "file") {
            val f = File(file(dir), name)
            check(if (mime == Doc.MIME_TYPE_DIR) f.mkdir() else f.createNewFile())
            Uri.fromFile(f)
        } else Documents.createDocument(resolver, Uri.parse(dir.uri), mime, name) ?: throw IOException("Create failed")
        check(siblings.uris.add(uri.toString())) { "Provider returned existing document" }
        val created = stat(dir.copy(uri = uri.toString(), parentUri = dir.uri, path = "${parent.path} / $name"))
        siblings.names.add(created.name.lowercase())
        return created
    }

    fun rename(entry: MediaFileEntry, name: String): MediaFileEntry {
        validateName(name)
        check(stat(entry).canRename)
        check(list(parent(entry)).none { it.uri != entry.uri && it.name.equals(name, true) })
        val uri = if (Uri.parse(entry.uri).scheme == "file") {
            val original = file(entry)
            val target = File(original.parentFile, name)
            check(!target.exists() && original.renameTo(target))
            Uri.fromFile(target)
        } else Documents.renameDocument(resolver, Uri.parse(entry.uri), name) ?: throw IOException("Rename failed")
        return stat(entry.copy(uri = uri.toString(), path = entry.path.substringBeforeLast(" / ") + " / " + name))
    }

    fun delete(entry: MediaFileEntry) {
        val actual = stat(entry)
        check(actual.canDelete && actual.directory == entry.directory)
        val uri = Uri.parse(entry.uri)
        check(if (uri.scheme == "file") file(entry).delete() else Documents.deleteDocument(resolver, uri))
    }

    fun mime(entry: MediaFileEntry): String {
        if (entry.directory) return Doc.MIME_TYPE_DIR
        val reported = resolver.getType(Uri.parse(entry.uri))
        return reported?.takeUnless { it == "application/octet-stream" } ?: android.webkit.MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(entry.name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"
    }

    fun input(entry: MediaFileEntry): InputStream = resolver.openInputStream(Uri.parse(entry.uri)) ?: throw IOException("Open failed")
    fun output(entry: MediaFileEntry): OutputStream = resolver.openOutputStream(Uri.parse(entry.uri), "w") ?: throw IOException("Open failed")

    private fun validateName(name: String) {
        require(name.isNotBlank() && name !in setOf(".", "..") && name.none { it.isISOControl() || it in "/\\" })
    }
}
