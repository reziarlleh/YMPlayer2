package dev.petrov.ymplayer2.playback

import android.content.Context
import android.content.SharedPreferences
import dev.petrov.ymplayer2.core.*
import org.json.JSONArray
import org.json.JSONObject

internal data class LocalListPosition(val trackId: String, val seconds: Int, val repeat: RepeatMode, val shuffle: Boolean)

internal fun LocalListTarget.json() = JSONObject().put("key", key).put("title", title).put("path", folderPath)
internal fun JSONObject.localListTarget() = LocalListTarget(getString("key"), optString("title"), optString("path"))

internal fun PlaybackOrigin.withLocalTarget(refs: List<PlaybackReference>): PlaybackOrigin {
    if (wave != null || source !in setOf(PlaybackSource.LIST, PlaybackSource.LOCAL_FAVORITES) ||
        refs.isEmpty() || refs.any { it.saved.source == Source.YANDEX }) return copy(localList = null)
    if (localList != null) return this
    val target = if (source == PlaybackSource.LOCAL_FAVORITES) LocalListTarget.favorites
        else LocalListTarget.selection(refs.map(PlaybackReference::id), title.ifBlank { refs.first().saved.title })
    return target.origin()
}

/** The engine owns writes on its application thread. Frequent position updates live apart
 * from the full queues, which are written only when a list or its order changes. */
internal class LocalListBookmarks(context: Context) {
    private val lists by lazy { context.getSharedPreferences("local-list-queues", Context.MODE_PRIVATE) }
    private val positions = context.getSharedPreferences("local-list-progress", Context.MODE_PRIVATE)
    private var loadedProfile: String? = null
    private var history = LocalListHistory()
    private var lastOrder: Any? = null
    private var lastRevision = -1L
    private var lastKey: String? = null

    fun history(profile: String): LocalListHistory {
        if (loadedProfile != profile) {
            val rows = runCatching { JSONArray(positions.getString("recent:$profile", "[]")) }.getOrNull()
            val saved = (0 until (rows?.length() ?: 0)).mapNotNull { i ->
                runCatching { rows!!.getJSONObject(i).let { it.localListTarget() to it.optInt("pin", -1) } }.getOrNull()
            }.distinctBy { it.first.key }.take(LocalListHistory.CAPACITY + 1)
            val pins = saved.filter { it.second in 0 until LocalListHistory.CAPACITY }.sortedBy { it.second }.map { it.first.key }
            history = LocalListHistory(saved.map { it.first }, pins.take(LocalListHistory.CAPACITY))
            loadedProfile = profile
        }
        return history
    }

    fun pin(profile: String, target: LocalListTarget, enabled: Boolean): LocalListHistory {
        val before = history(profile)
        val next = before.pin(target, enabled)
        if (next != before) {
            val cursor = positions.edit()
            val queues = lists.edit()
            writeHistory(profile, next, cursor, queues)
            queues.apply()
            cursor.apply()
        }
        return next
    }

    private fun writeHistory(profile: String, next: LocalListHistory, cursor: SharedPreferences.Editor, queues: SharedPreferences.Editor) {
        cursor.putString("recent:$profile", JSONArray(next.recent.map { target ->
            target.json().apply { next.pinned.indexOf(target.key).takeIf { it >= 0 }?.let { put("pin", it) } }
        }).toString())
        history.recent.filter { old -> next.recent.none { it.key == old.key } }.forEach { old ->
            queues.remove("queue:$profile:${old.key}")
            cursor.remove("cursor:$profile:${old.key}")
        }
        history = next
    }

    fun references(profile: String, target: LocalListTarget): List<PlaybackReference> {
        val rows = runCatching { JSONArray(lists.getString("queue:$profile:${target.key}", "[]")) }.getOrNull()
        return (0 until (rows?.length() ?: 0)).mapNotNull { i ->
            runCatching { PlaybackReference.read(rows!!.getJSONObject(i)) }.getOrNull()
        }.filter { it.saved.source != Source.YANDEX }
    }

    fun position(profile: String, target: LocalListTarget): LocalListPosition? = runCatching {
        val row = JSONObject(positions.getString("cursor:$profile:${target.key}", null) ?: return null)
        LocalListPosition(row.getString("track"), row.getInt("seconds").coerceAtLeast(0),
            RepeatMode.valueOf(row.getString("repeat")), row.getBoolean("shuffle"))
    }.getOrNull()

    fun record(state: PlaybackState, target: LocalListTarget, order: Any, revision: Long,
        references: () -> List<PlaybackReference>): LocalListHistory {
        val profile = state.profileId
        val before = history(profile)
        val current = state.current ?: return before
        val key = "$profile:${target.key}"
        val cursor = positions.edit()
        if (lastOrder !== order || lastRevision != revision || lastKey != key || before.recent.firstOrNull() != target) {
            val refs = references()
            if (refs.isEmpty() || refs.any { it.saved.source == Source.YANDEX }) return before
            val next = before.played(target)
            val edit = lists.edit().putString("queue:$key", JSONArray(refs.map(PlaybackReference::json)).toString())
            if (before != next) writeHistory(profile, next, cursor, edit)
            edit.apply()
            lastOrder = order
            lastRevision = revision
            lastKey = key
        }
        val point = JSONObject().put("track", current.id).put("seconds", state.positionSeconds)
            .put("repeat", state.repeatMode.name).put("shuffle", state.shuffle).toString()
        cursor.putString("cursor:$key", point).apply()
        return history
    }
}
