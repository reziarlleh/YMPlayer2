package dev.petrov.ymplayer2.yandex

import android.content.Context
import dev.petrov.ymplayer2.core.*
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URL
import java.net.URLEncoder
import java.net.UnknownHostException
import java.net.SocketTimeoutException
import java.util.UUID
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

enum class YandexEndpoint(val url: String) {
    CODE("https://oauth.yandex.ru/device/code"), TOKEN("https://oauth.yandex.ru/token"),
    ACCOUNT("https://api.music.yandex.net/account/status"),
}
class AuthRequest(val endpoint: YandexEndpoint, val form: Map<String, String> = emptyMap(), val token: String? = null) {
    override fun toString() = "AuthRequest(${endpoint.name}, redacted)"
}
class AuthReply(val status: Int, val body: JSONObject)
fun interface AuthTransport { suspend fun request(request: AuthRequest): AuthReply }

/** Fixed HTTPS destinations, standard certificate checks, no redirects or response logging. */
class HttpsAuthTransport(private val open: (YandexEndpoint) -> HttpsURLConnection = { URL(it.url).openConnection() as HttpsURLConnection }) : AuthTransport {
    override suspend fun request(request: AuthRequest): AuthReply = withContext(Dispatchers.IO) {
        suspendCancellableCoroutine { continuation ->
            val connection = open(request.endpoint)
            continuation.invokeOnCancellation { connection.disconnect() }
            try {
                connection.connectTimeout = 15_000; connection.readTimeout = 20_000
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("Accept", "application/json")
                // Keep the request identity used by the working 1.x Music adapter.
                connection.setRequestProperty("User-Agent", "Yandex-Music-API")
                connection.setRequestProperty("X-Yandex-Music-Client", "YandexMusicAndroid/24023621")
                connection.setRequestProperty("Accept-Language", "ru")
                request.token?.let { connection.setRequestProperty("Authorization", "OAuth $it") }
                if (request.endpoint != YandexEndpoint.ACCOUNT) {
                    connection.requestMethod = "POST"; connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                    val body = request.form.entries.joinToString("&") { (key, value) -> "${URLEncoder.encode(key, "UTF-8")}=${URLEncoder.encode(value, "UTF-8")}" }.toByteArray(Charsets.UTF_8)
                    connection.setFixedLengthStreamingMode(body.size)
                    connection.outputStream.use { it.write(body) }
                }
                val status = connection.responseCode
                // Outages often return HTML; classify the HTTP status before attempting JSON.
                if (status == 408 || status == 429 || status in 500..599) throw AuthException(AuthFailure.NETWORK, NetworkIssue.SERVICE,
                    connection.getHeaderField("Retry-After")?.toLongOrNull()?.coerceIn(1, 3600))
                if (status in 300..399) throw AuthException(AuthFailure.RESPONSE)
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                val bytes = ByteArrayOutputStream()
                stream?.use { input ->
                    val buffer = ByteArray(4096)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (bytes.size() + count > 65536) throw AuthException(AuthFailure.RESPONSE)
                        bytes.write(buffer, 0, count)
                    }
                }
                val reply = AuthReply(status, if (bytes.size() == 0) JSONObject() else JSONObject(bytes.toString("UTF-8")))
                continuation.resume(reply)
            } catch (e: Exception) {
                if (continuation.isActive) continuation.resumeWithException(when (e) {
                    is AuthException -> e
                    is IOException -> AuthException(AuthFailure.NETWORK, when (e) {
                        is UnknownHostException -> NetworkIssue.DNS
                        is SocketTimeoutException -> NetworkIssue.TIMEOUT
                        is SSLException -> NetworkIssue.TLS
                        else -> NetworkIssue.CONNECTION
                    })
                    else -> AuthException(AuthFailure.RESPONSE)
                })
            } finally { connection.disconnect() }
        }
    }
}

