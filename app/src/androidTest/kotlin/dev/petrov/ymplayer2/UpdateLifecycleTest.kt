package dev.petrov.ymplayer2

import android.content.Context
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.petrov.ymplayer2.updater.UpdateClient
import dev.petrov.ymplayer2.updater.UpdateConnectionFactory
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class UpdateLifecycleTest {
    @Test fun checkingAndDownloadingSurviveActivityRecreationWithoutDuplicateRequests() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("app_updates_2", Context.MODE_PRIVATE)
        val auto = prefs.getBoolean("auto_check", true)
        prefs.edit().putBoolean("auto_check", false).commit()
        val checkEntered = CountDownLatch(1); val checkRelease = CountDownLatch(1)
        val downloadEntered = CountDownLatch(1); val downloadRelease = CountDownLatch(1)
        val checks = AtomicInteger(); val downloads = AtomicInteger()
        val bytes = "abc".toByteArray()
        val build = context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode + 100
        val manifest = JSONObject().put("schemaVersion", 1).put("packageName", context.packageName)
            .put("versionCode", build).put("versionName", "2.2.1beta-build$build").put("channel", "beta")
            .put("minSdk", 28).put("releaseNotes", "fixture").put("apk", JSONObject()
                .put("primaryUrl", "https://primary.test/update.apk").put("alternativeUrl", "https://backup.test/update.apk")
                .put("sizeBytes", bytes.size).put("sha256", MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }))
        val client = UpdateClient(context, UpdateConnectionFactory { url ->
            object : HttpURLConnection(url) {
                override fun connect() = Unit
                override fun disconnect() = Unit
                override fun usingProxy() = false
                override fun getResponseCode(): Int {
                    if (url.path.endsWith("json")) {
                        checks.incrementAndGet(); checkEntered.countDown()
                        check(checkRelease.await(15, TimeUnit.SECONDS))
                    } else {
                        downloads.incrementAndGet(); downloadEntered.countDown()
                        check(downloadRelease.await(15, TimeUnit.SECONDS))
                    }
                    return 200
                }
                override fun getInputStream() = ByteArrayInputStream(if (url.path.endsWith("json")) manifest.toString().toByteArray() else bytes)
                override fun getContentLengthLong() = if (url.path.endsWith("json")) manifest.toString().toByteArray().size.toLong() else bytes.size.toLong()
            }
        }, manifests = listOf("https://primary.test/manifest.json"))
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        lateinit var production: UpdateCoordinator
        lateinit var updates: UpdateCoordinator
        try {
            scenario.onActivity { activity ->
                production = ViewModelProvider(activity)[UpdateCoordinator::class.java]
                updates = ViewModelProvider(activity, viewModelFactory { initializer { UpdateCoordinator(context, client) } })
                    .get("audit-update", UpdateCoordinator::class.java)
                updates.resume(activity); updates.check(true)
            }
            assertTrue(checkEntered.await(10, TimeUnit.SECONDS))
            scenario.recreate()
            scenario.onActivity { activity ->
                assertSame(production, ViewModelProvider(activity)[UpdateCoordinator::class.java])
                assertSame(updates, ViewModelProvider(activity).get("audit-update", UpdateCoordinator::class.java))
                updates.resume(activity); assertTrue(updates.state.value.checking)
            }
            checkRelease.countDown()
            await { updates.state.value.offer != null }
            scenario.onActivity { updates.download(false) }
            assertTrue(downloadEntered.await(10, TimeUnit.SECONDS))
            scenario.recreate()
            scenario.onActivity { activity ->
                assertSame(updates, ViewModelProvider(activity).get("audit-update", UpdateCoordinator::class.java))
                updates.resume(activity); assertTrue(updates.state.value.downloading)
                assertNotNull(updates.state.value.offer)
            }
            downloadRelease.countDown()
            await { !updates.state.value.downloading }
            // These deliberately invalid APK bytes exercise completion/fallback, never the installer.
            assertEquals(1, checks.get()); assertEquals(2, downloads.get())
            assertFalse(updates.state.value.ready)
            assertFalse(context.filesDir.resolve("updates/YMPlayer2-$build.apk").exists())
        } finally {
            checkRelease.countDown(); downloadRelease.countDown(); scenario.close()
            prefs.edit().putBoolean("auto_check", auto).commit()
        }
    }
    private fun await(condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 15000
        while (!condition() && System.currentTimeMillis() < end) Thread.sleep(25)
        assertTrue(condition())
    }
}
