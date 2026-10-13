package dev.petrov.ymplayer2.yandex

import android.content.Context
import dev.petrov.ymplayer2.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/** Cached marks and durable intent only. OAuth remains exclusively in KeystoreAccountStore. */
class AndroidTasteStore(context: Context) : TasteStore {
    private val prefs = context.applicationContext.getSharedPreferences("taste-actions", Context.MODE_PRIVATE)
    private fun key(owner: OfflineOwner) = MessageDigest.getInstance("SHA-256")
        .digest("${owner.profileId}\u0000${owner.accountId}".toByteArray()).joinToString("") { "%02x".format(it) }
    override suspend fun read(owner: OfflineOwner): TasteSnapshot = withContext(Dispatchers.IO) {
        val root = JSONObject(prefs.getString(key(owner), "{}")!!)
        fun ids(array: JSONArray?) = (0 until (array?.length() ?: 0)).map { array!!.getString(it) }.toSet()
        val lists = TasteKind.entries.mapNotNull { kind -> root.optJSONObject(kind.name)?.let {
            kind to TasteList(ids(it.optJSONArray("liked")), ids(it.optJSONArray("blocked")))
        } }.toMap()
        val rows = root.optJSONArray("pending") ?: JSONArray()
        val pending = (0 until rows.length()).map { i -> rows.getJSONObject(i).let {
            TasteMutation(TasteTarget(TasteKind.valueOf(it.getString("kind")), it.getString("id"), it.optString("title")),
                TasteAction.valueOf(it.getString("action")))
        } }
        TasteSnapshot(lists, pending)
    }
    override suspend fun write(owner: OfflineOwner, snapshot: TasteSnapshot) = withContext(Dispatchers.IO) {
        val root = JSONObject()
        snapshot.lists.forEach { (kind, list) -> root.put(kind.name, JSONObject().put("liked", JSONArray(list.liked.toList())).put("blocked", JSONArray(list.blocked.toList()))) }
        root.put("pending", JSONArray().apply { snapshot.pending.forEach {
            put(JSONObject().put("kind", it.target.kind.name).put("id", it.target.id).put("title", it.target.title).put("action", it.action.name))
        } })
        if (!prefs.edit().putString(key(owner), root.toString()).commit()) throw java.io.IOException("Taste persistence failed")
    }
}
