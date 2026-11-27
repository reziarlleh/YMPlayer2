package dev.petrov.ymplayer2

import android.content.ComponentName
import android.content.pm.PackageManager
import android.media.browse.MediaBrowser
import android.media.session.MediaController
import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaBrowser as Browser3
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.petrov.ymplayer2.core.*
import dev.petrov.ymplayer2.playback.AudioService
import dev.petrov.ymplayer2.localization.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import com.google.common.util.concurrent.SettableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.FutureTask

@RunWith(AndroidJUnit4::class)
class AndroidAutoBrowserTest {
    private val inst get() = InstrumentationRegistry.getInstrumentation()
    private val ctx get() = inst.targetContext
    private val app get() = ctx.applicationContext as PlayerApplication
    private val service get() = ComponentName(ctx, AudioService::class.java)
    private val hints get() = Bundle().apply { putBoolean("dev.petrov.ymplayer2.extra.CAR_CATALOG", true) }
    private fun <T> main(block: () -> T): T = FutureTask(block).also(inst::runOnMainSync).get()
    private fun await(label: String, block: () -> Boolean) {
        val until = android.os.SystemClock.elapsedRealtime() + 15_000
        while (!main(block) && android.os.SystemClock.elapsedRealtime() < until) Thread.sleep(50)
        assertTrue(label, main(block))
    }

    @Test fun declaresProjectionMediaInInstalledApk() {
        val info = ctx.packageManager.getApplicationInfo(ctx.packageName, PackageManager.GET_META_DATA)
        val res = info.metaData.getInt("com.google.android.gms.car.application")
        assertTrue(res != 0)
        val xml = ctx.resources.getXml(res)
        try {
            do {
                if (xml.eventType == org.xmlpull.v1.XmlPullParser.START_TAG && xml.name == "uses") {
                    assertEquals("media", xml.getAttributeValue(null, "name")); return
                }
            } while (xml.next() != org.xmlpull.v1.XmlPullParser.END_DOCUMENT)
            fail("Missing media capability")
        } finally { xml.close() }
    }

    @Test fun platformBrowserStartsFolderControlsPlaybackAndHandlesVoiceWithoutOpeningPhone() {
        ctx.contentResolver.call(Uri.parse("content://dev.petrov.ymplayer2.test.control"), "fixtures", null, null)
        runBlocking { app.library.addFolder(TestMusicProvider.tree.toString(), Source.LOCAL) }
        val connected = SettableFuture.create<Unit>()
        val browser = main { MediaBrowser(ctx, service, object : MediaBrowser.ConnectionCallback() {
            override fun onConnected() { connected.set(Unit) }
            override fun onConnectionFailed() { connected.setException(IllegalStateException("connect")) }
        }, hints).apply { connect() } }
        fun children(parent: String): List<MediaBrowser.MediaItem> {
            val result = SettableFuture.create<List<MediaBrowser.MediaItem>>()
            main { browser.subscribe(parent, object : MediaBrowser.SubscriptionCallback() {
                override fun onChildrenLoaded(parentId: String, rows: MutableList<MediaBrowser.MediaItem>) { result.set(rows) }
                override fun onError(parentId: String) { result.setException(IllegalStateException(parentId)) }
            }) }
            return result.get(15, TimeUnit.SECONDS).also { main { browser.unsubscribe(parent) } }
        }
        try {
            connected.get(15, TimeUnit.SECONDS)
            assertEquals("ymp_car", main { browser.root })
            val tabs = children("ymp_car")
            assertEquals(4, tabs.size)
            assertTrue(tabs.all { it.isBrowsable && !it.isPlayable })
            val local = tabs.first { it.description.title == tr(Msg.library_local_files) }
            val folders = children(local.mediaId!!).first { it.description.title == tr(Msg.files_dirs) }
            val root = children(folders.mediaId!!).first()
            val play = children(root.mediaId!!).first { it.description.title == tr(Msg.files_play_tree) }
            assertFalse(main { app.playback.state.value.playing })
            val transport = main { MediaController(ctx, browser.sessionToken).transportControls }
            transport.playFromMediaId(play.mediaId, null)
            await("recursive folder must play from browser") { app.playback.state.value.let { it.playing && it.queueCount >= 2 } }
            val first = main { app.playback.state.value.current!! }
            transport.skipToNext()
            await("steering wheel next") { app.playback.state.value.current?.id != first.id && app.playback.state.value.playing }
            transport.pause()
            await("pause") { !app.playback.state.value.playing }
            transport.playFromSearch(first.title, null)
            await("voice must select first title") { app.playback.state.value.let { it.playing && it.current?.id == first.id } }
            transport.stop()
            await("stop") { !app.playback.state.value.playing }
        } finally {
            main { browser.disconnect(); app.playback.stop() }
            runBlocking { app.library.forgetFolder(TestMusicProvider.tree.toString()) }
        }
    }

