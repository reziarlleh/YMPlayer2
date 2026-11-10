@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
package dev.petrov.ymplayer2

import android.net.Uri
import android.util.Log
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.petrov.ymplayer2.core.*
import dev.petrov.ymplayer2.playback.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

/** New adapter/account objects; restarting only the engine misses account/queue ordering. */
@RunWith(AndroidJUnit4::class)
class WaveColdRestoreTest {
    @get:Rule val compose = createAndroidComposeRule<OnlineTestActivity>()
    private val f get() = compose.activity.harness
    private fun waitFor(label: String, condition: () -> Boolean) {
        try { compose.waitUntil(20_000, condition) }
        catch (e: Throwable) { throw AssertionError(label, e) }
    }

    @Before fun prepare() {
        waitFor("Fixture ready") { f.library.state.value.ready && !f.library.state.value.scanning &&
            f.player.state.value.connected && f.online.catalog.value.enabled }
        compose.runOnIdle { f.player.stop(); f.player.switchProfile("owner"); f.player.clearQueue() }
        runBlocking { f.library.state.value.roots.forEach { f.library.forgetFolder(it.uri) } }
        compose.activity.contentResolver.call(Uri.parse("content://dev.petrov.ymplayer2.test.control"), "fixtures", null, null)
        runBlocking { f.library.addFolder(TestMusicProvider.tree.toString(), Source.LOCAL) }
        waitFor("Music fixtures") { f.library.testTracks.size == 2 }
    }

    @Test fun delayedAccountRestorePreservesWaveTrackPositionAndPlay() = coldRestore()
    @Test fun savedWavePausePreservesTrackAndPosition() = coldRestore(savedTransport = "pause")
    @Test fun savedWaveStopRemainsStoppedAtZero() = coldRestore(savedTransport = "stop")
    @Test fun pauseWhileAccountLoadsCancelsLatePlay() = coldRestore(duringAccount = "pause")
    @Test fun stopWhileAccountLoadsCancelsLatePlay() = coldRestore(duringAccount = "stop")
    @Test fun pauseAfterLaunchRequestWinsOverMetadataRefresh() = coldRestore(pauseAfterResume = true)
    @Test fun delayedAccountRestorePreservesOnlineListTrackPositionAndPlay() = coldRestore(wave = false)
    @Test fun noisyPauseRestoresWaveAsPausedAtTheSamePosition() = coldRestore(savedTransport = "noisy")
    @Test fun savedOnlineListPauseRemainsPaused() = coldRestore(wave = false, savedTransport = "pause")
    @Test fun savedOnlineListStopRemainsStopped() = coldRestore(wave = false, savedTransport = "stop")
    @Test fun metadataOnlyColdRestoreDoesNotRequestPlay() = coldRestore(requestLaunch = false)
    @Test fun legacyPausedCheckpointKeepsTrackAndPosition() = coldRestore(savedTransport = "pause", legacy = true)
    @Test fun legacyStoppedCheckpointRemainsStopped() = coldRestore(savedTransport = "stop", legacy = true)
    @Test fun failedPausedWavePreparationCannotSkipTheSavedTrack() = coldRestore(savedTransport = "pause", unavailablePaused = true)
    @Test fun readyAccountBeforeDelayedLibraryPreservesWavePlay() = coldRestore(accountFirst = true)
    @Test fun readyAccountBeforeDelayedLibraryPreservesWavePause() = coldRestore(savedTransport = "pause", accountFirst = true)
    @Test fun readyAccountBeforeDelayedLibraryPreservesWaveStop() = coldRestore(savedTransport = "stop", accountFirst = true)
    @Test fun failedAccountStorageCannotErasePausedWaveCheckpoint() = coldRestore(savedTransport = "pause", accountUnavailable = true)
    @Test fun failedAccountStorageCannotErasePlayingWaveCheckpoint() = coldRestore(accountUnavailable = true)

