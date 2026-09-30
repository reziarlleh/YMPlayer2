package dev.petrov.ymplayer2

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.petrov.ymplayer2.clips.ClipWaveController
import dev.petrov.ymplayer2.core.*
import dev.petrov.ymplayer2.yandex.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Collections

@RunWith(AndroidJUnit4::class)
class ClipLongSessionTest {
    @Test fun sixtyNaturalEndsSurviveEmptyBatchesAndKeepOnlyFortyBackSteps() {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val context = instrument.targetContext
        val fixture = File(context.cacheDir, "long-clip-fixture.mp4")
        instrument.context.assets.open("clip-transport/fixture.mp4").use { input -> fixture.outputStream().use(input::copyTo) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val accounts = AccountAuth(DemoCatalog().profiles, object : DeviceAuthApi {
            override val configured = true
            override suspend fun requestCode(profileId: String) = error("unused")
            override suspend fun poll(code: DeviceChallenge) = error("unused")
            override suspend fun account(credentials: OAuthCredentials) = error("unused")
        }, object : AccountStore {
            override suspend fun read(profileId: String) = AccountSession(YandexAccount("fixture", "Fixture"), OAuthCredentials("fixture", null, null))
            override suspend fun write(profileId: String, session: AccountSession?) = Unit
        }, scope)
        var sessions = 0
        val feedback = Collections.synchronizedList(mutableListOf<Pair<String, JSONObject>>())
        val transport = object : ClipTransport {
            override suspend fun get(url: String, token: String) = error("Preview fixtures need no player JSON")
            override suspend fun post(url: String, token: String, body: JSONObject): String = when {
                url.endsWith("/new") -> {
                    val session = ++sessions
                    val clips = JSONArray((1..5).map { offset ->
                        val number = (session - 1) * 5 + offset
                        JSONObject().put("clipId", "c$number").put("title", "Clip $number")
                            .put("previewUrl", "https://strm.yandex.ru/fixture.mp4")
                    })
                    JSONObject().put("result", JSONObject().put("sessionId", "s$session").put("batchId", "s$session").put("list", clips)).toString()
                }
                url.endsWith("/next") -> """{"result":{"batchId":"empty","list":[]}}"""
                url.endsWith("/feedback") -> { feedback += url to body; "" }
                else -> error(url)
            }
        }
        var controller: ClipWaveController? = null
        fun waitFor(label: String, condition: () -> Boolean) {
            val deadline = System.currentTimeMillis() + 10_000
            while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(20)
            assertTrue("$label; state=${controller?.state?.value}", condition())
        }
        try {
            instrument.runOnMainSync { accounts.activate("owner") }
            waitFor("Fixture account") { accounts.state.value.phase == AuthPhase.SIGNED_IN }
            instrument.runOnMainSync {
                // Wire responses use valid provider URLs; actual media loads our generated MP4.
                val base = DefaultMediaSourceFactory(context)
                val sources = object : MediaSource.Factory by base {
                    override fun createMediaSource(item: MediaItem) = base.createMediaSource(item.buildUpon().setUri(Uri.fromFile(fixture)).build())
                }
                val player = ExoPlayer.Builder(context).setMediaSourceFactory(sources).build().apply { volume = 0f }
                controller = ClipWaveController(YandexClipApi(accounts, transport), "owner", player, scope)
                controller!!.start()
            }
            for (number in 1..60) {
                waitFor("Current/next $number") { controller!!.state.value.let { it.clip?.id == "c$number" && it.nextClip?.id == "c${number + 1}" } }
                var ready = false
                waitFor("Ready $number") {
                    instrument.runOnMainSync { ready = controller!!.player.playbackState == Player.STATE_READY }
                    ready
                }
                instrument.runOnMainSync { controller!!.player.seekTo(controller!!.player.duration - 120) }
            }
            waitFor("Automatic final transition") { controller!!.state.value.clip?.id == "c61" }
            instrument.runOnMainSync { controller!!.pause() }
            waitFor("Finished feedback") { synchronized(feedback) { feedback.count { it.second.getJSONObject("event").getString("type") == "playableItemFinished" } == 60 } }
            synchronized(feedback) {
                feedback.filter { it.second.getJSONObject("event").getString("type") == "playableItemFinished" }.forEach { (url, body) ->
                    val clip = body.getJSONObject("event").getJSONObject("playable").getString("id").removePrefix("c").toInt()
                    assertTrue("Feedback must belong to the clip's original session", "/session/s${(clip - 1) / 5 + 1}/feedback" in url)
                }
            }
            assertEquals(13, sessions)
            for (number in 60 downTo 22) {
                instrument.runOnMainSync { controller!!.previous(); controller!!.pause() }
                waitFor("Back $number") { controller!!.state.value.clip?.id == "c$number" }
            }
            assertFalse(controller!!.state.value.canGoBack)
            instrument.runOnMainSync { controller!!.previous() }
            assertEquals("c22", controller!!.state.value.clip?.id)
            assertEquals(13, sessions)
        } finally {
            controller?.let { instrument.runOnMainSync { it.close() } }
            scope.cancel()
        }
    }
}
