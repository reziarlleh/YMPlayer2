package dev.petrov.ymplayer2.yandex

import dev.petrov.ymplayer2.core.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import javax.net.ssl.HttpsURLConnection
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

fun interface RadioTransport { suspend fun request(path: String, token: String?, body: String?): String }

/** Fixed API origin; redirects never carry OAuth. Media and covers have no credentials. */
class HttpsRadioTransport(private val open: (String) -> HttpsURLConnection = { URL(it).openConnection() as HttpsURLConnection }) : RadioTransport {
    override suspend fun request(path: String, token: String?, body: String?): String = withContext(Dispatchers.IO) {
        val url = "https://radio.api.music.yandex.ru$path"
        val uri = runCatching { URI(url) }.getOrNull()
        if (uri?.host != "radio.api.music.yandex.ru" || uri.scheme != "https" || uri.userInfo != null ||
            uri.port != -1 || uri.fragment != null || !uri.path.startsWith("/radio/v1/")) throw RadioException(RadioIssue.RESPONSE)
        suspendCancellableCoroutine { continuation ->
            var connection: HttpsURLConnection? = null
            try {
                val c = open(url); connection = c
                continuation.invokeOnCancellation { c.disconnect() }
                c.connectTimeout = 15_000; c.readTimeout = 20_000; c.instanceFollowRedirects = false
                c.setRequestProperty("Accept", "application/json")
                c.setRequestProperty("X-Yandex-Music-Client", "YandexMusicWebRadio/1.0.0")
                c.setRequestProperty("User-Agent", "YMPlayer2-Radio/1")
                token?.let { c.setRequestProperty("Authorization", "OAuth $it") }
                if (body != null) {
                    c.requestMethod = "POST"; c.doOutput = true
                    c.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                    val bytes = body.toByteArray(Charsets.UTF_8); c.setFixedLengthStreamingMode(bytes.size)
                    c.outputStream.use { it.write(bytes) }
                }
                when (c.responseCode) {
                    401, 403 -> throw RadioException(RadioIssue.ACCESS)
                    404, 451 -> throw RadioException(RadioIssue.UNAVAILABLE)
                    408, 429, in 500..599 -> throw RadioException(RadioIssue.NETWORK)
                    !in 200..299 -> throw RadioException(RadioIssue.RESPONSE)
                }
                val bytes = ByteArrayOutputStream()
                c.inputStream.use { input ->
                    val buffer = ByteArray(8192)
                    while (continuation.isActive) {
                        val count = input.read(buffer); if (count < 0) break
                        if (bytes.size() + count > 8 * 1024 * 1024) throw RadioException(RadioIssue.RESPONSE)
                        bytes.write(buffer, 0, count)
                    }
                }
                if (continuation.isActive) continuation.resume(bytes.toString("UTF-8"))
            } catch (e: Exception) { if (continuation.isActive) continuation.resumeWithException(
                if (e is RadioException) e else RadioException(if (e is IOException) RadioIssue.NETWORK else RadioIssue.RESPONSE)) }
            finally { connection?.disconnect() }
        }
    }
}

