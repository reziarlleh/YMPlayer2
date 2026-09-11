package dev.petrov.ymplayer2

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.petrov.ymplayer2.core.*
import dev.petrov.ymplayer2.yandex.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URL
import java.net.UnknownHostException
import java.net.SocketTimeoutException
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLHandshakeException
import java.security.cert.Certificate
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.SecretKey

@RunWith(AndroidJUnit4::class)
class YandexAdapterTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private class Connection(val status: Int = 200, val body: String = "{}", val failure: IOException? = null) : HttpsURLConnection(URL(YandexEndpoint.CODE.url)) {
        val sent = ByteArrayOutputStream()
        var closed = false
        var bodyRead = false
        override fun connect() {}
        override fun disconnect() { closed = true }
        override fun usingProxy() = false
        override fun getCipherSuite() = "fixture"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
        override fun getOutputStream() = sent
        override fun getResponseCode(): Int { failure?.let { throw it }; return status }
        override fun getHeaderField(name: String?) = if (name == "Retry-After") "60" else null
        override fun getInputStream() = body.byteInputStream().also { bodyRead = true }
        override fun getErrorStream() = inputStream
    }
    @Test fun transportMatchesLegacyHeadersAndClassifiesFailuresWithoutReadingErrorBodies() = runBlocking {
        for (status in listOf(408, 429, 500, 503)) {
            val connection = Connection(status, "<html>private upstream body</html>")
            try {
                HttpsAuthTransport { connection }.request(AuthRequest(YandexEndpoint.CODE, mapOf("client_id" to "fixture")))
                fail("Outage accepted")
            } catch (e: AuthException) {
                assertEquals(AuthFailure.NETWORK, e.failure); assertEquals(NetworkIssue.SERVICE, e.networkIssue)
                assertEquals(60L, e.retryAfterSeconds); assertFalse(e.toString().contains("private"))
            }
            assertTrue(connection.closed); assertFalse(connection.bodyRead)
            assertEquals("Yandex-Music-API", connection.getRequestProperty("User-Agent"))
            assertEquals("YandexMusicAndroid/24023621", connection.getRequestProperty("X-Yandex-Music-Client"))
            assertEquals("ru", connection.getRequestProperty("Accept-Language"))
            assertEquals(15000, connection.connectTimeout); assertEquals(20000, connection.readTimeout)
        }
        for ((error, kind) in listOf(UnknownHostException("private") to NetworkIssue.DNS, SocketTimeoutException("private") to NetworkIssue.TIMEOUT,
            SSLHandshakeException("private") to NetworkIssue.TLS, IOException("private") to NetworkIssue.CONNECTION)) {
            val connection = Connection(failure = error)
            try { HttpsAuthTransport { connection }.request(AuthRequest(YandexEndpoint.ACCOUNT)); fail("Network failure accepted") }
            catch (e: AuthException) { assertEquals(kind, e.networkIssue); assertFalse(e.toString().contains("private")) }
            assertTrue(connection.closed)
        }
    }
    @Test fun encryptedStoreReadsBuild7SchemaAndPersistsOAuthBeforeAccountMetadata() = runBlocking {
        val profile = "auth_test_migration"; val store = KeystoreAccountStore(context)
        try {
            store.write(profile, AccountSession(null, OAuthCredentials("new-private-token", "refresh", null)))
            val reopened = KeystoreAccountStore(context)
            assertNull(reopened.read(profile)!!.account)
            assertEquals("new-private-token", reopened.read(profile)!!.credentials.accessToken)
            // Build7 schema 1 used mandatory id/name fields in the same AES-GCM envelope.
            val legacy = JSONObject().put("schema", 1).put("id", "123").put("name", "Legacy fixture").put("access", "legacy-private-token")
            val key = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.getKey("ymplayer2.yandex.$profile", null) as SecretKey
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key); updateAAD(profile.toByteArray()) }
            val bytes = byteArrayOf(1) + cipher.iv + cipher.doFinal(legacy.toString().toByteArray())
            File(context.noBackupFilesDir, "yandex-accounts/$profile.bin").writeBytes(bytes)
            assertEquals("legacy-private-token", reopened.read(profile)!!.credentials.accessToken)
            assertEquals("Legacy fixture", reopened.read(profile)!!.account!!.name)
        } finally { store.write(profile, null) }
    }
    @Test fun parsesDeviceAndTokenProtocolAndKeepsDeviceIdsDistinctAndStable() = runBlocking {
        val requests = mutableListOf<AuthRequest>()
        var reply = AuthReply(200, JSONObject("""{"device_code":"secret-device","user_code":"ABCD1234","verification_url":"https://oauth.yandex.ru/device","expires_in":300,"interval":5}"""))
        val api = YandexDeviceApi(context, "client-fixture", "client-secret-fixture", AuthTransport { requests += it; reply }) { 1000 }
        val code = api.requestCode("test_owner"); api.requestCode("test_road")
        val restoredApi = YandexDeviceApi(context, "client-fixture", "client-secret-fixture", AuthTransport { requests += it; reply })
        restoredApi.requestCode("test_owner")
        assertEquals(requests[0].form["device_id"], requests[2].form["device_id"])
        assertNotEquals(requests[0].form["device_id"], requests[1].form["device_id"])
        assertEquals(300, code.expiresInSeconds); assertEquals(5, code.intervalSeconds)
        reply.body.put("verification_url", "https://ya.ru/device")
        assertEquals("https://ya.ru/device", api.requestCode("test_owner").verificationUrl)
        reply = AuthReply(400, JSONObject("""{"error":"authorization_pending","error_description":"untrusted secret detail"}"""))
        assertSame(TokenPoll.Pending, api.poll(code))
        reply = AuthReply(400, JSONObject("""{"error":"slow_down"}"""))
        assertSame(TokenPoll.SlowDown, api.poll(code))
        reply = AuthReply(200, JSONObject("""{"token_type":"bearer","access_token":"test-token","refresh_token":"test-refresh","expires_in":300}"""))
        val credentials = (api.poll(code) as TokenPoll.Granted).credentials
        assertEquals(301000L, credentials.expiresAtMillis)
        assertEquals("device_code", requests.last().form["grant_type"])
        assertEquals("secret-device", requests.last().form["code"])
        assertEquals("client-secret-fixture", requests.last().form["client_secret"])
        reply = AuthReply(200, JSONObject("""{"result":{"account":{"uid":123,"displayName":"Слушатель"}}}"""))
        assertEquals(YandexAccount("123", "Слушатель"), api.account(credentials))
        assertEquals("test-token", requests.last().token)
        assertFalse(requests.last().toString().contains("test-token"))
    }
    @Test fun rejectsWrongVerificationHostAndInvalidResponsesWithoutLeakingBody() = runBlocking {
        var reply = AuthReply(200, JSONObject("""{"device_code":"private","user_code":"CODE1234","verification_url":"https://oauth.yandex.ru.attacker.example/device","expires_in":300,"interval":5}"""))
        val api = YandexDeviceApi(context, "fixture", "", AuthTransport { reply })
        try { api.requestCode("test_owner"); fail("External verification page accepted") } catch (e: AuthException) { assertEquals(AuthFailure.RESPONSE, e.failure) }
        val challenge = DeviceChallenge("fixture", "CODE1234", "https://oauth.yandex.ru/device", 300, 5)
        for ((error, expected) in listOf("access_denied" to AuthFailure.DENIED, "invalid_grant" to AuthFailure.EXPIRED, "invalid_client" to AuthFailure.CLIENT)) {
            reply = AuthReply(400, JSONObject().put("error", error).put("error_description", "private-token-value"))
            try { api.poll(challenge); fail(error) } catch (e: AuthException) { assertEquals(expected, e.failure); assertFalse(e.toString().contains("private-token-value")) }
        }
        reply = AuthReply(200, JSONObject("""{"token_type":"bearer","access_token":"bad\ntoken"}"""))
        try { api.poll(challenge); fail("Header injection") } catch (e: AuthException) { assertEquals(AuthFailure.RESPONSE, e.failure) }
        reply = AuthReply(200, JSONObject("""{"result":{}}"""))
        try { api.account(OAuthCredentials("fixture", null, null)); fail("Missing account") } catch (e: AuthException) { assertEquals(AuthFailure.ACCOUNT, e.failure) }
    }
    @Test fun encryptedSessionsSurviveNewStoreRejectCrossProfileAndDeleteIndependently() = runBlocking {
        val store = KeystoreAccountStore(context)
        val owner = "auth_test_owner"; val road = "auth_test_road"
        try {
            store.write(owner, null); store.write(road, null)
            store.write(owner, AccountSession(YandexAccount("123", "Owner"), OAuthCredentials("secret-owner-token", "secret-refresh", 123456789)))
            store.write(road, AccountSession(YandexAccount("456", "Road"), OAuthCredentials("secret-road-token", null, null)))
            val file = File(context.noBackupFilesDir, "yandex-accounts/$owner.bin")
            val bytes = file.readBytes()
            assertFalse(String(bytes, Charsets.ISO_8859_1).contains("secret-owner-token"))
            val reopened = KeystoreAccountStore(context)
            assertEquals("secret-owner-token", reopened.read(owner)!!.credentials.accessToken)
            val roadFile = File(context.noBackupFilesDir, "yandex-accounts/$road.bin")
            val originalRoad = roadFile.readBytes()
            roadFile.writeBytes(bytes)
            try { reopened.read(road); fail("Cross-profile ciphertext accepted") } catch (e: AuthException) { assertEquals(AuthFailure.STORAGE, e.failure) }
            roadFile.writeBytes(originalRoad)
            store.write(owner, null)
            assertNull(reopened.read(owner)); assertEquals("456", reopened.read(road)!!.account!!.id)
            bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte(); file.writeBytes(bytes)
            try { reopened.read(owner); fail("Tampered ciphertext accepted") } catch (e: AuthException) { assertEquals(AuthFailure.STORAGE, e.failure) }
        } finally { store.write(owner, null); store.write(road, null) }
    }
}
