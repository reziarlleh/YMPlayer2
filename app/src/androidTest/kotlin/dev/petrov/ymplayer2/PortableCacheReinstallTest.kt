@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
package dev.petrov.ymplayer2

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.provider.DocumentsContract as Docs
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.petrov.ymplayer2.core.*
import dev.petrov.ymplayer2.offline.LikedFileStore
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.UUID

/** Two explicit phases separated by a real uninstall of the TARGET app; the independent SAF provider remains installed. */
@RunWith(AndroidJUnit4::class)
class PortableCacheReinstallTest {
    private class Isolated(base: Context) : ContextWrapper(base) {
        val root = File(base.filesDir, "portable-reinstall281").apply { mkdirs() }
        override fun getApplicationContext(): Context = this
        override fun getFilesDir() = File(root, "files").apply { mkdirs() }
        override fun getNoBackupFilesDir() = File(root, "no-backup").apply { mkdirs() }
        override fun getSharedPreferences(name: String, mode: Int) = baseContext.getSharedPreferences("portable-reinstall281-$name", mode)
    }
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun marker(create: Boolean): Uri {
        val resolver = instrumentation.targetContext.contentResolver
        val tree = TestStorageProvider.exportTree
        val parent = Docs.buildDocumentUriUsingTree(tree, Docs.getTreeDocumentId(tree))
        val children = Docs.buildChildDocumentsUriUsingTree(parent, Docs.getDocumentId(parent))
        resolver.query(children, arrayOf(Docs.Document.COLUMN_DOCUMENT_ID, Docs.Document.COLUMN_DISPLAY_NAME), null, null, null)!!.use { cursor ->
            while (cursor.moveToNext()) if (cursor.getString(1) == "portable-reinstall281.json") return Docs.buildDocumentUriUsingTree(tree, cursor.getString(0))
        }
        check(create) { "Missing independent reinstall marker" }
        return Docs.createDocument(resolver, parent, "application/json", "portable-reinstall281.json")!!
    }
    private fun digest(uri: String): String = instrumentation.targetContext.contentResolver.openInputStream(Uri.parse(uri))!!.use {
        MessageDigest.getInstance("SHA-256").digest(it.readBytes()).joinToString("") { b -> "%02x".format(b.toInt() and 255) }
    }
    @Test fun producePrivateAndSafCachesBeforeActualUninstall() = runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("cacheReinstallPhase") == "producer")
        val context = Isolated(instrumentation.targetContext)
        val data = 32000; val wav = ByteBuffer.allocate(44 + data).order(ByteOrder.LITTLE_ENDIAN)
        wav.put("RIFF".toByteArray()).putInt(data + 36).put("WAVEfmt ".toByteArray()).putInt(16)
            .putShort(1).putShort(1).putInt(8000).putInt(16000).putShort(2).putShort(16).put("data".toByteArray()).putInt(data)
        val source = File(context.root, "source.wav").also { it.writeBytes(wav.array()) }.toURI().toString()
        context.contentResolver.call(Uri.parse("content://dev.petrov.ymplayer2.test.control"), "storage-reset", null, null)
        val store = LikedFileStore(context)
        val customOwner = OfflineOwner(UUID.randomUUID().toString(), "reinstall-account")
        val privateOwner = OfflineOwner(UUID.randomUUID().toString(), "private-account")
        val track = Track("yandex:1:7", "Reinstall fixture", "Artist", "Album", Source.YANDEX, 2, false)
        assertNotNull(store.sync(customOwner, track, { source }, { true }, {}).track)
        val private = store.sync(privateOwner, track, { source }, { true }, {}).track!!
        store.move(customOwner, TestStorageProvider.tree.toString(), null, null, { true }) { _, _ -> }
        val cached = store.catalog(customOwner).single()
        // Preserve the exact root from the binding, not a guessed provider document ID.
        val folder = store.locationIdentity(customOwner)!!
        val saved = JSONObject().put("uri", folder).put("sha256", digest(cached.uri!!)).put("private", private.uri)
            .put("oldProfile", customOwner.profileId).put("firstInstallTime", context.packageManager.getPackageInfo(context.packageName, 0).firstInstallTime).toString()
        context.contentResolver.openOutputStream(marker(true), "wt")!!.use { it.write(saved.toByteArray()) }
    }
    @Test fun restoreSafCacheWithNewIdentityAfterActualUninstall() = runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("cacheReinstallPhase") == "consumer")
        val saved = JSONObject(instrumentation.targetContext.contentResolver.openInputStream(marker(false))!!.bufferedReader().use { it.readText() })
        val context = Isolated(instrumentation.targetContext)
        assertFalse("Default cache must be removed by uninstall", File(Uri.parse(saved.getString("private")).path!!).exists())
        assertTrue(context.packageManager.getPackageInfo(context.packageName, 0).firstInstallTime > saved.getLong("firstInstallTime"))
        val owner = OfflineOwner(UUID.randomUUID().toString(), "reinstall-account")
        assertNotEquals(saved.getString("oldProfile"), owner.profileId)
        val store = LikedFileStore(context)
        val selected = store.inspectLocation(owner, saved.getString("uri")).single()
        assertFalse(selected.legacy)
        store.move(owner, saved.getString("uri"), selected, null, { true }) { _, _ -> }
        val track = store.catalog(owner).single()
        assertEquals(saved.getString("sha256"), digest(store.audio(owner, track.id)!!))
        store.sync(owner, track.copy(offline = false), { error("Reinstall must reuse the original audio") }, { true }, {})
    }
}