class YandexRadioApi(private val accounts: AccountAuth, private val http: RadioTransport = HttpsRadioTransport(),
    private val now: () -> Long = System::currentTimeMillis) : RadioApi {
    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    private fun query(vararg values: Pair<String, String?>) = values.filter { it.second != null }
        .joinToString("&", prefix = "?") { "${it.first}=${encode(it.second!!)}" }
    private suspend fun get(path: String) = parse(http.request("/radio/v1/$path", null, null))
    private fun parse(body: String): JSONObject = try { JSONObject(body) } catch (_: Exception) { throw RadioException(RadioIssue.RESPONSE) }
    private suspend fun <T> personal(profile: String, action: suspend (String) -> T): T = accounts.withSession(profile) { action(it.credentials.accessToken) }
    override suspend fun stations(region: String?, cursor: String?) = page(get("stations" + query("userRegion" to region.orEmpty(), "limit" to "50", "continueFrom" to cursor)))
    override suspend fun station(slug: String, region: String?) = radioStation(get("stations/${encode(slug)}" + query("userRegion" to region.orEmpty())))
    override suspend fun cities() = filters(get("regions").requiredArray("cities"))
    override suspend fun genres() = filters(get("genres").requiredArray("genres"))
    override suspend fun city(slug: String, cursor: String?) = page(get("regions/${encode(slug)}/streams" + query("continueFrom" to cursor)), streams = true)
    override suspend fun genre(slug: String, region: String?, cursor: String?) = page(get("genres/${encode(slug)}/stations" + query("userRegion" to region.orEmpty(), "continueFrom" to cursor)))
    override suspend fun search(query: String, region: String?, cursor: String?): RadioPage {
        val data = get("search" + this.query("query" to query, "userRegion" to region.orEmpty(), "limit" to "50", "continueFrom" to cursor))
        val rows = data.requiredArray("items")
        val stations = (0 until rows.length()).mapNotNull { i -> rows.requiredObject(i).let { item ->
            if (item.optString("searchItemType") == "station") radioStation(item.requiredObject("station")) else null } }
        return result(data, stations)
    }
    override suspend fun favourites(profile: String, region: String?, cursor: String?) = personal(profile) { token ->
        page(parse(http.request("/radio/v1/collection/stations" + query("userRegion" to region.orEmpty(), "limit" to "50", "continueFrom" to cursor), token, null))) }
    override suspend fun favouriteSlugs(profile: String) = personal(profile) { token ->
        val rows = parse(http.request("/radio/v1/collection/stations/slugs", token, null)).requiredArray("slugs")
        (0 until rows.length()).map { rows.requiredString(it) }.toSet() }
    override suspend fun setFavourite(profile: String, slug: String, liked: Boolean) = personal(profile) { token ->
        val body = if (liked) JSONObject().put("stations", JSONArray().put(JSONObject().put("slug", slug).put("timestamp", now() / 1000)))
            else JSONObject().put("slugs", JSONArray().put(slug))
        http.request("/radio/v1/collection/stations${if (liked) "" else "/bulk-delete"}", token, body.toString()); Unit }
    override suspend fun stream(station: RadioStation, region: String?): RadioStream {
        val selected = if (station.streamSlug != null) station else station(station.slug, region)
        val streamSlug = selected.streamSlug ?: throw RadioException(RadioIssue.RESPONSE)
        val info = get("stations/${encode(station.slug)}/streams/${encode(streamSlug)}")
        val stream = radioStation(info, stream = true)
        val url = get("stations/${encode(station.slug)}/streams/${encode(streamSlug)}/url").requiredString("url")
        if (!publicRadioUrl(url)) throw RadioException(RadioIssue.UNAVAILABLE)
        return RadioStream(stream, streamSlug, url)
    }
    override suspend fun onAir(stationSlug: String, streamSlug: String): RadioOnAir {
        val data = get("stations/${encode(stationSlug)}/streams/${encode(streamSlug)}/widgets")
        val rows = data.requiredArray("widgets")
        val track = (0 until rows.length()).map { rows.requiredObject(it) }.firstOrNull { it.optString("type") == "music_track" }?.optJSONObject("track")
        return RadioOnAir(track?.optString("title").orEmpty(), track?.optString("artist").orEmpty(), data.optLong("pollAfterMs", 30_000).coerceIn(5_000, 60_000))
    }
    private fun filters(rows: JSONArray) = (0 until rows.length()).map { rows.requiredObject(it).let { RadioFilter(it.requiredString("slug"), it.requiredString("name")) } }
    private fun page(data: JSONObject, streams: Boolean = false): RadioPage {
        val rows = data.requiredArray(if (streams) "streams" else "stations")
        return result(data, (0 until rows.length()).map { radioStation(rows.requiredObject(it), streams) })
    }
    private fun result(data: JSONObject, stations: List<RadioStation>): RadioPage {
        val cursor = data.optString("continueFrom").takeIf { it.isNotBlank() && it != "null" }
        return RadioPage(stations, data.optBoolean("hasNext") && cursor != null, cursor)
    }
    private fun radioStation(row: JSONObject, stream: Boolean = false): RadioStation {
        val data = row.optJSONObject("station") ?: row
        val identity = if (stream) data.requiredObject("compactStation") else data
        val card = data.optJSONObject("cardInfo")
        val logo = card?.optString("logo")?.takeIf { it.isNotBlank() }?.let {
            (if (it.startsWith("https://")) it else "https://$it").replace("%%", "200x200").takeIf(::publicRadioUrl) }
        return RadioStation(identity.requiredString("slug"), identity.requiredString("name"), logo,
            card?.optString("logoBackgroundColor")?.takeIf { it.matches(Regex("#[0-9a-fA-F]{6}")) },
            (if (stream) data.optString("shortSlug") else data.optString("userDefaultStreamSlug")).takeIf { it.isNotBlank() && it != "null" },
            data.optJSONObject("region")?.optString("name"),
            card?.optString("description")?.takeIf { it.isNotBlank() }?.let {
                android.text.Html.fromHtml(it, android.text.Html.FROM_HTML_MODE_LEGACY).toString().trim()
            })
    }
}
fun publicRadioUrl(value: String): Boolean = runCatching { URI(value).let {
    it.scheme == "https" && !it.host.isNullOrBlank() && it.userInfo == null && it.port in listOf(-1, 443) && it.fragment == null
} }.getOrDefault(false)

private fun JSONObject.requiredArray(key: String): JSONArray = optJSONArray(key) ?: throw RadioException(RadioIssue.RESPONSE)
private fun JSONObject.requiredObject(key: String): JSONObject = optJSONObject(key) ?: throw RadioException(RadioIssue.RESPONSE)
private fun JSONObject.requiredString(key: String): String = opt(key).let { it as? String ?: throw RadioException(RadioIssue.RESPONSE) }
private fun JSONArray.requiredObject(index: Int): JSONObject = optJSONObject(index) ?: throw RadioException(RadioIssue.RESPONSE)
private fun JSONArray.requiredString(index: Int): String = opt(index).let { it as? String ?: throw RadioException(RadioIssue.RESPONSE) }
