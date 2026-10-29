package dev.petrov.ymplayer2

import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.petrov.ymplayer2.clips.ClipCheckpoint
import dev.petrov.ymplayer2.clips.ClipControlsView
import dev.petrov.ymplayer2.clips.ClipWaveController
import dev.petrov.ymplayer2.clips.ClipWaveState
import dev.petrov.ymplayer2.core.*
import dev.petrov.ymplayer2.localization.AppLanguages
import dev.petrov.ymplayer2.localization.trMessage
import dev.petrov.ymplayer2.yandex.ClipTransport
import dev.petrov.ymplayer2.yandex.YandexClip
import dev.petrov.ymplayer2.yandex.YandexClipApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Production ClipActivity, native overlay, controller and MP4 decoder. Only the account/queue are
 * local fixtures. Real touchscreen DOWN/UP goes through Android's input dispatcher: performClick
 * would bypass the touch-mode focus rule that consumed the first tap on every newly selected button.
 */
@RunWith(AndroidJUnit4::class)
class ClipTouchControlsTest {
    private val instrument get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrument.targetContext
    private val app get() = context.applicationContext as PlayerApplication

    @Test fun firstTouchOnUnfocusedPlayAndPauseExecutesImmediately() = withClip { scenario ->
        tapButton(scenario, "Воспроизвести")
        awaitState(scenario, "The first touchscreen Play must start the real decoder") { it.player.isPlaying }
        tapButton(scenario, "Пауза")
        awaitState(scenario, "One Pause must pause, without a focus-only tap") { !it.player.playWhenReady }
        tapButton(scenario, "Воспроизвести")
        awaitState(scenario, "A subsequent Play must still execute once") { it.player.isPlaying }
    }

    @Test fun firstTouchAfterSwitchingBetweenNextAndPreviousChangesClipOnce() = withClip { scenario ->
        tapButton(scenario, "Следующий клип")
        awaitState(scenario, "One Next must select exactly the second clip") {
            it.state.value.clip?.id == "touch-two" && it.player.currentMediaItem?.mediaId == "touch-two" && !it.state.value.loading
        }
        scenario.onActivity { controller(it).pause() }
        tapButton(scenario, "Предыдущий клип")
        awaitState(scenario, "The first tap on a different button must return to the first clip") {
            it.state.value.clip?.id == "touch-one" && it.player.currentMediaItem?.mediaId == "touch-one" && !it.state.value.loading
        }
        scenario.onActivity { controller(it).pause() }
        tapButton(scenario, "Следующий клип")
        awaitState(scenario, "Returning to Next must execute on the first tap, not skip two clips") {
            it.state.value.clip?.id == "touch-two" && it.player.currentMediaItem?.mediaId == "touch-two" && !it.state.value.loading
        }
    }

    @Test fun firstTouchOnUnfocusedBackClosesTheRealClipActivity() = withClip { scenario ->
        tapButton(scenario, "← Назад")
        waitFor("One touchscreen Back must close ClipActivity") { scenario.state == Lifecycle.State.DESTROYED }
    }

    @Test fun firstTouchOnRetryExecutesConnectionActionExactlyOnce() = withClip { scenario ->
        var retryCount = 0
        scenario.onActivity { controls(it).updateConnection(InternetStatus.OFFLINE) { retryCount++ } }
        tapButton(scenario, "Повторить подключение")
        instrument.runOnMainSync { assertEquals("First Retry must execute, rather than only taking focus", 1, retryCount) }
        tapButton(scenario, "Повторить подключение")
        instrument.runOnMainSync { assertEquals("Each additional tap executes exactly once", 2, retryCount) }
    }

    @Test fun videoTouchHidesAndRevealsControlsWithoutEatingTheNextPause() = withClip { scenario ->
        scenario.onActivity { controller(it).toggle() }
        awaitState(scenario, "Fixture must actually play before checking visibility") { it.player.isPlaying }
        tapVideo(scenario)
        assertControlsShown(scenario, false)
        awaitState(scenario, "Hiding controls must keep playback running") { it.player.playWhenReady }
        tapVideo(scenario)
        assertControlsShown(scenario, true)
        awaitState(scenario, "Revealing controls must not toggle playback") { it.player.playWhenReady }
        tapButton(scenario, "Пауза")
        awaitState(scenario, "First visible Pause must work after a touch reveal") { !it.player.playWhenReady }
    }

