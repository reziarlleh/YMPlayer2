package dev.petrov.ymplayer2

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.petrov.ymplayer2.core.CatalogFilter
import dev.petrov.ymplayer2.library.SafLibrary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class LocalIndexMigrationTest {
    @Test fun oldJsonImportsOnceAndKeepsStableIdAcrossRestart() = runBlocking {
        // Device-protected storage isolates the migration fixture from the running app's library.
        val context = InstrumentationRegistry.getInstrumentation().targetContext.createDeviceProtectedStorageContext()
        context.deleteDatabase("local-catalog.db")
        val oldFile = File(context.filesDir, "local-library.json")
        val track = JSONObject().put("id", "local:stable-id").put("title", "Прежняя запись")
            .put("artist", "Исполнитель").put("album", "Альбом").put("source", "USB")
            .put("duration", 19).put("genre", "Рок").put("folder", "USB / Музыка")
            .put("tint", 2).put("uri", "content://fixture/audio").put("root", "content://fixture/tree")
            .put("size", 100L).put("modified", 7L).put("artwork", "").put("artworkChecked", true)
        oldFile.writeText(JSONObject().put("roots", JSONArray()).put("tracks", JSONArray().put(track)).toString())
        val firstScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val secondScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val first = SafLibrary(context, firstScope)
            withTimeout(10_000) { first.state.first { it.ready } }
            val imported = first.pageTracks(CatalogFilter())
            assertEquals(1, imported.total)
            assertEquals("local:stable-id", imported.items.single().id)
            assertFalse(imported.items.single().available)
            assertEquals(setOf("local:stable-id"), first.tracksByIds(listOf("missing", "local:stable-id", "local:stable-id")).keys)
            assertEquals(setOf("local:stable-id"), first.tracksByIds((0..450).map { "missing:$it" } + "local:stable-id").keys)
            assertTrue(first.pageTracks(CatalogFilter(), offset = 50_000, limit = 80).items.isEmpty())

            // A stale legacy file must never replace the committed SQLite catalog.
            oldFile.writeText(JSONObject().put("roots", JSONArray()).put("tracks", JSONArray()).toString())
            val second = SafLibrary(context, secondScope)
            withTimeout(10_000) { second.state.first { it.ready } }
            assertEquals("local:stable-id", second.pageTracks(CatalogFilter()).items.single().id)
        } finally {
            firstScope.cancel(); secondScope.cancel()
            oldFile.delete()
            context.deleteDatabase("local-catalog.db")
        }
    }
}
