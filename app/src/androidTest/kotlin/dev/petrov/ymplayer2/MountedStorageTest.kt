package dev.petrov.ymplayer2

import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.petrov.ymplayer2.core.*
import dev.petrov.ymplayer2.library.SafLibrary
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Test
import org.junit.Rule
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class MountedStorageTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun mountedFolderIndexesAndPlaysReadOnlyAndRecoversAfterDisconnect() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<PlayerApplication>()
        context.contentResolver.call(Uri.parse("content://dev.petrov.ymplayer2.test.control"), "fixtures", null, null)
        val parent = File(context.cacheDir, "mounted-storage-test").apply { mkdirs() }
        val folder = File(parent, "usb").apply { mkdirs() }
        val nested = File(folder, "nested").apply { mkdirs() }
        val audio = File(nested, "ONE.WAV")
        val providerAudio = DocumentsContract.buildDocumentUriUsingTree(TestMusicProvider.tree, "one.wav")
        context.contentResolver.openInputStream(providerAudio)!!.use { input -> audio.outputStream().use { input.copyTo(it) } }
        File(folder, "ignored.aiff").writeText("not supported")
        File(folder, "ignored.txt").writeText("not audio")
        val original = audio.readBytes()
        val library = context.library
        library.state.first { it.ready && !it.scanning }
        val root = Uri.fromFile(folder).toString()
        try {
            library.addFolder(root, Source.USB)
            assertNull(library.state.value.roots.single { it.uri == root }.issue)
            val tracks = library.pageTracks(CatalogFilter(source = Source.USB), false, null, CatalogDimension.TRACKS, 0, 100).items.filter { it.rootId == root }
            assertEquals(1, tracks.size)
            assertTrue(tracks.single().durationSeconds > 0)
            context.contentResolver.openInputStream(Uri.parse(tracks.single().uri))!!.use { assertArrayEquals(original, it.readBytes()) }
            compose.runOnIdle { context.playback.playQueue(tracks.map(Track::id), tracks.single().id) }
            withTimeout(15000) { context.playback.state.first { it.playing && it.positionSeconds >= 1 } }
            compose.runOnIdle { context.playback.stop() }
            // Simulate an absent mount without deleting original media.
            val absent = File(parent, "disconnected")
            check(folder.renameTo(absent))
            library.refresh()
            assertNotNull(library.state.value.roots.single { it.uri == root }.issue)
            check(absent.renameTo(folder))
            library.refresh()
            assertNull(library.state.value.roots.single { it.uri == root }.issue)
            library.forgetFolder(root)
            assertArrayEquals(original, audio.readBytes())
        } finally {
            compose.runOnIdle { context.playback.stop() }
            library.forgetFolder(root)
            // Confined fixture paths created by this test only.
            parent.deleteRecursively()
        }
    }
    @Test fun sharedStorageNeedsReadPermissionAndNeverNeedsWritePermission() = runBlocking<Unit> {
        org.junit.Assume.assumeTrue(android.os.Build.VERSION.SDK_INT <= 29)
        // Prepared by adb push on our emulator; the player itself cannot create shared files.
        val folder = File("/sdcard/Music/ymplayer-mounted-fixture")
        val context = ApplicationProvider.getApplicationContext<PlayerApplication>()
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
            .grantRuntimePermission(context.packageName, android.Manifest.permission.READ_EXTERNAL_STORAGE)
        org.junit.Assume.assumeTrue("Stage the public emulator fixture before this test", File(folder, "nested/ONE.WAV").exists())
        assertNotEquals(android.content.pm.PackageManager.PERMISSION_GRANTED,
            context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE))
        val library = context.library
        library.state.first { it.ready && !it.scanning }
        val root = Uri.fromFile(folder).toString()
        try {
            library.addFolder(root, Source.USB)
            assertNull(library.state.value.roots.single { it.uri == root }.issue)
            val tracks = library.pageTracks(CatalogFilter(source = Source.USB), false, null, CatalogDimension.TRACKS, 0, 100).items.filter { it.rootId == root }
            assertEquals(1, tracks.size)
            assertTrue(tracks.single().durationSeconds > 0)
            compose.runOnIdle { context.playback.playQueue(tracks.map(Track::id), tracks.single().id) }
            withTimeout(15000) { context.playback.state.first { it.playing && it.positionSeconds >= 1 } }
        } finally {
            compose.runOnIdle { context.playback.stop() }
            library.forgetFolder(root)
        }
    }
}
