package music.tune.android

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal data class ListeningSummary(
    val duration: Long,
    val count: Int,
    val songs: List<Pair<Track, Int>>,
    val artists: List<Pair<Card, Int>>,
)

private data class ArtistBucket(
    var card: Card,
    var plays: Int = 0,
    var soloCover: String? = null,
    var anyCover: String? = null,
)

internal fun summarizeListening(records: Sequence<Pair<Track?, Long>>): ListeningSummary {
    var duration = 0L
    var count = 0
    val songs = linkedMapOf<String, Pair<Track, Int>>()
    for ((track, played) in records) {
        duration += played
        count++
        if (track == null) continue
        val previous = songs[track.id]
        songs[track.id] = (previous?.first ?: track) to ((previous?.second ?: 0) + 1)
    }
    val ordered = songs.values.sortedByDescending { it.second }
    val artists = linkedMapOf<String, ArtistBucket>()
    for ((track, plays) in ordered) {
        val unique = track.artists.distinctBy { it.id.ifBlank { it.title } }
        val solo = unique.size == 1
        for (artist in unique) {
            val key = artist.id.ifBlank { artist.title }
            val bucket = artists.getOrPut(key) { ArtistBucket(artist) }
            bucket.plays += plays
            if (bucket.card.cover == null && artist.cover != null) bucket.card = bucket.card.copy(cover = artist.cover)
            if (solo && bucket.soloCover == null && track.cover != null) bucket.soloCover = track.cover
            if (bucket.anyCover == null && track.cover != null) bucket.anyCover = track.cover
        }
    }
    val topArtists = artists.values.sortedByDescending { it.plays }.take(12).map { bucket ->
        bucket.card.copy(cover = bucket.card.cover ?: bucket.soloCover ?: bucket.anyCover) to bucket.plays
    }
    return ListeningSummary(duration, count, ordered.take(20), topArtists)
}

internal class ListeningHistory(context: Context) : SQLiteOpenHelper(context, "listening.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE listens (id INTEGER PRIMARY KEY, account TEXT NOT NULL, track_id TEXT NOT NULL, track TEXT NOT NULL, played INTEGER NOT NULL, timestamp INTEGER NOT NULL, wave INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX listens_by_account ON listens (account, timestamp)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    suspend fun add(uid: String, track: Track, duration: Long, wave: Boolean) = withContext(Dispatchers.IO) {
        if (duration < 20_000) return@withContext
        val db = writableDatabase
        db.beginTransaction()
        try {
            val account = digest(uid)
            db.insertOrThrow("listens", null, ContentValues().apply {
                put("account", account)
                put("track_id", track.id)
                put("track", track.stored().toString())
                put("played", duration)
                put("timestamp", System.currentTimeMillis())
                put("wave", if (wave) 1 else 0)
            })
            db.execSQL("DELETE FROM listens WHERE account = ? AND id < (SELECT id FROM listens WHERE account = ? ORDER BY id DESC LIMIT 1 OFFSET 19999)",
                arrayOf(account, account))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    suspend fun clear(uid: String) = withContext(Dispatchers.IO) {
        writableDatabase.delete("listens", "account = ?", arrayOf(digest(uid)))
    }

    suspend fun recentWave(uid: String, artist: Card, fromCatalog: Set<String>): List<Track> = withContext(Dispatchers.IO) {
        val out = linkedMapOf<String, Track>()
        readableDatabase.rawQuery("SELECT track FROM listens WHERE account = ? AND wave = 1 ORDER BY timestamp DESC",
            arrayOf(digest(uid))).use { cursor ->
            while (out.size < 100 && cursor.moveToNext()) {
                val track = optional { Track.parse(JSONObject(cursor.getString(0))) } ?: continue
                if (track.id in fromCatalog || track.artists.any { it.id == artist.id }) out.putIfAbsent(track.id, track)
            }
        }
        out.values.toList()
    }

    suspend fun summary(uid: String, days: Int?): ListeningSummary = withContext(Dispatchers.IO) {
        val since = days?.let { System.currentTimeMillis() - it * 86_400_000L } ?: 0L
        readableDatabase.rawQuery("SELECT track, played FROM listens WHERE account = ? AND timestamp >= ? ORDER BY timestamp DESC",
            arrayOf(digest(uid), since.toString())).use { cursor ->
            summarizeListening(sequence {
                while (cursor.moveToNext()) {
                    val track = optional { Track.parse(JSONObject(cursor.getString(0))) }
                    yield(track to cursor.getLong(1))
                }
            })
        }
    }
}
