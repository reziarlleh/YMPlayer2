package dev.petrov.ymplayer2

import android.app.NotificationManager
import android.net.Uri
import android.accessibilityservice.AccessibilityService
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.core.app.NotificationCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.petrov.ymplayer2.core.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class TvNotificationActionsTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
    private val app get() = context as PlayerApplication
    private val notifications get() = context.getSystemService(NotificationManager::class.java)
    private fun waitFor(block: () -> Boolean) = compose.waitUntil(20_000, block)
    private fun appHasFocus(): Boolean {
        var focused = false
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            focused = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED)
                .any { it is MainActivity && it.hasWindowFocus() }
        }
        return focused
    }
    private fun card() = notifications.activeNotifications.first { it.id == 1001 }
    private fun resumedActivity(): MainActivity? {
        var activity: MainActivity? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            activity = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED)
                .filterIsInstance<MainActivity>().singleOrNull()
        }
        return activity
    }
    private fun tv() = NotificationCompat.TvExtender(card().notification)
    private fun home() {
        assertTrue(InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME))
        waitFor { !appHasFocus() }
    }
    private fun backgroundAudioCardPresent(): Boolean {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("dumpsys notification")
        val dump = android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
        return dump.lineSequence().any { it.contains("NotificationRecord") &&
            it.contains("pkg=com.google.android.tvrecommendations") && it.contains("id=4321") }
    }
    private fun systemClick(vararg labels: String) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val until = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < until) {
            val root = automation.rootInActiveWindow
            for (label in labels) for (node in (if (label == "Dismiss")
                root?.findAccessibilityNodeInfosByViewId("com.google.android.tvrecommendations:id/dismiss_button")
                else root?.findAccessibilityNodeInfosByText(label)).orEmpty()) {
                if (!node.isVisibleToUser || !node.isEnabled) continue
                // Image-only TV controls may handle touch/focus without exposing ACTION_CLICK.
                // Tap their observed bounds instead of climbing to the notification's Open row.
                if (label in listOf("Dismiss", "Stop", "Остановить")) {
                    val bounds = android.graphics.Rect(); node.getBoundsInScreen(bounds)
                    java.io.File(context.getExternalFilesDir(null), "tv-dismiss-target.txt").writeText(
                        "id=${node.viewIdResourceName} bounds=$bounds clickable=${node.isClickable} enabled=${node.isEnabled}")
                    if (node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)) return
                    val time = android.os.SystemClock.uptimeMillis()
                    for (action in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
                        android.view.MotionEvent.obtain(time, android.os.SystemClock.uptimeMillis(), action,
                            bounds.exactCenterX(), bounds.exactCenterY(), 0).let {
                            it.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                            automation.injectInputEvent(it, true); it.recycle()
                        }
                    }
                    return
                }
                var current: android.view.accessibility.AccessibilityNodeInfo? = node
                while (current != null) {
                    if (current.isClickable && current.isEnabled && current.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)) return
                    current = current.parent
                }
            }
            Thread.sleep(100)
        }
        val file = java.io.File(context.getExternalFilesDir(null), "tv-panel-missing.png")
        automation.takeScreenshot()?.let { bitmap -> file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }
        val descriptions = mutableListOf<String>()
        fun describe(node: android.view.accessibility.AccessibilityNodeInfo?) {
            if (node == null) return
            if (node.text != null || node.contentDescription != null) descriptions += "${node.viewIdResourceName}: ${node.text} / ${node.contentDescription}"
            for (i in 0 until node.childCount) describe(node.getChild(i))
        }
        describe(automation.rootInActiveWindow)
        java.io.File(context.getExternalFilesDir(null), "tv-panel-missing.txt").writeText(descriptions.joinToString("\n"))
        throw AssertionError("TV launcher control was not found: ${labels.toList()}")
    }
    @Before fun setup() {
        context.startActivity(android.content.Intent(context, MainActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK))
        waitFor { appHasFocus() }
        context.contentResolver.call(Uri.parse("content://dev.petrov.ymplayer2.test.control"), "fixtures", null, null)
        runBlocking { app.library.addFolder(TestMusicProvider.tree.toString(), Source.LOCAL) }
        waitFor { app.library.testTracks.isNotEmpty() && app.playback.state.value.connected }
        compose.runOnIdle { app.radio.release(); app.playback.stop() }
    }
    @After fun cleanup() {
        compose.runOnIdle { app.radio.release(); app.playback.stop() }
        runBlocking { app.library.forgetFolder(TestMusicProvider.tree.toString()) }
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val monitor = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
            androidx.test.runner.lifecycle.Stage.values().flatMap { monitor.getActivitiesInStage(it) }
                .filterIsInstance<MainActivity>().distinct().forEach { it.finish() }
        }
    }
    @Test fun tvOpenPreservesLibraryAndStopDoesNotRepostOrAutoplay() {
        val ids = app.library.testTracks.take(2).map { it.id }
        compose.runOnIdle { app.playback.playQueue(ids) }
        waitFor { app.playback.state.value.playing && notifications.activeNotifications.any { it.id == 1001 } }
        compose.onNodeWithTag("nav_library").performClick().assertIsSelected()
        val originalActivity = resumedActivity(); assertNotNull(originalActivity)
        val open = tv().contentIntent; assertNotNull(open); assertTrue(open!!.isActivity)
        home(); open.send()
        waitFor { appHasFocus() }
        assertSame("Open must preserve the existing Activity and its UI state", originalActivity, resumedActivity())
        compose.onNodeWithTag("nav_library").assertIsSelected()
        assertTrue("Open must not pause playing music", app.playback.state.value.playing)

        home()
        val stop = tv().deleteIntent; assertNotNull(stop); stop!!.send()
        waitFor { !app.playback.state.value.playing && notifications.activeNotifications.none { it.id == 1001 } }
        val seconds = app.playback.state.value.positionSeconds
        Thread.sleep(3500)
        assertEquals("Stop must actually stop the audio timeline", seconds, app.playback.state.value.positionSeconds)
        assertFalse(app.playback.state.value.playing)
        assertTrue("The TV card must stay removed", notifications.activeNotifications.none { it.id == 1001 })
        assertFalse(app.launchState.read(app.playback.state.value.profileId).playing)
        open.send(); waitFor { appHasFocus() }
        compose.onNodeWithTag("nav_library").assertIsSelected()
        assertFalse("Opening a stopped player must not autoplay", app.playback.state.value.playing)
        compose.runOnIdle { app.playback.playQueue(ids) }
        waitFor { app.playback.state.value.playing && notifications.activeNotifications.any { it.id == 1001 } }
        assertNotNull(tv().deleteIntent)
    }
    @Test fun androidTvHomeOpenAndStopButtonsControlMusic() {
        val homeIntent = android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_HOME)
        val launcher = context.packageManager.resolveActivity(homeIntent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
        org.junit.Assume.assumeTrue("Android TV Home UI scenario requires its project TV image", launcher == "com.google.android.tvlauncher")
        compose.runOnIdle { app.playback.playQueue(app.library.testTracks.take(2).map { it.id }) }
        waitFor { app.playback.state.value.playing && notifications.activeNotifications.any { it.id == 1001 } }
        compose.onNodeWithTag("nav_library").performClick()
        val title = card().notification.extras.getCharSequence(android.app.Notification.EXTRA_TITLE).toString()
        home(); waitFor { backgroundAudioCardPresent() }; systemClick("notification", "Notifications")
        // Android TV Home opens through the row itself; other TV panels show Open.
        systemClick("Open", "Открыть", title)
        waitFor { appHasFocus() }
        compose.onNodeWithTag("nav_library").assertIsSelected()
        assertTrue(app.playback.state.value.playing)
        home(); systemClick("notification", "Notifications")
        systemClick("Dismiss", "Stop", "Остановить")
        val stopTarget = java.io.File(context.getExternalFilesDir(null), "tv-dismiss-target.txt").readText()
        assertTrue("Test must use Android TV's background-audio Stop, not app notification dismissal",
            stopTarget.contains("com.google.android.tvlauncher:id/tray_dismiss") ||
                stopTarget.contains("com.google.android.tvrecommendations:id/dismiss_button"))
        Thread.sleep(300)
        java.io.File(context.getExternalFilesDir(null), "tv-stop-click.txt").writeText(
            "focus=${appHasFocus()} playing=${app.playback.state.value.playing} cards=${notifications.activeNotifications.count { it.id == 1001 }} root=${InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow?.packageName}")
        // Android TV's background-audio card sends framework Pause. The ordinary
        // paused app notification can remain; it is a different card, not a restart.
        waitFor { !app.playback.state.value.playing }
        val stopped = app.playback.state.value.positionSeconds
        Thread.sleep(3500)
        assertEquals(stopped, app.playback.state.value.positionSeconds)
        assertFalse(app.playback.state.value.playing)
        val token = card().notification.extras.getParcelable<android.media.session.MediaSession.Token>("android.mediaSession")!!
        val platform = android.media.session.MediaController(context, token)
        assertEquals(android.media.session.PlaybackState.STATE_PAUSED, platform.playbackState?.state)
        assertFalse(app.launchState.read(app.playback.state.value.profileId).playing)
        waitFor { !backgroundAudioCardPresent() }
        Thread.sleep(2500)
        assertFalse("Background-audio card must not reappear", backgroundAudioCardPresent())
        // Return to the app without asking it to play so the test rule can clean up.
        context.startActivity(android.content.Intent(context, MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        waitFor { appHasFocus() }
    }
    @Test fun tvOpenPreservesLibraryWhileRadioPlaysAndStopCancelsOnAirPolling() {
        val field = dev.petrov.ymplayer2.playback.AndroidRadio::class.java.getDeclaredField("api").apply { isAccessible = true }
        val original = field.get(app.radio) as RadioApi
        val calls = AtomicInteger()
        val station = RadioStation("tv-open-fixture", "TV station fixture")
        val audio = android.provider.DocumentsContract.buildDocumentUriUsingTree(TestMusicProvider.tree, "one.wav").toString()
        val fixture = object : RadioApi by original {
            override suspend fun stream(station: RadioStation, region: String?) = RadioStream(station, "fixture", audio)
            override suspend fun onAir(stationSlug: String, streamSlug: String): RadioOnAir {
                calls.incrementAndGet(); return RadioOnAir("On air", "Fixture", 1000)
            }
        }
        try {
            compose.runOnIdle { field.set(app.radio, fixture); app.radio.switchProfile("owner"); app.radio.play(station) }
            waitFor { app.radio.state.value.playing && notifications.activeNotifications.any { it.id == 1001 } }
            compose.onNodeWithTag("nav_library").performClick()
            val originalActivity = resumedActivity(); assertNotNull(originalActivity)
            val open = tv().contentIntent; assertNotNull(open)
            home(); open!!.send(); waitFor { appHasFocus() }
            assertSame("Radio must not recreate the library screen", originalActivity, resumedActivity())
            compose.onNodeWithTag("nav_library").assertIsSelected()
            assertTrue(app.radio.state.value.playing)
            home(); tv().deleteIntent!!.send()
            waitFor { !app.radio.state.value.playing && !app.radio.state.value.reconnecting && notifications.activeNotifications.none { it.id == 1001 } }
            val stopped = calls.get(); Thread.sleep(6100)
            assertEquals(stopped, calls.get())
            assertFalse(app.radio.state.value.playing)
            assertTrue(notifications.activeNotifications.none { it.id == 1001 })
            open.send(); waitFor { appHasFocus() }
            compose.onNodeWithTag("nav_library").assertIsSelected()
            assertFalse("Open must not restart a stopped station", app.radio.state.value.playing)
        } finally { compose.runOnIdle { app.radio.release(); field.set(app.radio, original) } }
    }

    @Test fun myWaveOpenPreservesLibraryEvenWithStaleRadioCheckpoint() {
        // Emulator-only provider fixture: real product MainActivity/AudioService/Player,
        // fake account/API and local WAV. No user credentials or external network.
        lateinit var fixture: OnlineHarness
        var fixtureCreated = false
        val onlineField = dev.petrov.ymplayer2.playback.AndroidPlayback::class.java
            .getDeclaredField("online").apply { isAccessible = true }
        val waveField = dev.petrov.ymplayer2.playback.AndroidPlayback::class.java
            .getDeclaredField("waveApi").apply { isAccessible = true }
        val settingsField = dev.petrov.ymplayer2.playback.AndroidPlayback::class.java
            .getDeclaredField("waveSettings").apply { isAccessible = true }
        val tasteField = dev.petrov.ymplayer2.playback.AndroidPlayback::class.java
            .getDeclaredField("taste").apply { isAccessible = true }
        val originalOnline = onlineField.get(app.playback)
        val originalWave = waveField.get(app.playback)
        val originalSettings = settingsField.get(app.playback)
        val originalTaste = tasteField.get(app.playback)
        val profile = app.playback.state.value.profileId
        val checkpoint = app.launchState.read(profile)
        try {
            compose.runOnIdle {
                fixture = OnlineHarness(app)
                fixtureCreated = true
                fixture.auth.activate(profile)
                onlineField.set(app.playback, fixture.online)
                waveField.set(app.playback, fixture.waveApi)
                settingsField.set(app.playback, fixture.player.waveSettings)
                tasteField.set(app.playback, fixture.taste)
            }
            waitFor { fixture.online.catalog.value.phase == AuthPhase.SIGNED_IN }
            compose.runOnIdle { app.playback.playMyWave() }
            try {
                waitFor { app.playback.state.value.wave && app.playback.state.value.playing &&
                    app.playback.state.value.positionSeconds >= 1 }
            } catch (failure: Throwable) {
                throw AssertionError("Fixture wave state=${app.playback.state.value}; catalog=${fixture.online.catalog.value}; requests=${fixture.waveRequests}; resolved=${fixture.resolved}", failure)
            }
            compose.onNodeWithTag("nav_library").performClick().assertIsSelected()
            val originalActivity = resumedActivity(); assertNotNull(originalActivity)
            val open = tv().contentIntent!!
            home()
            compose.runOnIdle {
                app.launchState.write(profile, dev.petrov.ymplayer2.playback.PlaybackOutput.RADIO, true)
                assertTrue(app.playback.state.value.wave && app.playback.state.value.playing)
                open.send()
            }
            waitFor { appHasFocus() }
            assertSame(originalActivity, resumedActivity())
            compose.onNodeWithTag("nav_library").assertIsSelected()
            assertTrue(app.playback.state.value.wave && app.playback.state.value.playing)
        } finally {
            compose.runOnIdle {
                app.playback.stop()
                onlineField.set(app.playback, originalOnline)
                waveField.set(app.playback, originalWave)
                settingsField.set(app.playback, originalSettings)
                tasteField.set(app.playback, originalTaste)
                app.launchState.write(profile, checkpoint.output, checkpoint.playing)
                if (fixtureCreated) androidx.lifecycle.ViewModelStore().apply {
                    put("wave-open-fixture", fixture); clear()
                }
            }
        }
    }
}
