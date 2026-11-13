@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
package dev.petrov.ymplayer2

import android.graphics.Bitmap
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.petrov.ymplayer2.core.*
import dev.petrov.ymplayer2.offline.StorageNode
import dev.petrov.ymplayer2.offline.validCover
import dev.petrov.ymplayer2.yandex.KeystoreAccountStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.Instant

/** Real Android APIs and storage, including the API23 path; no live account credentials. */
class Android6CompatibilityTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun actualApplicationOpensWithItsRealLanguageAndNetworkObserver() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                val app = it.application as PlayerApplication
                app.internet.refresh()
                assertTrue(dev.petrov.ymplayer2.localization.AppLanguages.state.value.effective in
                    dev.petrov.ymplayer2.localization.AppLanguages.available.map { language -> language.tag })
                assertTrue(dev.petrov.ymplayer2.updater.installedVersionCode(it) > 0)
            }
        }
    }

    @Test fun desugaredAtomicReplacementPreservesContentsAndCanonicalBoundaries() {
        val root = File(context.cacheDir, "android6-nio-probe").apply { mkdirs() }
        try {
            val target = File(root, "checkpoint").apply { writeText("old") }
            val temp = File(root, "checkpoint.part").apply { writeText("complete new checkpoint") }
            StorageNode.file(context, target).replace(StorageNode.file(context, temp))
            assertEquals("complete new checkpoint", target.readText())
            assertFalse(temp.exists())
            assertTrue(target.canonicalFile.toPath().startsWith(root.canonicalFile.toPath()))
            assertFalse(File(root.parentFile, root.name + "-outside").canonicalFile.toPath().startsWith(root.canonicalFile.toPath()))
            assertEquals("1970-01-01T00:00:00Z", Instant.ofEpochMilli(0).toString())
        } finally { root.deleteRecursively() }
    }

    @Test fun realKeystoreRoundTripKeepsTheAccountEncrypted() = runBlocking {
        val store = KeystoreAccountStore(context)
        val profile = "android6-compatibility-probe"
        val session = AccountSession(YandexAccount("compatibility-fixture", "Fixture"), OAuthCredentials("fixture-only", null, null))
        try {
            store.write(profile, session)
            val restored = requireNotNull(store.read(profile))
            assertEquals(session.account?.id, restored.account?.id)
            assertEquals(session.account?.name, restored.account?.name)
            assertEquals(session.credentials.accessToken, restored.credentials.accessToken)
            assertEquals(session.credentials.refreshToken, restored.credentials.refreshToken)
            assertEquals(session.credentials.expiresAtMillis, restored.credentials.expiresAtMillis)
            val file = File(context.noBackupFilesDir, "yandex-accounts/$profile.bin")
            assertFalse(file.readBytes().toString(Charsets.ISO_8859_1).contains("fixture-only"))
        } finally { store.write(profile, null) }
    }

    @Test fun nativeCoverValidationRejectsTruncatedJpegAndPngWithoutLosingValidCovers() {
        val file = File(context.cacheDir, "android6-cover-probe")
        val bitmap = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.CYAN) }
        try {
            for (format in listOf(Bitmap.CompressFormat.JPEG, Bitmap.CompressFormat.PNG)) {
                file.outputStream().use { assertTrue(bitmap.compress(format, 90, it)) }
                val valid = file.readBytes()
                assertTrue("Complete $format", validCover(context, StorageNode.file(context, file)))
                // Missing PNG IEND alone still contains complete pixels and is
                // accepted by ImageDecoder. Remove encoded payload for a real
                // partial image on every Android; legacy also requires its tail.
                file.writeBytes(valid.copyOf(valid.size / 2))
                assertFalse("Truncated $format", validCover(context, StorageNode.file(context, file)))
                if (android.os.Build.VERSION.SDK_INT < 28) {
                    file.writeBytes(valid.copyOf(valid.size - if (format == Bitmap.CompressFormat.JPEG) 2 else 12))
                    assertFalse("Unfinished legacy $format container", validCover(context, StorageNode.file(context, file)))
                }
            }
        } finally { bitmap.recycle(); file.delete() }
    }

    @Test fun exportedNamesKeepCompleteUnicodeCodePointsOnTheOldRuntime() {
        val name = ExportFileName.safe("🎵".repeat(100))
        assertEquals(180, name.toByteArray(Charsets.UTF_8).size)
        assertEquals("🎵".repeat(45), name)
        assertEquals("_CON", ExportFileName.safe("CON"))
        assertEquals("artist_title", ExportFileName.safe("artist/title"))
    }

    @Test fun anOlderIncompatibleFeedStillReportsTheInstalledBuildAsCurrent() {
        val installed = dev.petrov.ymplayer2.updater.installedVersionCode(context)
        val json = org.json.JSONObject().put("schemaVersion", 1).put("packageName", context.packageName)
            .put("versionCode", installed).put("versionName", "2.7.0-build$installed").put("channel", "stable")
            .put("minSdk", android.os.Build.VERSION.SDK_INT + 1).put("apk", org.json.JSONObject()
                .put("primaryUrl", "https://release.test/update.apk").put("sizeBytes", 3).put("sha256", "a".repeat(64)))
        val client = dev.petrov.ymplayer2.updater.UpdateClient(context, channel = "stable")
        assertEquals(installed, client.parseManifest(json, "https://release.test/stable.json").versionCode)
        json.put("versionCode", installed + 1).put("versionName", "2.7.0-build${installed + 1}")
        assertThrows(java.io.IOException::class.java) { client.parseManifest(json, "https://release.test/stable.json") }
    }
}
