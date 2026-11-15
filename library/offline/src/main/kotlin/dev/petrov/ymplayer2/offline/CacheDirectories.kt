package dev.petrov.ymplayer2.offline

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import dev.petrov.ymplayer2.core.*
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID

/** Private bindings select independent roots. Portable passports contain no device-local profile identity. */
internal class CacheDirectories(private val context: Context) {
    private val legacy = context.getSharedPreferences("offline-location", Context.MODE_PRIVATE)
    private val bindings = context.getSharedPreferences("offline-locations-v2", Context.MODE_PRIVATE)
    init {
        // Capture old profile identities once, before later profiles are added. They alone inherit the old shared custom root.
        if (!bindings.contains("legacyProfiles")) runCatching {
            val file = File(context.filesDir, "user-profiles.json")
            if (file.isFile && file.length() in 1..1_048_576) {
                val rows = JSONObject(file.readText()).getJSONArray("profiles")
                val ids = (0 until rows.length()).mapTo(hashSetOf()) { rows.getJSONObject(it).getString("id") }
                if (!bindings.edit().putStringSet("legacyProfiles", ids).commit()) throw IOException("Не удалось сохранить папку кэша.")
            }
        }
    }
    private fun legacyProfile(owner: OfflineOwner) = bindings.getStringSet("legacyProfiles", null)?.contains(owner.profileId) != false
    private fun enabled() = context.getSharedPreferences("offline", Context.MODE_PRIVATE).getBoolean("enabled", true)
    private fun key(owner: OfflineOwner) = hash(owner.profileId + "\u0000" + owner.accountId)
    private fun defaultRoot(owner: OfflineOwner) = StorageNode.file(context, File(context.filesDir, "offline-liked/v1/${key(owner)}"))
    private fun oldBase() = legacy.getString("uri", null)?.let(::from) ?: StorageNode.file(context, File(context.filesDir, "offline-liked"))
    private fun oldRoot(owner: OfflineOwner) = oldBase().child("v1").child(key(owner))
    internal fun from(uri: String): StorageNode = Uri.parse(uri).let {
        when (it.scheme) {
            "file" -> StorageNode.file(context, File(it.path ?: throw IOException("Выбранная папка недоступна.")))
            "content" -> StorageNode.tree(context, it)
            else -> throw IOException("Выбранная папка недоступна.")
        }
    }
    private fun binding(owner: OfflineOwner): JSONObject? = bindings.getString(key(owner), null)?.let(::JSONObject)
    fun identity(owner: OfflineOwner): String? = binding(owner)?.getString("uri")
    fun state(owner: OfflineOwner?): CacheLocationState {
        val row = try { owner?.let(::binding) } catch (_: Exception) {
            return CacheLocationState(owner = owner, message = "Повреждено описание офлайн-кэша.")
        }
        return CacheLocationState(row?.optString("label") ?: "Внутренняя память приложения", row?.optBoolean("external") ?: false, owner = owner)
    }
    private fun allBindings() = bindings.all.values.filterIsInstance<String>().mapNotNull {
        runCatching { JSONObject(it) }.getOrNull()?.takeIf { row -> row.has("profile") && row.has("uri") }
    }
    private fun recoverLegacy() {
        val pending = legacy.getString("pending", null) ?: return
        val active = oldBase().uri.toString()
        val cleanup = if (active == pending) legacy.getString("previous", null) else pending
        if (cleanup != null && cleanup != active) {
            val node = from(cleanup)
            if (allBindings().any { contains(node, from(it.getString("uri"))) }) throw IOException("Не удалось завершить предыдущий перенос кэша.")
            if (node.exists() && !node.deleteRecursively()) throw IOException("Не удалось завершить предыдущий перенос кэша.")
        }
        if (!legacy.edit().remove("pending").remove("previous").commit()) throw IOException("Не удалось сохранить папку кэша.")
    }
    fun owner(owner: OfflineOwner): StorageNode {
        if (enabled()) {
            recoverPending(owner)
            if (legacyProfile(owner)) recoverLegacy() else runCatching { recoverLegacy() }
        }
        binding(owner)?.let { row ->
            val root = from(row.getString("uri"))
            if (!root.isDirectory && row.getBoolean("external")) throw IOException("Выбранная папка недоступна.")
            if (enabled()) ensurePassport(root, owner, row.getString("cacheId"))
            return root
        }
        val previous = if (legacyProfile(owner)) oldRoot(owner) else null
        val adopted = previous?.isDirectory == true
        val root = if (adopted) previous!! else defaultRoot(owner)
        if (!enabled()) return root
        val passport = ensurePassport(root, owner)
        save(owner, root, adopted && legacy.getBoolean("external", false),
            if (adopted) legacy.getString("label", "Внутренняя память приложения")!! else "Внутренняя память приложения", passport)
        return root
    }
    private data class Passport(val id: String, val account: String)
    private fun passport(root: StorageNode): Passport? {
        val file = root.child(PASSPORT)
        if (!file.exists()) return null
        if (!file.isFile || file.length() !in 1..16_384) throw IOException("Повреждено описание офлайн-кэша.")
        val json = try { JSONObject(file.readText()) } catch (_: Exception) { throw IOException("Повреждено описание офлайн-кэша.") }
        if (json.optString("applicationId") != "dev.petrov.ymplayer2") throw IOException("Повреждено описание офлайн-кэша.")
        if (json.optInt("format") != 2) throw IOException("Этот формат кэша требует другой версии YMPlayer2.")
        val id = json.optString("cacheId"); val account = json.optString("accountUid")
        if (!runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false) || account.isBlank() || account.length > 256)
            throw IOException("Повреждено описание офлайн-кэша.")
        return Passport(id, account)
    }
    private fun ensurePassport(root: StorageNode, owner: OfflineOwner, id: String = UUID.randomUUID().toString()): Passport {
        passport(root)?.let { if (it.account != owner.accountId) throw IOException("Этот кэш принадлежит другому аккаунту Яндекса."); return it }
        if (!root.mkdirs()) throw IOException("Не удалось создать папку кэша.")
        val result = Passport(id, owner.accountId)
        val bytes = JSONObject().put("applicationId", "dev.petrov.ymplayer2").put("format", 2)
            .put("cacheId", result.id).put("accountUid", result.account).toString().toByteArray()
        val temp = root.child("$PASSPORT.part")
        temp.writeBytes(bytes)
        if (!temp.inputStream().use { it.readBytes() }.contentEquals(bytes)) throw IOException("Проверка перенесённого файла не пройдена.")
        root.child(PASSPORT).replace(temp)
        return result
    }
    private fun occupied(owner: OfflineOwner, root: StorageNode) = allBindings().any { row ->
        (row.optString("profile") != owner.profileId || row.optString("account") != owner.accountId) &&
            (contains(from(row.getString("uri")), root) || contains(root, from(row.getString("uri"))))
    }
    private fun save(owner: OfflineOwner, root: StorageNode, external: Boolean, label: String, passport: Passport) {
        if (occupied(owner, root)) throw IOException("Эта папка уже используется другим профилем.")
        val value = JSONObject().put("profile", owner.profileId).put("account", owner.accountId)
            .put("uri", root.uri.toString()).put("external", external).put("label", label).put("cacheId", passport.id)
        if (!bindings.edit().putString(key(owner), value.toString()).commit()) throw IOException("Не удалось сохранить папку кэша.")
    }
    internal data class Candidate(val uri: String, val label: String, val legacy: Boolean, val tracks: Int)
    suspend fun inspect(owner: OfflineOwner, destination: String): List<Candidate> = withContext(Dispatchers.IO) {
        val selected = from(destination)
        if (!selected.isDirectory) throw IOException("Выбранная папка недоступна.")
        val result = mutableListOf<Candidate>()
        suspend fun inspectRoot(root: StorageNode) {
            val saved = passport(root)
            if (saved != null) {
                if (saved.account != owner.accountId) {
                    if (root.uri == selected.uri) throw IOException("Этот кэш принадлежит другому аккаунту Яндекса.")
                    return
                }
                if (occupied(owner, root)) {
                    if (root.uri == selected.uri) throw IOException("Эта папка уже используется другим профилем.")
                    return
                }
                val rows = records(root)
                result += Candidate(root.uri.toString(), sample(rows), false, rows.size)
            } else {
                val rows = records(root)
                if (rows.isNotEmpty()) result += Candidate(root.uri.toString(), sample(rows), true, rows.size)
            }
        }
        inspectRoot(selected)
        if (result.isEmpty()) for (entry in selected.listSnapshot().orEmpty().filter { !it.isFile }) {
            currentCoroutineContext().ensureActive()
            if (entry.name == "v1") entry.node.listSnapshot().orEmpty().filter { !it.isFile && it.name.matches(HASH) }.forEach { inspectRoot(it.node) }
            else if (entry.name.startsWith("YMPlayer2-offline-")) {
                inspectRoot(entry.node)
                if (entry.node.child("v1").isDirectory)
                    entry.node.child("v1").listSnapshot().orEmpty().filter { !it.isFile && it.name.matches(HASH) }.forEach { inspectRoot(it.node) }
            }
        }
        result
    }
    private fun sample(rows: Map<String, Pair<JSONObject, StorageNode>>) = rows.values.firstOrNull()?.first?.let {
        listOf(it.optString("artist"), it.optString("title")).filter(String::isNotBlank).joinToString(" — ").take(120)
    }.orEmpty()
    private suspend fun records(root: StorageNode): Map<String, Pair<JSONObject, StorageNode>> {
        val rows = linkedMapOf<String, Pair<JSONObject, StorageNode>>()
        for (entry in root.listSnapshot().orEmpty()) {
            currentCoroutineContext().ensureActive()
            if (!entry.isFile || !entry.name.matches(METADATA) || entry.bytes !in 1..65_535) continue
            runCatching {
                val json = JSONObject(entry.node.readText())
                val id = json.getString("id").removePrefix("yandex:").substringBefore(':')
                if (id.matches(Regex("[A-Za-z0-9_-]{1,256}")) && entry.name == hash(id) + ".json" && json.has("title") && json.has("duration"))
                    rows[id] = json to entry.node
            }
        }
        return rows
    }

    suspend fun migrate(owner: OfflineOwner, destination: String?, candidate: Candidate?, liked: Set<String>?,
        allowed: () -> Boolean, progress: (Long, Long) -> Unit) {
        check(allowed); recoverPending(owner)
        val source = this.owner(owner)
        val selected = destination?.let(::from) ?: StorageNode.file(context, File(context.filesDir, "offline-liked/v2"))
        if (destination == null && !selected.mkdirs() || destination != null && !selected.isDirectory) throw IOException("Выбранная папка недоступна.")
        val existing = candidate?.let { from(it.uri) }
        if (existing != null && contains(source, existing) && contains(existing, source)) return
        if (existing == null && contains(source, selected)) throw IOException("Выберите папку вне текущего офлайн-кэша.")
        if (existing != null && (contains(source, existing) || contains(existing, source))) throw IOException("Выберите папку вне текущего офлайн-кэша.")
        val legacyImport = candidate?.legacy == true
        if (legacyImport && liked == null) throw IOException("Для подключения старого кэша нужен список «Мне нравится».")
        if (existing != null && !legacyImport && passport(existing)?.account != owner.accountId)
            throw IOException("Этот кэш принадлежит другому аккаунту Яндекса.")
        val fresh = existing == null || legacyImport
        val parent = if (legacyImport) from(destination!!) else selected
        if (fresh && allBindings().any { row ->
                (row.optString("profile") != owner.profileId || row.optString("account") != owner.accountId) && contains(from(row.getString("uri")), parent) })
            throw IOException("Эта папка уже используется другим профилем.")
        if (!fresh && occupied(owner, existing!!)) throw IOException("Эта папка уже используется другим профилем.")
        val target = if (fresh) parent.child("YMPlayer2-offline-${UUID.randomUUID()}") else existing!!
        val files = linkedMapOf<String, StorageNode>()
        val selectedIds = hashSetOf<String>()
        val sourceRows = records(source)
        val sourcePrefixes = sourceRows.keys.mapTo(hashSetOf(), ::hash)
        if (source.listSnapshot().orEmpty().any { it.isFile && it.name.matches(Regex("[a-f0-9]{64}\\.audio")) && it.name.substringBefore('.') !in sourcePrefixes })
            throw IOException("Проверка перенесённого файла не пройдена.")
        for (root in listOfNotNull(source, existing.takeIf { legacyImport })) for ((id, _) in if (root === source) sourceRows else records(root)) {
            if (legacyImport && id !in liked!!) continue
            val prefix = hash(id)
            if (id in selectedIds) continue
            if (!complete(root.child("$prefix.audio"))) {
                if (root === source) throw IOException("Проверка перенесённого файла не пройдена.")
                continue
            }
            selectedIds += id
            for (suffix in listOf(".json", ".audio", ".cover")) {
                val part = root.child(prefix + suffix)
                if (complete(part)) files[part.name] = part
            }
        }
        val prefixes = files.keys.mapTo(hashSetOf()) { it.substringBefore('.') }
        val ready = prefixes.filterTo(hashSetOf()) { complete(target.child("$it.json")) && complete(target.child("$it.audio")) }
        // Reject obvious capacity failures using one metadata inventory, before reading any full audio body.
        val apparentCovers = files.keys.filterTo(hashSetOf()) { it.endsWith(".cover") && complete(target.child(it)) }
        val estimated = files.filterKeys { name ->
            if (name.endsWith(".cover")) name !in apparentCovers else name.substringBefore('.') !in ready
        }.values.sumOf { it.length() }
        if (parent.usableSpace < estimated + 32L * 1024 * 1024) throw IOException("Недостаточно свободного места для переноса офлайн-кэша.")
        val verified = ready.filterTo(hashSetOf()) { validBody(target.child("$it.audio")) }
        // A reusable audio file does not imply that its artwork is present and healthy.
        val verifiedCovers = apparentCovers.filterTo(hashSetOf()) { validBody(target.child(it)) }
        val missing = files.filterKeys { name ->
            if (name.endsWith(".cover")) name !in verifiedCovers else name.substringBefore('.') !in verified
        }
        val required = missing.values.sumOf { it.length() }
        if (parent.usableSpace < required + 32L * 1024 * 1024) throw IOException("Недостаточно свободного места для переноса офлайн-кэша.")
        check(allowed)
        if (!target.mkdirs()) throw IOException("Не удалось создать папку кэша.")
        val journal = "pending:${key(owner)}"
        val pending = JSONObject().put("uri", target.uri.toString()).put("source", source.uri.toString()).put("fresh", fresh)
        if (!bindings.edit().putString(journal, pending.toString()).commit()) throw IOException("Не удалось сохранить папку кэша.")
        var switched = false
        try {
            var copied = 0L; progress(0, required)
            for ((name, input) in missing) {
                currentCoroutineContext().ensureActive(); check(allowed)
                val output = target.child(name); val temp = target.child(name + ".part")
                try {
                    val digest = MessageDigest.getInstance("SHA-256")
                    input.inputStream().use { reader -> temp.outputStream().use { writer ->
                        val buffer = ByteArray(128 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive(); check(allowed)
                            val count = reader.read(buffer); if (count < 0) break
                            if (parent.usableSpace < count + 16L * 1024 * 1024) throw IOException("Недостаточно свободного места для переноса офлайн-кэша.")
                            writer.write(buffer, 0, count); digest.update(buffer, 0, count); copied += count; progress(copied, required)
                        }
                    } }
                    val expected = hex(digest.digest())
                    if (temp.length() != input.length() || hashFile(temp) != expected) throw IOException("Проверка перенесённого файла не пройдена.")
                    if (name.endsWith(".audio") || name.endsWith(".cover")) {
                        val original = input.parentFile!!.child(name + ".sha256")
                        if (!original.isFile || original.length() != 64L || original.readText() != expected)
                            throw IOException("Проверка перенесённого файла не пройдена.")
                    }
                    currentCoroutineContext().ensureActive(); check(allowed)
                    output.replace(temp)
                    target.child(name + ".invalid").takeIf { it.exists() }?.delete()
                    if (name.endsWith(".audio") || name.endsWith(".cover")) {
                        val sum = target.child(name + ".sha256"); val sumTemp = target.child(name + ".sha256.part")
                        sumTemp.writeBytes(expected.toByteArray()); sum.replace(sumTemp)
                    }
                } finally { if (temp.exists()) temp.delete() }
            }
            currentCoroutineContext().ensureActive(); check(allowed)
            val result = ensurePassport(target, owner)
            withContext(NonCancellable) {
                check(allowed)
                save(owner, target, destination != null,
                    destination?.let { uri -> Uri.parse(uri).let { if (it.scheme == "file") it.path.orEmpty() else DocumentsContract.getTreeDocumentId(it) } }
                        ?: "Внутренняя память приложения", result)
                switched = true
                // Never delete the old shared parent or an unselected legacy owner.
                if (source.exists() && !deleteManaged(source, owner)) throw IOException("Кэш перенесён. Старую папку не удалось удалить.")
                bindings.edit().remove(journal).commit()
            }
        } finally {
            if (!switched && (!fresh || runCatching { target.deleteRecursively() }.getOrDefault(false))) bindings.edit().remove(journal).commit()
        }
    }
    private suspend fun complete(file: StorageNode): Boolean {
        if (!file.isFile || file.length() <= 0) return false
        if (file.name.endsWith(".json")) return file.length() < 65_536 && runCatching {
            val json = JSONObject(file.readText())
            val id = json.getString("id").removePrefix("yandex:").substringBefore(':')
            file.name == hash(id) + ".json" && json.has("title") && json.getInt("duration") >= 0
        }.getOrDefault(false)
        val sum = file.parentFile!!.child(file.name + ".sha256")
        if (!sum.isFile || sum.length() != 64L || file.parentFile.child(file.name + ".invalid").exists()) return false
        return true
    }
    private suspend fun validBody(file: StorageNode): Boolean {
        if (!complete(file)) return false
        return try { hashFile(file) == file.parentFile!!.child(file.name + ".sha256").readText() }
            catch (e: CancellationException) { throw e } catch (_: Exception) { false }
    }
    private fun deleteManaged(root: StorageNode, owner: OfflineOwner): Boolean {
        if (passport(root)?.account != owner.accountId) return false
        val managed = Regex("[a-f0-9]{64}\\.(json|audio|cover)(\\.(sha256|invalid))?(\\.(part|backup))?")
        for (file in root.listFiles().orEmpty()) {
            if (file.isFile && (file.name.matches(managed) || file.name == PASSPORT || file.name.startsWith("$PASSPORT.")))
                if (!file.delete() && file.exists()) return false
        }
        return root.listFiles().orEmpty().isNotEmpty() || root.delete()
    }
    private fun recoverPending(owner: OfflineOwner) {
        val journal = "pending:${key(owner)}"
        val json = bindings.getString(journal, null)?.let(::JSONObject) ?: return
        val pending = from(json.getString("uri"))
        val active = identity(owner)?.let(::from)
        val committed = active != null && contains(pending, active) && contains(active, pending)
        val cleanup = if (committed) json.optString("source").takeIf { it.isNotBlank() }?.let(::from)
            else pending.takeIf { json.optBoolean("fresh", true) }
        if (cleanup != null && allBindings().none { contains(cleanup, from(it.getString("uri"))) } && cleanup.exists()) {
            val done = if (committed) deleteManaged(cleanup, owner) else cleanup.deleteRecursively()
            if (!done) {
                if (committed) return // Valid committed audio remains usable while old-root cleanup is pending.
                throw IOException("Не удалось завершить предыдущий перенос кэша.")
            }
        }
        if (!bindings.edit().remove(journal).commit()) throw IOException("Не удалось сохранить папку кэша.")
    }
    fun removeProfile(profile: String, knownAccount: String?) {
        val rows = allBindings().filter { it.optString("profile") == profile }.toMutableList()
        if (knownAccount != null && legacyProfile(OfflineOwner(profile, knownAccount)) && binding(OfflineOwner(profile, knownAccount)) == null) {
            val previous = runCatching { oldRoot(OfflineOwner(profile, knownAccount)).takeIf { it.isDirectory } }.getOrNull()
            if (previous != null) rows += JSONObject().put("profile", profile).put("account", knownAccount)
                .put("uri", previous.uri.toString()).put("external", legacy.getBoolean("external", false))
        }
        val privateFiles = StorageNode.file(context, context.filesDir)
        for (row in rows) {
            val root = from(row.getString("uri"))
            if (!row.getBoolean("external") && contains(privateFiles, root) && root.exists() && !root.deleteRecursively()) throw IOException("Не удалось очистить офлайн-файлы.")
            if (!bindings.edit().remove(key(OfflineOwner(profile, row.getString("account")))).commit()) throw IOException("Не удалось сохранить папку кэша.")
        }
    }
    private fun check(allowed: () -> Boolean) { if (!allowed() || !enabled()) throw CancellationException("Cache owner or global switch changed") }
    companion object {
        const val PASSPORT = "ymplayer2-cache.json"
        private val HASH = Regex("[a-f0-9]{64}")
        private val METADATA = Regex("[a-f0-9]{64}\\.json")
        private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
        private fun hash(text: String) = hex(MessageDigest.getInstance("SHA-256").digest(text.toByteArray()))
        private suspend fun hashFile(file: StorageNode): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input -> val buffer = ByteArray(128 * 1024); while (true) { currentCoroutineContext().ensureActive(); val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) } }
            return hex(digest.digest())
        }
        private fun contains(parent: StorageNode, child: StorageNode): Boolean {
            val a = parent.uri; val b = child.uri
            if (a.scheme == "file" && b.scheme == "file") return File(b.path!!).canonicalFile.toPath().startsWith(File(a.path!!).canonicalFile.toPath())
            if (a.scheme == "content" && b.scheme == "content" && a.authority == b.authority) {
                val p = DocumentsContract.getDocumentId(a); val c = DocumentsContract.getDocumentId(b)
                return p == c || c.startsWith("$p/")
            }
            return false
        }
    }
}
