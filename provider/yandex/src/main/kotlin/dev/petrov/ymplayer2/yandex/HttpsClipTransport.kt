package dev.petrov.ymplayer2.yandex

import dev.petrov.ymplayer2.core.MusicException
import dev.petrov.ymplayer2.core.MusicFailure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import java.net.URL
import javax.net.ssl.HttpsURLConnection

interface ClipTransport {
    suspend fun get(url: String, token: String): String
    suspend fun post(url: String, token: String, body: JSONObject): String
}

/** Sends OAuth only to the two exact clip API origins; signed stream URLs are played without it. */
class HttpsClipTransport(private val open: (String) -> HttpsURLConnection = { URL(it).openConnection() as HttpsURLConnection }) : ClipTransport {
    override suspend fun get(url: String, token: String) = execute(url, token, null)
    override suspend fun post(url: String, token: String, body: JSONObject) = execute(url, token, body.toString())

    private suspend fun execute(url: String, token: String, body: String?): String = withContext(Dispatchers.IO) {
        val uri = runCatching { URI(url) }.getOrNull() ?: throw MusicException(MusicFailure.RESPONSE)
        val host = uri.host?.lowercase()
        if (uri.scheme != "https" || uri.port !in listOf(-1, 443) || uri.userInfo != null || uri.fragment != null ||
            host !in setOf("api.music.yandex.net", "frontend.vh.yandex.ru") ||
            (body != null && host != "api.music.yandex.net") || token.isBlank()) throw MusicException(MusicFailure.RESPONSE)
        val connection = open(url)
        try {
            connection.connectTimeout = 12_000
            connection.readTimeout = 25_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Accept-Language", "ru")
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10; YMPlayer) YandexMusicAndroidTVKPHD/2.256.1")
            connection.setRequestProperty("X-Yandex-Music-Client", "YandexMusicAndroidTVKPHD/2.256.1")
            connection.setRequestProperty("Authorization", "OAuth $token")
            if (body != null) {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                val bytes = body.toByteArray(Charsets.UTF_8)
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }
            when (val status = connection.responseCode) {
                401, 403 -> throw MusicException(MusicFailure.ACCESS, status)
                404 -> throw MusicException(MusicFailure.UNAVAILABLE, status)
                408, 429, in 500..599 -> throw MusicException(MusicFailure.NETWORK, status)
                !in 200..299 -> throw MusicException(MusicFailure.RESPONSE, status)
            }
            val output = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > 8 * 1024 * 1024) throw MusicException(MusicFailure.RESPONSE)
                    output.write(buffer, 0, count)
                }
            }
            output.toString("UTF-8")
        } catch (e: MusicException) { throw e }
          catch (_: IOException) { throw MusicException(MusicFailure.NETWORK) }
          catch (_: Exception) { throw MusicException(MusicFailure.RESPONSE) }
        finally { connection.disconnect() }
    }
}