    @Test fun dpadFocusAndCenterStillControlPlaybackAndNavigationAfterTouchMode() = withClip { scenario ->
        // A video touch establishes actual touchscreen mode before switching to a remote.
        tapVideo(scenario)
        scenario.onActivity { focusVideo(it) }
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        scenario.onActivity {
            val overlay = controls(it)
            val play = labels(overlay).firstOrNull { view -> view.contentDescription == trMessage("Воспроизвести") }
            assertEquals("DPAD reveal: touchMode=${root(it).isInTouchMode}, rootFocused=${root(it).isFocused}, " +
                "overlayHasFocus=${overlay.hasFocus()}, playFocusable=${play?.isFocusable}, " +
                "playTouchFocusable=${play?.isFocusableInTouchMode}, playShown=${play?.isShown}, playHasFocus=${play?.hasFocus()}, " +
                "activityFinishing=${it.isFinishing}, windowFocus=${it.hasWindowFocus()}, currentFocus=${it.currentFocus}",
                trMessage("Воспроизвести"), it.currentFocus?.contentDescription)
            assertFalse("Reveal/focus press must not start playback", controller(it).player.playWhenReady)
        }
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        awaitState(scenario, "Focused DPAD Center starts video") { it.player.isPlaying }
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        awaitState(scenario, "Focused DPAD Center pauses video") { !it.player.playWhenReady }
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        scenario.onActivity { assertEquals(trMessage("Следующий клип"), it.currentFocus?.contentDescription) }
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        awaitState(scenario, "Remote Next still selects the second clip") { it.state.value.clip?.id == "touch-two" && !it.state.value.loading }
        key(KeyEvent.KEYCODE_DPAD_UP)
        scenario.onActivity { assertEquals(trMessage("← Назад"), it.currentFocus?.contentDescription) }
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("Remote Back still closes ClipActivity") { scenario.state == Lifecycle.State.DESTROYED }
    }

    private fun withClip(block: (ActivityScenario<ClipActivity>) -> Unit) {
        val fixtureScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val session = AccountSession(YandexAccount("touch-fixture", "Touch fixture"), OAuthCredentials("fixture", null, null))
        val accounts = AccountAuth(DemoCatalog().profiles, object : DeviceAuthApi {
            override val configured = true
            override suspend fun requestCode(profileId: String) = error("No OAuth request in this fixture")
            override suspend fun poll(code: DeviceChallenge) = error("No OAuth poll in this fixture")
            override suspend fun account(credentials: OAuthCredentials) = session.account!!
        }, object : AccountStore {
            override suspend fun read(profileId: String) = session
            override suspend fun write(profileId: String, session: AccountSession?) = Unit
        }, fixtureScope)
        val transport = object : ClipTransport {
            override suspend fun get(url: String, token: String) = error("Local clips need no HTTP stream")
            override suspend fun post(url: String, token: String, body: JSONObject): String = when {
                url.endsWith("/feedback") -> ""
                url.endsWith("/next") -> """{"result":{"batchId":"fixture-empty","list":[]}}"""
                url.endsWith("/new") -> """{"result":{"sessionId":"fixture-empty","batchId":"fixture-empty","list":[]}}"""
                else -> error("Unexpected fixture request")
            }
        }
        val checkpointPreferences = context.getSharedPreferences("clip-checkpoints", 0)
        val previousCheckpoint = checkpointPreferences.getString("owner", null)
        val oldLaunch = app.launchState.read("owner")
        val media = File(context.cacheDir, "touch-controls-fixture.mp4")
        instrument.context.assets.open("clip-transport/fixture.mp4").use { input -> media.outputStream().use(input::copyTo) }
        val clips = listOf("one", "two", "three").map { id ->
            YandexClip("touch-$id", "Touch $id", "Local fixture", "", null,
                Uri.fromFile(media).toString(), 12, emptyList(), "")
        }
        try {
            instrument.runOnMainSync {
                AppLanguages.initialize(context, "clip-touch-fixture-language", "ru")
                app.radio.release(); app.playback.connect(); app.playback.switchProfile("owner")
                accounts.activate("owner")
            }
            waitFor("Local fixture account and playback profile") {
                accounts.state.value.phase == AuthPhase.SIGNED_IN && app.playback.state.value.profileId == "owner"
            }
            checkpointPreferences.edit().remove("owner").commit()
            ActivityScenario.launch<ClipActivity>(Intent(context, ClipActivity::class.java)).use { scenario ->
                scenario.onActivity { activity ->
                    val wave = controller(activity)
                    // Keep the real Activity's player, listeners, state collectors and view hierarchy.
                    // Only substitute the account-bound API to prevent any network/auth dependency.
                    field("api").set(wave, YandexClipApi(accounts, transport))
                    ClipActivity::class.java.getDeclaredField("initialClipStarted").apply { isAccessible = true }.setBoolean(activity, true)
                    wave.player.volume = 0f
                    wave.player.playbackParameters = PlaybackParameters(.25f)
                    wave.retryFrom(ClipCheckpoint(clips[0], "touch-session", 0, false))
                }
                awaitState(scenario, "Production ExoPlayer decodes the paused MP4 fixture") {
                    it.state.value.clip?.id == "touch-one" && it.player.playbackState == Player.STATE_READY && !it.player.playWhenReady
                }
                scenario.onActivity { activity ->
                    val wave = controller(activity)
                    @Suppress("UNCHECKED_CAST")
                    val history = field("history").get(wave) as MutableList<Any>
                    val queued = Class.forName("dev.petrov.ymplayer2.clips.QueuedClip")
                        .getDeclaredConstructor(YandexClip::class.java, String::class.java).apply { isAccessible = true }
                    // A bounded, already-known history enables real Next/Previous without a fake URL.
                    clips.drop(1).forEach { history += queued.newInstance(it, "touch-session") }
                    @Suppress("UNCHECKED_CAST")
                    val state = field("mutable").get(wave) as MutableStateFlow<ClipWaveState>
                    state.value = state.value.copy(nextClip = clips[1])
                    controls(activity).showControls(focus = false)
                }
                waitFor("Clip window owns real input") {
                    var ready = false
                    scenario.onActivity { ready = it.hasWindowFocus() && controls(it).width > 0 }
                    ready
                }
                instrument.waitForIdleSync()
                block(scenario)
            }
        } finally {
            fixtureScope.cancel()
            checkpointPreferences.edit().apply {
                if (previousCheckpoint == null) remove("owner") else putString("owner", previousCheckpoint)
            }.commit()
            app.launchState.write("owner", oldLaunch.output, oldLaunch.playing)
            instrument.runOnMainSync { app.playback.stop(); app.leaveClips() }
        }
    }