    private fun coldRestore(savedTransport: String = "play", duringAccount: String? = null,
        pauseAfterResume: Boolean = false, wave: Boolean = true, requestLaunch: Boolean = true,
        legacy: Boolean = false, unavailablePaused: Boolean = false, accountFirst: Boolean = false,
        accountUnavailable: Boolean = false) {
        if (wave) compose.runOnIdle { f.player.playMyWave() }
        else {
            compose.runOnIdle { f.online.search("fixture", MusicKind.TRACKS, false) }
            waitFor("Search fixture") { f.online.state.value.loaded }
            compose.runOnIdle { f.player.playQueue(f.online.state.value.entries.mapNotNull { it.track?.id }) }
        }
        waitFor("Initial wave audible") { f.player.state.value.playing && f.player.state.value.positionSeconds >= 1 }
        var expectedId = ""
        var savedPosition = 4
        compose.runOnIdle {
            f.player.seek(4)
            when (savedTransport) {
                "pause" -> f.player.sessionPause()
                "stop" -> f.player.stop()
                "noisy" -> f.enableNoisyForTest()
            }
        }
        if (savedTransport == "noisy") {
            // The protected Android disconnect event is sent only on our rooted AVD.
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
                "su 0 am broadcast -a android.media.AUDIO_BECOMING_NOISY").use { descriptor ->
                java.io.FileInputStream(descriptor.fileDescriptor).use { it.readBytes() }
            }
            waitFor("Actual noisy receiver must pause") { !f.player.state.value.playing &&
                !LaunchStateStore(f.context).read("owner").playing }
        }
        compose.runOnIdle {
            f.player.saveForExit()
            expectedId = f.player.state.value.current!!.id
            savedPosition = f.player.state.value.positionSeconds
            assertEquals(savedTransport == "play", LaunchStateStore(f.context).read("owner").playing)
            f.closeEngineForColdRestore()
            if (legacy) f.context.getSharedPreferences("launch-state", 0).edit().remove("stopped:owner").commit()
        }

