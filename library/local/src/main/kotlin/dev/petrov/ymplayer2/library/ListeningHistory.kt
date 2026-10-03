package dev.petrov.ymplayer2.library

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import dev.petrov.ymplayer2.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/** Separate additive database; a history failure cannot interrupt the audio service. */
class ListeningHistory(context: Context, private val scope: CoroutineScope,
    databaseName: String = "listening-history.db", private val now: () -> Long = System::currentTimeMillis) : ListeningLog {
    private val database = object : SQLiteOpenHelper(context.applicationContext, databaseName, null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE history (sequence INTEGER PRIMARY KEY AUTOINCREMENT, profile TEXT NOT NULL, track_id TEXT NOT NULL, played_at INTEGER NOT NULL, metadata TEXT NOT NULL, UNIQUE(profile,track_id))")
            db.execSQL("CREATE INDEX history_profile_order ON history(profile,sequence DESC)")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = error("Unsupported history version")
    }
    private val lock = Mutex()
    private val mutable = MutableStateFlow(ListeningLogState())
    override val state = mutable.asStateFlow()

    fun record(profileId: String, track: Track) {
        val time = now()
        scope.launch {
            try { lock.withLock { withContext(Dispatchers.IO) {
                val db = database.writableDatabase
                db.beginTransaction()
                try {
                    db.delete("history", "profile=? AND track_id=?", arrayOf(profileId, track.id))
                    db.insertOrThrow("history", null, ContentValues().apply {
                        put("profile", profileId); put("track_id", track.id); put("played_at", time)
                        put("metadata", metadata(track).toString())
                    })
                    db.execSQL("DELETE FROM history WHERE profile=? AND sequence NOT IN (SELECT sequence FROM history WHERE profile=? ORDER BY sequence DESC LIMIT 500)", arrayOf(profileId, profileId))
                    db.setTransactionSuccessful()
                } finally { db.endTransaction() }
                changed()
            } } } catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutable.value = mutable.value.copy(failed = true) }
        }
    }

    override suspend fun page(profileId: String, offset: Int, limit: Int): CatalogPage<ListeningEntry> {
        require(offset >= 0 && limit in 1..500)
        return lock.withLock { withContext(Dispatchers.IO) {
            val db = database.readableDatabase
            val total = db.rawQuery("SELECT COUNT(*) FROM history WHERE profile=?", arrayOf(profileId)).use { it.moveToFirst(); it.getInt(0) }
            val rows = db.rawQuery("SELECT metadata,played_at FROM history WHERE profile=? ORDER BY sequence DESC LIMIT ? OFFSET ?", arrayOf(profileId, limit.toString(), offset.toString())).use { cursor ->
                buildList { while (cursor.moveToNext()) add(ListeningEntry(track(JSONObject(cursor.getString(0))), cursor.getLong(1))) }
            }
            CatalogPage(rows, total, offset)
        } }
    }

    override suspend fun clear(profileId: String) = lock.withLock { withContext(Dispatchers.IO) {
        database.writableDatabase.delete("history", "profile=?", arrayOf(profileId)); changed()
    } }
    private fun changed() { mutable.value = ListeningLogState(mutable.value.revision + 1) }

    private fun metadata(track: Track) = JSONObject().apply {
        put("id", track.id); put("title", track.title); put("artist", track.artist); put("album", track.album)
        put("source", track.source.name); put("duration", track.durationSeconds); put("genre", track.genre); put("tint", track.tint)
        put("artwork", track.artworkUri); put("albumId", track.albumId)
        put("artists", JSONArray().apply { track.artists.forEach { put(JSONObject().put("id", it.id).put("name", it.name)) } })
    }
    private fun track(row: JSONObject): Track = Track(row.getString("id"), row.getString("title"), row.getString("artist"), row.getString("album"),
        Source.valueOf(row.getString("source")), row.getInt("duration"), offline = false, available = false,
        genre = row.optString("genre"), tint = row.optInt("tint"), artworkUri = row.optString("artwork").takeIf(String::isNotEmpty),
        artists = row.optJSONArray("artists")?.let { artists -> (0 until artists.length()).map { artists.getJSONObject(it).let { artist -> ArtistRef(artist.getString("id"), artist.getString("name")) } } }.orEmpty(),
        albumId = row.optString("albumId").takeIf(String::isNotEmpty))
}
