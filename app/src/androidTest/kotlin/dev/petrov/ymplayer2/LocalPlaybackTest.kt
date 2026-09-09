package dev.petrov.ymplayer2

import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.petrov.ymplayer2.core.Source
import dev.petrov.ymplayer2.playback.AudioService
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalPlaybackTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val graph get() = compose.activity.application as PlayerApplication
    private val player get() = graph.playback
    private val library get() = graph.library
    private fun provider(method: String, arg: String? = null) = compose.activity.contentResolver.call(android.net.Uri.parse("content://dev.petrov.ymplayer2.test.control"), method, arg, null)
    private fun waitFor(condition: () -> Boolean) = compose.waitUntil(15000, condition)

    @Before fun fixtures() {
        waitFor { library.state.value.ready && !library.state.value.scanning && player.state.value.connected }
        compose.runOnIdle { player.stop(); player.switchProfile("owner"); player.chooseSource(null) }
        runBlocking { library.state.value.roots.forEach { library.forgetFolder(it.uri) } }
        provider("fixtures")
        runBlocking { library.addFolder(TestMusicProvider.tree.toString(), Source.LOCAL) }
        waitFor { library.state.value.tracks.size == 2 && player.state.value.queue.size == 2 }
    }
    @After fun stop() { compose.runOnIdle { player.stop() }; provider("unavailable", "false") }

    @Test fun safMetadataAndRealAudioContinueInBackground() {
        assertEquals(setOf("one", "two"), library.state.value.tracks.map { it.title }.toSet())
        assertTrue(library.state.value.tracks.all { it.durationSeconds == 30 && it.sizeBytes > 900000 })
        assertTrue(library.state.value.tracks.any { it.folder.contains("nested") })
        compose.onNodeWithTag("player_play").performScrollTo().performClick()
        waitFor { player.state.value.positionSeconds >= 2 && player.state.value.playing && !player.state.value.buffering }
        waitFor {
            graph.getSystemService(android.app.NotificationManager::class.java).activeNotifications.any {
                it.notification.flags and android.app.Notification.FLAG_FOREGROUND_SERVICE != 0
            }
        }
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        val before = player.state.value.positionSeconds
        // This state comes from ExoPlayer's AudioTrack clock, not a demo timer.
        waitFor { player.state.value.positionSeconds >= before + 2 }
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.runOnIdle { player.seek(10); player.skip(1) }
        waitFor { player.state.value.current?.title == "two" && player.state.value.positionSeconds >= 1 }
        compose.runOnIdle { player.toggle() }
        waitFor { !player.state.value.playing }
        assertNull(player.state.value.error)
    }

    @Test fun profileCheckpointsAndServiceRestartStayPaused() {
        compose.runOnIdle { player.seek(7); player.switchProfile("guest") }
        waitFor { player.state.value.profileId == "guest" }
        assertFalse(player.state.value.playing)
        compose.runOnIdle { player.seek(12); player.switchProfile("owner") }
        assertEquals(7, player.state.value.positionSeconds)
        compose.activityRule.scenario.recreate()
        assertEquals(7, player.state.value.positionSeconds)
        compose.runOnIdle { compose.activity.stopService(Intent(compose.activity, AudioService::class.java)) }
        waitFor { !player.state.value.connected }
        compose.runOnIdle { player.connect() }
        waitFor { player.state.value.connected && player.state.value.positionSeconds == 7 }
        assertFalse(player.state.value.playing)
        compose.runOnIdle { player.switchProfile("guest") }
        waitFor { player.state.value.positionSeconds == 12 }
        assertFalse(player.state.value.playing)
    }

    @Test fun missingRootKeepsIndexAndForgetLeavesOriginalReadable() {
        val tracks = library.state.value.tracks
        compose.runOnIdle { player.toggle() }
        waitFor { player.state.value.positionSeconds >= 1 }
        provider("unavailable", "true")
        runBlocking { library.refresh() }
        waitFor { !player.state.value.playing }
        assertEquals(tracks.map { it.id }, library.state.value.tracks.map { it.id })
        assertTrue(library.state.value.tracks.none { it.available })
        assertNotNull(library.state.value.roots.single().issue)
        provider("unavailable", "false")
        runBlocking { library.refresh() }
        waitFor { player.state.value.queue.size == 2 }
        assertTrue(library.state.value.tracks.all { it.available })
        runBlocking { library.forgetFolder(TestMusicProvider.tree.toString()) }
        waitFor { player.state.value.queue.isEmpty() }
        provider("grant")
        runBlocking { library.addFolder(TestMusicProvider.tree.toString(), Source.USB) }
        assertEquals(2, library.state.value.tracks.size)
        assertTrue(library.state.value.tracks.all { it.source == Source.USB })
    }

    @Test fun brokenAudioReportsErrorAndNextTrackStillPlays() {
        provider("corrupt")
        runBlocking { library.refresh() }
        val broken = library.state.value.tracks.single { it.title == "broken" }
        compose.runOnIdle { player.select(broken.id) }
        waitFor { player.state.value.error != null }
        assertFalse(player.state.value.playing)
        compose.runOnIdle { player.select(library.state.value.tracks.single { it.title == "one" }.id) }
        waitFor { player.state.value.playing && player.state.value.positionSeconds >= 1 }
        assertNull(player.state.value.error)
    }
}
