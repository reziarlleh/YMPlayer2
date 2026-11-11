@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
package dev.petrov.ymplayer2

import android.net.Uri
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.petrov.ymplayer2.core.*
import dev.petrov.ymplayer2.playback.LaunchStateStore
import dev.petrov.ymplayer2.playback.PlaybackOutput
import dev.petrov.ymplayer2.playback.AndroidPlayback
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith

/** Real engine/persistence regressions. Fixtures never use a personal account or network stream. */
@RunWith(AndroidJUnit4::class)
class LaunchResumeIntentTest {
    @get:Rule val compose = createAndroidComposeRule<OnlineTestActivity>()
    private val fixture get() = compose.activity.harness
    private val player get() = fixture.player
    private val launch get() = LaunchStateStore(fixture.context)
    private fun waitFor(test: () -> Boolean) {
        try { compose.waitUntil(25_000, test) }
        catch (error: Throwable) {
            compose.runOnIdle { android.util.Log.e("YM2LaunchIntent", "id=${player.state.value.current?.id} pos=${player.state.value.positionSeconds} wave=${player.state.value.wave} saved=${launch.read("owner")} items=${fixture.systemPlayer.mediaItemCount} engine=${fixture.systemPlayer.playbackState} requested=${fixture.systemPlayer.playWhenReady}") }
            throw error
        }
    }

    @Before fun prepare() {
        waitFor { fixture.library.state.value.ready && !fixture.library.state.value.scanning && player.state.value.connected }
        compose.runOnIdle { player.stop(); player.switchProfile("owner"); player.clearQueue() }
        runBlocking { fixture.library.state.value.roots.forEach { fixture.library.forgetFolder(it.uri) } }
        compose.activity.contentResolver.call(Uri.parse("content://dev.petrov.ymplayer2.test.control"), "fixtures", null, null)
        runBlocking { fixture.library.addFolder(TestMusicProvider.tree.toString(), Source.LOCAL) }
        waitFor { fixture.library.testTracks.size == 2 && fixture.online.catalog.value.enabled }
    }

    @After fun stop() { compose.runOnIdle { fixture.streamFailure = null; player.stop() } }

    private fun startLocal() {
        compose.runOnIdle { player.playQueue(fixture.library.testTracks.map(Track::id)) }
        waitFor { player.state.value.playing && player.state.value.positionSeconds >= 1 }
        assertTrue(launch.read("owner").playing)
    }

    private fun failOnlineStream() {
        compose.runOnIdle { fixture.online.search("fixture", MusicKind.TRACKS, false) }
        waitFor { fixture.online.state.value.loaded }
        compose.runOnIdle {
            fixture.streamFailure = MusicFailure.NETWORK
            player.playQueue(fixture.online.state.value.entries.mapNotNull { it.track?.id })
        }
        waitFor { player.state.value.error != null && !player.state.value.playing }
        compose.runOnIdle { player.saveForExit() }
    }

    @Test fun failedNetworkPreparationRetainsPlayForNextForegroundLaunch() {
        failOnlineStream()
        assertEquals(PlaybackOutput.MUSIC, launch.read("owner").output)
        assertTrue("A transient stream failure must not be persisted as a manual pause", launch.read("owner").playing)
    }

    @Test fun explicitPauseAfterNetworkFailureRemainsPausedAfterEngineRestart() {
        failOnlineStream()
        compose.runOnIdle { fixture.systemPlayer.pause(); player.saveForExit(); fixture.restartEngine() }
        waitFor { player.state.value.current != null && player.state.value.connected }
        assertFalse("An explicit Pause must survive a failed stream and a restart", launch.read("owner").playing)
        assertFalse(player.state.value.playing)
    }

    @Test fun metadataOnlyEngineRestartPreservesSavedPlayAndPosition() {
        startLocal()
        compose.runOnIdle { player.seek(4); player.saveForExit(); fixture.restartEngine() }
        waitFor { player.state.value.current != null && player.state.value.positionSeconds == 4 }
        // Allow asynchronous metadata reconciliation and its checkpoint to finish as well.
        compose.runOnIdle { player.saveForExit() }
        assertFalse("A metadata-only service restore itself must stay silent", player.state.value.playing)
        assertTrue("Restoring a silent engine must retain the foreground launch Play request", launch.read("owner").playing)
    }

