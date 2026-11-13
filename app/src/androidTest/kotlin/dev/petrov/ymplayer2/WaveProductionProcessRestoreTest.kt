@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
package dev.petrov.ymplayer2

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Process
import android.app.NotificationManager
import androidx.core.app.NotificationCompat
import androidx.media3.session.MediaBrowser
import androidx.media3.session.SessionToken
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.petrov.ymplayer2.core.*
import dev.petrov.ymplayer2.playback.LaunchStateStore
import dev.petrov.ymplayer2.yandex.KeystoreAccountStore
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.TimeUnit

/** Two processes: fixtures seed real storage, then the unmodified Application/Activity/service restore it. */
@RunWith(AndroidJUnit4::class)
class WaveProductionProcessRestoreTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrument get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrument.targetContext
    private val app get() = context.applicationContext as PlayerApplication
    private fun <T> main(action: () -> T): T {
        val result = AtomicReference<T>()
        instrument.runOnMainSync { result.set(action()) }
        return result.get()
    }
    private fun waitFor(label: String, condition: () -> Boolean) {
        val until = android.os.SystemClock.elapsedRealtime() + 30_000
        do {
            // Read one coherent main-thread state; a second off-thread read could
            // fail after observing success while the rolling window is replenished.
            if (main(condition)) return
            Thread.sleep(40)
        } while (android.os.SystemClock.elapsedRealtime() < until)
        fail(label)
    }

    @Test fun pausedWaveThroughActualProcessAndForeground() {
        val phase = InstrumentationRegistry.getArguments().getString("waveProcessPhase")
        assumeTrue("Run seed, force-stop, then verify in a new process", phase in setOf("seed", "verify"))
        if (phase == "seed") seed() else verify()
    }

    private fun copyPreferences(source: Context, name: String) {
        val edit = context.getSharedPreferences(name, 0).edit().clear()
        source.getSharedPreferences(name, 0).all.forEach { (key, value) ->
            when (value) {
                is String -> edit.putString(key, value)
                is Boolean -> edit.putBoolean(key, value)
                is Int -> edit.putInt(key, value)
                is Long -> edit.putLong(key, value)
                is Float -> edit.putFloat(key, value)
                is Set<*> -> edit.putStringSet(key, value.filterIsInstance<String>().toSet())
            }
        }
        assertTrue(edit.commit())
    }

    private fun seed() {
        ActivityScenario.launch(OnlineTestActivity::class.java).use { scenario ->
            lateinit var fixture: OnlineHarness
            scenario.onActivity { fixture = it.harness }
            waitFor("Fixture account and library") { fixture.library.state.value.ready &&
                !fixture.library.state.value.scanning && fixture.player.state.value.connected && fixture.online.catalog.value.enabled }
            main { fixture.player.stop(); fixture.player.clearQueue() }
            runBlocking {
                fixture.library.state.value.roots.forEach { fixture.library.forgetFolder(it.uri) }
                context.contentResolver.call(Uri.parse("content://dev.petrov.ymplayer2.test.control"), "fixtures", null, null)
                fixture.library.addFolder(TestMusicProvider.tree.toString(), Source.LOCAL)
            }
            waitFor("Fixture audio") { fixture.library.testTracks.size == 2 }
            main { fixture.enableNoisyForTest(); fixture.player.playMyWave() }
            fun full() = fixture.player.state.value.queue.size == 10 && !fixture.player.state.value.waveLoading &&
                fixture.player.bufferedWaveAudioIds().size == 10
            waitFor("Ten complete audio files") { full() && fixture.player.state.value.playing }
            repeat(3) {
                val old = fixture.player.state.value.current!!.id
                main { fixture.player.skip(1) }
                waitFor("Advanced and replenished window") { fixture.player.state.value.current?.id != old && full() }
            }
            main { fixture.player.seek(4) }
            instrument.uiAutomation.executeShellCommand("su 0 am broadcast -a android.media.AUDIO_BECOMING_NOISY").use {
                java.io.FileInputStream(it.fileDescriptor).use { stream -> stream.readBytes() }
            }
            waitFor("Noisy pause") { !fixture.player.state.value.playing && !LaunchStateStore(fixture.context).read("owner").playing }
            main { fixture.player.saveForExit(); fixture.closeEngineForColdRestore() }
            val saved = JSONObject(fixture.checkpointText())
            assertEquals(4, saved.getInt("position"))
            assertTrue(saved.getBoolean("wave"))
            copyPreferences(fixture.context, "playback")
            copyPreferences(fixture.context, "launch-state")
            val source = File(fixture.context.filesDir, "wave-audio")
            val target = File(context.filesDir, "wave-audio").apply { mkdirs() }
            source.listFiles()!!.filter { it.extension == "audio" }.forEach { it.copyTo(File(target, it.name), overwrite = true) }
            File(fixture.context.filesDir, "wave-audio-index.json")
                .copyTo(File(context.filesDir, "wave-audio-index.json"), overwrite = true)
            // Synthetic credentials only. The real Keystore store is intentionally not replaced on verification.
            runBlocking { KeystoreAccountStore(context).write("owner",
                AccountSession(YandexAccount("1", "Fixture"), OAuthCredentials("fixture", null, null))) }
            assertTrue(context.getSharedPreferences("wave-process-probe", 0).edit().clear()
                .putInt("pid", Process.myPid()).putString("queue", saved.toString()).commit())
            assertTrue(app.navigation.edit().putString("route", "player").commit())
        }
    }

    private fun verify() {
        val preferences = context.getSharedPreferences("wave-process-probe", 0)
        assertNotEquals("Verification must use a fresh process", preferences.getInt("pid", 0), Process.myPid())
        val expected = JSONObject(preferences.getString("queue", "{}")!!)
        val id = expected.getString("current")
        var position = expected.getInt("position")
        val ids = expected.getJSONArray("ids").let { list -> (0 until list.length()).map(list::getString) }
        val notificationAction = InstrumentationRegistry.getArguments().getString("waveNotificationAction") ?: "none"
        require(notificationAction in setOf("none", "dismiss", "controllerStop", "tvStop"))
        val trace = JSONArray()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        main { scope.launch { app.playback.state.collect { state ->
            if (trace.length() < 200) trace.put(JSONObject().put("id", state.current?.id)
                .put("position", state.positionSeconds).put("wave", state.wave).put("queue", state.queue.size)
                .put("playing", state.playing).put("stopped", app.launchState.read("owner").stopped)
                .put("auth", app.accounts.state.value.phase.name))
        } } }
        var activity: MainActivity? = null
        try {
            activity = instrument.startActivitySync(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            waitFor("Actual paused wave restoration") { app.playback.state.value.current?.id == id && app.playback.state.value.wave }
            Thread.sleep(12_000)
            fun unchanged() {
                val state = app.playback.state.value
                assertTrue(state.wave); assertEquals(id, state.current?.id)
                assertEquals(position, state.positionSeconds); assertEquals(ids, state.queue.map(Track::id))
                assertFalse(state.playing); assertFalse(app.launchState.read("owner").stopped)
                assertEquals(AuthPhase.SIGNED_IN, app.accounts.state.value.phase)
            }
            main { unchanged(); app.playback.sessionPlay() }
            waitFor("Actual service playing from cached audio") { app.playback.state.value.playing &&
                app.playback.state.value.positionSeconds > position }
            instrument.uiAutomation.executeShellCommand("su 0 am broadcast -a android.media.AUDIO_BECOMING_NOISY").use {
                java.io.FileInputStream(it.fileDescriptor).use { stream -> stream.readBytes() }
            }
            waitFor("Actual service noisy pause") { !app.playback.state.value.playing &&
                !app.launchState.read("owner").playing }
            main {
                position = app.playback.state.value.positionSeconds
                assertTrue(position > expected.getInt("position"))
                app.playback.saveForExit()
                unchanged(); activity!!.moveTaskToBack(true)
            }
            Thread.sleep(2_000)
            if (notificationAction != "none") {
                val notifications = context.getSystemService(NotificationManager::class.java)
                waitFor("Paused media card") { notifications.activeNotifications.any { it.id == 1001 } }
                val card = notifications.activeNotifications.single { it.id == 1001 }.notification
                when (notificationAction) {
                    "dismiss" -> { assertNotNull(card.deleteIntent); card.deleteIntent.send() }
                    "tvStop" -> {
                        val stop = NotificationCompat.TvExtender(card).deleteIntent
                        assertNotNull(stop); stop!!.send()
                    }
                    "controllerStop" -> {
                        val browser = MediaBrowser.Builder(context, SessionToken(context,
                            android.content.ComponentName(context, dev.petrov.ymplayer2.playback.AudioService::class.java)))
                            .buildAsync().get(20, TimeUnit.SECONDS)
                        try {
                            main { browser.stop() }
                            waitFor("Controller stopped the engine") { !app.playback.state.value.playing &&
                                notifications.activeNotifications.none { it.id == 1001 } }
                        } finally { main { browser.release() } }
                    }
                }
                waitFor("Dismissed/stopped media card removed") { notifications.activeNotifications.none { it.id == 1001 } }
                main { app.playback.saveForExit() }
                val checkpoint = JSONObject(context.getSharedPreferences("playback", 0).getString("queue:owner", "{}")!!)
                if (notificationAction == "tvStop") {
                    assertTrue(app.launchState.read("owner").stopped)
                    assertEquals(0, checkpoint.getInt("position"))
                    assertEquals(0, checkpoint.getJSONArray("ids").length())
                } else {
                    assertFalse("Dismissing a card/system Stop must retain a resumable pause", app.launchState.read("owner").stopped)
                    assertEquals(id, checkpoint.optString("current"))
                    assertEquals(position, checkpoint.getInt("position"))
                    assertEquals(ids, checkpoint.getJSONArray("ids").let { rows -> (0 until rows.length()).map(rows::getString) })
                }
            }
            instrument.uiAutomation.executeShellCommand("am start -W -f 0x10020000 -n " + context.packageName +
                "/dev.petrov.ymplayer2.MainActivity").use { java.io.FileInputStream(it.fileDescriptor).use { stream -> stream.readBytes() } }
            Thread.sleep(8_000)
            main {
                if (notificationAction == "tvStop") {
                    assertTrue(app.launchState.read("owner").stopped)
                    assertFalse(app.playback.state.value.playing)
                    assertNull(app.playback.state.value.current)
                    assertTrue(app.playback.state.value.queue.isEmpty())
                } else unchanged()
            }
        } finally {
            main {
                File(context.filesDir, "wave-process-trace.json").writeText(trace.toString(2))
                activity?.finish(); scope.cancel()
            }
        }
    }
}
