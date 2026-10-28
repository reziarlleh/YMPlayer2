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
    private fun waitFor(test: () -> Boolean) = compose.waitUntil(25_000, test)

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

    @Test fun launchPlayPendingSlowMetadataRetainsDurableIntent() = pendingRestoreCommand("play", true)
    @Test fun explicitPausePendingSlowMetadataClearsDurableIntent() = pendingRestoreCommand("pause", false)
    @Test fun explicitStopPendingSlowMetadataClearsDurableIntent() = pendingRestoreCommand("stop", false)

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
