@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
package dev.petrov.ymplayer2

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.petrov.ymplayer2.core.Source
import dev.petrov.ymplayer2.core.PlaybackSource
import dev.petrov.ymplayer2.playback.AndroidPlayback
import dev.petrov.ymplayer2.playback.AudioService
import dev.petrov.ymplayer2.playback.LaunchStateStore
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject

/** Deterministic engine replacement complements the actual Android service-stop scenario.
 * It exercises a replacement during asynchronous metadata preparation, without a product hook. */
@RunWith(AndroidJUnit4::class)
class EngineHandoffTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val graph get() = compose.activity.application as PlayerApplication
    @Before fun fixtures() {
        compose.waitUntil(20_000) { graph.library.state.value.ready && !graph.library.state.value.scanning }
        compose.runOnIdle { if (graph.playback.state.value.playing || graph.playback.state.value.buffering) graph.playback.stop() }
        runBlocking {
            graph.library.state.value.roots.forEach { graph.library.forgetFolder(it.uri) }
            compose.activity.contentResolver.call(Uri.parse("content://dev.petrov.ymplayer2.test.control"), "fixtures", null, null)
            graph.library.addFolder(TestMusicProvider.tree.toString(), Source.LOCAL)
        }
        compose.waitUntil(15_000) { graph.library.testTracks.size == 2 }
    }
    @Test fun pendingForegroundPlaySurvivesAnotherEngineReplacement() = replacement(requestResume = true)
    @Test fun newerPauseCancelsThePendingForegroundPlay() = replacement(requestResume = true, pause = true)
    @Test fun metadataOnlyAttachKeepsSavedPlaySilent() = replacement(requestResume = false)
    @Test fun coldInterfacePlayWaitsForSavedMetadata() = coldInterfacePlay()
    @Test fun newerPauseCancelsColdInterfacePlay() = coldInterfacePlay(pause = true)
    @Test fun coldInterfacePlaySurvivesEngineReplacement() = coldInterfacePlay(replace = true)
    @Test fun emptyOfflineCheckpointStillAttachesAConnectedEngine() {
        val context = isolatedContext()
        val profile = graph.library.profiles.first().id
        context.getSharedPreferences("playback", Context.MODE_PRIVATE).edit().clear()
            .putString("profile", profile).putString("queue:$profile", JSONObject()
                .put("origin", JSONObject().put("source", "OFFLINE"))
                .put("followLibrary", false).toString()).commit()
        LaunchStateStore(context).write(profile, dev.petrov.ymplayer2.playback.PlaybackOutput.MUSIC, false, true)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        lateinit var adapter: AndroidPlayback
        lateinit var engine: ExoPlayer
        try {
            compose.runOnIdle {
                adapter = AndroidPlayback(context, graph.library, scope, offline = graph.offline)
                engine = ExoPlayer.Builder(compose.activity).build().apply { volume = 0f }
                adapter.attach(engine)
            }
            compose.waitUntil(15_000) { adapter.state.value.connected && adapter.state.value.origin.source == PlaybackSource.OFFLINE }
            Thread.sleep(300)
            assertTrue(adapter.state.value.queue.isEmpty())
            assertNull(adapter.state.value.current)
            assertFalse(adapter.state.value.playing)
            assertTrue(LaunchStateStore(context).read(profile).stopped)
        } finally { compose.runOnIdle { adapter.detach(); engine.release(); scope.cancel() } }
    }
    private fun isolatedContext() = object : ContextWrapper(compose.activity) {
        override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("engine-handoff-$name", mode)
        override fun startService(service: Intent): ComponentName = ComponentName(this, AudioService::class.java)
    }
    private fun coldInterfacePlay(pause: Boolean = false, replace: Boolean = false) {
        val context = isolatedContext()
        context.getSharedPreferences("playback", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("launch-state", Context.MODE_PRIVATE).edit().clear().commit()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val gate = CompletableDeferred<Unit>()
        val local = object : dev.petrov.ymplayer2.core.IndexedLocalLibrary by graph.library {
            override suspend fun tracksByIds(ids: Collection<String>): Map<String, dev.petrov.ymplayer2.core.Track> {
                gate.await()
                return graph.library.tracksByIds(ids)
            }
        }
        val id = graph.library.testTracks.first().id
        lateinit var adapter: AndroidPlayback
        lateinit var engine: ExoPlayer
        fun freshEngine() = ExoPlayer.Builder(compose.activity).build().apply { volume = 0f }
        try {
            compose.runOnIdle {
                adapter = AndroidPlayback(context, graph.library, scope)
                engine = freshEngine(); adapter.attach(engine)
                adapter.playQueue(listOf(id)); adapter.seek(4)
            }
            compose.waitUntil(15_000) { adapter.state.value.playing && adapter.state.value.positionSeconds >= 4 }
            compose.runOnIdle {
                adapter.sessionPause(); adapter.saveForExit(); adapter.detach(); engine.release()
                adapter = AndroidPlayback(context, local, scope)
                engine = freshEngine(); adapter.attach(engine)
                assertTrue(adapter.state.value.connected)
                assertNull(adapter.state.value.current)
                adapter.playOnInterfaceOpen(adapter.launchRevision)
                if (replace) { adapter.detach(); engine.release(); engine = freshEngine(); adapter.attach(engine) }
                if (pause) adapter.sessionPause()
                gate.complete(Unit)
            }
            compose.waitUntil(15_000) { adapter.state.value.current?.id == id && adapter.state.value.positionSeconds >= 4 }
            if (!pause) compose.waitUntil(15_000) { adapter.state.value.playing }
            else {
                Thread.sleep(800)
                compose.runOnIdle { assertFalse(adapter.state.value.playing); assertFalse(engine.playWhenReady) }
            }
            assertEquals(!pause, LaunchStateStore(context).read(adapter.state.value.profileId).playing)
        } finally { compose.runOnIdle { gate.complete(Unit); adapter.detach(); engine.release(); scope.cancel() } }
    }
    private fun replacement(requestResume: Boolean, pause: Boolean = false) {
        // The fixture supplies genuine ExoPlayer instances itself, without a second app service.
        val context = isolatedContext()
        context.getSharedPreferences("playback", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("launch-state", Context.MODE_PRIVATE).edit().clear().commit()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        lateinit var adapter: AndroidPlayback
        lateinit var engine: ExoPlayer
        fun freshEngine() = ExoPlayer.Builder(compose.activity).build().apply { volume = 0f }
        val id = graph.library.testTracks.first().id
        try {
            compose.runOnIdle {
                adapter = AndroidPlayback(context, graph.library, scope)
                engine = freshEngine(); adapter.attach(engine)
                adapter.playQueue(listOf(id)); adapter.seek(4)
            }
            compose.waitUntil(15_000) { adapter.state.value.playing && adapter.state.value.positionSeconds >= 4 }
            compose.runOnIdle {
                adapter.saveForExit(); adapter.detach(); engine.release()
                engine = freshEngine(); adapter.attach(engine)
                if (requestResume) {
                    adapter.resumeOnLaunch()
                    // Release again before pending metadata/Play has completed, as in D-009.
                    adapter.detach(); engine.release()
                    if (pause) adapter.sessionPause()
                    engine = freshEngine(); adapter.attach(engine)
                }
            }
            compose.waitUntil(15_000) { adapter.state.value.current?.id == id && adapter.state.value.positionSeconds >= 4 }
            if (requestResume && !pause) compose.waitUntil(15_000) { adapter.state.value.playing }
            else {
                Thread.sleep(800)
                compose.runOnIdle { assertFalse(adapter.state.value.playing); assertFalse(engine.playWhenReady) }
            }
            assertEquals(id, adapter.state.value.current?.id)
            assertEquals(!pause, LaunchStateStore(context).read(adapter.state.value.profileId).playing)
        } finally {
            compose.runOnIdle { adapter.detach(); engine.release(); scope.cancel() }
        }
    }
}
