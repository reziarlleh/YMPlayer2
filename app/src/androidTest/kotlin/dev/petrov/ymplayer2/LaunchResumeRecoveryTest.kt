@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
package dev.petrov.ymplayer2

import android.net.Uri
import android.util.Log
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.petrov.ymplayer2.core.*
import dev.petrov.ymplayer2.playback.LaunchStateStore
import dev.petrov.ymplayer2.playback.PlaybackOutput
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

/** Actual startup recovery, not a forced Play and not an assertion of the old failure. */
@RunWith(AndroidJUnit4::class)
class LaunchResumeRecoveryTest {
    @get:Rule val compose = createAndroidComposeRule<OnlineTestActivity>()
    private val f get() = compose.activity.harness
    private val player get() = f.player
    private val saved get() = LaunchStateStore(f.context)
    private fun waitFor(condition: () -> Boolean) {
        try { compose.waitUntil(30_000, condition) }
        catch (e: Throwable) {
            Log.e("YMPLLaunchRecovery", "state=${player.state.value}; saved=${saved.read(player.state.value.profileId)}")
            throw e
        }
    }
    @Before fun prepare() {
        waitFor { f.library.state.value.ready && !f.library.state.value.scanning && player.state.value.connected }
        compose.runOnIdle { player.stop(); player.switchProfile("owner"); player.clearQueue(); f.internet.available.value = true }
        runBlocking { f.library.state.value.roots.forEach { f.library.forgetFolder(it.uri) } }
        compose.activity.contentResolver.call(Uri.parse("content://dev.petrov.ymplayer2.test.control"), "fixtures", null, null)
        runBlocking { f.library.addFolder(TestMusicProvider.tree.toString(), Source.LOCAL) }
        waitFor { f.library.testTracks.size == 2 && f.online.catalog.value.enabled &&
            f.taste.state.value.shelf(TasteKind.ARTIST).ready }
    }
    @After fun stop() { compose.runOnIdle {
        f.streamFailure = null; f.waveFailure = null; f.streamGates = emptyMap()
        f.internet.available.value = true; player.stop()
    } }
    private fun restore(wave: Boolean, failure: MusicFailure? = null, connected: Boolean = true,
        gate: CompletableDeferred<Unit>? = null) {
        if (wave) compose.runOnIdle { player.playMyWave() }
        else {
            compose.runOnIdle { f.online.search("fixture", MusicKind.TRACKS, false) }
            waitFor { f.online.state.value.loaded }
            compose.runOnIdle { player.playQueue(f.online.state.value.entries.mapNotNull { it.track?.id }) }
        }
        waitFor { player.state.value.playing && player.state.value.positionSeconds >= 1 }
        val id = player.state.value.current!!.id
        compose.runOnIdle {
            player.seek(2); player.saveForExit()
            f.streamFailure = failure
            if (wave) f.waveFailure = failure
            f.internet.available.value = connected
            if (gate != null) f.streamGates = mapOf(id to gate)
            if (wave && failure != null) {
                // Durable prepared audio is supposed to play without a server. This fixture
                // specifically exercises a cache miss followed by a failed remote preparation.
                java.io.File(f.context.filesDir, "wave-audio").listFiles().orEmpty()
                    .filter { it.extension == "audio" }.forEach { check(it.delete()) }
            }
            f.restartEngine()
        }
        waitFor { player.state.value.connected && player.state.value.current?.id == id }
        assertTrue(saved.read("owner").playing)
        assertFalse(player.state.value.playing)
    }
    private fun failedWave() {
        restore(true, MusicFailure.NETWORK, connected = false)
        compose.runOnIdle { player.resumeOnLaunch() }
        waitFor {
            val s = player.state.value
            val issue = s.waveIssue ?: s.error
            issue != null && !issue.contains("автоматически") && !s.waveLoading && !s.playing &&
                f.launchDiagnostics.any { it.operation == DiagnosticOperation.LAUNCH_RESUME_WAIT && it.category == DiagnosticCategory.NETWORK }
        }
    }
    private fun recoverSource() = compose.runOnIdle {
        f.streamFailure = null; f.waveFailure = null; f.internet.available.value = true
    }
    private fun observeNoRestart() {
        Thread.sleep(6_000)
        compose.runOnIdle { player.saveForExit() }
        assertFalse(player.state.value.playing)
    }
    @Test fun restoredWaveAutomaticallyContinuesWhenConnectionReturnsAfterRetryExhaustion() {
        failedWave()
        recoverSource()
        // No manual/system Play here. Position advancement proves actual playback.
        waitFor { player.state.value.playing && player.state.value.positionSeconds >= 3 }
        assertTrue(f.launchDiagnostics.any { it.operation == DiagnosticOperation.LAUNCH_RESUME_RETRY })
        waitFor { f.launchDiagnostics.any { it.operation == DiagnosticOperation.LAUNCH_RESUME_STARTED } }
        assertNull(player.state.value.error)
    }
    @Test fun restoredTrackRetriesTemporaryFailureWithoutAConnectivityEdge() {
        restore(false, MusicFailure.NETWORK)
        compose.runOnIdle { player.resumeOnLaunch() }
        waitFor { player.state.value.error != null }
        recoverSource()
        waitFor { player.state.value.playing && player.state.value.positionSeconds >= 3 }
        assertNull("Successful retry must clear the old error (B-046)", player.state.value.error)
        assertTrue(f.launchDiagnostics.any { it.operation == DiagnosticOperation.LAUNCH_RESUME_RETRY })
    }
    @Test fun healthyWaveStartsWithoutWaitingForRetryTimer() {
        restore(true)
        compose.runOnIdle { player.resumeOnLaunch() }
        waitFor { player.state.value.playing && player.state.value.positionSeconds >= 3 }
        assertFalse(f.launchDiagnostics.any { it.operation == DiagnosticOperation.LAUNCH_RESUME_RETRY })
    }
    @Test fun explicitPauseCancelsWaitingResume() {
        failedWave()
        compose.runOnIdle { f.systemPlayer.pause() }
        recoverSource(); observeNoRestart()
        assertFalse(saved.read("owner").playing)
        assertFalse(f.launchDiagnostics.any { it.operation == DiagnosticOperation.LAUNCH_RESUME_RETRY })
    }
    @Test fun explicitStopCancelsWaitingResume() {
        failedWave()
        compose.runOnIdle { player.stop() }
        recoverSource(); observeNoRestart()
        assertFalse(saved.read("owner").playing)
    }
    @Test fun localSelectionCancelsOldResumeAndRejectsLateLaunchRevision() {
        failedWave()
        val oldRevision = player.launchRevision
        val track = f.library.testTracks.first()
        compose.runOnIdle { player.playQueue(listOf(track.id)); player.resumeOnLaunch(oldRevision) }
        recoverSource()
        waitFor { player.state.value.playing && player.state.value.current?.id == track.id }
        Thread.sleep(6_000)
        assertEquals(track.id, player.state.value.current?.id)
        assertFalse(player.state.value.wave)
        assertFalse(f.launchDiagnostics.any { it.operation == DiagnosticOperation.LAUNCH_RESUME_RETRY })
    }
    @Test fun profileSwitchCancelsOldResume() {
        failedWave()
        compose.runOnIdle { player.switchProfile("road") }
        recoverSource(); observeNoRestart()
        assertEquals("road", player.state.value.profileId)
        assertFalse(saved.read("road").playing)
    }
    @Test fun radioTakesOwnershipWithoutOldMusicResuming() {
        failedWave()
        compose.runOnIdle { f.radio.play(f.radioApi.one) }
        recoverSource()
        waitFor { f.radio.state.value.playing }
        Thread.sleep(6_000)
        assertEquals(PlaybackOutput.RADIO, saved.read("owner").output)
        assertTrue(f.radio.state.value.ownsOutput)
        assertFalse(player.state.value.playing)
    }
    @Test fun metadataOnlyReconnectDoesNotRequestPlayback() {
        failedWave()
        compose.runOnIdle { f.restartEngine() }
        recoverSource(); observeNoRestart()
        assertTrue(saved.read("owner").playing)
    }
    @Test fun latePreparedStreamCannotOverridePause() {
        val gate = CompletableDeferred<Unit>()
        restore(false, gate = gate)
        val resolved = f.resolved.size
        compose.runOnIdle { player.resumeOnLaunch() }
        waitFor { f.resolved.size > resolved || player.state.value.buffering }
        compose.runOnIdle { f.systemPlayer.pause(); gate.complete(Unit) }
        observeNoRestart()
        assertFalse(saved.read("owner").playing)
    }
    @Test fun accessFailureDoesNotTriggerAutomaticPlay() {
        restore(false, MusicFailure.ACCESS)
        compose.runOnIdle { player.resumeOnLaunch() }
        waitFor { player.state.value.error != null }
        recoverSource(); observeNoRestart()
        assertFalse(f.launchDiagnostics.any { it.operation == DiagnosticOperation.LAUNCH_RESUME_RETRY })
    }
    @Test fun temporaryServerFailureCanRecoverWithoutManualPlay() {
        // A status has no effect until restore() injects the actual failure.
        compose.runOnIdle { f.streamHttpStatus = 503 }
        restore(false, MusicFailure.RESPONSE, connected = false)
        compose.runOnIdle { player.resumeOnLaunch() }
        waitFor { player.state.value.error != null }
        recoverSource()
        waitFor { player.state.value.playing && player.state.value.positionSeconds >= 3 }
        assertTrue(f.launchDiagnostics.any { it.operation == DiagnosticOperation.LAUNCH_RESUME_RETRY && it.category == DiagnosticCategory.SERVER })
    }
    @Test fun permanentAudioFocusLossCancelsWaitingLaunch() {
        val gate = CompletableDeferred<Unit>()
        restore(false, gate = gate)
        compose.runOnIdle { f.enableAudioFocusForTest(); player.resumeOnLaunch() }
        waitFor { player.state.value.buffering }
        val audio = compose.activity.getSystemService(android.media.AudioManager::class.java)
        val request: Any = if (android.os.Build.VERSION.SDK_INT >= 26) android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA).build())
            .setOnAudioFocusChangeListener { }.build()
        else android.media.AudioManager.OnAudioFocusChangeListener { }
        try {
            compose.runOnIdle {
                @Suppress("DEPRECATION")
                val result = if (android.os.Build.VERSION.SDK_INT >= 26) audio.requestAudioFocus(request as android.media.AudioFocusRequest)
                    else audio.requestAudioFocus(request as android.media.AudioManager.OnAudioFocusChangeListener,
                        android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.AUDIOFOCUS_GAIN)
                assertEquals(android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED, result)
            }
            waitFor { f.launchDiagnostics.any { it.operation == DiagnosticOperation.LAUNCH_RESUME_CANCELLED } }
            compose.runOnIdle { gate.complete(Unit) }
            observeNoRestart()
            assertFalse(f.launchDiagnostics.any { it.operation == DiagnosticOperation.LAUNCH_RESUME_RETRY })
        } finally {
            gate.complete(Unit)
            compose.runOnIdle {
                @Suppress("DEPRECATION")
                if (android.os.Build.VERSION.SDK_INT >= 26) audio.abandonAudioFocusRequest(request as android.media.AudioFocusRequest)
                else audio.abandonAudioFocus(request as android.media.AudioManager.OnAudioFocusChangeListener)
            }
        }
    }
}
