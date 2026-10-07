@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
package dev.petrov.ymplayer2

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.petrov.ymplayer2.core.*
import dev.petrov.ymplayer2.library.ArtworkCache
import dev.petrov.ymplayer2.library.LocalCatalogIndex
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecentCatalogTest {
    @Test fun versionThreeTagMigrationInvalidatesCacheWithoutGuessingRealNamesOrChangingDates() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "tags-test-${System.nanoTime()}.db"
        val artwork = ArtworkCache(context.cacheDir.resolve("tags-test-artwork"))
        val root = LibraryRoot("root", "Music", Source.USB)
        val real = Track("real", "Real", "Неизвестный исполнитель", "Без альбома", Source.USB, 30, true,
            genre = "Без жанра", uri = "content://real", rootId = root.uri)
        var index = LocalCatalogIndex(context, artwork, name) { 1234L }
        try {
            index.replaceRoot(root, listOf(real), setOf(real.id)); index.close()
            SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use { it.version = 3 }
            index = LocalCatalogIndex(context, artwork, name) { 9000L }
            val checked = mutableSetOf<String>()
            assertEquals(real.artist, index.cachedTrack(real.id, checked)!!.artist)
            assertTrue("Tags must be re-read from media, not guessed from Russian text", checked.isEmpty())
            assertEquals(1234L, index.readableDatabase.rawQuery("SELECT added_at FROM tracks WHERE id='real'", null).use { it.moveToFirst(); it.getLong(0) })
            index.replaceRoot(root, listOf(real.copy(artist = "", album = "", genre = "")), setOf(real.id))
            assertEquals("", index.tracksByIds(listOf(real.id)).getValue(real.id).artist)
            assertEquals(1234L, index.readableDatabase.rawQuery("SELECT added_at FROM tracks WHERE id='real'", null).use { it.moveToFirst(); it.getLong(0) })
        } finally { index.close(); context.deleteDatabase(name) }
    }
    @Test fun versionTwoMigrationRescansUnavailableAndRestartPreserveFirstAppearance() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "recent-test-${System.nanoTime()}.db"
        val path = context.getDatabasePath(name).apply { parentFile!!.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
            db.execSQL("CREATE TABLE meta (key TEXT PRIMARY KEY,value TEXT NOT NULL)")
            db.execSQL("INSERT INTO meta VALUES('initialized','1')")
            db.execSQL("CREATE TABLE roots (uri TEXT PRIMARY KEY,name TEXT NOT NULL,source TEXT NOT NULL)")
            db.execSQL("CREATE TABLE tracks (id TEXT PRIMARY KEY,title TEXT NOT NULL,artist TEXT NOT NULL,album TEXT NOT NULL,source TEXT NOT NULL,duration INTEGER NOT NULL,available INTEGER NOT NULL,genre TEXT NOT NULL,folder TEXT NOT NULL,tint INTEGER NOT NULL,uri TEXT NOT NULL,root TEXT NOT NULL,size INTEGER NOT NULL,modified INTEGER NOT NULL,artwork TEXT,artwork_checked INTEGER NOT NULL,title_key TEXT NOT NULL,artist_key TEXT NOT NULL,album_key TEXT NOT NULL)")
            db.execSQL("INSERT INTO roots VALUES('root','Music','LOCAL')")
            db.execSQL("INSERT INTO tracks VALUES('old','Old','Artist','Album','LOCAL',90,1,'Genre','Music',0,'content://old','root',1,999999,NULL,0,'old','artist','album')")
            db.version = 2
        }
        var time = 1000L
        val artwork = ArtworkCache(context.cacheDir.resolve("recent-test-artwork"))
        var index = LocalCatalogIndex(context, artwork, name) { time }
        val root = LibraryRoot("root", "Music", Source.LOCAL)
        fun track(id: String, source: Source = Source.LOCAL, rootId: String = "root") = Track(id, id, "Artist", "Album", source, 90, true,
            uri = "content://$id", rootId = rootId, modifiedMillis = 999999999)
        try {
            assertTrue(index.initialized())
            assertEquals("Old", index.tracksByIds(listOf("old")).getValue("old").title)
            assertEquals(0, index.pageRecentTracks(CatalogFilter(), 0, 80).total)
            index.replaceRoot(root, listOf(track("old"), track("a")), emptySet())
            time = 2000
            index.replaceRoot(root, listOf(track("old").copy(modifiedMillis = 5), track("a"), track("b")), emptySet())
            assertEquals(listOf("b", "a"), index.pageRecentTracks(CatalogFilter(), 0, 80).items.map { it.id })
            val usb = LibraryRoot("usb-root", "USB", Source.USB)
            time = 3000
            index.replaceRoot(usb, (0..84).map { track("usb-$it", Source.USB, usb.uri) }, emptySet())
            val first = index.pageRecentTracks(CatalogFilter(), 0, 80)
            val second = index.pageRecentTracks(CatalogFilter(), 80, 80)
            assertEquals(87, first.total); assertTrue(first.hasMore); assertFalse(second.hasMore)
            assertEquals(87, (first.items + second.items).map { it.id }.distinct().size)
            assertEquals(85, index.pageRecentTracks(CatalogFilter(source = Source.USB), 0, 80).total)
            assertEquals(listOf("b", "a"), index.pageRecentTracks(CatalogFilter(source = Source.LOCAL), 0, 80).items.map { it.id })
            assertEquals(1, index.pageRecentTracks(CatalogFilter(query = "usb-84"), 0, 80).total)
            index.markRootUnavailable(usb)
            assertEquals(87, index.pageRecentTracks(CatalogFilter(), 0, 80).total)
            assertEquals(2, index.pageRecentTracks(CatalogFilter(availableOnly = true), 0, 80).total)
            time = 9000
            index.replaceRoot(root, listOf(track("a"), track("b"), track("old")), emptySet())
            index.close()
            index = LocalCatalogIndex(context, artwork, name) { time }
            assertEquals(listOf("b", "a"), index.pageRecentTracks(CatalogFilter(source = Source.LOCAL), 0, 80).items.map { it.id })
            assertEquals(3, index.pageTracks(CatalogFilter(source = Source.LOCAL), false, null, CatalogDimension.TRACKS, 0, 80).total)
            index.forgetRoot(usb.uri)
            assertEquals(2, index.pageRecentTracks(CatalogFilter(), 0, 80).total)
        } finally { index.close(); context.deleteDatabase(name) }
    }

    @Test fun legacyJsonImportDoesNotBecomeNewWhenRescanned() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "recent-legacy-${System.nanoTime()}.db"
        val index = LocalCatalogIndex(context, ArtworkCache(context.cacheDir.resolve("recent-test-artwork")), name) { 9999L }
        val root = LibraryRoot("root", "Music", Source.LOCAL)
        val old = Track("old", "Old", "Artist", "Album", Source.LOCAL, 90, true, uri = "content://old", rootId = root.uri)
        try {
            index.replace(LibrarySnapshot(listOf(root), listOf(old)), emptySet())
            index.replaceRoot(root, listOf(old), emptySet())
            assertEquals(0, index.pageRecentTracks(CatalogFilter(), 0, 80).total)
            assertEquals(1, index.catalogTrackIds(null).size)
        } finally { index.close(); context.deleteDatabase(name) }
    }
}
