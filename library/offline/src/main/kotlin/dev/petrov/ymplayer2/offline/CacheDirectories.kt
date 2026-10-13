package dev.petrov.ymplayer2.offline

import android.content.Context
import android.net.Uri
import dev.petrov.ymplayer2.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import java.io.IOException
import java.security.MessageDigest

internal class CacheDirectories(private val context: Context) {
    private val prefs = context.getSharedPreferences("offline-location", Context.MODE_PRIVATE)
    fun base(): StorageNode = prefs.getString("uri", null)?.let { from(Uri.parse(it)) }
        ?: StorageNode.file(context, File(context.filesDir, "offline-liked"))
    fun from(uri: Uri): StorageNode = when (uri.scheme) {
        "file" -> StorageNode.file(context, File(uri.path!!))
        "content" -> StorageNode.tree(context, uri)
        else -> throw IOException("Unsupported storage")
    }
    fun owner(owner: OfflineOwner): StorageNode {
        recover()
        val hash = MessageDigest.getInstance("SHA-256").digest((owner.profileId + "\u0000" + owner.accountId).toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        return base().child("v1").child(hash)
    }
    fun state() = CacheLocationState(prefs.getString("label", "Внутренняя память приложения")!!,
        prefs.getBoolean("external", false))
    fun identity(): String? = prefs.getString("uri", null)
    /** Private journal holds only generated app-owned roots; recovery never removes the picked parent. */
    private fun recover(strict: Boolean = false) {
        val pending = prefs.getString("pending", null) ?: return
        val active = base().uri.toString()
        val cleanup = if (active == pending) prefs.getString("previous", null) else pending
        val done = runCatching {
            if (cleanup != null && cleanup != active) {
                val node = from(Uri.parse(cleanup))
                if (node.exists() && !node.deleteRecursively()) throw IOException("Cleanup failed")
            }
            prefs.edit().remove("pending").remove("previous").commit()
        }.getOrDefault(false)
        if (!done && strict) throw IOException("Не удалось завершить предыдущий перенос кэша.")
    }
    suspend fun migrate(destination: String?, progress: (Long, Long) -> Unit) {
        recover(strict = true)
        val source = base()
        if (prefs.contains("uri") && !source.isDirectory) throw IOException("Выбранная папка недоступна.")
        val selected = destination?.let { from(Uri.parse(it)) } ?: StorageNode.file(context, context.filesDir)
        if (!selected.isDirectory) throw IOException("Выбранная папка недоступна.")
        if (source.exists()) {
            val old = source.uri; val next = selected.uri
            val nested = if (old.scheme == "file" && next.scheme == "file") {
                val a = File(old.path!!).canonicalFile.toPath(); val b = File(next.path!!).canonicalFile.toPath()
                b.startsWith(a)
            } else if (old.scheme == "content" && next.scheme == "content" && old.authority == next.authority) {
                val a = android.provider.DocumentsContract.getDocumentId(old)
                val b = android.provider.DocumentsContract.getDocumentId(next)
                b == a || b.startsWith("$a/")
            } else false
            if (nested) throw IOException("Выберите папку вне текущего офлайн-кэша.")
        }
        val files = mutableListOf<Pair<List<String>, StorageNode>>()
        suspend fun visit(node: StorageNode, path: List<String>) {
            currentCoroutineContext().ensureActive()
            for (entry in node.listFiles().orEmpty()) {
                if (entry.isDirectory) visit(entry, path + entry.name)
                else if (entry.isFile) files += (path + entry.name) to entry
            }
        }
        if (source.exists()) visit(source, emptyList())
        val required = files.sumOf { it.second.length() }
        val free = selected.usableSpace // Unknown free space is an error, never an optimistic success.
        if (free < required + 32L * 1024 * 1024) throw IOException("Недостаточно свободного места для переноса офлайн-кэша.")
        val rootName = (if (destination == null) "offline-liked-" else "YMPlayer2-offline-") + java.util.UUID.randomUUID()
        val target = selected.child(rootName)
        var switched = false
        try {
            // Save the generated URI before writing bytes, including provider-assigned document IDs.
            if (!target.mkdirs()) throw IOException("Не удалось создать папку кэша.")
            if (!prefs.edit().putString("pending", target.uri.toString()).putString("previous", source.uri.toString()).commit()) throw IOException("Не удалось сохранить папку кэша.")
            var copied = 0L; progress(0, required)
            for ((path, input) in files) {
                currentCoroutineContext().ensureActive()
                var output = target
                for (name in path) output = output.child(name)
                if (!output.parentFile!!.mkdirs()) throw IOException("Cannot create cache directory")
                val sourceHash = MessageDigest.getInstance("SHA-256")
                input.inputStream().use { reader -> output.outputStream().use { writer ->
                    val buffer = ByteArray(128 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = reader.read(buffer); if (count < 0) break
                        if (selected.usableSpace < count + 16L * 1024 * 1024) throw IOException("Недостаточно свободного места для переноса офлайн-кэша.")
                        writer.write(buffer, 0, count); sourceHash.update(buffer, 0, count)
                        copied += count; progress(copied, required)
                    }
                } }
                val targetHash = MessageDigest.getInstance("SHA-256")
                output.inputStream().use { reader -> val buffer = ByteArray(128 * 1024)
                    while (true) { currentCoroutineContext().ensureActive(); val count = reader.read(buffer); if (count < 0) break; targetHash.update(buffer, 0, count) }
                }
                if (output.length() != input.length() || !sourceHash.digest().contentEquals(targetHash.digest())) throw IOException("Проверка перенесённого файла не пройдена.")
            }
            currentCoroutineContext().ensureActive()
            val label = if (destination == null) "Внутренняя память приложения" else {
                val picked = Uri.parse(destination)
                if (picked.scheme == "file") picked.path.orEmpty() else android.provider.DocumentsContract.getTreeDocumentId(picked)
            }
            withContext(NonCancellable) {
                if (!prefs.edit().putString("uri", target.uri.toString()).putString("label", label).putBoolean("external", destination != null).commit()) throw IOException("Не удалось сохранить папку кэша.")
                switched = true
                // Retain the journal when cleanup fails: the next IO access retries it.
                if (source.exists() && !source.deleteRecursively()) throw IOException("Кэш перенесён. Старую папку не удалось удалить.")
                prefs.edit().remove("pending").remove("previous").commit()
            }
        } finally {
            if (!switched && runCatching { target.deleteRecursively() }.getOrDefault(false)) {
                prefs.edit().remove("pending").remove("previous").commit()
            }
        }
    }
}

class CacheLocationController(private val store: LikedFileStore, private val offline: OfflineMusic,
    private val scope: CoroutineScope, private val otherTransfer: () -> Boolean = { false }) : CacheLocationAccess {
    private val mutable = MutableStateFlow(store.locationState())
    override val state = mutable.asStateFlow()
    private var job: Job? = null
    override fun move(destination: String?) {
        if (mutable.value.moving || otherTransfer()) return
        offline.cancel()
        mutable.value = mutable.value.copy(moving = true, copiedBytes = 0, totalBytes = 0, message = "Подготовка переноса кэша…")
        job = scope.launch {
            val initial = store.locationIdentity()
            var message: String
            try {
                store.move(destination) { done, total -> mutable.value = mutable.value.copy(copiedBytes = done, totalBytes = total, message = "Перенос офлайн-кэша…") }
                message = "Офлайн-кэш перенесён."
            } catch (e: CancellationException) { message = if (store.locationIdentity() != initial) "Офлайн-кэш перенесён." else "Перенос отменён. Исходный кэш сохранён." }
            catch (e: Exception) { message = if (store.locationIdentity() != initial) "Кэш перенесён. Старую папку не удалось удалить." else e.message?.takeIf { it in setOf(
                "Выбранная папка недоступна.", "Выберите папку вне текущего офлайн-кэша.",
                "Недостаточно свободного места для переноса офлайн-кэша.", "Не удалось создать папку кэша.",
                "Проверка перенесённого файла не пройдена.", "Не удалось сохранить папку кэша.",
                "Кэш перенесён. Старую папку не удалось удалить.", "Не удалось завершить предыдущий перенос кэша.",
                "Не удалось определить свободное место. Выберите папку на устройстве или USB.") }
                ?: "Не удалось перенести кэш. Исходная папка сохранена." }
            finally { offline.reloadCache() }
            mutable.value = store.locationState().copy(message = message)
        }
    }
    override fun cancel() { job?.cancel() }
    fun report(message: String) { mutable.value = mutable.value.copy(message = message) }
}
