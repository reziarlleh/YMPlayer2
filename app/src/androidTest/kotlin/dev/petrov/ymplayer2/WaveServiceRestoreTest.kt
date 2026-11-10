@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
package dev.petrov.ymplayer2

import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.petrov.ymplayer2.core.Source
import dev.petrov.ymplayer2.playback.AudioService
import dev.petrov.ymplayer2.playback.LaunchStateStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real MainActivity, Application and AudioService; only the account/media providers are fixtures. */
@RunWith(AndroidJUnit4::class)
class WaveServiceRestoreTest {
    @get:Rule val compose = createAndroidComposeRule<OnlineTestActivity>()
    private val f get() = compose.activity.harness
    private val instrument get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = compose.activity.application as PlayerApplication
    private var heldConnection: android.content.ServiceConnection? = null
    private fun waitFor(label: String, condition: () -> Boolean) {
        try { compose.waitUntil(25_000, condition) }
        catch (error: Throwable) { throw AssertionError(label, error) }
    }

    @Test fun savedWavePlaySurvivesBackgroundServiceRecreation() = recreateService(paused = false)
    @Test fun savedWavePauseSurvivesBackgroundServiceRecreation() = recreateService(paused = true)
    @Test fun actualMedia3TaskRemovalPreservesSavedWavePlay() = recreateService(paused = false, taskRemoval = true)
    @Test fun noisyPauseSurvivesActualForegroundTimeout() = recreateService(paused = true, idleTimeout = 1_500)
    @Test fun foregroundRepairsStoppedEngineWithSavedWavePlayWithoutServiceRestart() = recreateService(paused = false, warmIdle = true)
    @Test fun foregroundPreparesSavedWavePauseWithoutServiceRestart() = recreateService(paused = true, warmIdle = true)
    @Test fun noisyPauseSurvivesDefaultTenMinuteForegroundTimeout() {
        org.junit.Assume.assumeTrue("Explicit long native idle run", InstrumentationRegistry.getArguments().getString("fullIdleTimeout") == "true")
        recreateService(paused = true, idleTimeout = 600_000)
    }

    private fun currentService(keepBound: Boolean = false): AudioService {
        var found: AudioService? = null
        val connection = object : android.content.ServiceConnection {
            override fun onServiceConnected(name: android.content.ComponentName, binder: android.os.IBinder) {
                // Media3 1.11.0's own local binder; no Android hidden API or product hook.
                val field = binder.javaClass.getDeclaredField("serviceReference").apply { isAccessible = true }
                found = (field.get(binder) as java.lang.ref.WeakReference<*>).get() as AudioService
            }
            override fun onServiceDisconnected(name: android.content.ComponentName) = Unit
        }
        assertTrue(compose.activity.bindService(Intent(androidx.media3.session.MediaSessionService.SERVICE_INTERFACE)
            .setClass(compose.activity, AudioService::class.java), connection, android.content.Context.BIND_AUTO_CREATE))
        try {
            waitFor("Local service binder") { found != null }
            if (keepBound) heldConnection = connection
            return found!!
        } finally { if (!keepBound) compose.activity.unbindService(connection) }
    }

    private fun timeoutState(service: AudioService): String {
        val managerField = androidx.media3.session.MediaSessionService::class.java
            .getDeclaredField("mediaNotificationManager").apply { isAccessible = true }
        val manager = managerField.get(service) ?: return "no-manager"
        val values = listOf("startedInForeground", "isUserEngaged", "isUserEngagedTimeoutEnabled", "userEngagedTimeoutMs")
            .joinToString { name -> manager.javaClass.getDeclaredField(name).run { isAccessible = true; "$name=${get(manager)}" } }
        val handler = manager.javaClass.getDeclaredField("mainHandler").run { isAccessible = true; get(manager) as android.os.Handler }
        return "$values timeoutPending=${handler.hasMessages(1)}"
    }

