package dev.petrov.ymplayer2

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.petrov.ymplayer2.core.*
import dev.petrov.ymplayer2.library.ListeningHistory
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ListeningHistoryTest {
    @Test fun profileIsolationBoundedOrderRestartAndMetadataOnly() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "history-test-${System.nanoTime()}.db"
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var time = 1000L
        val store = ListeningHistory(context, scope, name) { time++ }
        fun fixture(id: String, source: Source = Source.LOCAL) = Track(id, "Track $id", "Artist", "Album", source, 90, false,
            uri = "secret-stream-url", rootId = "old-grant", artists = listOf(ArtistRef("artist-id", "Artist")), albumId = "album-id")
        try {
            for (i in 0..504) { store.record("owner", fixture("$i")); store.page("owner", limit = 1) }
            store.record("road", fixture("road", Source.YANDEX)); store.page("road")
            val first = store.page("owner", limit = 80)
            assertEquals(500, first.total); assertEquals("504", first.items.first().track.id); assertTrue(first.hasMore)
            assertEquals("5", store.page("owner", offset = 499, limit = 1).items.single().track.id)
            store.record("owner", fixture("250", Source.USB))
            val repeated = store.page("owner", limit = 500)
            assertEquals(500, repeated.total); assertEquals("250", repeated.items.first().track.id)
            assertEquals(1, repeated.items.count { it.track.id == "250" })
            val restarted = ListeningHistory(context, scope, name)
            val track = restarted.page("owner").items.first().track
            assertEquals(Source.USB, track.source); assertNull(track.uri); assertNull(track.rootId); assertFalse(track.available)
            assertEquals("artist-id", track.artists.single().id); assertEquals("album-id", track.albumId)
            android.database.sqlite.SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY).use { db ->
                db.rawQuery("SELECT metadata FROM history", null).use { cursor -> while (cursor.moveToNext()) {
                    assertFalse(cursor.getString(0).contains("secret-stream-url")); assertFalse(cursor.getString(0).contains("old-grant"))
                } }
            }
            restarted.clear("owner")
            assertEquals(0, store.page("owner").total); assertEquals("road", store.page("road").items.single().track.id)
        } finally { scope.cancel() }
    }
}
