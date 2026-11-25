package dev.petrov.ymplayer2

import android.app.NotificationManager
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.petrov.ymplayer2.core.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real application graph + foreground service, separate from the Compose view-model fixtures. */
@RunWith(AndroidJUnit4::class)
class FileTransferServiceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val app get() = context.applicationContext as PlayerApplication
    private fun await(block: () -> Boolean) {
        val until = System.currentTimeMillis() + 30_000
        while (!block() && System.currentTimeMillis() < until) Thread.sleep(25)
        assertTrue("Transfer state=${app.cacheLocation.state.value}", block())
    }
    @Test fun folderPermissionsAreReusableForReadingAndWritingAfterDetachingLibrary() {
        val resolver = context.contentResolver
        resolver.call(Uri.parse("content://dev.petrov.ymplayer2.test.control"), "storage-reset", null, null)
        val media = TestStorageProvider.tree
        val destination = TestStorageProvider.exportTree
        val read = Intent.FLAG_GRANT_READ_URI_PERMISSION
        val write = Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        try {
            runCatching { resolver.releasePersistableUriPermission(media, read or write) }
            resolver.call(Uri.parse("content://dev.petrov.ymplayer2.test.control"), "storage-grant", null, null)
            resolver.takePersistableUriPermission(media, read)
            context.rememberExportFolderAccess(destination)
            // Selecting an already granted destination does not request another grant.
            context.rememberExportFolderAccess(destination)
            val writeChoices = context.allowedFolders(writable = true, connected = emptySet(), recent = destination.toString())
            assertEquals(destination.toString(), writeChoices.first().uri)
            assertTrue(writeChoices.first().recent)
            assertFalse(writeChoices.any { it.uri == media.toString() })

            runBlocking { app.library.addFolder(media.toString(), Source.LOCAL) }
            assertTrue(context.allowedFolders(false, setOf(media.toString()), null).first { it.uri == media.toString() }.connected)
            runBlocking { app.library.forgetFolder(media.toString()) }
            val reopened = context.allowedFolders(false, emptySet(), null).first { it.uri == media.toString() }
            assertFalse(reopened.connected)
            runBlocking { app.library.addFolder(reopened.uri, Source.LOCAL) }
            assertEquals(1, app.library.state.value.roots.count { it.uri == reopened.uri })
            assertTrue(resolver.persistedUriPermissions.any { it.uri == destination && it.isWritePermission })
        } finally {
            runBlocking { app.library.forgetFolder(media.toString()) }
            runCatching { resolver.releasePersistableUriPermission(media, read) }
            runCatching { resolver.releasePersistableUriPermission(destination, read or write) }
        }
    }
    @Test fun serviceMovesCacheWhileActivityIsRecreatedAndStopsItsOwnNotification() {
        context.contentResolver.call(Uri.parse("content://dev.petrov.ymplayer2.test.control"), "storage-reset", null, null)
        context.contentResolver.call(Uri.parse("content://dev.petrov.ymplayer2.test.control"), "fixtures", null, null)
        val owner = OfflineOwner("service-fixture", "service-account")
        val track = Track("yandex:service-fixture:1", "Fixture", "Fixture", "", Source.YANDEX, 30, false)
        val document = android.provider.DocumentsContract.buildDocumentUriUsingTree(TestMusicProvider.tree, "one.wav")
        runBlocking { app.offlineStore.sync(owner, track, { document.toString() }, { true }, {}) }
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val notifications = context.getSystemService(NotificationManager::class.java)
        try {
            context.contentResolver.takePersistableUriPermission(TestStorageProvider.tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            scenario.onActivity { androidx.core.content.ContextCompat.startForegroundService(it, Intent(it, FileTransferService::class.java).setAction(FileTransferService.MOVE).putExtra("destination", TestStorageProvider.tree.toString())) }
            await { app.cacheLocation.state.value.external && !app.cacheLocation.state.value.moving }
            assertEquals(1, runBlocking { app.offlineStore.load(owner) }.size)
            assertTrue(runBlocking { app.offlineStore.audio(owner, track.id) }!!.startsWith("content:"))
            scenario.recreate()
            assertTrue(app.cacheLocation.state.value.external)
            await { notifications.activeNotifications.none { it.id == 202 } }
            scenario.onActivity { androidx.core.content.ContextCompat.startForegroundService(it, Intent(it, FileTransferService::class.java).setAction(FileTransferService.MOVE)) }
            await { !app.cacheLocation.state.value.external && !app.cacheLocation.state.value.moving }
            assertEquals(1, runBlocking { app.offlineStore.load(owner) }.size)
            await { notifications.activeNotifications.none { it.id == 202 } }
        } finally {
            if (app.cacheLocation.state.value.external) { scenario.onActivity { app.cacheLocation.move(null) }; await { !app.cacheLocation.state.value.moving } }
            runBlocking { app.offlineStore.clear(owner) }; scenario.close()
        }
    }
}
