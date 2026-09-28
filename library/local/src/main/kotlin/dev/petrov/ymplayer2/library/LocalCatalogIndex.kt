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

    fun orderedTrackIds(source: Source?): List<String> {
        val where = if (source == null) "" else " WHERE source=?"
        val args = if (source == null) null else arrayOf(source.name)
        return readableDatabase.rawQuery("SELECT id FROM tracks$where ORDER BY title_key,id", args).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }
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
