package dev.petrov.ymplayer2.library

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import dev.petrov.ymplayer2.core.*

/** Transactional metadata index. Document URIs point to the original media; audio is never copied. */
internal class LocalCatalogIndex(context: Context, private val artwork: ArtworkCache) :
    SQLiteOpenHelper(context, "local-catalog.db", null, 2) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
        db.execSQL("CREATE TABLE roots (uri TEXT PRIMARY KEY, name TEXT NOT NULL, source TEXT NOT NULL)")
        db.execSQL("""CREATE TABLE tracks (
            id TEXT PRIMARY KEY, title TEXT NOT NULL, artist TEXT NOT NULL, album TEXT NOT NULL,
            source TEXT NOT NULL, duration INTEGER NOT NULL, available INTEGER NOT NULL,
            genre TEXT NOT NULL, folder TEXT NOT NULL, tint INTEGER NOT NULL,
            uri TEXT NOT NULL, root TEXT NOT NULL, size INTEGER NOT NULL, modified INTEGER NOT NULL,
            artwork TEXT, artwork_checked INTEGER NOT NULL,
            title_key TEXT NOT NULL, artist_key TEXT NOT NULL, album_key TEXT NOT NULL
        )""")
        db.execSQL("CREATE INDEX tracks_order ON tracks(title_key, id)")
        db.execSQL("CREATE INDEX tracks_source_order ON tracks(source, title_key, id)")
        db.execSQL("CREATE INDEX tracks_root ON tracks(root)")
        db.execSQL("CREATE INDEX tracks_album ON tracks(album)")
        db.execSQL("CREATE INDEX tracks_artist ON tracks(artist)")
        db.execSQL("CREATE INDEX tracks_genre ON tracks(genre)")
        db.execSQL("CREATE INDEX tracks_folder ON tracks(folder)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion == 1 && newVersion == 2) {
            db.execSQL("CREATE INDEX tracks_source_order ON tracks(source, title_key, id)")
            return
        }
        error("Unsupported local catalog schema $oldVersion → $newVersion")
    }

    fun initialized(): Boolean = readableDatabase.rawQuery("SELECT value FROM meta WHERE key='initialized'", null).use { it.moveToFirst() }

    /** Replace only after every SAF root was scanned or its previous unavailable rows were retained. */
    fun replace(snapshot: LibrarySnapshot, artworkChecked: Set<String>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("tracks", null, null)
            db.delete("roots", null, null)
            snapshot.roots.forEach { root ->
                db.insertOrThrow("roots", null, ContentValues().apply {
                    put("uri", root.uri); put("name", root.name); put("source", root.source.name)
                })
            }
            snapshot.tracks.forEach { track ->
                db.insertOrThrow("tracks", null, ContentValues().apply {
                    put("id", track.id); put("title", track.title); put("artist", track.artist); put("album", track.album)
                    put("source", track.source.name); put("duration", track.durationSeconds)
                    put("available", if (track.available) 1 else 0)
                    put("genre", track.genre); put("folder", track.folder); put("tint", track.tint)
                    put("uri", requireNotNull(track.uri)); put("root", requireNotNull(track.rootId))
                    put("size", track.sizeBytes); put("modified", track.modifiedMillis)
                    put("artwork", track.artworkUri?.let { Uri.parse(it).lastPathSegment })
                    put("artwork_checked", if (track.id in artworkChecked) 1 else 0)
                    put("title_key", track.title.lowercase()); put("artist_key", track.artist.lowercase())
                    put("album_key", track.album.lowercase())
                })
            }
            db.execSQL("INSERT OR REPLACE INTO meta(key,value) VALUES('initialized','1')")
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    /** SAF availability is unknown after process restart until the grant and root are scanned. */
    fun markUnavailable() { writableDatabase.execSQL("UPDATE tracks SET available=0") }

    fun playableTrackIds(source: Source?): List<String> = readableDatabase.rawQuery(
        "SELECT id FROM tracks WHERE available=1${if (source == null) "" else " AND source=?"} ORDER BY title_key,id",
        if (source == null) emptyArray() else arrayOf(source.name),
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }

    fun adjacentTrack(currentId: String?, direction: Int, source: Source?, wrap: Boolean): Track? {
        require(direction == -1 || direction == 1)
        val db = readableDatabase
        val key = currentId?.let { id -> db.rawQuery("SELECT title_key,id FROM tracks WHERE id=?", arrayOf(id)).use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) to cursor.getString(1) else null
        } }
        val sourceClause = if (source == null) "" else " AND source=?"
        val sign = if (direction > 0) ">" else "<"
        val order = if (direction > 0) "ASC" else "DESC"
        fun find(after: Pair<String, String>?): Track? {
            val boundary = if (after == null) "" else " AND (title_key $sign ? OR (title_key=? AND id $sign ?))"
            val args = buildList {
                if (source != null) add(source.name)
                if (after != null) { add(after.first); add(after.first); add(after.second) }
            }.toTypedArray()
            return db.rawQuery("SELECT $trackColumns FROM tracks WHERE available=1$sourceClause$boundary ORDER BY title_key $order,id $order LIMIT 1", args).use { cursor ->
                if (cursor.moveToFirst()) track(cursor) else null
            }
        }
        return find(key) ?: if (wrap && key != null) find(null) else null
    }

    fun playbackWindow(currentId: String?, source: Source?): LocalPlaybackWindow? {
        val db = readableDatabase
        val sourceArgs = if (source == null) emptyArray() else arrayOf(source.name)
        val sourceWhere = if (source == null) "" else " WHERE source=?"
        val sourceAnd = if (source == null) "" else " AND source=?"
        fun anchor(sql: String, args: Array<String>): Pair<Track, String>? = db.rawQuery(sql, args).use { cursor ->
            if (cursor.moveToFirst()) track(cursor) to cursor.getString(16) else null
        }
        fun rows(sql: String, args: Array<String>): List<Track> = db.rawQuery(sql, args).use { cursor ->
            buildList { while (cursor.moveToNext()) add(track(cursor)) }
        }

        // These reads describe one catalog revision even if a SAF scan commits concurrently.
        db.beginTransactionNonExclusive()
        try {
            val selected = (if (currentId != null) anchor(
                "SELECT $trackColumns,title_key FROM tracks WHERE id=?$sourceAnd", arrayOf(currentId) + sourceArgs,
            ) else anchor(
                "SELECT $trackColumns,title_key FROM tracks WHERE available=1$sourceAnd ORDER BY title_key,id LIMIT 1", sourceArgs,
            ) ?: anchor(
                "SELECT $trackColumns,title_key FROM tracks$sourceWhere ORDER BY title_key,id LIMIT 1", sourceArgs,
            )) ?: return null
            val (current, key) = selected
            val boundary = arrayOf(key, key, current.id)
            val rank = db.rawQuery("SELECT COUNT(*) FROM tracks WHERE ${if (source == null) "" else "source=? AND "}" +
                "(title_key<? OR (title_key=? AND id<?))", sourceArgs + boundary).use {
                it.moveToFirst(); it.getInt(0)
            }
            val total = db.rawQuery("SELECT COUNT(*) FROM tracks$sourceWhere", sourceArgs).use {
                it.moveToFirst(); it.getInt(0)
            }
            val offset = (rank - 4).coerceAtLeast(0)
            val visible = rows("SELECT $trackColumns FROM tracks$sourceWhere ORDER BY title_key,id LIMIT 29 OFFSET ?",
                sourceArgs + arrayOf(offset.toString()))
            val previous = rows("SELECT $trackColumns FROM tracks WHERE available=1$sourceAnd AND " +
                "(title_key<? OR (title_key=? AND id<?)) ORDER BY title_key DESC,id DESC LIMIT 4",
                sourceArgs + boundary).asReversed()
            val upcoming = rows("SELECT $trackColumns FROM tracks WHERE available=1$sourceAnd AND " +
                "(title_key>? OR (title_key=? AND id>?)) ORDER BY title_key,id LIMIT ?",
                sourceArgs + boundary + arrayOf(if (current.available) "24" else "25"))
            db.setTransactionSuccessful()
            return LocalPlaybackWindow(current, rank, CatalogPage(visible, total, offset),
                previous + listOfNotNull(current.takeIf(Track::available)) + upcoming)
        } finally { db.endTransaction() }
    }

    fun tracksByIds(ids: Collection<String>): Map<String, Track> {
        if (ids.isEmpty()) return emptyMap()
        val db = readableDatabase
        return buildMap {
            ids.distinct().chunked(400).forEach { batch ->
                val placeholders = List(batch.size) { "?" }.joinToString(",")
                db.rawQuery("SELECT $trackColumns FROM tracks WHERE id IN ($placeholders)", batch.toTypedArray()).use { cursor ->
                    while (cursor.moveToNext()) track(cursor).let { put(it.id, it) }
                }
            }
        }
    }

    fun read(artworkChecked: MutableSet<String>): LibrarySnapshot {
        val db = readableDatabase
        val roots = db.rawQuery("SELECT uri,name,source FROM roots ORDER BY rowid", null).use { cursor ->
            buildList { while (cursor.moveToNext()) add(LibraryRoot(cursor.getString(0), cursor.getString(1), Source.valueOf(cursor.getString(2)))) }
        }
        val tracks = db.rawQuery("SELECT $trackColumns FROM tracks ORDER BY title_key,id", null).use { cursor ->
            buildList { while (cursor.moveToNext()) add(track(cursor, artworkChecked)) }
        }
        return LibrarySnapshot(roots, tracks)
    }

    fun pageTracks(filter: CatalogFilter, descending: Boolean, group: String?, dimension: CatalogDimension,
        offset: Int, limit: Int): CatalogPage<Track> {
        validate(offset, limit)
        val (where, args) = condition(filter, group, dimension)
        val db = readableDatabase
        val total = db.rawQuery("SELECT COUNT(*) FROM tracks$where", args).use { it.moveToFirst(); it.getInt(0) }
        val direction = if (descending) "DESC" else "ASC"
        val page = db.rawQuery("SELECT $trackColumns FROM tracks$where ORDER BY title_key $direction,id $direction LIMIT ? OFFSET ?",
            args + arrayOf(limit.toString(), offset.toString())).use { cursor ->
            buildList { while (cursor.moveToNext()) add(track(cursor)) }
        }
        return CatalogPage(page, total, offset)
    }

    fun pageGroups(filter: CatalogFilter, dimension: CatalogDimension, descending: Boolean,
        offset: Int, limit: Int): CatalogPage<CatalogGroup> {
        validate(offset, limit)
        val column = groupColumn(dimension)
        val (where, args) = condition(filter, null, dimension)
        val db = readableDatabase
        val total = db.rawQuery("SELECT COUNT(DISTINCT $column) FROM tracks$where", args).use { it.moveToFirst(); it.getInt(0) }
        val direction = if (descending) "DESC" else "ASC"
        // One correlated lookup per group stays in SQLite. The old implementation ran
        // pageTracks (COUNT + SELECT) for every visible group on the UI request path.
        val (sampleWhere, sampleArgs) = condition(filter, null, dimension, "sample.")
        val sampleFilter = if (sampleWhere.isEmpty()) "" else " AND ${sampleWhere.removePrefix(" WHERE ")}"
        val rows = db.rawQuery("""SELECT $column,COUNT(*),
            (SELECT sample.id FROM tracks AS sample WHERE sample.$column=tracks.$column$sampleFilter
                ORDER BY sample.title_key,sample.id LIMIT 1)
            FROM tracks$where GROUP BY $column
            ORDER BY $column COLLATE NOCASE $direction,$column $direction LIMIT ? OFFSET ?""",
            sampleArgs + args + arrayOf(limit.toString(), offset.toString())).use { cursor ->
            buildList { while (cursor.moveToNext()) add(Triple(cursor.getString(0), cursor.getInt(1), cursor.getString(2))) }
        }
        val samples = tracksByIds(rows.map { it.third })
        return CatalogPage(rows.map { (name, count, id) -> CatalogGroup(name, count, samples.getValue(id)) }, total, offset)
    }

    private fun condition(filter: CatalogFilter, group: String?, dimension: CatalogDimension, prefix: String = ""): Pair<String, Array<String>> {
        val clauses = mutableListOf<String>()
        val args = mutableListOf<String>()
        filter.source?.let { clauses += "${prefix}source=?"; args += it.name }
        if (filter.availableOnly || filter.offlineOnly) clauses += "${prefix}available=1"
        val query = filter.query.trim().lowercase()
        if (query.isNotEmpty()) {
            clauses += "(instr(${prefix}title_key,?)>0 OR instr(${prefix}artist_key,?)>0 OR instr(${prefix}album_key,?)>0)"
            repeat(3) { args += query }
        }
        if (group != null) { clauses += "${prefix}${groupColumn(dimension)}=?"; args += group }
        return (if (clauses.isEmpty()) "" else " WHERE ${clauses.joinToString(" AND ")}") to args.toTypedArray()
    }

    private fun groupColumn(dimension: CatalogDimension) = when (dimension) {
        CatalogDimension.ALBUMS -> "album"
        CatalogDimension.ARTISTS -> "artist"
        CatalogDimension.GENRES -> "genre"
        CatalogDimension.FOLDERS -> "folder"
        CatalogDimension.TRACKS -> error("Tracks have no group column")
    }

    private fun track(cursor: Cursor, checked: MutableSet<String>? = null): Track {
        val id = cursor.getString(0)
        if (cursor.getInt(15) != 0) checked?.add(id)
        return Track(id, cursor.getString(1), cursor.getString(2), cursor.getString(3),
            Source.valueOf(cursor.getString(4)), cursor.getInt(5), true, cursor.getInt(6) != 0,
            cursor.getString(7), cursor.getString(8), cursor.getInt(9), cursor.getString(10),
            cursor.getString(11), cursor.getLong(12), cursor.getLong(13), cursor.getString(14)?.let(artwork::uri))
    }

    private fun validate(offset: Int, limit: Int) { require(offset >= 0 && limit in 1..50_000 && offset <= Int.MAX_VALUE - limit) }

    private companion object {
        const val trackColumns = "id,title,artist,album,source,duration,available,genre,folder,tint,uri,root,size,modified,artwork,artwork_checked"
    }
}
