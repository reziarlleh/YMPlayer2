package dev.petrov.ymplayer2.library

import android.content.Context
import android.util.AtomicFile
import dev.petrov.ymplayer2.core.Profile
import dev.petrov.ymplayer2.core.UserProfiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Add the built-in local identity without moving existing accounts, queues or preferences. */
fun loadUserProfiles(context: Context, beforeRemove: suspend (String, String) -> Unit = { _, _ -> }): UserProfiles {
    val app = context.applicationContext
    val file = AtomicFile(File(app.filesDir, "user-profiles.json"))
    val legacy = listOf("owner", "road", "guest").map { id ->
        Profile(id, "Яндекс", "")
    }
    val local = UserProfiles.localProfile()
    var writable = true
    var migration = false
    val initial = try {
        val loaded = if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()) {
            check(maxOf(file.baseFile.length(), File(file.baseFile.path + ".bak").length()) <= 1_048_576)
            val json = JSONObject(file.openRead().bufferedReader().use { it.readText() })
            val schema = json.getInt("schema")
            require(schema in 1..3)
            migration = schema != 3
            val rows = json.getJSONArray("profiles")
            (0 until rows.length()).map { rows.getJSONObject(it).let { row ->
                val manual = if (schema == 1) row.optInt("defaultNumber") <= 0 else row.optBoolean("userNamed")
                val cachedName = !manual && row.optInt("defaultNumber") <= 0
                Profile(row.getString("id"), if (cachedName) row.getString("name") else "Яндекс", "",
                    accountConnected = schema == 3 && row.optBoolean("accountConnected"),
                    provisional = schema == 3 && row.optBoolean("provisional"))
            } }.also { rows ->
                require(rows.isNotEmpty() && rows.map(Profile::id).distinct().size == rows.size)
                rows.forEach { require(it.id.matches(Regex("[a-zA-Z0-9_-]{1,64}"))); UserProfiles.checkedName(it.name) }
            }
        } else {
            migration = true
            val oldInstall = app.getSharedPreferences("playback", Context.MODE_PRIVATE).all.isNotEmpty() ||
                File(app.filesDir, "local-library.json").exists() || File(app.filesDir, "user-collections.json").exists() ||
                File(app.noBackupFilesDir, "yandex-accounts").listFiles().orEmpty().isNotEmpty()
            if (oldInstall) legacy else emptyList()
        }
        (listOf(local) + loaded.filterNot(Profile::isLocal)).also { if (it != loaded) migration = true }
    } catch (_: Exception) {
        // Never overwrite an unreadable user list with defaults or destroy the old encrypted accounts.
        writable = false
        listOf(local) + legacy
    }
    fun write(rows: List<Profile>) {
        val json = JSONObject().put("schema", 3).put("profiles", JSONArray(rows.map {
            JSONObject().put("id", it.id).put("name", it.name).put("defaultNumber", it.defaultNumber)
                .put("userNamed", false).put("accountConnected", it.accountConnected).put("provisional", it.provisional)
        }))
        val stream = file.startWrite()
        try { stream.write(json.toString().toByteArray(Charsets.UTF_8)); file.finishWrite(stream) }
        catch (error: Exception) { file.failWrite(stream); throw error }
    }
    // Persist migration before lazy playback initialization can change the legacy-detection inputs.
    if (writable && migration) try { write(initial) } catch (_: Exception) { writable = false }
    return UserProfiles(initial, { rows -> withContext(Dispatchers.IO) { write(rows) } }, beforeRemove, writable)
}
