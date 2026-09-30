@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
package dev.petrov.ymplayer2

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.petrov.ymplayer2.core.*
import dev.petrov.ymplayer2.playback.AndroidPlayback
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProfileRestoreTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val graph get() = compose.activity.application as PlayerApplication
    private val library get() = graph.library
    private fun waitFor(condition: () -> Boolean) = compose.waitUntil(15_000, condition)

    @Before fun fixtures() {
        waitFor { library.state.value.ready && !library.state.value.scanning && graph.playback.state.value.connected }
        compose.runOnIdle { graph.playback.stop() }
        runBlocking {
            library.state.value.roots.forEach { library.forgetFolder(it.uri) }
            compose.activity.contentResolver.call(android.net.Uri.parse("content://dev.petrov.ymplayer2.test.control"), "fixtures", null, null)
            library.addFolder(TestMusicProvider.tree.toString(), Source.LOCAL)
        }
        waitFor { library.testTracks.size == 2 }
    }

    @Test fun slowProfileReadNeverPublishesOutgoingTrackIntoIncomingProfile() = delayedRestore(detachDuringRead = false)
    @Test fun detachDuringProfileReadPreservesIncomingCheckpointForNextProcess() = delayedRestore(detachDuringRead = true)

    private fun delayedRestore(detachDuringRead: Boolean) {
        val tracks = library.testTracks.sortedBy(Track::title)
        val context = object : ContextWrapper(compose.activity) {
            override fun getSharedPreferences(name: String, mode: Int) =
                super.getSharedPreferences("profile-restore-test-$name", mode)
        }
        val prefs = context.getSharedPreferences("playback", Context.MODE_PRIVATE)
        fun checkpoint(track: Track, seconds: Int) = JSONObject().put("followLibrary", true)
            .put("current", track.id).put("position", seconds).toString()
        val owner = checkpoint(tracks[0], 3)
        val guest = checkpoint(tracks[1], 12)
        prefs.edit().clear().putString("profile", "owner")
            .putString("queue:owner", owner).putString("queue:guest", guest).commit()
        var gate: CompletableDeferred<Unit>? = null
        val entered = CompletableDeferred<Unit>()
        val indexed = object : IndexedLocalLibrary by library {
            override fun tracks(profileId: String): List<Track> = error("Profile restore must not enumerate the catalog")
            override suspend fun playbackWindow(currentId: String?, source: Source?): LocalPlaybackWindow? {
                gate?.let { entered.complete(Unit); it.await() }
                return library.playbackWindow(currentId, source)
            }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        lateinit var cursor: AndroidPlayback
        lateinit var engine: ExoPlayer
        compose.runOnIdle {
            cursor = AndroidPlayback(context, indexed, scope)
            engine = ExoPlayer.Builder(context).build()
            cursor.attach(engine)
        }
        var attached = true
        try {
            waitFor { cursor.state.value.current?.id == tracks[0].id && cursor.state.value.positionSeconds == 3 }
            gate = CompletableDeferred()
            compose.runOnIdle { cursor.switchProfile("guest") }
            runBlocking { withTimeout(5_000) { entered.await() }; delay(1_200) }
            assertEquals("guest", cursor.state.value.profileId)
            if (detachDuringRead) {
                compose.runOnIdle { cursor.detach(); engine.release() }
                attached = false
                assertEquals("Interrupted restore must not overwrite the saved guest track", guest, prefs.getString("queue:guest", null))
                gate = null
                compose.runOnIdle {
                    cursor = AndroidPlayback(context, indexed, scope)
                    engine = ExoPlayer.Builder(context).build()
                    cursor.attach(engine)
                }
                attached = true
            } else {
                assertNull("Outgoing track must not appear in the guest while its metadata is pending", cursor.state.value.current)
                assertEquals(0, cursor.state.value.positionSeconds)
                assertEquals(guest, prefs.getString("queue:guest", null))
                gate!!.complete(Unit); gate = null
            }
            waitFor { cursor.state.value.current?.id == tracks[1].id && cursor.state.value.positionSeconds == 12 }
            assertFalse(cursor.state.value.playing)
            assertEquals("guest", cursor.state.value.profileId)
            compose.runOnIdle { cursor.switchProfile("owner") }
            waitFor { cursor.state.value.current?.id == tracks[0].id && cursor.state.value.positionSeconds == 3 }
        } finally {
            gate?.complete(Unit)
            compose.runOnIdle { if (attached) { cursor.detach(); engine.release() }; scope.cancel() }
        }
    }
}
