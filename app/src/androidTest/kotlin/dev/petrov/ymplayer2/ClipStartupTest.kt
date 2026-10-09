package dev.petrov.ymplayer2

import android.graphics.Matrix
import android.content.Intent
import android.net.Uri
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import dev.petrov.ymplayer2.clips.*
import dev.petrov.ymplayer2.core.*
import dev.petrov.ymplayer2.localization.trMessage
import dev.petrov.ymplayer2.yandex.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Real decoder/surface and main-immediate start; no account/network fixture is needed. */
@RunWith(AndroidJUnit4::class)
class ClipStartupTest {
    private val instrument get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrument.targetContext
    private fun video(): File = File(context.cacheDir, "startup-video.mp4").also { file ->
        instrument.context.assets.open("clip-transport/fixture.mp4").use { input -> file.outputStream().use(input::copyTo) }
    }
    private fun waitFor(label: String, test: () -> Boolean) {
        val until = System.currentTimeMillis() + 15000
        while (!test() && System.currentTimeMillis() < until) Thread.sleep(30)
        assertTrue(label, test())
    }
    @Test fun explicitOpenPlaysPausedCheckpointButAutomaticRestoreKeepsPause() {
        val app = context.applicationContext as PlayerApplication
        val store = KeystoreAccountStore(context)
        val previous = runBlocking { store.read("owner") }
        val prefs = context.getSharedPreferences("clip-checkpoints", 0)
        val previousCheckpoint = prefs.getString("owner", null)
        val file = video()
        val clip = YandexClip("startup", "Startup", "Fixture", "", null, Uri.fromFile(file).toString(), 12, emptyList(), "")
        val field = ClipActivity::class.java.getDeclaredField("clips").apply { isAccessible = true }
        try {
            runBlocking { store.write("owner", AccountSession(YandexAccount("fixture", "Fixture"), OAuthCredentials("fixture", null, null))) }
            instrument.runOnMainSync { app.playback.connect(); app.playback.switchProfile("owner"); app.accounts.activate("road") }
            waitFor("Playback connected") { app.playback.state.value.connected && app.playback.state.value.profileId == "owner" }
            for (explicit in listOf(false, true)) {
                instrument.runOnMainSync { app.clipCheckpoints.write("owner", ClipCheckpoint(clip, "", 3000, false)) }
                val intent = Intent(context, ClipActivity::class.java).putExtra(ClipActivity.EXTRA_PLAY, explicit)
                ActivityScenario.launch<ClipActivity>(intent).use { scenario ->
                    var ready = false
                    waitFor("Actual ClipActivity decodes checkpoint") {
                        scenario.onActivity {
                            val player = (field.get(it) as ClipWaveController).player
                            ready = player.playbackState == Player.STATE_READY && player.currentPosition >= 3000
                        }
                        ready
                    }
                    scenario.onActivity {
                        val player = (field.get(it) as ClipWaveController).player
                        assertEquals("Explicit entry starts; restored pause stays paused", explicit, player.playWhenReady)
                        assertEquals(explicit, player.isPlaying)
                        val root = it.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as ViewGroup
                        assertEquals("Video must block idle screensavers only while playing", explicit, root.getChildAt(0).keepScreenOn)
                    }
                    if (explicit) {
                        lateinit var videoView: android.view.View
                        scenario.onActivity {
                            videoView = (it.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as ViewGroup).getChildAt(0)
                            (field.get(it) as ClipWaveController).pause()
                        }
                        waitFor("Pause permits the screensaver") {
                            instrument.runOnMainSync { ready = !videoView.keepScreenOn }; ready
                        }
                        scenario.onActivity { (field.get(it) as ClipWaveController).toggle() }
                        waitFor("Play blocks the screensaver again") {
                            instrument.runOnMainSync { ready = videoView.keepScreenOn }; ready
                        }
                        scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
                        instrument.runOnMainSync { assertFalse("Background does not keep the TV awake", videoView.keepScreenOn) }
                        scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
                        waitFor("Foreground return resumes video") {
                            scenario.onActivity { ready = (field.get(it) as ClipWaveController).player.isPlaying && videoView.keepScreenOn }
                            ready
                        }
                    }
                }
            }
        } finally {
            runBlocking { store.write("owner", previous) }
            prefs.edit().apply { if (previousCheckpoint == null) remove("owner") else putString("owner", previousCheckpoint) }.commit()
            instrument.runOnMainSync { app.accounts.activate("guest"); app.accounts.activate("owner"); app.playback.stop(); app.leaveClips() }
        }
    }
    @Test fun pendingAutoplayShowsPauseAndOnePlayResumesPausedClip() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val session = AccountSession(YandexAccount("fixture", "Fixture"), OAuthCredentials("fixture", null, null))
        val auth = AccountAuth(DemoCatalog().profiles, object : DeviceAuthApi {
            override val configured = true
            override suspend fun requestCode(profileId: String) = error("unused")
            override suspend fun poll(code: DeviceChallenge) = error("unused")
            override suspend fun account(credentials: OAuthCredentials) = session.account!!
        }, object : AccountStore {
            override suspend fun read(profileId: String) = session
            override suspend fun write(profileId: String, session: AccountSession?) = Unit
        }, scope)
        instrument.runOnMainSync { auth.activate("owner") }
        waitFor("Fixture account") { auth.state.value.phase == AuthPhase.SIGNED_IN }
        val file = video()
        val clip = YandexClip("startup", "Startup", "Fixture", "", null, Uri.fromFile(file).toString(), 12, emptyList(), "")
        var controller: ClipWaveController? = null
        try {
            instrument.runOnMainSync {
                val player = ExoPlayer.Builder(context).build().apply { volume = 0f }
                controller = ClipWaveController(YandexClipApi(auth), "owner", player, scope)
                controller!!.start(ClipCheckpoint(clip, "", 0, true))
                assertTrue("Immediate start retains play intent before decoder is ready", player.playWhenReady)
                val controls = ClipControlsView(context, controller!!) { }
                controls.render(controller!!.state.value)
                val labels = mutableListOf<String>()
                fun read(view: android.view.View) {
                    if (view is TextView) labels += view.contentDescription?.toString().orEmpty()
                    if (view is ViewGroup) for (i in 0 until view.childCount) read(view.getChildAt(i))
                }
                read(controls)
                assertTrue("Buffering Play must not masquerade as a paused clip", trMessage("Пауза") in labels)
                controls.handleRemoteKey(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_MEDIA_PLAY))
                assertTrue("Repeated media Play does not cancel a pending start", player.playWhenReady)
                controller!!.pause()
                controls.handleRemoteKey(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_MEDIA_PLAY))
                assertTrue("One Play is enough", player.playWhenReady)
            }
            var playing = false
            waitFor("One Play starts actual video") {
                instrument.runOnMainSync { playing = controller!!.player.isPlaying && controller!!.player.currentPosition > 200 }
                playing
            }
        } finally { instrument.runOnMainSync { controller?.close() }; scope.cancel() }
    }
    @Test fun firstSurfaceAndReattachedSurfaceKeepVideoProportionsWithoutRotation() {
        val file = video()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var player: ExoPlayer? = null
            lateinit var root: FrameLayout
            lateinit var texture: android.view.TextureView
            var fitDetails = ""
            try {
                scenario.onActivity { activity ->
                    (context.applicationContext as PlayerApplication).playback.stop()
                    root = FrameLayout(activity)
                    activity.setContentView(root)
                    player = ExoPlayer.Builder(activity).build().apply { volume = 0f }
                    texture = clipVideoView(activity, player!!)
                    root.addView(texture, FrameLayout.LayoutParams(300, 400))
                    player!!.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
                    player!!.prepare(); player!!.play()
                }
                fun fitted(): Boolean {
                    var result = false
                    scenario.onActivity {
                        val p = player!!
                        val matrix = FloatArray(9); texture.getTransform(Matrix()).getValues(matrix)
                        fitDetails = "state=${p.playbackState} size=${p.videoSize} view=${texture.width}x${texture.height} surface=${texture.isAvailable} matrix=${matrix.toList()} error=${p.playerError}"
                        if (p.playbackState in listOf(Player.STATE_READY, Player.STATE_ENDED) && p.videoSize.width > 0 && texture.width > 0) {
                            val values = FloatArray(9); texture.getTransform(Matrix()).getValues(values)
                            val ratio = p.videoSize.width * p.videoSize.pixelWidthHeightRatio / p.videoSize.height
                            val viewRatio = texture.width.toFloat() / texture.height
                            val expected = if (viewRatio < ratio) viewRatio / ratio else 1f
                            result = kotlin.math.abs(values[Matrix.MSCALE_Y] - expected) < .01f
                        }
                    }
                    return result
                }
                try { waitFor("First rendered surface fits the real video") { fitted() } }
                catch (e: AssertionError) { throw AssertionError(fitDetails, e) }
                scenario.onActivity { activity ->
                    root.removeView(texture)
                    texture = clipVideoView(activity, player!!)
                    root.addView(texture, FrameLayout.LayoutParams(300, 400))
                }
                waitFor("Already-known size is applied on attach, without orientation change") { fitted() }
            } finally { scenario.onActivity { player?.release() } }
        }
    }
}
