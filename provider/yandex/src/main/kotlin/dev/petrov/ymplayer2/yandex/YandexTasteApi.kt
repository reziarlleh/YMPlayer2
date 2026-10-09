package dev.petrov.ymplayer2.yandex

import dev.petrov.ymplayer2.core.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/** Track mutations ported from YandexMusicClient 1.x; artist/album endpoints stay distinct. */
class YandexTasteApi(private val music: YandexMusicApi) : MusicTasteApi {
    override suspend fun taste(profileId: String, kind: TasteKind): TasteList = music.account(profileId) { token, uid ->
        TasteList(read(token, uid, kind, "likes"), if (kind == TasteKind.ALBUM) emptySet() else read(token, uid, kind, "dislikes"))
    }
    private suspend fun read(token: String, uid: String, kind: TasteKind, collection: String): Set<String> {
        val type = kind.name.lowercase()
        val result = music.api(token, "/users/$uid/$collection/${type}s?if-modified-since-revision=0&rich=true&with-timestamps=true")
        val rows = if (kind == TasteKind.TRACK) (result as JSONObject).getJSONObject("library").getJSONArray("tracks") else result as JSONArray
        return rows.objects().mapNotNull { row ->
            (row.optJSONObject(type) ?: row).optString("id").takeIf {
                if (kind == TasteKind.TRACK) validYandexTrackKey(it) else it.matches(Regex("[0-9]+"))
            }?.substringBefore(':')
        }.toSet()
    }
    override suspend fun react(profileId: String, target: TasteTarget, action: TasteAction) = music.account(profileId) { token, uid ->
        val type = target.kind.name.lowercase()
        val id = target.id.removePrefix("yandex:")
        require(if (target.kind == TasteKind.TRACK) validYandexTrackKey(id) else id.matches(Regex("[0-9]+")))
        require(target.kind != TasteKind.ALBUM || action in setOf(TasteAction.LIKE, TasteAction.UNLIKE))
        suspend fun write(collection: String, remove: Boolean) {
            val form = listOf("$type-ids" to id)
            val path = "/users/$uid/$collection/${type}s"
            val result = try { music.api(token, "$path/${if (remove) "remove" else "add-multiple"}", form) }
            catch (e: MusicException) {
                // Original 1.x compatibility endpoint. Never retry authorization/network failures.
                if (!remove || target.kind != TasteKind.TRACK || e.failure !in setOf(MusicFailure.UNAVAILABLE, MusicFailure.RESPONSE)) throw e
                music.api(token, "$path/${URLEncoder.encode(id, "UTF-8")}/remove", form)
            }
            if (result != "ok" && !(result is JSONObject && result.has("revision"))) throw MusicException(MusicFailure.RESPONSE)
        }
        when (action) {
            TasteAction.LIKE -> {
                if (target.kind != TasteKind.ALBUM && target.key in read(token, uid, target.kind, "dislikes")) write("dislikes", true)
                write("likes", false)
            }
            TasteAction.UNLIKE -> write("likes", true)
            TasteAction.BLOCK -> {
                write("dislikes", false)
                if (target.key in read(token, uid, target.kind, "likes")) write("likes", true)
            }
            TasteAction.UNBLOCK -> write("dislikes", true)
        }
    }
}
