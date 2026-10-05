package dev.petrov.ymplayer2

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.petrov.ymplayer2.updater.UpdateClient
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class ManualBetaUpdateTest {
    @Test fun productionChannelStaysStableAndRejectsNewerBeta() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("stable", BuildConfig.UPDATE_CHANNEL)
        assertTrue(UpdateClient.manifestUrls(BuildConfig.UPDATE_CHANNEL).all { it.endsWith("/stable.json") })
        val client = UpdateClient(context, channel = BuildConfig.UPDATE_CHANNEL)
        val manifest = JSONObject().put("schemaVersion", 1).put("packageName", context.packageName)
            .put("versionCode", 1000).put("versionName", "2.4.0beta-build1000")
            .put("channel", "beta").put("minSdk", 28)
            .put("apk", JSONObject().put("primaryUrl", "https://fixture.test/update.apk")
                .put("sizeBytes", 3).put("sha256", "a".repeat(64)))
        try {
            client.parseManifest(manifest, "https://fixture.test/stable.json")
            fail("A prerelease must not enter the production update channel")
        } catch (_: IOException) { }
        manifest.put("versionName", "2.4.0-build1000").put("channel", "stable")
        assertEquals(1000L, client.parseManifest(manifest, "https://fixture.test/stable.json").versionCode)
    }
}
