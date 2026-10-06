package dev.petrov.ymplayer2

import android.content.Context
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
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class UpdateAutoOfferTest {
    @Test fun launchOffersNewReleaseOnceAndRespectsDailyCheckAndOptOut() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("app_updates_2", Context.MODE_PRIVATE)
        val oldAuto = prefs.getBoolean("auto_check", true)
        val oldLast = prefs.getLong("last_check", 0)
        // The real activity's own check stays disabled; this test controls a fake transport.
        prefs.edit().putBoolean("auto_check", false).commit()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        lateinit var activity: MainActivity
        scenario.onActivity { activity = it }
        val manifestUrl = "https://fixture.test/manifest.json"
        val next = activity.packageManager.getPackageInfo(activity.packageName, 0).longVersionCode + 1
        val manifest = JSONObject().put("schemaVersion", 1).put("packageName", activity.packageName)
            .put("versionCode", next).put("versionName", "2.0.0beta-build$next")
            .put("channel", "beta").put("minSdk", 28).put("releaseNotes", "Новая версия")
            .put("apk", JSONObject().put("primaryUrl", "https://fixture.test/update.apk")
                .put("alternativeUrl", "https://backup.test/update.apk")
                .put("sizeBytes", 3).put("sha256", "a".repeat(64)))
        val bytes = manifest.toString().toByteArray()
        val requests = AtomicInteger()
        val client = UpdateClient(activity, UpdateConnectionFactory { url ->
            requests.incrementAndGet()
            object : HttpURLConnection(url) {
                override fun connect() = Unit
                override fun disconnect() = Unit
                override fun usingProxy() = false
                override fun getResponseCode() = 200
                override fun getInputStream() = ByteArrayInputStream(bytes)
                override fun getContentLengthLong() = bytes.size.toLong()
            }
        }, manifests = listOf(manifestUrl))
        try {
            val updates = UpdateCoordinator(activity, client)
            updates.setAutoCheck(true)
            prefs.edit().putLong("last_check", 0).commit()
            updates.checkOnLaunch()
            val deadline = System.currentTimeMillis() + 5_000
            while (!updates.state.value.prompt && System.currentTimeMillis() < deadline) Thread.sleep(25)
            assertTrue(updates.state.value.prompt)
            assertEquals("2.0.0beta-build$next", updates.state.value.offer?.versionName)
            assertEquals(1, requests.get())
            updates.dismissPrompt()
            updates.checkOnLaunch()
            assertFalse(updates.state.value.prompt)
            assertEquals(1, requests.get())
            updates.setAutoCheck(false)
            prefs.edit().putLong("last_check", 0).commit()
            updates.checkOnLaunch()
            assertEquals(1, requests.get())
        } finally {
            scenario.close()
            prefs.edit().putBoolean("auto_check", oldAuto).putLong("last_check", oldLast).commit()
        }
    }
}
