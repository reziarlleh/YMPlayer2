package dev.petrov.ymplayer2.yandex

import dev.petrov.ymplayer2.core.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject

/** 1.x create/private, fresh revision + append diff, delete + endpoint fallbacks. */
class YandexPlaylistApi(private val music: YandexMusicApi) : CloudPlaylistApi {
    private fun numeric(value: String): String { require(value.matches(Regex("[0-9]{1,30}"))); return value }
    private suspend fun <T> owned(owner: PlaylistOwner, action: suspend (String, String) -> T): T {
        currentCoroutineContext().ensureActive()
        return music.account(owner.profileId) { token, uid ->
            if (uid != owner.accountId) throw MusicException(MusicFailure.SIGN_IN)
            action(token, numeric(uid))
        }
    }
    private fun summary(row: JSONObject, owner: String): CloudPlaylist {
        val uid = row.optJSONObject("owner")?.optString("uid")?.takeIf(String::isNotBlank) ?: owner
        if (uid != owner) throw MusicException(MusicFailure.ACCESS)
        val title = row.optString("title")
        if (title.isBlank()) throw MusicException(MusicFailure.RESPONSE)
        return CloudPlaylist(numeric(row.optString("kind")), uid, title, row.optInt("trackCount", row.optJSONArray("tracks")?.length() ?: 0).coerceAtLeast(0))
    }
    override suspend fun list(owner: PlaylistOwner): List<CloudPlaylist> = owned(owner) { token, uid ->
        (music.api(token, "/users/$uid/playlists/list") as JSONArray).objects().mapNotNull { row ->
            // Like 1.x, skip malformed summaries; never offer another owner's playlist as writable.
            runCatching { summary(row, uid) }.getOrNull()
        }
    }
    override suspend fun create(owner: PlaylistOwner, title: String): CloudPlaylist = owned(owner) { token, uid ->
        require(title.trim().isNotEmpty() && title.trim().length <= 200)
        summary(music.api(token, "/users/$uid/playlists/create", listOf("title" to title.trim(), "visibility" to "private")) as JSONObject, uid)
    }
    private fun check(owner: PlaylistOwner, playlist: CloudPlaylist): String {
        if (playlist.ownerId != owner.accountId) throw MusicException(MusicFailure.ACCESS)
        return numeric(playlist.id)
    }
    override suspend fun add(owner: PlaylistOwner, playlist: CloudPlaylist, track: Track): CloudPlaylist {
        val kind = check(owner, playlist)
        val (id, album) = track.cloudTrackKey() ?: throw MusicException(MusicFailure.UNAVAILABLE)
        val current = owned(owner) { token, uid -> music.api(token, "/users/$uid/playlists/$kind") as JSONObject }
        val fresh = summary(current, owner.accountId)
        if (fresh.id != kind) throw MusicException(MusicFailure.RESPONSE)
        val operation = JSONObject().put("op", "insert").put("at", fresh.trackCount)
            .put("tracks", JSONArray().put(JSONObject().put("id", id).put("albumId", album)))
        val form = listOf("kind" to kind, "revision" to current.optInt("revision", 1).coerceAtLeast(1).toString(), "diff" to JSONArray().put(operation).toString())
        suspend fun post(endpoint: String) = owned(owner) { token, uid -> summary(music.api(token, "/users/$uid/playlists/$kind/$endpoint", form) as JSONObject, uid) }
        return fallback({ post("change") }, { post("change-relative") })
    }
    override suspend fun delete(owner: PlaylistOwner, playlist: CloudPlaylist) {
        val kind = check(owner, playlist)
        suspend fun post(path: String) = owned(owner) { token, uid -> music.acknowledge(token, "/users/$uid/playlists/$path", listOf("kind" to kind)) }
        fallback({ post("$kind/delete") }, { post("delete") })
    }
    private suspend fun <T> fallback(primary: suspend () -> T, alternate: suspend () -> T): T = try { primary() }
        catch (e: MusicException) {
            // Unlike a missing endpoint, timeout/5xx can mean the write already happened. Do not replay it.
            if (e.httpStatus !in setOf(404, 405)) throw e
            alternate()
        }
}
