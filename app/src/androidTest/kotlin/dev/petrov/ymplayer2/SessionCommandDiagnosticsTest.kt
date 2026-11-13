@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
package dev.petrov.ymplayer2

import android.content.ComponentName
import android.media.browse.MediaBrowser as PlatformBrowser
import android.media.session.MediaController as PlatformController
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaBrowser
import androidx.media3.session.SessionToken
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.petrov.ymplayer2.core.Source
import dev.petrov.ymplayer2.playback.AudioService
import dev.petrov.ymplayer2.playback.LaunchStateStore
import dev.petrov.ymplayer2.playback.sessionPlayer
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class SessionCommandDiagnosticsTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val app get() = context.applicationContext as PlayerApplication
    private val service get() = ComponentName(context, AudioService::class.java)
    private lateinit var screen: ActivityScenario<MainActivity>
    private lateinit var browser: MediaBrowser
    private fun <T> main(action: () -> T): T {
        val value = AtomicReference<T>()
        instrumentation.runOnMainSync { value.set(action()) }
        return value.get()
    }
    private fun await(action: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 20_000
        while (!action() && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertTrue("Expected player/diagnostic state", action())
    }
    private fun journal() = runBlocking { app.diagnostics.snapshot() }
    private fun commands() = journal().lineSequence().filter { "operation=SESSION_COMMAND " in it }.toList()
    private fun pauseRequest(line: String) = " command=PAUSE " in line || " command=SET_PLAY_WHEN_READY_FALSE " in line
    private fun playRequest(line: String) = " command=PLAY " in line || " command=SET_PLAY_WHEN_READY_TRUE " in line
    private fun playFixture() {
        main { app.playback.playQueue(listOf(app.library.testTracks.first().id)) }
        await { app.playback.state.value.playing }
    }

    @Before fun setup() {
        context.contentResolver.call(android.net.Uri.parse("content://dev.petrov.ymplayer2.test.control"), "fixtures", null, null)
        runBlocking { app.library.addFolder(TestMusicProvider.tree.toString(), Source.LOCAL) }
        screen = ActivityScenario.launch(MainActivity::class.java)
        await { app.playback.state.value.connected && app.library.testTracks.isNotEmpty() }
        browser = MediaBrowser.Builder(context, SessionToken(context, service)).buildAsync().get(20, TimeUnit.SECONDS)
        main { app.playback.stop() }
        runBlocking { app.diagnostics.clear() }
    }
    @After fun cleanup() {
        if (::browser.isInitialized) main { app.playback.stop(); browser.release() }
        if (::screen.isInitialized) screen.close()
        runBlocking { app.library.forgetFolder(TestMusicProvider.tree.toString()) }
    }

    @Test fun media3CommandsAreAttributedAndForegroundKeepsManualPause() {
        playFixture()
        val id = app.playback.state.value.current!!.id
        main { app.playback.seek(3); browser.pause() }
        await { !app.playback.state.value.playing && commands().any(::pauseRequest) }
        val pause = commands().last(::pauseRequest)
        assertTrue(pause, "controllerOrigin=MEDIA_CONTROLLER" in pause)
        assertTrue(pause, "controllerPackage=${context.packageName}" in pause)
        assertTrue(pause, "intent=PLAY" in pause)
        assertTrue(pause, "elapsedMs=" in pause)
        val state = LaunchStateStore(context)
        assertFalse(state.read(app.playback.state.value.profileId).playing)
        screen.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        screen.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        await { "operation=APP_BACKGROUND " in journal() && "operation=APP_FOREGROUND " in journal() }
        Thread.sleep(1000)
        assertFalse(app.playback.state.value.playing)
        assertEquals(id, app.playback.state.value.current?.id)
        main { browser.play() }
        await { app.playback.state.value.playing && commands().any(::playRequest) }
        main { browser.stop() }
        await { !app.playback.state.value.playing && commands().any { " command=STOP " in it } }
        assertTrue(journal().contains("reason=CONTROLLER_STOP"))
    }

    @Test fun platformControllerPauseIsRecordedThroughTheLegacySession() {
        val connected = CountDownLatch(1)
        val platform = main { PlatformBrowser(context, service, object : PlatformBrowser.ConnectionCallback() {
            override fun onConnected() { connected.countDown() }
            override fun onConnectionFailed() { connected.countDown() }
        }, null) }
        main { platform.connect() }
        try {
            assertTrue(connected.await(20, TimeUnit.SECONDS))
            assertTrue(platform.isConnected)
            val controller = main { PlatformController(context, platform.sessionToken) }
            playFixture()
            main { controller.transportControls.pause() }
            await { !app.playback.state.value.playing && commands().any(::pauseRequest) }
            val pause = commands().last()
            assertFalse(pause, "controllerOrigin=NO_CONTROLLER" in pause)
            assertTrue(pause, "controllerPackage=" in pause)
            assertFalse(LaunchStateStore(context).read(app.playback.state.value.profileId).playing)
        } finally { main { platform.disconnect() } }
    }

    @Test fun failedDiagnosticObserverCannotSwallowPause() {
        playFixture()
        val player = main { ExoPlayer.Builder(context).build() }
        try {
            main { sessionPlayer(player, app.playback) { throw IllegalStateException("diagnostic fixture") }.pause() }
            await { !app.playback.state.value.playing }
            assertFalse(LaunchStateStore(context).read(app.playback.state.value.profileId).playing)
        } finally { main { player.release() } }
    }
}