    @Test fun carClipResumesItsSoundtrackAndLeavesMusicAvailable() {
        val oldProfile = app.playback.state.value.profileId
        val profile = runBlocking { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) { app.users.createYandex() } }.id
        val store = dev.petrov.ymplayer2.yandex.KeystoreAccountStore(ctx)
        val file = java.io.File(ctx.cacheDir, "car-test-clip.mp4")
        inst.context.assets.open("clip-transport/fixture.mp4").use { input -> file.outputStream().use(input::copyTo) }
        val browser = Browser3.Builder(ctx, SessionToken(ctx, service)).setConnectionHints(hints).buildAsync().get(15, TimeUnit.SECONDS)
        try {
            runBlocking { store.write(profile, AccountSession(YandexAccount("fixture", "Car fixture"), OAuthCredentials("fixture", null, null))) }
            main { app.playback.switchProfile(profile) }
            await("profile") { app.playback.state.value.profileId == profile }
            val clip = dev.petrov.ymplayer2.yandex.YandexClip("car", "Car fixture", "Artist", "", null, Uri.fromFile(file).toString(), 16, emptyList(), "")
            main { app.clipCheckpoints.write(profile, dev.petrov.ymplayer2.clips.ClipCheckpoint(clip, "test", 3000, false)) }
            fun children(id: String) = main { browser.getChildren(id, 0, Int.MAX_VALUE, null) }.get(15, TimeUnit.SECONDS).value!!
            val music = children("ymp_car").single { it.mediaMetadata.title == tr(Msg.msg_c39959813ccd) }
            val target = children(music.mediaId).single { it.mediaMetadata.title == tr(Msg.car_clip_audio) }
            main { browser.setMediaItem(target); browser.prepare(); browser.play() }
            await("soundtrack and restored position") { browser.isPlaying && browser.currentPosition >= 3100 && browser.mediaMetadata.title == "Car fixture" }
            assertTrue(main { browser.currentTracks.isTypeSelected(androidx.media3.common.C.TRACK_TYPE_AUDIO) })
            assertFalse(main { browser.currentTracks.isTypeSelected(androidx.media3.common.C.TRACK_TYPE_VIDEO) })
            main { browser.pause() }
            await("soundtrack paused") { !browser.isPlaying && app.clipCheckpoints.read(profile)?.playing == false }
            assertTrue(app.clipCheckpoints.read(profile)!!.positionMs >= 3000)
            main { app.playback.switchProfile(oldProfile) }
            await("restored phone player") { app.playback.state.value.profileId == oldProfile && !app.playback.carClipOutputActive }
            assertNull(main { browser.getItem(target.mediaId) }.get(10, TimeUnit.SECONDS).value)
        } finally {
            main { browser.release(); app.playback.switchProfile(oldProfile) }
            runBlocking { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) { app.users.remove(profile) } }
            file.delete()
        }
    }

    @Test fun media3SearchAndUnresolvableIdsCannotStartArbitraryAudio() {
        val browser = Browser3.Builder(ctx, SessionToken(ctx, service)).setConnectionHints(hints).buildAsync().get(15, TimeUnit.SECONDS)
        try {
            val root = main { browser.getLibraryRoot(androidx.media3.session.MediaLibraryService.LibraryParams.Builder().setExtras(hints).build()) }.get(15, TimeUnit.SECONDS).value!!
            assertEquals("ymp_car", root.mediaId)
            val tabs = main { browser.getChildren(root.mediaId, 0, Int.MAX_VALUE, null) }.get(15, TimeUnit.SECONDS).value!!
            assertEquals(4, tabs.size)
            assertNull(main { browser.getItem("ymp_car/previous-profile/unknown") }.get(10, TimeUnit.SECONDS).value)
            val results = main { browser.getSearchResult("no-such-test-file", 0, 15, null) }.get(15, TimeUnit.SECONDS)
            assertTrue(results.value!!.isEmpty())
            val before = main { app.playback.state.value.current?.id }
            main { browser.setMediaItem(MediaItem.Builder().setMediaId("ymp_car/invalid").setUri("file:///unknown").build()) }
            Thread.sleep(300)
            assertEquals(before, main { app.playback.state.value.current?.id })
        } finally { main { browser.release() } }
    }
}
