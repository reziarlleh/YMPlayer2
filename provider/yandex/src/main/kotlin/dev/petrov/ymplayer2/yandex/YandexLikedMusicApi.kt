package dev.petrov.ymplayer2.yandex

import dev.petrov.ymplayer2.core.*
import org.json.JSONObject

/** Fetch the complete ID snapshot once. Missing metadata never authorizes pruning other liked IDs. */
class YandexLikedMusicApi(private val music: YandexMusicApi) : LikedMusicApi {
    private suspend fun rows(token: String, uid: String): List<String> {
        val rows = (music.api(token, "/users/$uid/likes/tracks?if-modified-since-revision=0") as JSONObject)
            .getJSONObject("library").getJSONArray("tracks")
        return (0 until rows.length()).map { index ->
            val row = rows.getJSONObject(index)
            val id = row.getString("id")
            require(validYandexTrackKey(id))
            if (':' in id) id else id + row.optString("albumId").takeIf { it.matches(Regex("[0-9]{1,30}")) }?.let { ":$it" }.orEmpty()
        }.distinctBy { it.substringBefore(':') }
    }
    override suspend fun keys(profileId: String): Set<String> = music.account(profileId) { token, uid -> rows(token, uid).mapTo(linkedSetOf()) { it.substringBefore(':') } }
    override suspend fun snapshot(profileId: String): LikedSnapshot = music.account(profileId) { token, uid ->
        val ids = rows(token, uid)
        LikedSnapshot(ids.mapTo(linkedSetOf()) { it.substringBefore(':') }, music.tracks(token, ids))
    }
}
