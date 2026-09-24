package dev.petrov.ymplayer2

import android.content.ComponentName
import android.media.browse.MediaBrowser as PlatformBrowser
import android.media.session.MediaController as PlatformController
import android.os.Bundle
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaBrowser
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.petrov.ymplayer2.playback.AudioService
import dev.petrov.ymplayer2.core.Source
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class SystemBrowserTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val service get() = ComponentName(context, AudioService::class.java)
    private fun <T> main(block: () -> T): T {
        val result = AtomicReference<T>()
        instrumentation.runOnMainSync { result.set(block()) }
        return result.get()
    }
    private fun awaitState(block: () -> Boolean) {
        val until = System.currentTimeMillis() + 15_000
        while (!block() && System.currentTimeMillis() < until) Thread.sleep(50)
        assertTrue("Expected player state was not reached", block())
    }

    @Test fun media3BrowserPublishesOnlyLegacySourcesAndRejectsInjectedMedia() {
        val browser = MediaBrowser.Builder(context, SessionToken(context, service)).buildAsync().get(20, TimeUnit.SECONDS)
        try {
            val root = main { browser.getLibraryRoot(null) }.get(20, TimeUnit.SECONDS).value!!
            assertEquals("ymp_root", root.mediaId)
            assertTrue(root.mediaMetadata.isBrowsable == true)
            val children = main { browser.getChildren(root.mediaId, 0, 10, null) }.get(20, TimeUnit.SECONDS).value!!
            assertEquals(listOf("ymp_my_wave", "ymp_liked_cache"), children.map { it.mediaId })
            assertTrue(children.all { it.mediaMetadata.isPlayable == true && it.localConfiguration == null })
            assertEquals(listOf("ymp_liked_cache"), main { browser.getChildren(root.mediaId, 1, 1, null) }
                .get(20, TimeUnit.SECONDS).value!!.map { it.mediaId })
            assertTrue(main { browser.getChildren(root.mediaId, Int.MAX_VALUE, Int.MAX_VALUE, null) }
                .get(20, TimeUnit.SECONDS).value!!.isEmpty())
            assertEquals("ymp_liked_cache", main { browser.getItem("ymp_liked_cache") }.get(20, TimeUnit.SECONDS).value?.mediaId)
            assertNull(main { browser.getItem("file:///private") }.get(20, TimeUnit.SECONDS).value)

            val before = main { browser.mediaItemCount }
            instrumentation.runOnMainSync {
                browser.setMediaItem(MediaItem.Builder().setMediaId("not-a-source")
                    .setUri("file:///private").build())
            }
            instrumentation.waitForIdleSync()
            assertEquals(before, main { browser.mediaItemCount })
        } finally {
            instrumentation.runOnMainSync { browser.release() }
        }
    }

    @Test fun platformBrowserCanBrowseSameRootWithoutStartingPlayback() {
        val connected = CountDownLatch(1)
        val childrenReady = CountDownLatch(1)
        var rootId: String? = null
        var ids: List<String>? = null
        lateinit var browser: PlatformBrowser
        browser = main { PlatformBrowser(context, service, object : PlatformBrowser.ConnectionCallback() {
            override fun onConnected() {
                rootId = browser.root
                browser.subscribe(browser.root ?: "", object : PlatformBrowser.SubscriptionCallback() {
                    override fun onChildrenLoaded(parentId: String, children: MutableList<PlatformBrowser.MediaItem>) {
                        ids = children.map { it.mediaId.orEmpty() }
                        childrenReady.countDown()
                    }
                })
                connected.countDown()
            }
            override fun onConnectionFailed() { connected.countDown() }
        }, Bundle.EMPTY) }
        try {
            instrumentation.runOnMainSync { browser.connect() }
            assertTrue("Platform browser could not connect", connected.await(20, TimeUnit.SECONDS))
            assertEquals("ymp_root", rootId)
            assertTrue("Platform browser did not receive children", childrenReady.await(20, TimeUnit.SECONDS))
            assertEquals(listOf("ymp_my_wave", "ymp_liked_cache"), ids)
            assertFalse((context.applicationContext as PlayerApplication).playback.state.value.playing)
        } finally {
            instrumentation.runOnMainSync { browser.disconnect() }
        }
    }

    @Test fun selectedEmptyLikedCacheDoesNotResumeThePreviousLocalQueue() {
        val app = context.applicationContext as PlayerApplication
        context.contentResolver.call(Uri.parse("content://dev.petrov.ymplayer2.test.control"), "fixtures", null, null)
        runBlocking { app.library.addFolder(TestMusicProvider.tree.toString(), Source.LOCAL) }
        val browser = MediaBrowser.Builder(context, SessionToken(context, service)).buildAsync().get(20, TimeUnit.SECONDS)
        try {
            awaitState { app.playback.state.value.connected && app.library.state.value.tracks.isNotEmpty() }
            assertTrue("Fixture expects no signed-in offline cache", app.offline.state.value.tracks.isEmpty())
            val id = app.library.state.value.tracks.first().id
            main { app.playback.playQueue(listOf(id)) }
            awaitState { app.playback.state.value.current?.id == id && app.playback.state.value.playing }
            main { browser.pause() }
            awaitState { !app.playback.state.value.playing }

            main { browser.setMediaItem(MediaItem.Builder().setMediaId("ymp_liked_cache").build()) }
            main { browser.play() }
            Thread.sleep(800)
            assertFalse("An empty cache must not resume the old queue", app.playback.state.value.playing)
            assertEquals(id, app.playback.state.value.current?.id)

            val connected = CountDownLatch(1)
            val legacy = main { PlatformBrowser(context, service, object : PlatformBrowser.ConnectionCallback() {
                override fun onConnected() { connected.countDown() }
                override fun onConnectionFailed() { connected.countDown() }
            }, Bundle.EMPTY) }
            try {
                main { legacy.connect() }
                assertTrue(connected.await(20, TimeUnit.SECONDS))
                assertTrue(legacy.isConnected)
                main { PlatformController(context, legacy.sessionToken).transportControls.playFromMediaId("ymp_liked_cache", null) }
                Thread.sleep(800)
                assertFalse("Legacy playFromMediaId must use the empty cache, not the old queue", app.playback.state.value.playing)
            } finally {
                main { legacy.disconnect() }
            }
        } finally {
            main { app.playback.stop(); browser.release() }
            runBlocking { app.library.forgetFolder(TestMusicProvider.tree.toString()) }
        }
    }
}
