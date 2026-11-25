package dev.petrov.ymplayer2

import android.net.Uri
import android.provider.DocumentsContract as Documents
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.petrov.ymplayer2.core.*
import dev.petrov.ymplayer2.library.LocalFileManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class LocalMediaFilesTest {
    @Test fun foldersPlayAndTransferBetweenMountedAndSafAndRelinkPlaylists() = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val app = ctx.applicationContext as PlayerApplication
        val resolver = ctx.contentResolver
        resolver.call(Uri.parse("content://dev.petrov.ymplayer2.test.control"), "storage-reset", null, null)
        val localDir = File(ctx.cacheDir, "browser-fixture-${System.nanoTime()}").canonicalFile
        val album = File(localDir, "Album").apply { check(mkdirs()) }
        val disc = File(album, "CD 2").apply { check(mkdir()) }
        val bytes = ByteArray(239) { it.toByte() }
        File(album, "10.mp3").writeBytes(bytes)
        File(album, "2.mp3").writeBytes(bytes)
        File(disc, "3.mp3").writeBytes(bytes)
        File(disc, "notes.txt").writeText("keep")
        val mediaRoot = Uri.fromFile(localDir).toString()
        val exportRoot = TestStorageProvider.exportTree.toString()
        val files = LocalFileManager(ctx, app.library, app.collections)
        val profile = UserProfiles.LOCAL_PROFILE_ID
        var playlist: String? = null
        var favorite: String? = null
        var nestedRoot: String? = null
        try {
            app.library.addFolder(mediaRoot, Source.LOCAL)
            app.library.addFolder(exportRoot, Source.USB)
            val roots = files.roots()
            val mounted = roots.single { it.rootUri == mediaRoot }
            val usb = roots.single { it.rootUri == exportRoot }
            assertTrue(usb.canCreate)
            val folder = files.children(mounted).single()
            val selected = files.children(folder).first { it.name == "2.mp3" }
            assertEquals(listOf("2.mp3", "10.mp3", "3.mp3"), files.tracks(listOf(folder, selected)).map { it.fileName })
            // A song copied by another application is visible immediately, before the next catalog scan.
            val external = File(localDir, "Just added.wav").apply { writeBytes(bytes) }
            val latest = files.children(mounted).single { it.name == external.name }
            assertTrue(app.library.tracksByIds(listOf(requireNotNull(latest.trackId))).isEmpty())
            assertEquals(listOf(latest.trackId), files.tracks(listOf(latest)).map(Track::id))
            assertEquals(listOf("10.mp3", "2.mp3", "3.mp3"), files.audioFiles(listOf(folder),
                compareBy(mediaFileNameOrder, MediaFileEntry::name).reversed()).map(MediaFileEntry::name))
            app.collections.state.first { it.ready }
            assertTrue(app.collections.edit(profile, CollectionEdit.CreateMany("File manager fixture", listOf(selected.trackId!!))))
            playlist = app.collections.state.value.profile(profile).playlists.single { it.name == "File manager fixture" }.id
            assertTrue(app.collections.edit(profile, CollectionEdit.Favorite(selected.trackId!!, true)))
            favorite = selected.trackId

            val subfolder = files.children(folder).single { it.directory }
            assertEquals(listOf("Album"), files.transfer(listOf(folder), subfolder, move = true).failed)
            assertEquals(1, files.transfer(listOf(folder), usb, move = false).completed)
            val duplicate = files.transfer(listOf(folder), usb, move = true)
            assertEquals(listOf("Album"), duplicate.failed)
            assertTrue(album.isDirectory)
            val copied = files.children(usb).single { it.name == "Album" }
            val copiedSong = files.children(copied).single { it.name == "2.mp3" }
            assertArrayEquals(bytes, resolver.openInputStream(Uri.parse(copiedSong.uri))!!.use { it.readBytes() })
            val nestedId = Documents.getDocumentId(Uri.parse(copied.uri))
            resolver.call(Uri.parse("content://dev.petrov.ymplayer2.test.control"), "storage-grant-subtree", nestedId, null)
            val nested = Documents.buildTreeDocumentUri(TestStorageProvider.AUTHORITY, nestedId).toString()
            nestedRoot = nested
            app.library.addFolder(nested, Source.USB)
            val nestedEntry = files.roots().single { it.rootUri == nested }
            val alias = files.children(nestedEntry).single { it.name == "2.mp3" }
            assertNotEquals(copiedSong.uri, alias.uri)
            assertEquals(copiedSong.fileKey, alias.fileKey)
            assertEquals(3, files.tracks(listOf(copied, nestedEntry, alias)).size)
            assertTrue(app.collections.edit(profile, CollectionEdit.Favorite(requireNotNull(alias.trackId), true)))
            val other = files.rename(copiedSong, "alias.mp3")
            assertTrue(app.collections.state.value.profile(profile).favorites.any { it.id == other.trackId })
            app.collections.edit(profile, CollectionEdit.Favorite(requireNotNull(other.trackId), false))
            app.library.forgetFolder(nested)
            nestedRoot = null
            assertEquals(1, files.delete(listOf(copied)).completed)

            val moved = files.transfer(listOf(folder, selected), usb, move = true)
            assertEquals(emptyList<String>(), moved.failed)
            assertEquals(1, moved.completed)
            assertFalse(album.exists())
            val transferred = files.children(usb).single { it.name == "Album" }
            // The provider physically renames the folder, then fails before returning its new metadata.
            resolver.call(Uri.parse("content://dev.petrov.ymplayer2.test.control"), "storage-interrupt-rename", null, null)
            assertTrue(runCatching { files.rename(transferred, "Live") }.isFailure)
            LocalFileManager(ctx, app.library, app.collections).recoverPending()
            val renamed = files.children(usb).single { it.name == "Live" }
            val renamedSong = files.children(renamed).single { it.name == "2.mp3" }
            favorite = renamedSong.trackId
            val collections = app.collections.state.value.profile(profile)
            assertEquals(renamedSong.trackId, collections.playlists.single { it.id == playlist }.tracks.single().id)
            assertTrue(collections.favorites.any { it.id == renamedSong.trackId && it.source == Source.USB })
            assertTrue(files.tracks(listOf(renamed)).all { it.source == Source.USB && it.available })
            val empty = files.createFolder(usb, "Empty")
            assertTrue(files.children(empty).isEmpty())
            assertTrue(files.delete(listOf(usb)).failed.isNotEmpty())
            assertEquals(2, files.delete(listOf(empty, renamed)).completed)
        } finally {
            playlist?.let { app.collections.edit(profile, CollectionEdit.Delete(it)) }
            favorite?.let { app.collections.edit(profile, CollectionEdit.Favorite(it, false)) }
            nestedRoot?.let { app.library.forgetFolder(it) }
            app.library.forgetFolder(exportRoot)
            app.library.forgetFolder(mediaRoot)
            localDir.deleteRecursively()
        }
    }
}
