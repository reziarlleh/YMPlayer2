package dev.petrov.ymplayer2

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.petrov.ymplayer2.core.*
import dev.petrov.ymplayer2.offline.LikedFileStore
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException

/** A new store on each read, hundreds of real SAF entries, and closed provider gates.
 * No prior audio proof or ready OfflineMusic instance can hide a cold enumeration. */
@RunWith(AndroidJUnit4::class)
class OfflineColdCatalogTest {
    private val owner = OfflineOwner("cold", "account")
    private val count = 400
    private lateinit var context: Context
    private val uri = Uri.parse("content://dev.petrov.ymplayer2.test.control")
    private fun control(method: String, arg: String? = null, extras: Bundle? = null) =
        context.contentResolver.call(uri, method, arg, extras) ?: Bundle.EMPTY
    private fun stats() = control("storage-catalog-stats")
    private fun seedExtras() = Bundle().apply { putString("profile", owner.profileId); putString("account", owner.accountId); putInt("count", count) }
    @Before fun setup() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        context = object : ContextWrapper(app) {
            override fun getFilesDir() = File(app.filesDir, "cold-catalog-fixture").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int) = app.getSharedPreferences("cold-catalog-fixture-$name", mode)
        }
        context.filesDir.deleteRecursively(); context.filesDir.mkdirs()
        context.getSharedPreferences("offline-location",0).edit().clear().putString("uri", TestStorageProvider.tree.toString()).commit()
        control("storage-reset"); control("storage-seed-catalog", extras = seedExtras())
        control("storage-catalog-counters-reset")
    }
    @After fun cleanup() { control("storage-catalog-gate", "open"); control("storage-unavailable", "false"); control("storage-catalog-unknown-mtime","false"); control("storage-catalog-constant-mtime","false") }
    private fun store() = LikedFileStore(context)
    private fun metric(label: String, start: Long, size: Int) {
        val b=stats()
        InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply {
            putString("coldCatalogMetric", "$label tracks=$size elapsedMs=${android.os.SystemClock.elapsedRealtime()-start} fileQueries=${b.getInt("fileQueries")} childQueries=${b.getInt("children")} jsonOpens=${b.getInt("jsonOpens")}")
        })
    }
    private suspend fun gatedRead(gate: String): List<Track> = coroutineScope {
        control("storage-catalog-counters-reset"); control("storage-catalog-gate", gate)
        val start=android.os.SystemClock.elapsedRealtime()
        val read = async(Dispatchers.IO) { store().catalog(owner) }
        try {
            withTimeoutOrNull(4_000) { read.join() }
            assertTrue("Cold catalog blocked on $gate: ${stats()}", read.isCompleted)
            read.await().also { metric("gate-$gate", start, it.size) }
        } finally {
            control("storage-catalog-gate", "open")
            withContext(NonCancellable) { withTimeout(20_000) { read.cancelAndJoin() } }
        }
    }
    @Test fun legacyColdCatalogUsesBulkAttributesWithoutIndividualStatQueries() = runBlocking {
        val tracks = gatedRead("query")
        assertEquals(count, tracks.size)
        assertEquals("Bulk catalog must not stat every metadata/audio/cover individually", 0, stats().getInt("fileQueries"))
        assertEquals(count, stats().getInt("jsonOpens"))
        assertEquals(count / 2, tracks.count { it.artworkUri != null })
    }
    @Test fun aNewStoreReusesPersistedMetadataWithoutOpeningEveryJsonAgain() = runBlocking {
        assertEquals(count, store().catalog(owner).size)
        val tracks = gatedRead("json")
        assertEquals(count, tracks.size); assertEquals(0, stats().getInt("jsonOpens"))
        assertEquals(0, stats().getInt("fileQueries"))
    }
    @Test fun freshInventoryDetectsRemovedAudioAndOnlyRereadsChangedMetadata() = runBlocking {
        store().catalog(owner)
        control("storage-edit-catalog", extras = seedExtras().apply { putString("id","seed1") })
        control("storage-edit-catalog", extras = seedExtras().apply { putString("id","seed2"); putString("action","removeAudio") })
        control("storage-catalog-counters-reset")
        val tracks = store().catalog(owner)
        assertEquals(count - 1, tracks.size)
        assertEquals("Changed title with a different metadata length", tracks.single { it.id == "yandex:seed1:7" }.title)
        assertFalse(tracks.any { it.id == "yandex:seed2:7" })
        assertEquals(1, stats().getInt("jsonOpens"))
    }
    @Test fun appWriteInvalidatesOnlyOneIndexEntryEvenWithSameSizeAndCoarseTimestamp() = runBlocking {
        control("storage-catalog-constant-mtime","true")
        val store = store(); val before = store.catalog(owner)
        val changed = before.single { it.id == "yandex:seed1:7" }.copy(title = "Cached SONG 1")
        store.sync(owner, changed, { throw IOException("No network in cold fixture") }, { true }, { throw IOException("No downloads in cold fixture") })
        control("storage-catalog-counters-reset")
        val after = store().catalog(owner)
        assertEquals("Cached SONG 1", after.single { it.id == changed.id }.title)
        assertEquals("Changing one like must preserve all other indexed metadata",1, stats().getInt("jsonOpens"))
    }
    @Test fun unknownTimestampsAndCorruptPrivateIndexFallBackToFreshMetadata() = runBlocking {
        store().catalog(owner)
        val files = context.filesDir.walkTopDown().filter { it.isFile && it.extension == "json" }.toList()
        assertTrue("A catalog index must survive a new store/process",files.isNotEmpty())
        files.forEach { it.writeText("{") }
        control("storage-catalog-counters-reset")
        assertEquals(count, store().catalog(owner).size); assertEquals(count, stats().getInt("jsonOpens"))
        control("storage-catalog-unknown-mtime","true"); control("storage-catalog-counters-reset")
        assertEquals(count, store().catalog(owner).size); assertEquals(count, stats().getInt("jsonOpens"))
    }
    @Test fun privateIndexCannotBypassStorageAccessOrLeakAnotherOwnerOrLocation() = runBlocking {
        store().catalog(owner)
        assertTrue(store().catalog(OfflineOwner("other","account")).isEmpty())
        context.getSharedPreferences("offline-location",0).edit().putString("uri",TestStorageProvider.exportTree.toString()).commit()
        assertTrue(store().catalog(owner).isEmpty())
        context.getSharedPreferences("offline-location",0).edit().putString("uri",TestStorageProvider.tree.toString()).commit()
        control("storage-unavailable","true")
        assertTrue("Cached metadata must not grant access to a disconnected provider",runCatching { store().catalog(owner) }.isFailure)
    }
    @Test fun reportFreshStoreFirstAndIndexedColdReadCosts() = runBlocking {
        repeat(2) { pass ->
            control("storage-catalog-counters-reset")
            val start=android.os.SystemClock.elapsedRealtime(); val tracks=store().catalog(owner)
            assertEquals(count,tracks.size); metric("fresh-store-$pass",start,tracks.size)
        }
    }
}