    private fun returnFromLauncher() {
        instrument.uiAutomation.executeShellCommand("am start -W -f 0x10020000 -n " +
            compose.activity.packageName + "/dev.petrov.ymplayer2.MainActivity").use { descriptor ->
            java.io.FileInputStream(descriptor.fileDescriptor).use { it.readBytes() }
        }
        waitFor("Player Activity foreground") {
            var resumed = false
            instrument.runOnMainSync {
                resumed = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).any { it is MainActivity }
            }
            resumed
        }
    }

    private fun recreateService(paused: Boolean, taskRemoval: Boolean = false, idleTimeout: Long? = null, warmIdle: Boolean = false) {
        waitFor("Fixture ready") { f.library.state.value.ready && !f.library.state.value.scanning &&
            f.player.state.value.connected && f.online.catalog.value.enabled }
        compose.runOnIdle { f.player.stop(); f.player.switchProfile("owner"); f.player.clearQueue() }
        runBlocking {
            f.library.state.value.roots.forEach { f.library.forgetFolder(it.uri) }
            compose.activity.contentResolver.call(Uri.parse("content://dev.petrov.ymplayer2.test.control"), "fixtures", null, null)
            f.library.addFolder(TestMusicProvider.tree.toString(), Source.LOCAL)
        }
        waitFor("Music fixtures") { f.library.testTracks.size == 2 }
        // A preceding cold-adapter test may have started the application's original service.
        // Finish it before replacing providers, so onDestroy cannot detach the test's new player.
        val originalPlayer = app.playback
        compose.activity.stopService(Intent(compose.activity, AudioService::class.java))
        waitFor("Original service shutdown") { !originalPlayer.state.value.connected }
        compose.runOnIdle { f.player.playMyWave() }
        waitFor("Playing wave") { f.player.state.value.playing && f.player.state.value.positionSeconds >= 1 }
        val selected = f.player.state.value.current!!.id
        compose.runOnIdle {
            f.player.seek(4)
            if (paused) f.player.sessionPause()
            f.player.saveForExit()
            f.closeEngineForColdRestore()
        }
        val saved = LaunchStateStore(f.context).read("owner")
        val originals = linkedMapOf<java.lang.reflect.Field, Any?>()
        var activity: MainActivity? = null
        try {
            compose.runOnIdle {
                // Replace lazy providers only in the test process. No account/token from the app is used.
                val fixtures = mapOf("accounts" to f.auth, "online" to f.online, "playback" to f.player,
                    "radio" to f.radio, "launchState" to LaunchStateStore(f.context), "taste" to f.taste,
                    "offline" to f.offline, "collections" to f.collections, "history" to f.history,
                    "audioQuality" to f.audioQuality, "cloudPlaylists" to f.cloudPlaylists,
                    "radioCatalog" to f.radioCatalog, "cacheLocation" to f.cacheLocation, "audioExporter" to f.audioExporter)
                fixtures.forEach { (name, value) ->
                    PlayerApplication::class.java.getDeclaredField(name + "\$delegate").apply {
                        isAccessible = true
                        originals[this] = get(app)
                        set(app, lazyOf(value))
                    }
                }
                PlayerApplication::class.java.getDeclaredField("launchRestored").apply {
                    isAccessible = true; setBoolean(app, false)
                }
                app.navigation.edit().putString("route", "player").commit()
            }
            activity = instrument.startActivitySync(Intent(compose.activity, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            waitFor("First foreground restore") { f.player.state.value.current?.id == selected &&
                f.player.state.value.wave && f.player.state.value.playing != paused &&
                f.player.state.value.positionSeconds >= 4 }
            val service = if (idleTimeout != null || warmIdle) currentService(keepBound = warmIdle) else null
            if (service != null && idleTimeout != null) {
                compose.runOnIdle {
                    f.player.sessionPlay()
                }
                waitFor("Service entered foreground") { var ongoing = false
                    instrument.runOnMainSync { ongoing = service.isPlaybackOngoing }; ongoing && f.player.state.value.playing }
                // Wait for Media3's notification controller to observe the resumed state too.
                Thread.sleep(1_000)
                compose.runOnIdle {
                    if (idleTimeout != 600_000L) service.setForegroundServiceTimeoutMs(idleTimeout!!)
                    f.player.seek(4)
                    Log.i("YM2WaveServiceRestore", "Before noisy: ${timeoutState(service)}")
                }
                instrument.uiAutomation.executeShellCommand("su 0 am broadcast -a android.media.AUDIO_BECOMING_NOISY").use { descriptor ->
                    java.io.FileInputStream(descriptor.fileDescriptor).use { it.readBytes() }
                }
                waitFor("Actual service noisy Pause") { !f.player.state.value.playing && !LaunchStateStore(f.context).read("owner").playing }
                Thread.sleep(500)
                compose.runOnIdle { Log.i("YM2WaveServiceRestore", "After noisy: ${timeoutState(service)}") }
            }
            compose.runOnIdle { activity!!.moveTaskToBack(true) }
            waitFor("Activity background") { !activity!!.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }
            if (warmIdle) {
                val field = AudioService::class.java.getDeclaredField("engine").apply { isAccessible = true }
                val raw = field.get(service) as androidx.media3.exoplayer.ExoPlayer
                compose.runOnIdle {
                    // Technical engine loss with the application/service still alive, no user Stop.
                    raw.stop(); raw.pause(); f.player.saveForExit()
                }
                assertEquals(saved, LaunchStateStore(f.context).read("owner"))
                assertTrue(f.player.state.value.connected)
                returnFromLauncher()
                waitFor("Warm foreground must recover the saved transport") {
                    var recovered = false
                    instrument.runOnMainSync { recovered = if (paused) !raw.playWhenReady && raw.playbackState == androidx.media3.common.Player.STATE_READY
                        else raw.isPlaying && raw.currentPosition >= 5_000 }
                    recovered
                }
                assertEquals(selected, f.player.state.value.current?.id)
                assertTrue(f.player.state.value.positionSeconds >= 4)
                assertEquals(saved, LaunchStateStore(f.context).read("owner"))
                return
            }
            if (service != null && idleTimeout != null) {
                try { compose.waitUntil(if (idleTimeout == 600_000L) 660_000 else 15_000) {
                    var expired = false
                    instrument.runOnMainSync {
                        val observed = timeoutState(service)
                        expired = observed.contains("isUserEngaged=false") && observed.contains("timeoutPending=false")
                    }
                    expired
                } } finally { compose.runOnIdle { Log.i("YM2WaveServiceRestore", "After timeout: ${timeoutState(service)}") } }
                // Media3 1.11.0's isPlaybackOngoing flag latches true after the first
                // foreground start. Verify the real Android service, not that stale flag.
                Thread.sleep(500)
                val dump = instrument.uiAutomation.executeShellCommand("dumpsys activity services " + compose.activity.packageName).use { descriptor ->
                    java.io.FileInputStream(descriptor.fileDescriptor).use { String(it.readBytes()) }
                }
                val record = dump.substringAfter("/dev.petrov.ymplayer2.playback.AudioService", "")
                    .substringBefore("* ServiceRecord")
                assertFalse("The actual Android AudioService must have left foreground", record.contains("isForeground=true"))
                Log.i("YM2WaveServiceRestore", "Android service foreground=false after native timer")
                assertEquals(saved, LaunchStateStore(f.context).read("owner"))
                Log.i("YM2WaveServiceRestore", "Actual foreground timeout elapsed: $idleTimeout ms; position=${f.player.state.value.positionSeconds}")
            }
            compose.activity.stopService(Intent(compose.activity, AudioService::class.java))
            waitFor("Service destroyed") { !f.player.state.value.connected }
            assertEquals("Teardown must retain the saved transport", saved, LaunchStateStore(f.context).read("owner"))
            compose.runOnIdle { f.player.connect() }
            waitFor("Silent replacement service") { f.player.state.value.connected && f.player.state.value.wave &&
                f.player.state.value.current?.id == selected }
            compose.runOnIdle { f.player.saveForExit() }
            assertEquals("Metadata-only replacement must retain the saved transport", saved, LaunchStateStore(f.context).read("owner"))
            assertTrue("Metadata-only replacement must retain position", f.player.state.value.positionSeconds >= 4)
            if (taskRemoval) {
                val replacement = currentService()
                compose.runOnIdle { replacement.onTaskRemoved(null) }
                waitFor("Media3 task removal stopped service") { !f.player.state.value.connected }
                assertEquals("Actual Media3 teardown must not erase saved Play", saved, LaunchStateStore(f.context).read("owner"))
                compose.runOnIdle { f.player.connect() }
                waitFor("Second silent replacement") { f.player.state.value.connected && f.player.state.value.wave }
            }
            returnFromLauncher()
            waitFor("Wave restored after background service replacement") { f.player.state.value.current?.id == selected &&
                f.player.state.value.wave && f.player.state.value.playing != paused &&
                f.player.state.value.positionSeconds >= 4 }
            assertEquals(saved, LaunchStateStore(f.context).read("owner"))
        } finally {
            Log.i("YM2WaveServiceRestore", "paused=$paused state=${f.player.state.value.current?.id}/${f.player.state.value.positionSeconds} saved=${LaunchStateStore(f.context).read("owner")}")
            compose.runOnIdle {
                androidx.test.runner.lifecycle.Stage.entries.flatMap {
                    androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(it)
                }.filterIsInstance<MainActivity>().distinct().forEach { it.finish() }
            }
            heldConnection?.let { compose.activity.unbindService(it); heldConnection = null }
            compose.activity.stopService(Intent(compose.activity, AudioService::class.java))
            waitFor("Cleanup service") { !f.player.state.value.connected }
            compose.runOnIdle { originals.forEach { (field, original) -> field.set(app, original) } }
        }
    }
}
