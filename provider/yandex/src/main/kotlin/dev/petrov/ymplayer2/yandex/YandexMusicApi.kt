package dev.petrov.ymplayer2.yandex

import dev.petrov.ymplayer2.core.*
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.security.MessageDigest
import javax.xml.parsers.DocumentBuilderFactory

/** Provider catalog and the same download-info/XML signing protocol as the working 1.x. */
class YandexMusicApi(private val accounts: AccountAuth, private val transport: MusicTransport = HttpsMusicTransport()) : OnlineMusicApi {
    override suspend fun page(profileId: String, request: MusicRequest, page: Int): MusicPage = safe {
        require(page in 0..1000)
        accounts.withSession(profileId) { session ->
            val token = session.credentials.accessToken
            val entity = request.entity
            when {
                entity != null -> details(token, entity, page, request.kind)
                request.recommended -> recommendations(token)
                request.collection -> collection(token, session.account?.id ?: accountId(token), request.kind, page)
                else -> search(token, request, page)
            }
        }
    }

    internal suspend fun api(token: String, path: String, form: List<Pair<String, String>>? = null, json: JSONObject? = null): Any {
        val text = if (json != null) transport.json("https://api.music.yandex.net$path", token, json.toString())
            else transport.request("https://api.music.yandex.net$path", token, form)
        if (text.trim().equals("ok", true)) return "ok"
        val root = JSONObject(text)
        if (root.has("error")) throw MusicException(MusicFailure.ACCESS)
        return root.opt("result")?.takeUnless { it == JSONObject.NULL } ?: throw MusicException(MusicFailure.RESPONSE)
    }
    private suspend fun accountId(token: String): String = numeric((api(token, "/account/status") as JSONObject).getJSONObject("account").getString("uid"))
    internal suspend fun <T> account(profile: String, action: suspend (String, String) -> T): T = safe {
        accounts.withSession(profile) { action(it.credentials.accessToken, it.account?.id ?: accountId(it.credentials.accessToken)) }
    }

    private suspend fun search(token: String, request: MusicRequest, page: Int): MusicPage {
        val query = request.query.trim().take(200)
        if (query.isEmpty()) return MusicPage(emptyList())
        val kind = request.kind.name.lowercase()
        val type = kind.removeSuffix("s")
        val result = api(token, "/search?type=$type&page=$page&nocorrect=false&text=${encode(query)}") as JSONObject
        val block = result.optJSONObject(kind) ?: return MusicPage(emptyList())
        val rows = block.optJSONArray("results") ?: JSONArray()
        val entries = rows.objects().mapNotNull { entry(it, request.kind) }
        val perPage = block.optInt("perPage", rows.length()).coerceAtLeast(1)
        val next = if (rows.length() > 0 && (page.toLong() + 1) * perPage < block.optLong("total", 0)) page + 1 else null
        return MusicPage(entries, next)
    }

    private suspend fun collection(token: String, uid: String, kind: MusicKind, page: Int): MusicPage {
        val owner = numeric(uid)
        return when (kind) {
            MusicKind.TRACKS -> {
                val library = (api(token, "/users/$owner/likes/tracks?if-modified-since-revision=0") as JSONObject).getJSONObject("library")
                val rows = library.optJSONArray("tracks") ?: JSONArray()
                resolveRows(token, rows, page)
            }
            MusicKind.PLAYLISTS -> {
                val rows = api(token, "/users/$owner/playlists/list") as JSONArray
                MusicPage(rows.objects().drop(page * PAGE_SIZE).take(PAGE_SIZE).mapNotNull { entry(it, kind, owner) }, next(page, rows.length()))
            }
            MusicKind.ARTISTS, MusicKind.ALBUMS -> {
                val type = kind.name.lowercase()
                val rows = api(token, "/users/$owner/likes/$type?rich=true&with-timestamps=true") as JSONArray
                MusicPage(rows.objects().drop(page * PAGE_SIZE).take(PAGE_SIZE).mapNotNull {
                    entry(it.optJSONObject(type.removeSuffix("s")) ?: it, kind)
                }, next(page, rows.length()))
            }
        }
    }

