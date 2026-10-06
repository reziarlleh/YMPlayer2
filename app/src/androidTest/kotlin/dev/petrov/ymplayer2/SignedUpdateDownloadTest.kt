package dev.petrov.ymplayer2

import android.content.ContextWrapper
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.petrov.ymplayer2.updater.UpdateClient
import dev.petrov.ymplayer2.updater.UpdateConnectionFactory
import dev.petrov.ymplayer2.updater.UpdateRelease
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.HttpURLConnection
import java.security.MessageDigest

/** Opt-in release evidence: a real signed APK supplied externally, never bundled in tests. */
@RunWith(AndroidJUnit4::class)
class SignedUpdateDownloadTest {
    @Test fun signedReleaseIsVerifiedBeforePublishingTemporaryDownload() {
        val args = InstrumentationRegistry.getArguments()
        val build = args.getString("signedBuild")?.toLongOrNull()
        assumeTrue("Requires a newer signed release fixture", build != null)
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val fixture = File(target.getExternalFilesDir(null), "audit-update.apk")
        assertTrue(fixture.isFile)
        val scratch = File(target.cacheDir, "signed-update-audit").apply { mkdirs() }
        val context = object : ContextWrapper(target) {
            override fun getPackageName() = "dev.petrov.ymplayer2"
            override fun getFilesDir() = scratch
        }
        val client = UpdateClient(context, UpdateConnectionFactory { url ->
            object : HttpURLConnection(url) {
                override fun connect() = Unit
                override fun disconnect() = Unit
                override fun usingProxy() = false
                override fun getResponseCode() = 200
                override fun getContentLengthLong() = fixture.length()
                override fun getInputStream() = fixture.inputStream()
            }
        }, channel = "stable")
        val minimum = args.getString("signedMinSdk")?.toIntOrNull() ?: 29
        val release = UpdateRelease(build!!, args.getString("signedVersion")!!, "stable", minimum,
            "signed fixture", "https://release.test/update.apk", "", fixture.length(),
            args.getString("signedSha256")!!, "https://release.test/stable.json")
        try {
            val pm = context.packageManager
            val flags = PackageManager.GET_SIGNING_CERTIFICATES
            fun certificates(info: android.content.pm.PackageInfo?) = info?.signingInfo?.apkContentsSigners?.map {
                MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString("") { b -> "%02x".format(b) }
            }
            println("Installed certificate: ${certificates(pm.getPackageInfo(context.packageName, flags))}")
            for (suffix in listOf("apk", "part")) {
                val copy = File(scratch, "fixture.$suffix")
                fixture.copyTo(copy, overwrite = true)
                println("Archive .$suffix certificate: ${certificates(pm.getPackageArchiveInfo(copy.absolutePath, flags))}")
                copy.delete()
            }
            val result = runBlocking { client.download(release) }
            assertEquals("YMPlayer2-$build.apk", result.file.name)
            assertTrue(client.verifyFile(result.file, release))
            client.verifyArchive(result.file, release)
            assertFalse(File(scratch, "updates").listFiles().orEmpty().any { it.extension == "part" })
            assertFalse(result.usedAlternative)
        } finally { scratch.deleteRecursively() }
    }
}
