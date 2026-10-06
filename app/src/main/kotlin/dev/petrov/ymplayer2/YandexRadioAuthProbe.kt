package dev.petrov.ymplayer2

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import javax.net.ssl.HttpsURLConnection

internal data class RadioProbeResponse(val status: Int? = null, val body: String? = null, val failure: String? = null)
internal fun interface RadioProbeTransport {
    suspend fun get(url: String, token: String?): RadioProbeResponse
}

/** A separate read-only transport: never relax the production Music/media origin boundary. */
internal class RadioProbeHttp(private val open: (String) -> HttpsURLConnection = { URL(it).openConnection() as HttpsURLConnection }) : RadioProbeTransport {
    override suspend fun get(url: String, token: String?): RadioProbeResponse = withContext(Dispatchers.IO) {
        val target = URL(url)
        require(target.protocol == "https" && target.host in setOf("api.music.yandex.net", "radio.api.music.yandex.ru") &&
            target.port == -1 && target.userInfo == null && target.query == null)
        val connection = open(url)
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            connection.instanceFollowRedirects = false
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("X-Yandex-Music-Client", "YandexMusicWebRadio/1.0.0")
            if (token != null) connection.setRequestProperty("Authorization", "OAuth $token")
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val bytes = stream?.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (out.size() + count > 2 * 1024 * 1024) return@withContext RadioProbeResponse(status, failure = "BODY_LIMIT")
                    out.write(buffer, 0, count)
                }
                out.toByteArray()
            }
            RadioProbeResponse(status, bytes?.toString(Charsets.UTF_8))
        } catch (e: CancellationException) { throw e }
        catch (_: java.net.SocketTimeoutException) { RadioProbeResponse(failure = "TIMEOUT") }
        catch (_: javax.net.ssl.SSLException) { RadioProbeResponse(failure = "TLS") }
        catch (_: java.io.IOException) { RadioProbeResponse(failure = "NETWORK") }
        finally { connection.disconnect() }
    }
}

/** Only statuses, counts and booleans may reach diagnostics; raw responses and UIDs stay local. */
internal class RadioProbeReport(private val safe: JSONObject) {
    fun diagnosticText(): String = safe.toString(2)
    override fun toString() = "RadioProbeReport(redacted)"
    val summary: String get() = when {
        safe.optBoolean("personalAccountIdentityVerified") -> "Вход в Радио и совпадение аккаунта подтверждены. Результат сохранён в диагностике."
        safe.optBoolean("existingTokenVerifiedForFmCollection") && safe.optBoolean("fmUidPresent") -> "Избранные станции прочитаны, но UID Радио и Музыки не совпали. Сохрани диагностику для разбора результата."
        safe.optBoolean("existingTokenVerifiedForFmCollection") -> "Токен принят: избранные станции прочитаны. Радио не вернуло UID для отдельной сверки аккаунта. Результат в диагностике."
        else -> "Проверка завершена без подтверждения входа. Сохрани диагностику для разбора результата."
    }
}

internal class YandexRadioAuthProbe(private val http: RadioProbeTransport = RadioProbeHttp()) {
    companion object {
        const val FM = "https://radio.api.music.yandex.ru"
        const val MUSIC = "https://api.music.yandex.net"
        const val STATIONS = "/radio/v1/collection/stations/slugs"
        const val ABOUT = "/account/about"
        const val TRACKS = "/radio/v1/collection/tracks/ids"
        const val INVALID = "YMPlayer2-intentionally-invalid-probe-token"
    }

    suspend fun run(token: String, expectedUid: String?): RadioProbeReport {
        val safe = JSONObject().put("probe", "YandexRadioExistingMusicOAuth")
            .put("schema", 1).put("checkedAtUtcMillis", System.currentTimeMillis())
            .put("readOnly", true).put("newTokenRequested", false).put("browserCookiesUsed", false)
        val music = http.get(MUSIC + "/account/status", token)
        val musicUid = uid(parse(music.body))
        val profileMatches = musicUid != null && expectedUid != null && musicUid == expectedUid
        safe.put("musicAccount", metadata(music)).put("musicUidPresent", musicUid != null)
            .put("musicUidMatchesSavedProfile", profileMatches)
        if (music.status != 200 || !profileMatches) {
            return RadioProbeReport(safe.put("existingTokenVerifiedForFmCollection", false)
                .put("personalAccountIdentityVerified", false).put("stoppedBeforeFm", true))
        }
        val controls = JSONObject()
        var protected = true
        var stationRead = false
        var fmUid: String? = null
        for ((label, credential) in listOf("anonymous" to null, "invalidOAuth" to INVALID, "existingMusicOAuth" to token)) {
            val rows = JSONObject()
            for (path in listOf(ABOUT, STATIONS, TRACKS)) {
                val result = http.get(FM + path, credential)
                val data = parse(result.body)
                val row = metadata(result)
                if (path != ABOUT && result.status == 200) count(data)?.let { row.put("itemCount", it) }
                rows.put(path, row)
                if (path == STATIONS) {
                    if (label != "existingMusicOAuth") protected = protected && result.status in setOf(400, 401, 403)
                    else stationRead = result.status == 200 && count(data) != null
                }
                if (path == ABOUT && label == "existingMusicOAuth" && result.status == 200) fmUid = uid(data)
            }
            controls.put(label, rows)
        }
        val access = protected && stationRead
        safe.put("controls", controls).put("favouriteStationsReadableWithOAuth", access)
            .put("existingTokenVerifiedForFmCollection", access).put("fmUidPresent", fmUid != null)
            .put("accountUidMatches", fmUid?.let { it == musicUid } ?: JSONObject.NULL)
            .put("personalAccountIdentityVerified", access && fmUid != null && fmUid == musicUid)
        return RadioProbeReport(safe)
    }

    private fun parse(body: String?): Any? = try {
        when (body?.trim()?.firstOrNull()) { '{' -> JSONObject(body); '[' -> JSONArray(body); else -> null }
    } catch (_: Exception) { null }

    private fun metadata(response: RadioProbeResponse) = JSONObject().apply {
        put("status", response.status ?: JSONObject.NULL)
        put("json", parse(response.body) != null)
        response.failure?.let { put("failure", it) }
    }

    private fun uid(value: Any?): String? {
        val obj = value as? JSONObject ?: return null
        for (key in listOf("uid", "puid", "passportUid", "passport_uid")) {
            val id = obj.optString(key)
            if (id.isNotEmpty() && id.all { it in '0'..'9' }) return id
        }
        return listOf("result", "account", "user", "data").firstNotNullOfOrNull { uid(obj.opt(it)) }
    }

    private fun count(value: Any?): Int? {
        if (value is JSONArray) return value.length()
        val obj = value as? JSONObject ?: return null
        for (key in listOf("items", "slugs", "ids", "stationSlugs", "trackIds")) obj.optJSONArray(key)?.let { return it.length() }
        return listOf("result", "data").firstNotNullOfOrNull { count(obj.opt(it)) }
    }
}
