package dev.petrov.ymplayer2.yandex

import dev.petrov.ymplayer2.core.*
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import javax.net.ssl.HttpsURLConnection
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

fun interface MusicTransport {
    suspend fun request(url: String, token: String?, form: List<Pair<String, String>>?): String
}

/** OAuth is only sent to the API origin, never to media/cover servers or redirects. */
class HttpsMusicTransport(private val open: (String) -> HttpsURLConnection = { URL(it).openConnection() as HttpsURLConnection }) : MusicTransport {
    override suspend fun request(url: String, token: String?, form: List<Pair<String, String>>?): String = withContext(Dispatchers.IO) {
        val uri = runCatching { URI(url) }.getOrNull() ?: throw MusicException(MusicFailure.RESPONSE)
        if (!isYandexMediaUrl(url) || (token != null && uri.host != "api.music.yandex.net")) throw MusicException(MusicFailure.RESPONSE)
        suspendCancellableCoroutine { continuation ->
            var connection: HttpsURLConnection? = null
            try {
                val c = open(url); connection = c
                continuation.invokeOnCancellation { c.disconnect() }
                c.connectTimeout = 15_000; c.readTimeout = 20_000; c.instanceFollowRedirects = false
                c.setRequestProperty("User-Agent", "Yandex-Music-API")
                c.setRequestProperty("X-Yandex-Music-Client", "YandexMusicAndroid/24023621")
                c.setRequestProperty("Accept-Language", "ru")
                token?.let { c.setRequestProperty("Authorization", "OAuth $it") }
                if (form != null) {
                    c.requestMethod = "POST"; c.doOutput = true
                    c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                    val bytes = form.joinToString("&") { (key, value) -> "${URLEncoder.encode(key, "UTF-8")}=${URLEncoder.encode(value, "UTF-8")}" }.toByteArray(Charsets.UTF_8)
                    c.setFixedLengthStreamingMode(bytes.size)
                    c.outputStream.use { it.write(bytes) }
                }
                when (val status = c.responseCode) {
                    401, 403 -> throw MusicException(MusicFailure.ACCESS)
                    404 -> throw MusicException(MusicFailure.UNAVAILABLE)
                    408, 429, in 500..599 -> throw MusicException(MusicFailure.NETWORK)
                    !in 200..299 -> throw MusicException(MusicFailure.RESPONSE)
                }
                val bytes = ByteArrayOutputStream()
                c.inputStream.use { input ->
                    val buffer = ByteArray(8192)
                    while (continuation.isActive) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (bytes.size() + count > 8 * 1024 * 1024) throw MusicException(MusicFailure.RESPONSE)
                        bytes.write(buffer, 0, count)
                    }
                }
                if (continuation.isActive) continuation.resume(bytes.toString("UTF-8"))
            } catch (e: Exception) {
                if (continuation.isActive) continuation.resumeWithException(when (e) {
                    is MusicException -> e
                    is IOException -> MusicException(MusicFailure.NETWORK)
                    else -> MusicException(MusicFailure.RESPONSE)
                })
            } finally { connection?.disconnect() }
        }
    }
}

fun isYandexMediaUrl(value: String): Boolean = runCatching {
    val uri = URI(value)
    val host = uri.host?.lowercase().orEmpty()
    uri.scheme == "https" && uri.userInfo == null && uri.port in listOf(-1, 443) && uri.fragment == null &&
        listOf("yandex.net", "yandex.ru", "yandex.com").any { host.endsWith(".$it") }
}.getOrDefault(false)
