package dev.petrov.ymplayer2

import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.petrov.ymplayer2.clips.ClipWaveController
import dev.petrov.ymplayer2.core.*
import dev.petrov.ymplayer2.yandex.ClipTransport
import dev.petrov.ymplayer2.yandex.YandexClipApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ClipQueueInfoTest {
    @Test fun infoNamesTheClipThatNextWillPlay() {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val context = instrument.targetContext
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val accounts = AccountAuth(DemoCatalog().profiles, object : DeviceAuthApi {
            override val configured = true
            override suspend fun requestCode(profileId: String) = error("unused")
            override suspend fun poll(code: DeviceChallenge) = error("unused")
            override suspend fun account(credentials: OAuthCredentials) = error("unused")
        }, object : AccountStore {
            override suspend fun read(profileId: String) = AccountSession(
                YandexAccount("fixture", "Fixture"), OAuthCredentials("fixture", null, null))
            override suspend fun write(profileId: String, session: AccountSession?) = Unit
        }, scope)
        val transport = object : ClipTransport {
            override suspend fun get(url: String, token: String) = error("No player URL expected")
            override suspend fun post(url: String, token: String, body: JSONObject): String = when {
                url.endsWith("/new") -> """{"result":{"sessionId":"session","batchId":"batch","list":[
                    {"clipId":"one","title":"Now","artists":[{"name":"Current Artist"}],"previewUrl":"https://strm.yandex.ru/one.mp4"},
                    {"clipId":"two","title":"Next Title","artists":[{"name":"Next Artist"}],"previewUrl":"https://strm.yandex.ru/two.mp4"}
                ]}}"""
                url.endsWith("/next") -> """{"result":{"batchId":"empty","list":[]}}"""
                url.endsWith("/feedback") -> ""
                else -> error(url)
            }
        }
        var controller: ClipWaveController? = null
        try {
            instrument.runOnMainSync { accounts.activate("owner") }
            waitFor { accounts.state.value.phase == AuthPhase.SIGNED_IN }
            instrument.runOnMainSync {
                val player = ExoPlayer.Builder(context).build()
                controller = ClipWaveController(YandexClipApi(accounts, transport), "owner", player, scope)
                controller!!.start()
            }
            waitFor { controller?.state?.value?.nextClip?.id == "two" }
            assertEquals("Next Title", controller?.state?.value?.nextClip?.title)
            assertEquals("Next Artist", controller?.state?.value?.nextClip?.artist)
            instrument.runOnMainSync { controller!!.next() }
            waitFor { controller?.state?.value?.clip?.id == "two" }
            assertEquals("Next Title", controller?.state?.value?.clip?.title)
        } finally {
            controller?.let { instrument.runOnMainSync { it.close() } }
            scope.cancel()
        }
    }

    private fun waitFor(predicate: () -> Boolean) {
        val end = System.currentTimeMillis() + 10_000
        while (!predicate() && System.currentTimeMillis() < end) Thread.sleep(50)
        assertTrue("Clip queue did not become ready", predicate())
    }
}
