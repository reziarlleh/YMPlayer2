package dev.petrov.ymplayer2

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.media3.session.MediaBrowser
import androidx.media3.session.SessionToken
import dev.petrov.ymplayer2.core.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.json.JSONObject
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class LargeCatalogTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val graph get() = compose.activity.application as PlayerApplication
    private val library get() = graph.library
    private val player get() = graph.playback
    private fun waitFor(condition: () -> Boolean) = compose.waitUntil(30000, condition)
    private fun provider(method: String, arg: String? = null) = graph.contentResolver.call(
        android.net.Uri.parse("content://dev.petrov.ymplayer2.test.control"), method, arg, null)

    @Test fun fiveThousandSafDocumentsStayOnDiskAndFarSelectionsRestorePaused() {
        waitFor { library.state.value.ready && !library.state.value.scanning && player.state.value.connected }
        compose.runOnIdle { player.stop(); player.switchProfile("owner"); player.chooseSource(Source.LOCAL); player.setRepeatMode(RepeatMode.OFF); player.setShuffle(false) }
        runBlocking { library.state.value.roots.forEach { library.forgetFolder(it.uri) } }
        provider("fixtures"); provider("bulk", "5000")
        var browser: MediaBrowser? = null
        try {
            val started = android.os.SystemClock.elapsedRealtime()
            runBlocking { library.addFolder(TestMusicProvider.tree.toString(), Source.LOCAL) }
            println("M11 SAF scan: 5002 documents in ${android.os.SystemClock.elapsedRealtime() - started} ms")
            waitFor { player.state.value.queueCount == 5002 }
            assertTrue(library.state.value.tracks.isEmpty())
            assertEquals(5002, library.state.value.roots.single().trackCount)
            val far = runBlocking { library.pageTracks(CatalogFilter(source = Source.LOCAL), offset = 4500, limit = 2) }
            assertEquals(5002, far.total); assertEquals(2, far.items.size)
            assertEquals(1, runBlocking { library.pageTracks(CatalogFilter(query = "bulk-4999"), limit = 80) }.total)
            browser = MediaBrowser.Builder(compose.activity, SessionToken(compose.activity,
                android.content.ComponentName(compose.activity, dev.petrov.ymplayer2.playback.AudioService::class.java)))
                .buildAsync().get(20, TimeUnit.SECONDS)
            val controller = browser
            val selected = far.items.first().id
            compose.runOnIdle { player.select(selected); player.seek(7); player.toggle() }
            waitFor { player.state.value.current?.id == selected && player.state.value.positionSeconds == 7 && !player.state.value.playing }
            assertEquals(4500, player.state.value.index)
            assertTrue(player.state.value.queue.size <= 29)
            assertTrue(compose.runOnIdle { controller.mediaItemCount } <= 29)
            val page = runBlocking { player.queuePage(4480, 80) }
            assertEquals(5002, page.total); assertEquals(80, page.items.size)
            compose.runOnIdle { player.setRepeatMode(RepeatMode.ALL); player.setShuffle(true) }
            waitFor { player.state.value.shuffle && player.state.value.repeatMode == RepeatMode.ALL &&
                compose.runOnIdle { controller.shuffleModeEnabled && controller.mediaItemCount <= 3 } }
            compose.runOnIdle { player.skip(1) }
            waitFor { player.state.value.current?.id != selected }
            val shuffled = player.state.value.current!!.id
            assertTrue(player.state.value.queue.size <= 29)
            val saved = JSONObject(graph.getSharedPreferences("playback", 0).getString("queue:owner", "{}")!!)
            assertEquals(0, saved.getJSONArray("ids").length())
            assertEquals(1, saved.getJSONArray("tracks").length())
            compose.runOnIdle { controller.release() }
            browser = null
            compose.runOnIdle { compose.activity.stopService(android.content.Intent(compose.activity, dev.petrov.ymplayer2.playback.AudioService::class.java)) }
            waitFor { !player.state.value.connected }
            compose.runOnIdle { player.connect() }
            waitFor { player.state.value.connected && player.state.value.current?.id == shuffled }
            assertFalse(player.state.value.playing)
            assertTrue(player.state.value.shuffle)
            assertTrue(library.state.value.tracks.isEmpty())
        } finally {
            browser?.let { compose.runOnIdle { it.release() } }
            compose.runOnIdle { player.stop(); player.setShuffle(false); player.setRepeatMode(RepeatMode.OFF) }
            runBlocking { library.forgetFolder(TestMusicProvider.tree.toString()) }
            provider("fixtures")
        }
    }
}