    private fun field(name: String) = ClipWaveController::class.java.getDeclaredField(name).apply { isAccessible = true }
    private fun controller(activity: ClipActivity) = ClipActivity::class.java.getDeclaredField("clips")
        .apply { isAccessible = true }.get(activity) as ClipWaveController
    private fun root(activity: ClipActivity) = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as ViewGroup
    private fun controls(activity: ClipActivity) = root(activity).getChildAt(1) as ClipControlsView
    private fun labels(view: View): List<TextView> = when (view) {
        is TextView -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { labels(view.getChildAt(it)) }
        else -> emptyList()
    }

    private fun tapButton(scenario: ActivityScenario<ClipActivity>, label: String) {
        val bounds = Rect()
        scenario.onActivity { activity ->
            val target = labels(controls(activity)).single { it.contentDescription == trMessage(label) || it.text.toString() == trMessage(label) }
            // Deliberately start each tap with another view focused. The previous control's retained
            // focus must not make a broken implementation accidentally pass on a second button.
            focusVideo(activity)
            assertFalse("$label must be unfocused before its first touchscreen tap", target.hasFocus())
            assertTrue("$label must be enabled", target.isEnabled)
            assertTrue("$label must be visible on screen", target.isShown && target.getGlobalVisibleRect(bounds))
            assertTrue("$label must have a real touch target", bounds.width() > 0 && bounds.height() > 0)
        }
        touch(bounds.exactCenterX(), bounds.exactCenterY())
    }

    private fun tapVideo(scenario: ActivityScenario<ClipActivity>) {
        val bounds = Rect()
        scenario.onActivity { activity -> assertTrue(root(activity).getChildAt(0).getGlobalVisibleRect(bounds)) }
        // Middle of the video is outside the top and bottom native control panels.
        touch(bounds.exactCenterX(), bounds.exactCenterY())
    }

    private fun focusVideo(activity: ClipActivity) = root(activity).apply {
        val previousPolicy = descendantFocusability
        descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        requestFocus()
        descendantFocusability = previousPolicy
    }

    private fun touch(x: Float, y: Float) {
        val downTime = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            if (action == MotionEvent.ACTION_UP) SystemClock.sleep(40)
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0).apply {
                source = InputDevice.SOURCE_TOUCHSCREEN
            }
            try { assertTrue("Android must accept the real touchscreen event", instrument.uiAutomation.injectInputEvent(event, true)) }
            finally { event.recycle() }
        }
        instrument.waitForIdleSync()
    }

    private fun key(code: Int) { instrument.sendKeyDownUpSync(code); instrument.waitForIdleSync() }
    private fun assertControlsShown(scenario: ActivityScenario<ClipActivity>, expected: Boolean) {
        scenario.onActivity { activity ->
            val play = labels(controls(activity)).single { it.contentDescription in listOf(trMessage("Пауза"), trMessage("Воспроизвести")) }
            assertEquals("Control visibility", expected, play.isShown)
        }
    }
    private fun awaitState(scenario: ActivityScenario<ClipActivity>, label: String, predicate: (ClipWaveController) -> Boolean) = waitFor(label) {
        var ready = false
        scenario.onActivity { ready = predicate(controller(it)) }
        ready
    }
    private fun waitFor(label: String, predicate: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 12_000
        while (!predicate() && SystemClock.uptimeMillis() < end) SystemClock.sleep(40)
        assertTrue(label, predicate())
    }
}
