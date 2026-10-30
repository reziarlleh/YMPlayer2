package dev.petrov.ymplayer2.offline

import android.content.Context
import android.util.AtomicFile
import dev.petrov.ymplayer2.core.OfflineOwner
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

/** Disposable app-private metadata, never an integrity proof or the inventory of available audio. */
internal class CatalogMetadataIndex(context: Context) {
    data class Entry(val uri: String, val bytes: Long, val modified: Long, val metadata: JSONObject) {
        fun matches(file: StorageNode.Entry) = modified > 0 && file.modified == modified &&
            bytes == file.bytes && uri == file.node.uri.toString()
    }
    private val directory = File(context.filesDir, "offline-catalog-v1")
    private fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun file(owner: OfflineOwner, location: String?) = AtomicFile(File(File(directory, hash(location.orEmpty())),
        hash(owner.profileId + "\u0000" + owner.accountId) + ".json"))

    fun read(owner: OfflineOwner, location: String?): Map<String, Entry> = synchronized(monitor) { read(file(owner, location)) }
    private fun read(file: AtomicFile): Map<String, Entry> = runCatching {
        if (file.baseFile.length() > 64L * 1024 * 1024) return@runCatching emptyMap()
        val (json, invalidated) = file.openRead().bufferedReader().use { reader ->
            val json = JSONObject(reader.readLine() ?: throw IOException("Empty catalog index"))
            val invalidated = hashSetOf<String>()
            reader.forEachLine { name ->
                require(name.matches(metadataName))
                invalidated += name
            }
            json to invalidated
        }
        require(json.getInt("schema") == 1)
        val rows = json.getJSONArray("entries")
        buildMap {
            for (i in 0 until rows.length()) {
                val row = rows.getJSONObject(i)
                val bytes = row.getLong("bytes"); val modified = row.getLong("modified")
                if (bytes in 1 until 64L * 1024 && modified > 0 && row.getString("name") !in invalidated) put(row.getString("name"),
                    Entry(row.getString("uri"), bytes, modified, row.getJSONObject("metadata")))
            }
        }
    }.getOrDefault(emptyMap())

    fun replace(owner: OfflineOwner, location: String?, entries: Map<String, Entry>, allowed: () -> Boolean) = synchronized(monitor) {
        if (allowed()) write(file(owner, location), entries, allowed)
    }
    fun remove(owner: OfflineOwner, location: String?, names: Set<String>) = synchronized(monitor) {
        val file = file(owner, location)
        val metadataNames = names.filter { it.matches(metadataName) }
        if (metadataNames.isEmpty()) return@synchronized
        // Restore an interrupted AtomicFile write before appending. Each invalidation
        // is durable before its metadata changes; the next catalog compacts the file.
        // No parse/rewrite of N indexed tracks is needed for each like in a full sync.
        try { file.openRead().close() } catch (_: FileNotFoundException) { return@synchronized }
        try {
            FileOutputStream(file.baseFile, true).use { output ->
                output.write(metadataNames.joinToString("\n", "\n").toByteArray())
                output.fd.sync()
            }
        } catch (error: IOException) {
            file.delete()
            if (file.baseFile.exists()) throw IOException("Cannot invalidate offline metadata index", error)
        }
    }
    fun clear(owner: OfflineOwner, location: String?) = synchronized(monitor) { file(owner, location).delete() }
    fun clearLocation(location: String?) = synchronized(monitor) {
        File(directory, hash(location.orEmpty())).listFiles()?.forEach { it.delete() }
    }
    private fun write(file: AtomicFile, entries: Map<String, Entry>, allowed: () -> Boolean) {
        // A missing/unwritable index merely costs a metadata scan next time.
        runCatching {
            val rows = JSONArray()
            entries.forEach { (name, entry) -> rows.put(JSONObject().put("name", name).put("uri", entry.uri)
                .put("bytes", entry.bytes).put("modified", entry.modified).put("metadata", entry.metadata)) }
            val bytes = JSONObject().put("schema", 1).put("entries", rows).toString().toByteArray()
            if (!allowed()) return@runCatching
            file.baseFile.parentFile!!.mkdirs()
            val output = file.startWrite()
            try {
                output.write(bytes)
                if (allowed()) file.finishWrite(output) else file.failWrite(output)
            } catch (error: Exception) { file.failWrite(output); throw error }
        }
    }
    private companion object {
        val monitor = Any()
        val metadataName = Regex("[a-f0-9]{64}\\.json")
    }
}
