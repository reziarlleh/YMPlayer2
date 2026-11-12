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
    @Test fun completePausedWindowRestoresOnNewAdapterWithServerUnavailable() = coldRestore(savedTransport = "pause", fullQueue = true)
    @Test fun completePlayingWindowRestoresOnNewAdapterWithServerUnavailable() = coldRestore(fullQueue = true)
    @Test fun touchPlayWhilePausedAccountLoadsIsExecutedWhenReady() = coldRestore(savedTransport = "pause", duringAccount = "play")
    @Test fun pauseAfterTouchPlayWhileAccountLoadsStillWins() = coldRestore(savedTransport = "pause", duringAccount = "playThenPause")
    @Test fun noisyAfterTouchPlayWhileAccountLoadsKeepsPreparedPause() = coldRestore(savedTransport = "pause", duringAccount = "playThenNoisy")
    @Test fun expiredSavedTokenKeepsPausedTrackPositionAndAllAudio() = coldRestore(savedTransport = "pause", fullQueue = true, expiredToken = true)
    @Test fun expiredSavedTokenKeepsPlayingIntentWithoutSkippingOrErasingQueue() = coldRestore(fullQueue = true, expiredToken = true)
    @Test fun cachedWaveResumesWhileLocalLibraryIsStillScanning() = coldRestore(fullQueue = true, accountFirst = true, holdLibraryUntilResumed = true)
    @Test fun anotherAccountCannotInheritSavedWaveWindow() = coldRestore(fullQueue = true, otherAccount = true)

    private fun coldRestore(savedTransport: String = "play", duringAccount: String? = null,
        pauseAfterResume: Boolean = false, wave: Boolean = true, requestLaunch: Boolean = true,
        legacy: Boolean = false, unavailablePaused: Boolean = false, accountFirst: Boolean = false,
        accountUnavailable: Boolean = false, fullQueue: Boolean = false, expiredToken: Boolean = false,
        holdLibraryUntilResumed: Boolean = false, otherAccount: Boolean = false) {
        if (wave) compose.runOnIdle { f.player.playMyWave() }
        else {
            compose.runOnIdle { f.online.search("fixture", MusicKind.TRACKS, false) }
            waitFor("Search fixture") { f.online.state.value.loaded }
            compose.runOnIdle { f.player.playQueue(f.online.state.value.entries.mapNotNull { it.track?.id }) }
        }
        waitFor("Initial wave audible") { f.player.state.value.playing && f.player.state.value.positionSeconds >= 1 }
        if (fullQueue) waitFor("All ten complete files") { f.player.state.value.queue.size == 10 &&
            !f.player.state.value.waveLoading && f.player.bufferedWaveAudioIds().size == 10 }
        val preparedIds = f.player.state.value.queue.map(Track::id)
        val requests = f.waveRequests.size
        val streams = f.resolved.size
        var expectedId: String? = null
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
            expectedId = f.player.state.value.current?.id
            savedPosition = f.player.state.value.positionSeconds
            assertEquals(savedTransport == "play", LaunchStateStore(f.context).read("owner").playing)
            if (unavailablePaused) {
                val uri = Uri.parse(f.player.resolveStream("owner", requireNotNull(expectedId)))
                if (uri.scheme == "file") java.io.File(uri.path!!).delete()
            }
            if (fullQueue) {
                f.internet.available.value = false
                f.waveFailure = MusicFailure.NETWORK; f.streamFailure = MusicFailure.NETWORK
            }
            f.closeEngineForColdRestore()
            if (legacy) f.context.getSharedPreferences("launch-state", 0).edit().remove("stopped:owner").commit()
        }

        val accountGate = CompletableDeferred<Unit>()
        if (accountFirst) accountGate.complete(Unit)
        val libraryState = kotlinx.coroutines.flow.MutableStateFlow(f.library.state.value.copy(ready = !accountFirst, scanning = holdLibraryUntilResumed))
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
                        return AccountSession(YandexAccount(if (otherAccount) "2" else "1", "Fixture"), OAuthCredentials("fixture", null, if (expiredToken) 1L else null))
                    }
                    override suspend fun write(profileId: String, session: AccountSession?) = Unit
                }, scope)
                val online = OnlineMusic(accounts, f.online.api, scope)
                restored = AndroidPlayback(f.context, restoredLibrary, scope, online, waveApi = f.waveApi,
                    networkAvailable = { f.internet.available.value })
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
                if (holdLibraryUntilResumed) {
                    waitFor("Cached wave must resume before the held local scan finishes") {
                        restored.state.value.current?.id == expectedId && restored.state.value.playing &&
                            restored.state.value.positionSeconds >= savedPosition + 1
                    }
                    assertTrue(libraryState.value.scanning)
                } else Thread.sleep(500)
                compose.runOnIdle { libraryState.value = f.library.state.value }
            } else waitFor("Saved metadata before account") { restored.state.value.current?.id == expectedId &&
                restored.state.value.positionSeconds == savedPosition }
            compose.runOnIdle {
                when (duringAccount) {
                    "pause" -> restored.sessionPause()
                    "stop" -> restored.stop()
                    "play" -> restored.toggle()
                    "playThenPause" -> { restored.toggle(); restored.sessionPause() }
                    "playThenNoisy" -> {
                        restored.toggle()
                        cleanupEngine!!.setHandleAudioBecomingNoisy(true)
                        val listener = cleanupEngine!!.javaClass.getDeclaredField("componentListener").apply { isAccessible = true }.get(cleanupEngine)
                        listener.javaClass.getDeclaredMethod("onAudioBecomingNoisy").apply { isAccessible = true }.invoke(listener)
                    }
                }
                if (unavailablePaused) f.streamFailure = MusicFailure.NETWORK
                accountGate.complete(Unit)
            }
            val shouldPlay = requestLaunch && !pauseAfterResume &&
                (savedTransport == "play" && duringAccount == null || duringAccount == "play")
            if (otherAccount) {
                waitFor("Different account clears only the old account's wave") {
                    !restored.state.value.wave && restored.state.value.queue.isEmpty() && !LaunchStateStore(f.context).read("owner").playing
                }
                assertEquals(0, restored.state.value.positionSeconds)
                assertFalse(restored.state.value.playing)
                assertTrue(dev.petrov.ymplayer2.playback.WaveAudioBuffer(f.context) { "1" }.ids().isEmpty())
                return
            }
            if (accountUnavailable || expiredToken) {
                Thread.sleep(1_000)
                compose.runOnIdle { restored.saveForExit() }
                assertEquals("An account read failure must retain the selected track", expectedId, restored.state.value.current?.id)
                assertEquals(savedPosition, restored.state.value.positionSeconds)
                assertTrue(restored.state.value.wave)
                if (fullQueue) {
                    val persisted = dev.petrov.ymplayer2.playback.WaveAudioBuffer(f.context) { "1" }
                    assertEquals(preparedIds.toSet(), persisted.ids())
                    assertEquals(preparedIds, restored.state.value.queue.map(Track::id))
                }
                assertFalse(restored.state.value.playing)
                assertEquals(savedTransport == "play", LaunchStateStore(f.context).read("owner").playing)
                return
            } else if (shouldPlay) waitFor("Cold source must actually resume at saved position") {
                restored.state.value.current?.id == expectedId && restored.state.value.playing &&
                    restored.state.value.positionSeconds >= savedPosition + 1
            } else {
                if (wave && (savedTransport == "stop" || duringAccount == "stop"))
                    waitFor("Stopped wave has an empty saved window") { restored.state.value.connected &&
                        restored.state.value.wave && restored.state.value.queue.isEmpty() }
                else waitFor("Playable metadata has arrived") { restored.state.value.current?.available == true }
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
            assertEquals(if (wave && duringAccount == "stop") null else expectedId, restored.state.value.current?.id)
            if (fullQueue) {
                assertEquals(preparedIds, restored.state.value.queue.map(Track::id))
                assertEquals(preparedIds.toSet(), restored.bufferedWaveAudioIds())
                assertEquals("Restoring a complete window must not request a replacement batch", requests, f.waveRequests.size)
                assertEquals("Restoring complete audio must not download again", streams, f.resolved.size)
            }
            assertEquals(wave, restored.state.value.wave)
            if (wave) assertEquals(PlaybackSource.MY_WAVE, restored.state.value.origin.source)
        } finally {
            accountGate.complete(Unit)
            compose.runOnIdle {
                f.waveFailure = null
                f.streamFailure = null
                f.internet.available.value = true
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