        val accountGate = CompletableDeferred<Unit>()
        if (accountFirst) accountGate.complete(Unit)
        val libraryState = kotlinx.coroutines.flow.MutableStateFlow(f.library.state.value.copy(ready = !accountFirst))
        val restoredLibrary = object : IndexedLocalLibrary by f.library {
            override val state = libraryState
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        lateinit var restored: AndroidPlayback
        var cleanupPlayer: AndroidPlayback? = null
        var cleanupEngine: ExoPlayer? = null
        try {
            compose.runOnIdle {
                val accounts = AccountAuth(f.library.profiles, object : DeviceAuthApi {
                    override val configured = true
                    override suspend fun requestCode(profileId: String) = error("unused")
                    override suspend fun poll(code: DeviceChallenge) = error("unused")
                    override suspend fun account(credentials: OAuthCredentials) = error("unused")
                }, object : AccountStore {
                    override suspend fun read(profileId: String): AccountSession {
                        accountGate.await()
                        if (accountUnavailable) throw AuthException(AuthFailure.STORAGE)
                        return AccountSession(YandexAccount("1", "Fixture"), OAuthCredentials("fixture", null, null))
                    }
                    override suspend fun write(profileId: String, session: AccountSession?) = Unit
                }, scope)
                val online = OnlineMusic(accounts, f.online.api, scope)
                restored = AndroidPlayback(f.context, restoredLibrary, scope, online, waveApi = f.waveApi)
                cleanupPlayer = restored
                val saved = LaunchStateStore(f.context).read("owner")
                val revision = restored.launchRevision
                accounts.activate("owner")
                // PlayerApplication registers this wait before AudioService attaches its engine.
                if (requestLaunch) scope.launch {
                    online.catalog.first { it.profileId == "owner" && it.phase != AuthPhase.LOADING }
                    if (LaunchStateStore(f.context).read("owner") == saved) {
                        restored.resumeOnLaunch(revision)
                        if (pauseAfterResume) restored.sessionPause()
                    }
                }
                val engine = ExoPlayer.Builder(f.context).setMediaSourceFactory(DefaultMediaSourceFactory(f.context)
                    .setDataSourceFactory(onlineDataSourceFactory(f.context) { profile, track -> restored.resolveStream(profile, track) }))
                    .build().apply { volume = 0f }
                cleanupEngine = engine
                restored.attach(engine)
            }
            if (accountFirst) {
                Thread.sleep(500)
                compose.runOnIdle { libraryState.value = f.library.state.value }
            } else waitFor("Saved metadata before account") { restored.state.value.current?.id == expectedId &&
                restored.state.value.positionSeconds == savedPosition }
            compose.runOnIdle {
                when (duringAccount) {
                    "pause" -> restored.sessionPause()
                    "stop" -> restored.stop()
                }
                if (unavailablePaused) f.streamFailure = MusicFailure.NETWORK
                accountGate.complete(Unit)
            }
            val shouldPlay = requestLaunch && savedTransport == "play" && duringAccount == null && !pauseAfterResume
            if (accountUnavailable) {
                Thread.sleep(1_000)
                compose.runOnIdle { restored.saveForExit() }
                assertEquals("An account read failure must retain the selected track", expectedId, restored.state.value.current?.id)
                assertEquals(savedPosition, restored.state.value.positionSeconds)
                assertTrue(restored.state.value.wave)
                assertFalse(restored.state.value.playing)
                assertEquals(savedTransport == "play", LaunchStateStore(f.context).read("owner").playing)
                return
            } else if (shouldPlay) waitFor("Cold source must actually resume at saved position") {
                restored.state.value.current?.id == expectedId && restored.state.value.playing &&
                    restored.state.value.positionSeconds >= savedPosition + 1
            } else {
                waitFor("Playable metadata has arrived") { restored.state.value.current?.available == true }
                if (unavailablePaused) waitFor("Paused stream failure is visible") { restored.state.value.error != null }
                Thread.sleep(1_200)
                assertFalse(restored.state.value.playing)
                assertEquals(if (duringAccount == "stop") 0 else savedPosition, restored.state.value.positionSeconds)
                if (savedTransport in setOf("pause", "noisy") && !unavailablePaused) compose.runOnIdle {
                    assertEquals("A saved Pause must not become an idle Stop", androidx.media3.common.Player.STATE_READY,
                        cleanupEngine!!.playbackState)
                }
                if (savedTransport == "stop" || duringAccount == "stop") compose.runOnIdle {
                    assertEquals(androidx.media3.common.Player.STATE_IDLE, cleanupEngine!!.playbackState)
                }
            }
            assertEquals(if (requestLaunch) shouldPlay else savedTransport == "play", LaunchStateStore(f.context).read("owner").playing)
            assertEquals(savedTransport == "stop" || duringAccount == "stop", LaunchStateStore(f.context).read("owner").stopped)
            assertEquals(expectedId, restored.state.value.current?.id)
            assertEquals(wave, restored.state.value.wave)
            if (wave) assertEquals(PlaybackSource.MY_WAVE, restored.state.value.origin.source)
        } finally {
            accountGate.complete(Unit)
            compose.runOnIdle {
                f.streamFailure = null
                cleanupPlayer?.state?.value?.let { state ->
                    Log.i("YM2ColdRestore", "id=${state.current?.id}, pos=${state.positionSeconds}, wave=${state.wave}, playing=${state.playing}, saved=${LaunchStateStore(f.context).read(state.profileId)}, engine=${cleanupEngine?.playbackState}, requested=${cleanupEngine?.playWhenReady}")
                }
                cleanupPlayer?.detach()
                cleanupEngine?.release()
                scope.cancel()
            }
        }
    }
}
