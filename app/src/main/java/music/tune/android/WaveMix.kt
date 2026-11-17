package music.tune.android

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.random.Random

internal class WaveMix(private val store: MusicStore) {
    private var session: String? = null
    private var untilInsert = 12

    suspend fun incorporate(uid: String, source: PlayContext, incoming: List<Track>, recent: List<Track>): List<Track> {
        if (source.item != "user:onyourwave" || source.wave == null || incoming.isEmpty()) return incoming
        val preferences = store.preferences
        val restored = preferences.getBoolean("restoredInWave", false) && store.alternateEnabled
        val uploads = preferences.getBoolean("uploadsInWave", false)
        if (!restored && !uploads) return incoming
        if (session != source.wave) {
            session = source.wave
            untilInsert = Random.nextInt(10, 16)
        }
        if (incoming.size < untilInsert) {
            untilInsert -= incoming.size
            return incoming
        }
        val offset = (untilInsert - 1).coerceIn(0, incoming.size)
        val key = "waveExtrasPlayed:${digest(uid)}"
        val seen = optional { JSONObject(preferences.getString(key, "{}").orEmpty()) } ?: JSONObject()
        val now = System.currentTimeMillis()
        val week = 7 * 86_400_000L
        val excluded = (recent + incoming).map { it.id }.toSet() + store.dislikes.value
        val likes = store.likes.value
        val artists = (recent.takeLast(25) + incoming).flatMap { it.artists }.map { it.id }.filter(String::isNotBlank).toSet()
        val likedArtists = store.savedCards.value.filter { it.startsWith("ARTIST::") }.map { it.removePrefix("ARTIST::") }.toSet()
        val candidates = withContext(Dispatchers.IO) {
            val own = if (uploads) likes.filter { !it.all(Char::isDigit) && it !in excluded && now - seen.optLong(it) > week }.take(100)
                .let { ids -> if (ids.isEmpty()) emptyList() else optional { store.api().tracks(ids) }.orEmpty().filter { it.uploaded } } else emptyList()
            val catalog = if (restored) {
                val ids = (artists + likedArtists).flatMap { store.versions.artistInsertions(it).first } +
                    likes.filter { store.versions.hasAudio(it) }
                ids.asSequence().distinct().filter { it !in excluded && now - seen.optLong(it) > week }
                    .mapNotNull { store.versions.synthetic(it) }.take(100).toList()
            } else emptyList()
            own + catalog
        }
        if (store.profile.value?.uid != uid) return incoming
        val weights = candidates.map { track ->
            1 + (if (track.id in likes) 3 else 0) +
                (if (track.artists.any { it.id in likedArtists }) 2 else 0) +
                (if (track.artists.any { it.id in artists }) 2 else 0)
        }
        if (weights.isEmpty()) return incoming
        var choice = Random.nextInt(weights.sum())
        val chosen = candidates[weights.indexOfFirst { weight -> choice -= weight; choice < 0 }]
        untilInsert = Random.nextInt(10, 16) - (incoming.size - offset).coerceAtMost(8)
        seen.put(chosen.id, now)
        val iterator = seen.keys()
        while (iterator.hasNext()) {
            val id = iterator.next()
            if (now - seen.optLong(id) > week) iterator.remove()
        }
        preferences.edit().putString(key, seen.toString()).apply()
        return incoming.take(offset) + chosen + incoming.drop(offset)
    }
}
