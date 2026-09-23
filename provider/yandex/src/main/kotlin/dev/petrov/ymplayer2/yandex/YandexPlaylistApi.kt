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
        val form = listOf("kind" to kind, "revision" to current.optLong("revision", 1).coerceAtLeast(0).toString(), "diff" to JSONArray().put(operation).toString())
        suspend fun post(endpoint: String) = owned(owner) { token, uid -> summary(music.api(token, "/users/$uid/playlists/$kind/$endpoint", form) as JSONObject, uid) }
        return fallback({ post("change") }, { post("change-relative") })
    }
    override suspend fun delete(owner: PlaylistOwner, playlist: CloudPlaylist) {
        val kind = check(owner, playlist)
        suspend fun post(path: String) = owned(owner) { token, uid -> music.acknowledge(token, "/users/$uid/playlists/$path", listOf("kind" to kind)) }
        fallback({ post("$kind/delete") }, { post("delete") })
    }
    private suspend fun read(owner: PlaylistOwner, playlist: CloudPlaylist): CloudPlaylistSnapshot {
        val kind = check(owner, playlist)
        val row = owned(owner) { token, uid -> music.api(token, "/users/$uid/playlists/$kind") as JSONObject }
        val fresh = summary(row, owner.accountId)
        val revision = row.optLong("revision", -1)
        val rows = row.optJSONArray("tracks") ?: if (fresh.trackCount == 0) JSONArray() else throw MusicException(MusicFailure.RESPONSE)
        if (fresh.id != kind || revision < 0 || rows.length() != fresh.trackCount) throw MusicException(MusicFailure.RESPONSE)
        fun JSONObject.text(key: String) = optString(key).takeIf { it.isNotBlank() && it != "null" }
        val entries = (0 until rows.length()).map { index ->
            val item = rows.getJSONObject(index); val track = item.optJSONObject("track")
            val fullId = item.text("id") ?: track?.text("id") ?: throw MusicException(MusicFailure.RESPONSE)
            val album = item.text("albumId") ?: fullId.substringAfter(':', "").takeIf(String::isNotBlank)
                ?: track?.optJSONArray("albums")?.optJSONObject(0)?.text("id")
            CloudPlaylistEntry(fullId.substringBefore(':'), album, track?.text("title") ?: item.text("title").orEmpty(),
                track?.optJSONArray("artists")?.objects()?.mapNotNull { it.text("name") }?.joinToString(", ").orEmpty())
        }
        return CloudPlaylistSnapshot(fresh, revision, entries)
    }
    override suspend fun load(owner: PlaylistOwner, playlist: CloudPlaylist): CloudPlaylistSnapshot {
        val snapshot = read(owner, playlist)
        val missing = snapshot.tracks.filter { it.title.isBlank() && it.id.matches(Regex("[0-9]+")) }
            .map { it.id + (it.albumId?.let { album -> ":$album" } ?: "") }.distinct()
        val metadata = missing.chunked(50).flatMap { ids -> owned(owner) { token, _ -> music.tracks(token, ids) } }
            .associateBy { it.id.removePrefix("yandex:").substringBefore(':') }
        return snapshot.copy(tracks = snapshot.tracks.map { entry ->
            val track = metadata[entry.id]
            if (entry.title.isNotBlank()) entry else entry.copy(title = track?.title ?: "Недоступный трек ${entry.id}", artist = track?.artist.orEmpty())
        })
    }
    private suspend fun current(owner: PlaylistOwner, snapshot: CloudPlaylistSnapshot): CloudPlaylistSnapshot {
        val fresh = read(owner, snapshot.playlist)
        if (fresh.revision != snapshot.revision || fresh.playlist.title != snapshot.playlist.title ||
            fresh.tracks.map { it.key } != snapshot.tracks.map { it.key }) throw MusicException(MusicFailure.RESPONSE, 409)
        return fresh
    }
    override suspend fun rename(owner: PlaylistOwner, snapshot: CloudPlaylistSnapshot, title: String): CloudPlaylistSnapshot {
        val name = title.trim(); require(name.isNotEmpty() && name.length <= 200)
        current(owner, snapshot)
        val kind = check(owner, snapshot.playlist)
        owned(owner) { token, uid -> music.api(token, "/users/$uid/playlists/$kind/name", listOf("value" to name)) }
        return load(owner, snapshot.playlist).also {
            if (it.playlist.title != name) throw MusicException(MusicFailure.RESPONSE, 409)
        }
    }
    override suspend fun edit(owner: PlaylistOwner, snapshot: CloudPlaylistSnapshot, change: CloudTrackEdit): CloudPlaylistSnapshot {
        val expected = snapshot.edited(change)
        val fresh = current(owner, snapshot)
        val kind = check(owner, fresh.playlist)
        val from = when (change) { is CloudTrackEdit.Remove -> change.index; is CloudTrackEdit.Move -> change.from }
        val diff = JSONArray().put(JSONObject().put("op", "delete").put("from", from).put("to", from + 1))
        if (change is CloudTrackEdit.Move) {
            if (change.from == change.to) return load(owner, fresh.playlist)
            val entry = fresh.tracks[from]
            diff.put(JSONObject().put("op", "insert").put("at", change.to)
                .put("tracks", JSONArray().put(JSONObject().put("id", entry.id).put("albumId", entry.albumId))))
        }
        val form = listOf("kind" to kind, "revision" to fresh.revision.toString(), "diff" to diff.toString())
        suspend fun post(endpoint: String) = owned(owner) { token, uid -> music.api(token, "/users/$uid/playlists/$kind/$endpoint", form) }
        // Move is one server change containing delete + insert, never two independent writes.
        fallback({ post("change") }, { post("change-relative") })
        return load(owner, fresh.playlist).also {
            if (it.tracks.map { entry -> entry.key } != expected.map { entry -> entry.key }) throw MusicException(MusicFailure.RESPONSE, 409)
        }
    }
    private suspend fun <T> fallback(primary: suspend () -> T, alternate: suspend () -> T): T = try { primary() }
        catch (e: MusicException) {
            // Unlike a missing endpoint, timeout/5xx can mean the write already happened. Do not replay it.
            if (e.httpStatus !in setOf(404, 405)) throw e
            alternate()
        }
}
