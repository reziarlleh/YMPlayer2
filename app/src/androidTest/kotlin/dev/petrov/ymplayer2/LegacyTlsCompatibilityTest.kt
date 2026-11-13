package dev.petrov.ymplayer2

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLException

/** Opt-in, public TLS/HTTP only: never sends an account token, form, cookie or body. */
class LegacyTlsCompatibilityTest {
    @Test fun publicServiceAndModernCertificateOriginsAreReachable() {
        assumeTrue("Host explicitly enables live networking", InstrumentationRegistry.getArguments().getString("compatLiveNetwork") == "true")
        for (origin in listOf("https://api.music.yandex.net/", "https://oauth.yandex.ru/",
            "https://avatars.yandex.net/", "https://valid-isrgrootx1.letsencrypt.org/",
            "https://valid.x2.test-certs.letsencrypt.org/")) {
            val connection = URL(origin).openConnection() as HttpsURLConnection
            try {
                connection.requestMethod = "HEAD"; connection.instanceFollowRedirects = false
                connection.connectTimeout = 12_000; connection.readTimeout = 12_000
                val status = connection.responseCode
                println("TLS host=${URL(origin).host} http=$status")
                assertTrue("HTTP transport to ${URL(origin).host}", status in 100..599)
            } finally { connection.disconnect() }
        }
    }

    @Test fun expiredCertificateRemainsRejected() {
        assumeTrue("Host explicitly enables live networking", InstrumentationRegistry.getArguments().getString("compatLiveNetwork") == "true")
        for (origin in listOf("https://expired.x1.test-certs.letsencrypt.org/")) {
            val connection = URL(origin).openConnection() as HttpsURLConnection
            try {
                connection.requestMethod = "HEAD"; connection.instanceFollowRedirects = false
                connection.connectTimeout = 12_000; connection.readTimeout = 12_000
                connection.responseCode
                org.junit.Assert.fail("Invalid TLS identity accepted: ${URL(origin).host}")
            } catch (_: SSLException) {
                println("TLS correctly rejected=${URL(origin).host}")
            } finally { connection.disconnect() }
        }
    }

    @Test fun compatibilitySetupKeepsThePlatformHostnameVerifier() {
        val verifier = HttpsURLConnection.getDefaultHostnameVerifier()
        LegacyTls.install(InstrumentationRegistry.getInstrumentation().targetContext)
        org.junit.Assert.assertSame(verifier, HttpsURLConnection.getDefaultHostnameVerifier())
    }
}