    private suspend fun recommendations(token: String): MusicPage {
        val entries = mutableListOf<MusicEntry>()
        var failure: Exception? = null
        try {
            val landing = api(token, "/landing3?blocks=personalplaylists,playlists") as JSONObject
            landing.optJSONArray("blocks")?.objects()?.forEach { block ->
                block.optJSONArray("entities")?.objects()?.forEach entity@{ entity ->
                    val data = entity.optJSONObject("data") ?: return@entity
                    if (data.has("ready") && !data.optBoolean("ready")) return@entity
                    entry(data.optJSONObject("data") ?: data.optJSONObject("playlist") ?: data, MusicKind.PLAYLISTS)?.let(entries::add)
                }
            }
        } catch (e: CancellationException) { throw e } catch (e: Exception) { failure = e }
        if (entries.isEmpty()) {
            try {
                val feed = api(token, "/feed") as JSONObject
                feed.optJSONArray("generatedPlaylists")?.objects()?.forEach {
                    if (!it.has("ready") || it.optBoolean("ready")) entry(it.optJSONObject("data") ?: it, MusicKind.PLAYLISTS)?.let(entries::add)
                }
            } catch (e: CancellationException) { throw e } catch (e: Exception) { throw failure ?: e }
        }
        return MusicPage(entries.distinctBy(MusicEntry::id))
    }

    private suspend fun details(token: String, entity: MusicEntity, page: Int, section: MusicKind): MusicPage = when (entity.kind) {
        MusicKind.ALBUMS -> {
            val album = api(token, "/albums/${numeric(entity.id)}/with-tracks") as JSONObject
            val rows = JSONArray()
            val volumes = album.optJSONArray("volumes") ?: JSONArray()
            for (i in 0 until volumes.length()) volumes.optJSONArray(i)?.objects()?.forEach { rows.put(it) }
            MusicPage(rows.objects().drop(page * PAGE_SIZE).take(PAGE_SIZE).mapNotNull { trackEntry(it, album) }, next(page, rows.length()))
        }
        MusicKind.ARTISTS -> {
            if (section == MusicKind.ALBUMS) {
                val result = api(token, "/artists/${numeric(entity.id)}/direct-albums?page=$page&page-size=$PAGE_SIZE&sort-by=year") as JSONObject
                val rows = result.optJSONArray("albums") ?: JSONArray()
                val entries = rows.objects().mapNotNull { entry(it, MusicKind.ALBUMS) }
                val pager = result.optJSONObject("pager")
                val hasNext = if (pager != null && pager.has("total")) (page.toLong() + 1) * pager.optInt("perPage", PAGE_SIZE).coerceAtLeast(1) < pager.getLong("total") else rows.length() >= PAGE_SIZE
                MusicPage(entries, if (hasNext && entries.isNotEmpty()) page + 1 else null)
            } else {
                val result = api(token, "/artists/${numeric(entity.id)}/brief-info") as JSONObject
                val rows = result.optJSONArray("popularTracks") ?: JSONArray()
                MusicPage(rows.objects().drop(page * PAGE_SIZE).take(PAGE_SIZE).mapNotNull { trackEntry(it) }, next(page, rows.length()))
            }
        }
        MusicKind.PLAYLISTS -> {
            val list = api(token, "/users/${numeric(entity.ownerId.orEmpty())}/playlists/${numeric(entity.id)}") as JSONObject
            resolveRows(token, list.optJSONArray("tracks") ?: JSONArray(), page)
        }
        MusicKind.TRACKS -> throw MusicException(MusicFailure.UNAVAILABLE)
    }

    /** Page metadata first; only this page's unresolved IDs are fetched. Mixed embedded/ID rows retain order. */
    private suspend fun resolveRows(token: String, rows: JSONArray, page: Int): MusicPage {
        val slice = rows.objects().drop(page * PAGE_SIZE).take(PAGE_SIZE)
        val missing = slice.filter { it.optJSONObject("track") == null }.mapNotNull(::key)
        val resolved = tracks(token, missing).associateBy { it.id.removePrefix("yandex:").substringBefore(':') }
        val entries = slice.mapNotNull { row ->
            row.optJSONObject("track")?.let { trackEntry(it) } ?: key(row)?.let { resolved[it.substringBefore(':')]?.let(::asEntry) }
        }
        return MusicPage(entries, next(page, rows.length()))
    }

