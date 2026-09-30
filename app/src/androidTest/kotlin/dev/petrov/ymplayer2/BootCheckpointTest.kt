package dev.petrov.ymplayer2

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.petrov.ymplayer2.core.Source
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicReference

/** Run seed and verify in separate instrumentation processes with an emulator reboot between them. */
@RunWith(AndroidJUnit4::class)
class BootCheckpointTest {
    private val instrument get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrument.targetContext.applicationContext as PlayerApplication
    private fun <T> main(block: () -> T): T {
        val result = AtomicReference<T>()
        instrument.runOnMainSync { result.set(block()) }
        return result.get()
    }
    private fun awaitState(condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 20_000
        while (!condition() && System.currentTimeMillis() < end) Thread.sleep(50)
        assertTrue("Expected checkpoint state was not reached", condition())
    }
    private fun fixtures() = instrument.targetContext.contentResolver.call(
        Uri.parse("content://dev.petrov.ymplayer2.test.control"), "fixtures", null, null)

    @Test fun checkpointAcrossEmulatorBoot() {
        val phase = InstrumentationRegistry.getArguments().getString("bootPhase")
        assumeTrue("Run only through tools/check-boot-recovery.ps1", phase == "seed" || phase == "verify")
        fixtures()
        if (phase == "seed") {
            main { app.playback.connect() }
            awaitState { app.playback.state.value.connected && app.library.state.value.ready }
            main { app.playback.stop(); app.playback.switchProfile("road") }
            runBlocking {
                app.library.state.value.roots.forEach { app.library.forgetFolder(it.uri) }
                app.library.addFolder(TestMusicProvider.tree.toString(), Source.LOCAL)
            }
            awaitState { app.library.testTracks.size == 2 }
            val first = app.library.testTracks.first().id
            main { app.playback.chooseSource(Source.LOCAL) }
            awaitState { app.playback.state.value.profileId == "road" && app.playback.state.value.automaticLocal && app.playback.state.value.queueCount == 2 }
            main { app.playback.clearQueue() }
            awaitState { app.playback.state.value.queueCount == 0 && app.playback.state.value.current == null }
            main { app.playback.playQueue(listOf(first)); app.playback.seek(9); app.playback.toggle() }
            awaitState { app.playback.state.value.current?.id == first && app.playback.state.value.positionSeconds >= 9 && !app.playback.state.value.playing }
            assertEquals("road", app.playback.state.value.profileId)
            instrument.targetContext.getSharedPreferences("boot-checkpoint-test", 0)
                .edit().putString("expected", first).commit()
            assertTrue(instrument.targetContext.getSharedPreferences("playback", 0)
                .getString("queue:road", "").orEmpty().contains(first))
        } else {
            main { app.playback.connect() }
            awaitState { app.playback.state.value.connected && app.playback.state.value.current != null &&
                app.library.state.value.ready && !app.library.state.value.scanning }
            val state = app.playback.state.value
            assertEquals("road", state.profileId)
            assertEquals(1, state.queue.size)
            assertEquals(instrument.targetContext.getSharedPreferences("boot-checkpoint-test", 0)
                .getString("expected", null), state.current?.id)
            assertTrue(state.positionSeconds >= 9)
            assertFalse("Boot must restore on pause", state.playing)
            assertNull(state.error)
            main { app.playback.stop(); app.playback.switchProfile("owner") }
            runBlocking { app.library.forgetFolder(TestMusicProvider.tree.toString()) }
        }
    }
}