class YandexDeviceApi(
    context: Context, private val clientId: String, private val clientSecret: String,
    private val transport: AuthTransport = HttpsAuthTransport(), private val now: () -> Long = System::currentTimeMillis,
) : DeviceAuthApi {
    private val preferences = context.applicationContext.getSharedPreferences("yandex-devices", Context.MODE_PRIVATE)
    override val configured get() = clientId.isNotBlank()
    override suspend fun requestCode(profileId: String): DeviceChallenge {
        if (!configured) throw AuthException(AuthFailure.CLIENT)
        val deviceId = withContext(Dispatchers.IO) {
            preferences.getString(profileId, null) ?: UUID.randomUUID().toString().also {
                if (!preferences.edit().putString(profileId, it).commit()) throw AuthException(AuthFailure.STORAGE)
            }
        }
        val reply = transport.request(AuthRequest(YandexEndpoint.CODE, mapOf("client_id" to clientId, "device_id" to deviceId, "device_name" to "YMPlayer 2 · $profileId")))
        checkReply(reply)
        val body = reply.body
        val uri = body.optString("verification_url")
        if (uri !in setOf("https://ya.ru/device", "https://oauth.yandex.ru/device", "https://oauth.yandex.com/device")) throw AuthException(AuthFailure.RESPONSE)
        val deviceCode = body.optString("device_code")
        val userCode = body.optString("user_code")
        val expires = body.optLong("expires_in", 0)
        val interval = body.optLong("interval", 5)
        if (deviceCode.length !in 1..2048 || !userCode.matches(Regex("[A-Za-z0-9-]{4,32}")) || expires !in 1..3600 || interval !in 1..3600) throw AuthException(AuthFailure.RESPONSE)
        return DeviceChallenge(deviceCode, userCode, uri, expires, interval)
    }
    override suspend fun poll(code: DeviceChallenge): TokenPoll {
        val form = mutableMapOf("grant_type" to "device_code", "code" to code.deviceCode, "client_id" to clientId)
        if (clientSecret.isNotEmpty()) form["client_secret"] = clientSecret
        val reply = transport.request(AuthRequest(YandexEndpoint.TOKEN, form))
        when (reply.body.optString("error")) {
            "authorization_pending" -> return TokenPoll.Pending
            "slow_down" -> return TokenPoll.SlowDown
        }
        checkReply(reply)
        val token = reply.body.optString("access_token")
        if (!token.matches(Regex("[!-~]{1,16384}")) || !reply.body.optString("token_type").equals("bearer", true)) throw AuthException(AuthFailure.RESPONSE)
        val duration = if (reply.body.has("expires_in")) reply.body.optLong("expires_in", -1) else null
        if (duration != null && (duration <= 0 || duration > (Long.MAX_VALUE - now()) / 1000)) throw AuthException(AuthFailure.RESPONSE)
        return TokenPoll.Granted(OAuthCredentials(token, reply.body.optString("refresh_token").takeIf { it.isNotBlank() }, duration?.let { now() + it * 1000 }))
    }
    override suspend fun account(credentials: OAuthCredentials): YandexAccount {
        val reply = transport.request(AuthRequest(YandexEndpoint.ACCOUNT, token = credentials.accessToken))
        if (reply.status == 408 || reply.status == 429 || reply.status in 500..599) throw AuthException(AuthFailure.NETWORK, NetworkIssue.SERVICE)
        if (reply.status !in 200..299 || reply.body.has("error")) throw AuthException(AuthFailure.ACCOUNT)
        val account = reply.body.optJSONObject("result")?.optJSONObject("account") ?: throw AuthException(AuthFailure.ACCOUNT)
        val id = account.optString("uid")
        if (!id.matches(Regex("[1-9][0-9]{0,19}"))) throw AuthException(AuthFailure.ACCOUNT)
        val name = account.optString("displayName").ifBlank { account.optString("login").ifBlank { "Яндекс ID $id" } }.take(200)
        return YandexAccount(id, name)
    }
    private fun checkReply(reply: AuthReply) {
        if (reply.status in 200..299 && !reply.body.has("error")) return
        val failure = when (reply.body.optString("error")) {
            "authorization_declined", "access_denied" -> AuthFailure.DENIED
            "expired_token", "invalid_grant", "bad_verification_code" -> AuthFailure.EXPIRED
            "invalid_client", "unauthorized_client", "invalid_scope" -> AuthFailure.CLIENT
            else -> if (reply.status >= 500 || reply.status == 429) AuthFailure.NETWORK else AuthFailure.RESPONSE
        }
        throw AuthException(failure)
    }
}
