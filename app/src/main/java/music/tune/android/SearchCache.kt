package music.tune.android

import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

internal class SearchCache(cacheDir: File) {
    private val directory = File(cacheDir, "search")

    internal data class Key(val account: String, val query: String, val type: String, val uncensored: Boolean) {
        fun fileName() = digest(JSONArray(listOf(account, query.lowercase(Locale.ROOT), type, if (uncensored) "v2" else false)).toString()) + ".json"
    }

    @Synchronized
    fun read(key: Key): SearchResults? {
        val file = File(directory, key.fileName())
        return optional { decode(JSONObject(AtomicFile(file).readFully().toString(Charsets.UTF_8))) }
            ?.also { file.setLastModified(System.currentTimeMillis()) }
    }

    @Synchronized
    fun write(key: Key, results: SearchResults) {
        if (!directory.isDirectory && !directory.mkdirs()) return
        optional { AtomicFile(File(directory, key.fileName())).writeText(encode(results).toString()) }
        directory.listFiles { file -> file.extension == "json" }.orEmpty()
            .sortedByDescending(File::lastModified).drop(40).forEach(File::delete)
    }

    companion object {
        internal fun encode(results: SearchResults): JSONObject {
            val best = when (val item = results.best) {
                is SearchBest.Song -> JSONObject().put("track", item.track.stored())
                is SearchBest.Entry -> JSONObject().put("card", item.card.cached())
                null -> null
            }
            return JSONObject().put("tracks", JSONArray(results.tracks.map(Track::stored)))
                .put("cards", JSONArray(results.cards.map(Card::cached))).put("best", best).put("hasMore", results.hasMore)
        }

        internal fun decode(value: JSONObject): SearchResults {
            val best = value.obj("best")
            val song = best.optJSONObject("track")?.let { Track.parse(it) }?.let { SearchBest.Song(it) }
            val entry = best.optJSONObject("card")?.let(Card::fromCache)?.let { SearchBest.Entry(it) }
            return SearchResults(value.arr("tracks").objects().mapNotNull { Track.parse(it) },
                value.arr("cards").objects().mapNotNull(Card::fromCache), song ?: entry, value.opt("hasMore") as? Boolean)
        }
    }
}
