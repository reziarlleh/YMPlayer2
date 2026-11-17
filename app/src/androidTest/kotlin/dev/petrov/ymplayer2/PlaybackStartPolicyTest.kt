package dev.petrov.ymplayer2

import android.content.Intent
import android.net.Uri
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.petrov.ymplayer2.core.*
import dev.petrov.ymplayer2.playback.AudioService
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

/** Real application/Activity/engine; the source is a local SAF fixture, not a personal account. */
@RunWith(AndroidJUnit4::class)
class PlaybackStartPolicyTest {
    private val instrument get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrument.targetContext
    private val app get() = context.applicationContext as PlayerApplication
    private var screen: ActivityScenario<MainActivity>? = null
    private fun main(action: () -> Unit) = instrument.runOnMainSync(action)
    private fun waitFor(label: String, predicate: () -> Boolean) {
        val end = System.currentTimeMillis() + 25_000
        while (!predicate() && System.currentTimeMillis() < end) Thread.sleep(40)
        assertTrue("$label; player=${app.playback.state.value}", predicate())
    }
    @Before fun prepare() {
        main {
            app.navigation.edit().putString("route", "player").commit()
            app.playbackStart.remove("local")
            app.playback.connect(); app.playback.switchProfile("local"); app.playback.stop(); app.playback.clearQueue()
            app.accounts.activate("local")
        }
        waitFor("Local engine/library") { app.playback.state.value.profileId == "local" &&
            app.playback.state.value.connected && app.library.state.value.ready && !app.library.state.value.scanning }
        runBlocking {
            app.library.state.value.roots.forEach { app.library.forgetFolder(it.uri) }
            context.contentResolver.call(Uri.parse("content://dev.petrov.ymplayer2.test.control"), "fixtures", null, null)
            app.library.addFolder(TestMusicProvider.tree.toString(), Source.LOCAL)
        }
        waitFor("Fixture tracks") { app.library.testTracks.size == 2 }
    }
    @After fun cleanup() {
        main { app.playback.stop(); app.playback.clearQueue(); app.playbackStart.remove("local"); app.playbackStart.remove("startup-other") }
        screen?.close(); screen = null
    }
    private fun open(mode: PlaybackStartMode) {
        main { app.playbackStart.set("local", mode) }
        screen = ActivityScenario.launch(Intent(context, MainActivity::class.java))
        waitFor("Foreground engine") { app.playback.state.value.connected }
    }
    private fun play(): String {
        val id = app.library.testTracks.first().id
        main { app.playback.playQueue(listOf(id)); app.playback.seek(2) }
        waitFor("Decoded fixture") { app.playback.state.value.playing && app.playback.state.value.positionSeconds >= 2 }
        return id
    }
    private fun pause() {
        main { app.playback.toggle() }
        waitFor("Paused fixture") { !app.playback.state.value.playing }
    }
    private fun roundTrip() {
        screen!!.moveToState(Lifecycle.State.CREATED)
        screen!!.moveToState(Lifecycle.State.RESUMED)
    }
    @Test fun onResumesWarmPauseAtSameTrackAndPosition() {
        open(PlaybackStartMode.ON)
        val id = play(); pause()
        val position = app.playback.state.value.positionSeconds
        roundTrip()
        waitFor("ON resumes on UI return") { app.playback.state.value.playing }
        assertEquals(id, app.playback.state.value.current?.id)
        assertTrue(app.playback.state.value.positionSeconds >= position)
    }
    @Test fun offDoesNotInterruptMusicAlreadyPlayingInBackground() {
        open(PlaybackStartMode.OFF)
        val id = play()
        roundTrip()
        waitFor("Background playback continues") { app.playback.state.value.playing }
        assertEquals(id, app.playback.state.value.current?.id)
    }
    @Test fun autoKeepsWarmManualPause() {
        open(PlaybackStartMode.AUTO)
        val id = play(); pause(); roundTrip()
        Thread.sleep(700)
        assertFalse(app.playback.state.value.playing)
        assertEquals(id, app.playback.state.value.current?.id)
    }
    @Test fun onDoesNotUndoManualPauseOnConfigurationRecreation() {
        open(PlaybackStartMode.ON)
        val id = play(); pause()
        screen!!.recreate()
        Thread.sleep(700)
        assertFalse(app.playback.state.value.playing)
        assertEquals(id, app.playback.state.value.current?.id)
    }
    @Test fun coldEntryPoliciesRespectSavedPlayOrPause() {
        for ((mode, savedPlaying, expected) in listOf(
            Triple(PlaybackStartMode.AUTO, true, true), Triple(PlaybackStartMode.AUTO, false, false),
            Triple(PlaybackStartMode.OFF, true, false), Triple(PlaybackStartMode.ON, false, true))) {
            open(mode)
            val id = play()
            if (!savedPlaying) pause()
            main { app.playback.saveForExit() }
            screen!!.close(); screen = null
            main { context.stopService(Intent(context, AudioService::class.java)) }
            waitFor("Previous engine released") { !app.playback.state.value.connected }
            open(mode)
            waitFor("Restored track") { app.playback.state.value.current?.id == id }
            if (expected) waitFor("$mode restores Play") { app.playback.state.value.playing }
            else { Thread.sleep(700); assertFalse("$mode stays paused", app.playback.state.value.playing) }
            assertTrue("Position retained for $mode", app.playback.state.value.positionSeconds >= 2)
            main { app.playback.stop() }
            screen!!.close(); screen = null
        }
    }
    @Test fun offWithEmptyQueueRemainsStopped() {
        open(PlaybackStartMode.OFF)
        roundTrip(); Thread.sleep(400)
        assertNull(app.playback.state.value.current)
        assertFalse(app.playback.state.value.playing)
    }
    @Test fun aNewPauseCancelsAnOlderInterfacePlayRequest() {
        open(PlaybackStartMode.AUTO)
        play()
        val revision = app.playback.launchRevision
        main { app.playback.toggle(); app.playback.playOnInterfaceOpen(revision) }
        Thread.sleep(500)
        assertFalse(app.playback.state.value.playing)
    }
    @Test fun profilePreferencesPersistIndependentlyAndMissingProfilesDefaultToAuto() {
        main { app.playbackStart.set("local", PlaybackStartMode.ON); app.playbackStart.set("startup-other", PlaybackStartMode.OFF) }
        val restored = loadPlaybackStartSettings(context)
        assertEquals(PlaybackStartMode.ON, restored.mode("local"))
        assertEquals(PlaybackStartMode.OFF, restored.mode("startup-other"))
        assertEquals(PlaybackStartMode.AUTO, restored.mode("new-profile"))
        main { app.playbackStart.remove("startup-other") }
        assertEquals(PlaybackStartMode.AUTO, loadPlaybackStartSettings(context).mode("startup-other"))
    }
}