    @Test fun metadataOnlyEngineRestartPreservesManualPauseAndPosition() {
        startLocal()
        compose.runOnIdle { player.seek(4); fixture.systemPlayer.pause(); player.saveForExit(); fixture.restartEngine() }
        waitFor { player.state.value.current != null && player.state.value.positionSeconds == 4 }
        compose.runOnIdle { player.saveForExit() }
        assertFalse(launch.read("owner").playing)
        assertFalse(player.state.value.playing)
    }

    @Test fun metadataOnlyWaveReconnectAndPositionUpdateMustNotEraseSavedPlay() {
        compose.runOnIdle { player.playMyWave() }
        waitFor { player.state.value.playing && player.state.value.positionSeconds >= 1 }
        val id = player.state.value.current!!.id
        compose.runOnIdle { player.seek(4); player.saveForExit(); fixture.restartEngine() }
        waitFor { player.state.value.connected && player.state.value.wave && player.state.value.current?.id == id &&
            player.state.value.positionSeconds == 4 }
        assertTrue(launch.read("owner").playing)
        compose.runOnIdle { player.seek(4); player.saveForExit() }
        assertFalse("A metadata-only service must stay silent", player.state.value.playing)
        assertTrue("A position-only update must not replace saved Play with the silent engine's pause", launch.read("owner").playing)
        assertEquals(4, player.state.value.positionSeconds)
    }

    @Test fun emptyEngineCannotErasePausedWaveTrackAndPosition() {
        val gate = CompletableDeferred<Unit>()
        compose.runOnIdle { fixture.waveRequestGates = mapOf(2 to gate); player.playMyWave() }
        waitFor { player.state.value.playing && player.state.value.positionSeconds >= 1 && 2 in fixture.waveRequests }
        val id = player.state.value.current!!.id
        try {
            compose.runOnIdle { player.seek(4); player.sessionPause(); player.saveForExit() }
            compose.runOnIdle { fixture.systemPlayer.clearMediaItems(); player.saveForExit() }
            assertEquals("Clearing a service engine is not clearing the logical queue", id, player.state.value.current?.id)
            assertEquals("An empty engine must not replace the saved position with zero", 4, player.state.value.positionSeconds)
            gate.complete(Unit)
            waitFor { player.state.value.queue.size >= 2 }
            assertEquals("Late prefetch cannot populate an empty engine at zero instead of the saved position", 4, player.state.value.positionSeconds)
            assertFalse(launch.read("owner").playing)
            assertFalse(launch.read("owner").stopped)
            compose.runOnIdle { player.resumeOnLaunch() }
            waitFor { player.state.value.current?.id == id && player.state.value.positionSeconds == 4 }
            compose.runOnIdle { assertTrue(fixture.systemPlayer.mediaItemCount > 0) }
            assertFalse(player.state.value.playing)
        } finally { gate.complete(Unit); compose.runOnIdle { fixture.waveRequestGates = emptyMap() } }
    }

    @Test fun media3TaskCleanupMustNotReplaceSavedPlayWithPause() {
        compose.runOnIdle { player.playMyWave() }
        waitFor { player.state.value.playing && player.state.value.positionSeconds >= 1 }
        val id = player.state.value.current!!.id
        compose.runOnIdle { player.seek(4); player.saveForExit(); fixture.restartEngine() }
        waitFor { player.state.value.connected && player.state.value.wave && player.state.value.current?.id == id }
        compose.runOnIdle { fixture.systemPlayer.prepare() }
        waitFor {
            var prepared = false
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync {
                prepared = fixture.systemPlayer.playbackState == androidx.media3.common.Player.STATE_READY
            }
            prepared
        }
        compose.runOnIdle {
            player.onServiceTaskRemoved { fixture.systemPlayer.playWhenReady = false }
            player.saveForExit()
        }
        assertTrue("Media3 cleanup must preserve the previously requested Play", launch.read("owner").playing)
        assertFalse(player.state.value.playing)
        assertEquals(4, player.state.value.positionSeconds)
        compose.runOnIdle { player.restoreForegroundSessionIfNeeded() }
        waitFor { player.state.value.playing && player.state.value.positionSeconds >= 5 }
        assertTrue(fixture.launchDiagnostics.any { it.operation == DiagnosticOperation.SESSION_RELEASE &&
            it.reason == SessionStateReason.SERVICE_TASK_REMOVED && it.intent == SessionIntent.PLAY })
    }

