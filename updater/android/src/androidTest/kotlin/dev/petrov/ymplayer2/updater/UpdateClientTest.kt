package dev.petrov.ymplayer2.updater

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.File
import kotlinx.coroutines.CancellationException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
class UpdateClientTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun manifest(packageName: String = context.packageName, primary: String = "https://primary.test/app.apk",
        build: Long = 100) = JSONObject()
        .put("schemaVersion", 1).put("packageName", packageName).put("versionCode", build)
        .put("versionName", "2.0.0beta-build$build").put("minSdk", 29)
        .put("channel", "beta")
        .put("releaseNotes", "Проверка")
        .put("apk", JSONObject().put("primaryUrl", primary)
            .put("alternativeUrl", "https://backup.test/app.apk")
            .put("sizeBytes", 3)
            .put("sha256", MessageDigest.getInstance("SHA-256").digest("abc".toByteArray()).joinToString("") { "%02x".format(it) }))

    @Test fun manifestFallsBackAndKeepsOrderedApkSources() = runBlocking {
        val calls = java.util.Collections.synchronizedList(mutableListOf<String>())
        val json = manifest().toString().toByteArray()
        val client = UpdateClient(context, UpdateConnectionFactory { url ->
            calls += url.host
            FakeConnection(url, if (url.host == "primary.test") 503 else 200, json)
        }, manifests = listOf("https://primary.test/manifest.json", "https://backup.test/manifest.json"))
        val release = client.check()
        assertEquals(setOf("primary.test", "backup.test"), calls.toSet())
        assertEquals("backup.test", URL(release.manifestUrl).host)
        assertEquals(listOf("https://backup.test/app.apk", "https://primary.test/app.apk"), release.sources(true))
    }

    @Test fun newestValidManifestWinsWhenCdnEdgesDisagree() = runBlocking {
        val client = UpdateClient(context, UpdateConnectionFactory { url ->
            val build = when (url.host) { "primary.test" -> 100L; "backup.test" -> 98L; else -> 101L }
            FakeConnection(url, 200, manifest(build = build).toString().toByteArray())
        }, manifests = listOf("https://primary.test/manifest.json", "https://backup.test/manifest.json",
            "https://gcore.test/manifest.json"))
        val release = client.check()
        assertEquals(101L, release.versionCode)
        assertEquals("gcore.test", URL(release.manifestUrl).host)
    }

    @Test fun pinnedCdnApkCanFallBackToGcoreMirror() {
        val release = UpdateClient(context).parseManifest(manifest().apply {
            getJSONObject("apk").put("alternativeUrl", "https://cdn.jsdelivr.net/gh/owner/repo@v2/file.apk")
        }, "https://gcore.test/manifest.json")
        assertEquals(listOf("https://cdn.jsdelivr.net/gh/owner/repo@v2/file.apk",
            "https://gcore.jsdelivr.net/gh/owner/repo@v2/file.apk", "https://primary.test/app.apk"),
            release.sources(true))
    }

    @Test fun wrongPackageHttpAndInvalidDigestAreRejected() {
        val client = UpdateClient(context)
        assertThrows(IOException::class.java) { client.parseManifest(manifest("dev.petrov.yaplay"), "https://primary.test/manifest") }
        assertThrows(IOException::class.java) { client.parseManifest(manifest(primary = "http://primary.test/app.apk"), "https://primary.test/manifest") }
        val bad = manifest().apply { getJSONObject("apk").put("sha256", "bad") }
        assertThrows(IOException::class.java) { client.parseManifest(bad, "https://primary.test/manifest") }
        assertThrows(IOException::class.java) { client.parseManifest(manifest().put("versionCode", 101), "https://primary.test/manifest") }
        val stable = UpdateClient(context, channel = "stable")
        assertThrows(IOException::class.java) { stable.parseManifest(manifest(), "https://primary.test/manifest") }
    }

    @Test fun corruptDownloadFailsBothSourcesAndRemovesPartialFile() = runBlocking {
        val calls = mutableListOf<String>()
        val client = UpdateClient(context, UpdateConnectionFactory { url ->
            calls += url.host
            FakeConnection(url, 200, "bad".toByteArray())
        })
        val release = client.parseManifest(manifest(), "https://primary.test/manifest")
        assertThrows(IOException::class.java) { runBlocking { client.download(release) } }
        assertEquals(listOf("primary.test", "backup.test"), calls)
        assertFalse(context.filesDir.resolve("updates/YMPlayer2-100.part").exists())
        assertFalse(context.filesDir.resolve("updates/YMPlayer2-100.apk").exists())
    }

    @Test fun cancellationStopsFallbackAndRemovesItsPartialFile() = runBlocking {
        val calls = mutableListOf<String>()
        val client = UpdateClient(context, UpdateConnectionFactory { url ->
            calls += url.host
            FakeConnection(url, 200, "abc".toByteArray())
        })
        val release = client.parseManifest(manifest(), "https://primary.test/manifest")
        assertThrows(CancellationException::class.java) {
            runBlocking { client.download(release) { _, _ -> throw CancellationException("Fixture cancellation") } }
        }
        assertEquals(listOf("primary.test"), calls)
        assertFalse(context.filesDir.resolve("updates").listFiles().orEmpty().any {
            it.name.startsWith("YMPlayer2-100-") && it.name.endsWith(".part")
        })
        assertFalse(context.filesDir.resolve("updates/YMPlayer2-100.apk").exists())
    }

    @Test fun cleanupRemovesOnlyAlreadyInstalledOwnedApks() {
        val installed = context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
        val directory = File(context.filesDir, "updates").apply { mkdirs() }
        val current = File(directory, "YMPlayer2-$installed.apk")
        val old = File(directory, "YMPlayer2-${(installed - 1).coerceAtLeast(0)}.apk")
        val pending = File(directory, "YMPlayer2-${installed + 12345}.apk")
        val unrelated = File(directory, "audit-keep.txt")
        val partial = File(directory, "YMPlayer2-${installed + 12345}-audit.part")
        try {
            listOf(current, old, pending, unrelated, partial).forEach { it.writeText("fixture") }
            UpdateClient(context).pruneInstalledApks()
            assertFalse(current.exists()); assertFalse(old.exists())
            assertTrue(pending.exists()); assertTrue(unrelated.exists()); assertTrue(partial.exists())
        } finally { listOf(current, old, pending, unrelated, partial).forEach { it.delete() } }
    }

    private class FakeConnection(url: URL, private val status: Int, private val bytes: ByteArray) : HttpURLConnection(url) {
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy() = false
        override fun getResponseCode() = status
        override fun getInputStream() = ByteArrayInputStream(bytes)
        override fun getContentLengthLong() = bytes.size.toLong()
    }
}
