package dev.petrov.ymplayer2

import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.petrov.ymplayer2.core.Source
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ClipHandoffTest {
    @Test fun musicStartedInBackgroundStopsWhenReturningToVideo() {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val context = instrument.targetContext
        val app = context.applicationContext as PlayerApplication
        instrument.runOnMainSync { app.playback.connect(); app.playback.switchProfile("owner") }
        waitFor { app.playback.state.value.connected && app.library.state.value.ready && !app.library.state.value.scanning }
        runBlocking { app.library.state.value.roots.forEach { app.library.forgetFolder(it.uri) } }
        context.contentResolver.call(Uri.parse("content://dev.petrov.ymplayer2.test.control"), "fixtures", null, null)
        runBlocking { app.library.addFolder(TestMusicProvider.tree.toString(), Source.LOCAL) }
        waitFor { app.library.testTracks.size == 2 }
        val id = app.library.testTracks.first().id
        val activity = instrument.startActivitySync(Intent(context, ClipActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ClipActivity
        try {
            instrument.runOnMainSync { activity.moveTaskToBack(true) }
            waitFor { !activity.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED) }
            instrument.runOnMainSync { app.playback.playQueue(listOf(id)) }
            waitFor { app.playback.state.value.playing }
            context.startActivity(Intent(context, ClipActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            waitFor { activity.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) }
            waitFor { !app.playback.state.value.playing }
            assertEquals(dev.petrov.ymplayer2.playback.PlaybackOutput.CLIPS, app.launchState.read("owner").output)
            assertFalse(app.radio.state.value.ownsOutput)
        } finally { instrument.runOnMainSync { activity.finish(); app.playback.stop() } }
    }
    @Test fun openingAndClosingVideoLeavesAudioPaused() {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val context = instrument.targetContext
        val app = context.applicationContext as PlayerApplication
        instrument.runOnMainSync { app.playback.connect(); app.playback.switchProfile("owner") }
        waitFor { app.playback.state.value.connected && app.playback.state.value.profileId == "owner" && app.library.state.value.ready && !app.library.state.value.scanning }
        runBlocking {
            app.library.state.value.roots.forEach { app.library.forgetFolder(it.uri) }
        }
        // Forgetting an existing fixture root releases its grant. Re-grant as a new picker selection.
        context.contentResolver.call(Uri.parse("content://dev.petrov.ymplayer2.test.control"), "fixtures", null, null)
        runBlocking {
            app.library.addFolder(TestMusicProvider.tree.toString(), Source.LOCAL)
        }
        waitFor { app.library.testTracks.size == 2 }
        val id = app.library.testTracks.first().id
        val deadline = System.currentTimeMillis() + 15_000
        while (!app.playback.state.value.playing && System.currentTimeMillis() < deadline) {
            instrument.runOnMainSync { app.playback.playQueue(listOf(id)) }
            Thread.sleep(250)
        }
        assertTrue("Audio fixture did not start: ${app.playback.state.value}", app.playback.state.value.playing)
        val activity = instrument.startActivitySync(Intent(context, ClipActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        waitFor { !app.playback.state.value.playing }
        instrument.runOnMainSync { activity.finish() }
        waitFor { activity.isDestroyed }
        assertFalse("Leaving clips must not resume audio", app.playback.state.value.playing)
        instrument.runOnMainSync { app.playback.stop() }
        runBlocking { app.library.forgetFolder(TestMusicProvider.tree.toString()) }
    }
    private fun waitFor(predicate: () -> Boolean) {
        val end = System.currentTimeMillis() + 15_000
        while (!predicate() && System.currentTimeMillis() < end) Thread.sleep(50)
        assertTrue(predicate())
    }
}