    @Test fun explicitLogoutMustCancelSavedWavePlay() {
        compose.runOnIdle { player.playMyWave() }
        waitFor { player.state.value.playing && player.state.value.positionSeconds >= 1 }
        compose.runOnIdle { fixture.auth.signOut() }
        waitFor { fixture.auth.state.value.phase == AuthPhase.SIGNED_OUT && !player.state.value.wave &&
            player.state.value.current == null }
        assertFalse(launch.read("owner").playing)
        assertFalse(player.state.value.playing)
    }

    @Test fun metadataOnlyWavePreparationFailureAfterSeekCannotSkipSavedTrack() {
        compose.runOnIdle { player.playMyWave() }
        waitFor { player.state.value.playing && player.state.value.positionSeconds >= 1 && player.state.value.canSkipNext }
        val id = player.state.value.current!!.id
        compose.runOnIdle { player.seek(4); player.saveForExit(); fixture.restartEngine() }
        waitFor { player.state.value.connected && player.state.value.wave && player.state.value.current?.id == id &&
            player.state.value.positionSeconds == 4 }
        val failures = fixture.launchDiagnostics.count { it.operation == DiagnosticOperation.AUDIO_PLAYBACK }
        compose.runOnIdle {
            val uri = Uri.parse(player.resolveStream("owner", id))
            if (uri.scheme == "file") java.io.File(uri.path!!).delete()
            fixture.streamFailure = MusicFailure.NETWORK; player.seek(4); fixture.systemPlayer.prepare()
        }
        waitFor { fixture.launchDiagnostics.count { it.operation == DiagnosticOperation.AUDIO_PLAYBACK } > failures }
        Thread.sleep(1_200)
        assertFalse(player.state.value.playing)
        assertTrue(launch.read("owner").playing)
        assertEquals("A metadata-only prepare cannot consume the saved track", id, player.state.value.current?.id)
        assertEquals(4, player.state.value.positionSeconds)
    }

    @Test fun foregroundRepairCannotOverrideExplicitStop() {
        compose.runOnIdle { player.playMyWave() }
        waitFor { player.state.value.playing }
        compose.runOnIdle { player.stop(); player.restoreForegroundSessionIfNeeded() }
        assertTrue(launch.read("owner").stopped)
        assertFalse(launch.read("owner").playing)
        assertFalse(player.state.value.playing)
        assertEquals(0, player.state.value.positionSeconds)
        compose.runOnIdle { assertEquals(androidx.media3.common.Player.STATE_IDLE, fixture.systemPlayer.playbackState) }
    }

    @Test fun pauseAfterForegroundRepairRequestCancelsThePendingPlay() {
        compose.runOnIdle { player.playMyWave() }
        waitFor { player.state.value.playing && player.state.value.positionSeconds >= 1 }
        compose.runOnIdle { player.seek(4); player.saveForExit(); fixture.restartEngine() }
        waitFor { player.state.value.connected && player.state.value.wave && player.state.value.positionSeconds == 4 }
        compose.runOnIdle { player.restoreForegroundSessionIfNeeded(); player.sessionPause() }
        Thread.sleep(600)
        assertFalse(launch.read("owner").playing)
        assertFalse(player.state.value.playing)
        assertEquals(4, player.state.value.positionSeconds)
    }

    @Test fun launchPlayPendingSlowMetadataRetainsDurableIntent() = pendingRestoreCommand("play", true)
    @Test fun explicitPausePendingSlowMetadataClearsDurableIntent() = pendingRestoreCommand("pause", false)
    @Test fun explicitStopPendingSlowMetadataClearsDurableIntent() = pendingRestoreCommand("stop", false)