    internal suspend fun tracks(token: String, ids: List<String>): List<Track> {
        if (ids.isEmpty()) return emptyList()
        val result = mutableListOf<Track>()
        for (batch in ids.chunked(PAGE_SIZE)) {
            val rows = api(token, "/tracks", listOf("with-positions" to "true") + batch.map { "track-ids" to trackKey(it) }) as JSONArray
            val parsed = rows.objects().mapNotNull { trackEntry(it)?.track }.associateBy { it.id.removePrefix("yandex:").substringBefore(':') }
            result += batch.mapNotNull { parsed[it.substringBefore(':')] }
        }
        return result
    }

    override suspend fun stream(profileId: String, trackId: String, quality: AudioQuality): String = safe {
        require(trackId.startsWith("yandex:"))
        val id = trackKey(trackId.removePrefix("yandex:"))
        accounts.withSession(profileId) { session ->
            val variants = api(session.credentials.accessToken, "/tracks/$id/download-info") as JSONArray
            // 1.x chooseDownloadInfo: best <= target, otherwise closest above it.
            // AUTO/MAX choose the highest bitrate. Preserve the small MP3 bonus and skip previews.
            val best = variants.objects().filter { !it.optBoolean("preview", false) }
                .maxByOrNull {
                    val bitrate = it.optInt("bitrateInKbps", 0).toLong()
                    val bonus = if (it.optString("codec").equals("mp3", true)) 2 else 0
                    val target = quality.targetKbps
                    when {
                        target == null -> bitrate + bonus
                        bitrate <= target -> 200_000 + bitrate + bonus
                        else -> 100_000 - kotlin.math.abs(bitrate - target) + bonus
                    }
                }
                ?: throw MusicException(MusicFailure.UNAVAILABLE)
            val infoUrl = secureUrl(best.getString("downloadInfoUrl"))
            // The signed media URL itself grants access. OAuth never leaves the API origin.
            buildDirectLink(transport.request(infoUrl, null, null))
        }
    }

    private fun entry(item: JSONObject, kind: MusicKind, owner: String? = null): MusicEntry? {
        if (kind == MusicKind.TRACKS) return trackEntry(item.optJSONObject("track") ?: item)
        val id = item.optString(if (kind == MusicKind.PLAYLISTS) "kind" else "id")
        if (!id.matches(NUMERIC)) return null
        val title = item.optString(if (kind == MusicKind.ARTISTS) "name" else "title").take(500)
        if (title.isBlank()) return null
        val uid = if (kind == MusicKind.PLAYLISTS) item.optJSONObject("owner")?.optString("uid")?.takeIf { it.matches(NUMERIC) } ?: owner else null
        if (kind == MusicKind.PLAYLISTS && uid == null) return null
        val subtitle = when (kind) {
            MusicKind.ALBUMS -> artists(item) + item.optInt("year").takeIf { it > 0 }?.let { " · $it" }.orEmpty()
            MusicKind.PLAYLISTS -> "${item.optInt("trackCount", 0)} треков"
            else -> "Исполнитель"
        }
        return MusicEntry("${kind.name}:$uid:$id", title, subtitle, entity = MusicEntity(id, title, kind, uid))
    }

