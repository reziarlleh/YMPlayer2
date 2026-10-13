package dev.petrov.ymplayer2.offline

import android.content.Context
import android.net.Uri
import android.os.StatFs
import android.provider.DocumentsContract as Docs
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption.*

/** File and SAF operations for an app-owned cache or an explicitly selected export directory. */
internal class StorageNode private constructor(val context: Context, private val local: File?,
    private var document: Uri?, val parentFile: StorageNode?, val name: String) {
    private var children: MutableMap<String, StorageNode>? = null
    val uri: Uri get() = local?.let(Uri::fromFile) ?: resolve() ?: throw IOException("Storage entry missing")
    val path: String get() = local?.absolutePath ?: uri.toString()
    private fun resolve(): Uri? {
        if (document == null) document = parentFile?.listFiles()?.firstOrNull { it.name == name }?.document
        return document
    }
    private fun field(column: String): Any? = resolve()?.let { u ->
        context.contentResolver.query(u, arrayOf(column), null, null, null)?.use { c ->
            if (!c.moveToFirst() || c.isNull(0)) null else if (c.getType(0) == android.database.Cursor.FIELD_TYPE_INTEGER) c.getLong(0) else c.getString(0)
        }
    }
    fun exists(): Boolean = local?.exists() ?: runCatching { field(Docs.Document.COLUMN_DOCUMENT_ID) != null }.getOrDefault(false)
    val isDirectory: Boolean get() = local?.isDirectory ?: (field(Docs.Document.COLUMN_MIME_TYPE) == Docs.Document.MIME_TYPE_DIR)
    val isFile: Boolean get() = local?.isFile ?: (exists() && !isDirectory)
    fun length(): Long = local?.length() ?: ((field(Docs.Document.COLUMN_SIZE) as? Long) ?: 0)
    fun lastModified(): Long = local?.lastModified() ?: ((field(Docs.Document.COLUMN_LAST_MODIFIED) as? Long) ?: 0)
    fun currentName(): String? = local?.takeIf(File::exists)?.name ?: if (local == null) field(Docs.Document.COLUMN_DISPLAY_NAME) as? String else null
    fun child(name: String): StorageNode {
        require(name.isNotBlank() && '/' !in name && '\\' !in name && name != "." && name != "..")
        return children?.get(name) ?: StorageNode(context, local?.let { File(it, name) }, null, this, name)
    }
    fun listFiles(): List<StorageNode>? {
        if (local != null) return local.listFiles()?.map { file(context, it) }
        val directory = resolve() ?: return null
        children?.let { return it.values.toList() }
        val tree = Docs.buildChildDocumentsUriUsingTree(directory, Docs.getDocumentId(directory))
        val items = mutableMapOf<String, StorageNode>()
        context.contentResolver.query(tree, arrayOf(Docs.Document.COLUMN_DOCUMENT_ID, Docs.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(1)
                items[name] = StorageNode(context, null, Docs.buildDocumentUriUsingTree(directory, c.getString(0)), this, name)
            }
        } ?: throw IOException("Cannot list storage")
        children = items
        return items.values.toList()
    }
    fun mkdirs(): Boolean {
        if (local != null) return local.isDirectory || local.mkdirs()
        if (exists()) return isDirectory
        val parent = parentFile ?: return false
        if (!parent.mkdirs()) return false
        document = Docs.createDocument(context.contentResolver, parent.uri, Docs.Document.MIME_TYPE_DIR, name)
        parent.children?.set(name, this)
        return document != null
    }
    fun create(): StorageNode {
        if (local != null) { parentFile?.mkdirs(); if (!local.exists() && !local.createNewFile()) throw IOException("Cannot create file"); return this }
        if (exists()) return this
        val parent = parentFile ?: throw IOException("Storage parent missing")
        if (!parent.mkdirs()) throw IOException("Cannot create directory")
        val extension = name.substringAfterLast('.', "").lowercase(java.util.Locale.ROOT)
        val mime = when (extension) {
            "json" -> "application/json"
            "mp3" -> "audio/mpeg"
            else -> android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "application/octet-stream"
        }
        document = Docs.createDocument(context.contentResolver, parent.uri, mime, name)
            ?: throw IOException("Cannot create storage entry")
        if (field(Docs.Document.COLUMN_DISPLAY_NAME) != name) {
            Docs.deleteDocument(context.contentResolver, document!!); document = null
            throw IOException("Storage changed the file name")
        }
        parent.children?.set(name, this)
        return this
    }
    fun inputStream(): InputStream = local?.inputStream() ?: context.contentResolver.openInputStream(uri) ?: throw IOException("Cannot read storage")
    fun outputStream(): OutputStream {
        create()
        return local?.outputStream() ?: context.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("Cannot write storage")
    }
    fun readText(): String = inputStream().bufferedReader().use { it.readText() }
    fun writeBytes(bytes: ByteArray) = outputStream().use { it.write(bytes) }
    fun delete(): Boolean {
        val removed = local?.delete() ?: (!exists() || Docs.deleteDocument(context.contentResolver, uri))
        if (removed) { parentFile?.children?.remove(name); document = null }
        return removed
    }
    fun deleteRecursively(): Boolean {
        if (!exists()) return true
        if (local != null) return local.deleteRecursively()
        return delete() // SAF provider owns recursive directory deletion; only our own cache directory is passed.
    }
    fun replace(temp: StorageNode) {
        if (local != null && temp.local != null) {
            Files.move(temp.local.toPath(), local.toPath(), ATOMIC_MOVE, REPLACE_EXISTING); return
        }
        val parent = parentFile ?: throw IOException("Storage parent missing")
        val backup = parent.child(name + ".backup")
        if (backup.exists()) {
            if (!exists()) backup.rename(name) else backup.delete()
            document = parent.child(name).resolve()
        }
        var old: StorageNode? = null
        try {
            if (exists()) old = rename(name + ".backup")
            val published = temp.rename(name)
            document = published.document
            parent.children?.set(name, this)
            old?.delete()
        } catch (e: Exception) {
            if (!exists()) old?.let { restored -> document = restored.rename(name).document }
            throw e
        }
    }
    fun rename(next: String): StorageNode {
        val parent = parentFile ?: throw IOException("Storage parent missing")
        val renamed = if (local != null) {
            val target = File(local.parentFile, next)
            Files.move(local.toPath(), target.toPath()); file(context, target)
        } else StorageNode(context, null, Docs.renameDocument(context.contentResolver, uri, next)
            ?: throw IOException("Storage does not support rename"), parent, next)
        parent.children?.remove(name); parent.children?.set(next, renamed)
        document = null
        return renamed
    }
    val usableSpace: Long get() = local?.let { f -> generateSequence(f) { it.parentFile }.firstOrNull { it.exists() }?.usableSpace ?: 0 }
        ?: availableBytes(context, uri)
    companion object {
        fun file(context: Context, file: File): StorageNode = StorageNode(context, file, null, file.parentFile?.let { file(context, it) }, file.name)
        fun tree(context: Context, uri: Uri): StorageNode {
            val document = if (Docs.isDocumentUri(context, uri)) uri else Docs.buildDocumentUriUsingTree(uri, Docs.getTreeDocumentId(uri))
            return StorageNode(context, null, document, null, "")
        }
        fun temporary(parent: StorageNode, prefix: String, extension: String = ".part"): StorageNode = parent.child(prefix + java.util.UUID.randomUUID() + extension).create()
        fun availableBytes(context: Context, uri: Uri): Long {
            val id = Docs.getTreeDocumentId(uri)
            if (uri.authority == "com.android.externalstorage.documents") {
                val volume = id.substringBefore(':')
                val path = if (volume == "primary") android.os.Environment.getExternalStorageDirectory() else File("/storage/$volume")
                runCatching { StatFs(path.absolutePath).availableBytes }.getOrNull()?.let { return it }
            }
            val roots = Docs.buildRootsUri(uri.authority!!)
            context.contentResolver.query(roots, arrayOf(Docs.Root.COLUMN_ROOT_ID, Docs.Root.COLUMN_DOCUMENT_ID, Docs.Root.COLUMN_AVAILABLE_BYTES), null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val document = c.getString(1)
                    if (document != null && (id == document || id.startsWith(document) || id.substringBefore(':') == c.getString(0)) && !c.isNull(2)) {
                        val free = c.getLong(2); if (free >= 0) return free
                    }
                }
            }
            throw IOException("Не удалось определить свободное место. Выберите папку на устройстве или USB.")
        }
    }
}
