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

/** Migrates the three old slots in place; their encrypted account IDs stay valid. */
fun loadUserProfiles(context: Context, beforeRemove: suspend (String, String) -> Unit = { _, _ -> }): UserProfiles {
    val app = context.applicationContext
    val file = AtomicFile(File(app.filesDir, "user-profiles.json"))
    val legacy = listOf("owner", "road", "guest").mapIndexed { i, id ->
        Profile(id, "Пользователь ${i + 1}", "", defaultNumber = i + 1)
    }
    var writable = true
    val initial = try {
        if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()) {
            check(file.baseFile.length() <= 1_048_576)
            val json = JSONObject(file.openRead().bufferedReader().use { it.readText() })
            require(json.getInt("schema") == 1)
            val rows = json.getJSONArray("profiles")
            (0 until rows.length()).map { rows.getJSONObject(it).let { row ->
                Profile(row.getString("id"), row.getString("name"), "",
                    defaultNumber = row.optInt("defaultNumber").takeIf { it > 0 }, userNamed = row.optInt("defaultNumber") <= 0)
            } }.also { rows ->
                require(rows.isNotEmpty() && rows.map(Profile::id).distinct().size == rows.size)
                rows.forEach { require(it.id.matches(Regex("[a-zA-Z0-9_-]{1,64}"))); UserProfiles.checkedName(it.name) }
            }
        } else {
            val oldInstall = app.getSharedPreferences("playback", Context.MODE_PRIVATE).all.isNotEmpty() ||
                File(app.filesDir, "local-library.json").exists() || File(app.filesDir, "user-collections.json").exists() ||
                File(app.noBackupFilesDir, "yandex-accounts").listFiles().orEmpty().isNotEmpty()
            if (oldInstall) legacy else legacy.take(1)
        }
    } catch (_: Exception) {
        // Never overwrite an unreadable user list with defaults or destroy the old encrypted accounts.
        writable = false
        legacy
    }
    suspend fun save(rows: List<Profile>) = withContext(Dispatchers.IO) {
        val json = JSONObject().put("schema", 1).put("profiles", JSONArray(rows.map {
            JSONObject().put("id", it.id).put("name", it.name).put("defaultNumber", it.defaultNumber)
        }))
        val stream = file.startWrite()
        try { stream.write(json.toString().toByteArray(Charsets.UTF_8)); file.finishWrite(stream) }
        catch (error: Exception) { file.failWrite(stream); throw error }
    }
    // Persist migration before lazy playback initialization can change the legacy-detection inputs.
    if (writable && !file.baseFile.exists()) {
        try {
            val json = JSONObject().put("schema", 1).put("profiles", JSONArray(initial.map {
                JSONObject().put("id", it.id).put("name", it.name).put("defaultNumber", it.defaultNumber)
            }))
            val stream = file.startWrite()
            try { stream.write(json.toString().toByteArray(Charsets.UTF_8)); file.finishWrite(stream) }
            catch (error: Exception) { file.failWrite(stream); throw error }
        } catch (_: Exception) { writable = false }
    }
    return UserProfiles(initial, ::save, beforeRemove, writable)
}