    @Test fun staleLaunchQueuedBeforeEngineRestartCannotEraseNewPlay() {
        val context = object : ContextWrapper(compose.activity) {
            override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("resume-stale-$name", mode)
        }
        val track = fixture.library.testTracks.first()
        context.getSharedPreferences("playback", Context.MODE_PRIVATE).edit().clear()
            .putString("profile", "owner").putString("queue:owner", JSONObject().put("followLibrary", true)
                .put("current", track.id).put("position", 4).toString()).commit()
        val saved = LaunchStateStore(context)
        saved.write("owner", PlaybackOutput.MUSIC, true); saved.flush()
        val entered = java.util.concurrent.atomic.AtomicInteger()
        val gate = CompletableDeferred<Unit>()
        val library = object : IndexedLocalLibrary by fixture.library {
            override suspend fun playbackWindow(currentId: String?, source: Source?): LocalPlaybackWindow? {
                entered.incrementAndGet(); gate.await()
                return fixture.library.playbackWindow(currentId, source)
            }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        lateinit var cursor: AndroidPlayback
        lateinit var engine: ExoPlayer
        compose.runOnIdle {
            cursor = AndroidPlayback(context, library, scope)
            engine = ExoPlayer.Builder(context).build(); cursor.attach(engine)
        }
        try {
            waitFor { entered.get() >= 1 }
            compose.runOnIdle {
                cursor.resumeOnLaunch(); cursor.detach(); engine.release()
                engine = ExoPlayer.Builder(context).build(); cursor.attach(engine)
            }
            waitFor { entered.get() >= 2 }
            compose.runOnIdle { cursor.resumeOnLaunch(); gate.complete(Unit) }
            waitFor { cursor.state.value.playing && cursor.state.value.positionSeconds >= 5 }
            assertTrue(saved.read("owner").playing)
        } finally {
            gate.complete(Unit)
            compose.runOnIdle { cursor.detach(); engine.release(); scope.cancel() }
        }
    }

    @Test fun returningFromRadioPersistsMusicOwnershipAndPlayBeforeRestart() {
        startLocal()
        compose.runOnIdle { fixture.radio.play(fixture.radioApi.one) }
        waitFor { fixture.radio.state.value.playing }
        assertEquals(PlaybackOutput.RADIO, launch.read("owner").output)
        compose.runOnIdle { player.playQueue(fixture.library.testTracks.map(Track::id)) }
        waitFor { player.state.value.playing && player.state.value.positionSeconds >= 1 && !fixture.radio.state.value.ownsOutput }
        compose.runOnIdle { player.saveForExit() }
        assertEquals("Stopping the outgoing radio must not overwrite the new music owner", PlaybackOutput.MUSIC, launch.read("owner").output)
        assertTrue(launch.read("owner").playing)
        compose.runOnIdle { fixture.restartEngine() }
        waitFor { player.state.value.current != null && player.state.value.connected }
        assertEquals(PlaybackOutput.MUSIC, launch.read("owner").output)
        assertTrue(launch.read("owner").playing)
    }

    private fun pendingRestoreCommand(command: String, expected: Boolean) {
        val context = object : ContextWrapper(compose.activity) {
            override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("resume-pending-$name", mode)
        }
        val track = fixture.library.testTracks.first()
        context.getSharedPreferences("playback", Context.MODE_PRIVATE).edit().clear()
            .putString("profile", "owner").putString("queue:owner", JSONObject().put("followLibrary", true)
                .put("current", track.id).put("position", 4).toString()).commit()
        val saved = LaunchStateStore(context)
        saved.write("owner", PlaybackOutput.MUSIC, true); saved.flush()
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val library = object : IndexedLocalLibrary by fixture.library {
            override suspend fun playbackWindow(currentId: String?, source: Source?): LocalPlaybackWindow? {
                entered.complete(Unit); gate.await()
                return fixture.library.playbackWindow(currentId, source)
            }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        lateinit var cursor: AndroidPlayback
        lateinit var engine: ExoPlayer
        compose.runOnIdle {
            cursor = AndroidPlayback(context, library, scope)
            engine = ExoPlayer.Builder(context).build()
            cursor.attach(engine)
        }
        try {
            runBlocking { withTimeout(5_000) { entered.await() } }
            compose.runOnIdle {
                when (command) {
                    "play" -> cursor.resumeOnLaunch()
                    "pause" -> cursor.sessionPause()
                    "stop" -> cursor.stop()
                }
                cursor.saveForExit()
            }
            assertEquals("A command waiting for metadata must retain its explicit intent across process death", expected,
                saved.read("owner").playing)
        } finally {
            compose.runOnIdle { cursor.detach(); engine.release(); scope.cancel() }
            gate.complete(Unit)
        }
    }
}