    internal fun trackEntry(item: JSONObject, fallbackAlbum: JSONObject? = null): MusicEntry? {
        val rawId = item.optString("id")
        if (!rawId.matches(NUMERIC)) return null
        val album = item.optJSONArray("albums")?.optJSONObject(0) ?: fallbackAlbum
        val albumId = album?.optString("id")?.takeIf { it.matches(NUMERIC) }
        val title = item.optString("title").ifBlank { rawId }.take(500)
        val cover = item.optString("coverUri").ifBlank { album?.optString("coverUri").orEmpty() }
        val artwork = cover.takeIf { it.isNotBlank() }?.let { runCatching { secureUrl(it.replace("%%", "400x400")) }.getOrNull() }
        val track = Track("yandex:$rawId" + albumId?.let { ":$it" }.orEmpty(), title,
            artists(item).ifBlank { album?.let(::artists).orEmpty() }, album?.optString("title").orEmpty().take(500),
            Source.YANDEX, (item.optLong("durationMs", item.optLong("duration", 0)) / 1000).coerceIn(0, Int.MAX_VALUE.toLong()).toInt(),
            offline = false, available = item.optBoolean("available", true), genre = album?.optString("genre").orEmpty(), folder = "Яндекс Музыка", artworkUri = artwork,
            artists = artistRefs(item).ifEmpty { album?.let(::artistRefs).orEmpty() }, albumId = albumId)
        return asEntry(track)
    }

    private fun asEntry(track: Track) = MusicEntry(track.id, track.title, track.artist, track = track)
    private fun artists(item: JSONObject) = item.optJSONArray("artists")?.objects()?.map { it.optString("name") }?.filter(String::isNotBlank)?.joinToString(", ").orEmpty().take(500)
    private fun artistRefs(item: JSONObject) = item.optJSONArray("artists")?.objects()?.mapNotNull {
        val id = it.optString("id"); val name = it.optString("name").take(500)
        if (id.matches(NUMERIC) && name.isNotBlank()) ArtistRef(id, name) else null
    }?.distinctBy(ArtistRef::id).orEmpty()
    private fun key(item: JSONObject): String? = item.optString("id").takeIf { it.matches(NUMERIC) }?.let { id ->
        id + item.optString("albumId").takeIf { it.matches(NUMERIC) }?.let { ":$it" }.orEmpty()
    }
    private fun next(page: Int, count: Int) = if ((page.toLong() + 1) * PAGE_SIZE < count) page + 1 else null
    private fun numeric(value: String): String { require(value.matches(NUMERIC)); return value }
    private fun trackKey(value: String): String { require(value.matches(Regex("[0-9]+(:[0-9]+)?")) && value.length <= 80); return value }
    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")
    private suspend fun <T> safe(action: suspend () -> T): T = try { action() }
        catch (e: CancellationException) { throw e }
        catch (e: MusicException) { throw e }
        catch (_: Exception) { throw MusicException(MusicFailure.RESPONSE) }

    companion object {
        private const val PAGE_SIZE = 50
        private val NUMERIC = Regex("[0-9]{1,30}")
        fun secureUrl(value: String): String {
            val url = when { value.startsWith("http://") -> "https://" + value.removePrefix("http://"); value.startsWith("https://") -> value; else -> "https://$value" }
            if (!isYandexMediaUrl(url)) throw MusicException(MusicFailure.RESPONSE)
            return url
        }
        fun buildDirectLink(xml: String): String {
            if (xml.length > 65536 || xml.contains("<!DOCTYPE", true) || xml.contains("<!ENTITY", true)) throw MusicException(MusicFailure.RESPONSE)
            val factory = DocumentBuilderFactory.newInstance().apply { isExpandEntityReferences = false }
            val document = factory.newDocumentBuilder().parse(xml.byteInputStream())
            fun tag(name: String): String {
                val nodes = document.getElementsByTagName(name)
                if (nodes.length != 1) throw MusicException(MusicFailure.RESPONSE)
                return nodes.item(0).textContent
            }
            val host = tag("host"); val path = tag("path"); val ts = tag("ts"); val salt = tag("s")
            // 1.x passes ts through verbatim. Real Yandex XML uses hex, e.g. 1a093e33038.
            // Validate only that it stays a single safe URI segment; never parse it as a number.
            if (!host.matches(Regex("[A-Za-z0-9.-]+")) || !path.startsWith('/') || path.startsWith("//") || !ts.matches(Regex("[A-Za-z0-9_-]+"))) throw MusicException(MusicFailure.RESPONSE)
            val signature = MessageDigest.getInstance("MD5").digest(("XGRlBW9FXlekgbPrRHuSiA" + path.removePrefix("/") + salt).toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            return secureUrl("https://$host/get-mp3/$signature/$ts$path")
        }
    }
}

internal fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }
