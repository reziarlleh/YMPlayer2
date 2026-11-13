package dev.petrov.ymplayer2

import android.content.Context
import android.os.Build
import java.security.KeyStore
import java.security.cert.CertificateFactory
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/** API23 predates Network Security Config; use the same explicit ISRG roots there. */
internal object LegacyTls {
    fun install(context: Context) {
        if (Build.VERSION.SDK_INT >= 24) return
        val platform = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
            init(null as KeyStore?)
        }.trustManagers.filterIsInstance<X509TrustManager>().single()
        val roots = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null) }
        platform.acceptedIssuers.forEachIndexed { index, certificate ->
            roots.setCertificateEntry("system-$index", certificate)
        }
        val parser = CertificateFactory.getInstance("X.509")
        for (resource in listOf(R.raw.isrg_root_x1, R.raw.isrg_root_x2)) {
            val certificate = context.resources.openRawResource(resource).use(parser::generateCertificate)
            roots.setCertificateEntry("isrg-$resource", certificate)
        }
        val trusted = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(roots) }
        val tls = SSLContext.getInstance("TLS").apply { init(null, trusted.trustManagers, null) }
        // All product HTTP clients, artwork and Media3 use URLConnection. Keep
        // the platform's hostname verifier and its full certificate validation.
        HttpsURLConnection.setDefaultSSLSocketFactory(tls.socketFactory)
    }
}
