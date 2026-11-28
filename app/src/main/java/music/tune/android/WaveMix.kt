package music.tune.android

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.random.Random

internal class WaveMix(private val store: MusicStore) {
    private var session: String? = null
    private var untilInsert = 12

    suspend fun incorporate(uid: String, source: PlayContext, incoming: List<Track>, recent: List<Track>): List<Track> {
        if (source.kind != "radio" || source.wave == null || incoming.isEmpty()) return incoming
        val preferences = store.preferences
        val restored = preferences.getBoolean("restoredInWave", false) && (store.alternateEnabled || store.catalogEnabled)
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
        val recentTracks = recent.takeLast(25) + incoming
        val recentArtistIds = recentTracks.flatMap { it.artists }.map { it.id }.filter(String::isNotBlank).toSet()
        val recentArtistNames = recentTracks.flatMap { it.artists }.map { WaveTaste.normName(it.title) }.filter(String::isNotEmpty).toSet()
        val likedArtists = (
            store.savedCards.value.filter { it.startsWith("ARTIST::") }.map { it.removePrefix("ARTIST::") } +
                store.waveTaste.likedArtists()
            ).distinct()

        val baseSeeds = source.item.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val settings = if (baseSeeds == listOf("user:onyourwave")) {
            preferences.getStringSet("waveSettings", emptySet()).orEmpty()
        } else emptySet()
        val recentProfiles = recent.takeLast(12).mapNotNull { store.audioProfiles.get(it.id) }
        val ctx = WaveContext.fromSeeds(baseSeeds + settings, recentProfiles)
        if (ctx.blockedWave) return incoming

        val simSeeds = (listOfNotNull(ctx.focusArtist) + likedArtists.take(4)).distinct()
        store.waveTaste.loadSimilar(simSeeds, store.scope) { id -> store.api().similarArtistIds(id) }

        val (candidates, historySet) = withContext(Dispatchers.IO) {
            val catalogHistory = if (store.catalogEnabled) store.catalogLibrary.historyEntries(uid).map { it.first } else emptyList()
            val listenHistory = store.listening.recentTrackIds(uid)
            val allHistory = (catalogHistory + listenHistory).toSet()

            val own = if (uploads) {
                (likes + allHistory).asSequence().distinct()
                    .filter { !it.all(Char::isDigit) && !store.versions.isSyntheticTrackId(it) && it !in excluded && now - seen.optLong(it) > week }
                    .take(100).toList()
                    .let { ids -> if (ids.isEmpty()) emptyList() else optional { store.api().tracks(ids) }.orEmpty().filter { it.uploaded } }
            } else emptyList()

            val catalog = if (restored) {
                val ids = (recentArtistIds + likedArtists).flatMap { store.versions.artistInsertions(it).first } +
                    likes.filter { store.versions.hasAudio(it) || store.versions.isSyntheticTrackId(it) } +
                    (if (store.catalogEnabled) store.versions.catalogWaveTrackIds() else emptyList()) +
                    allHistory.filter { store.versions.hasAudio(it) || store.versions.isSyntheticTrackId(it) }
                ids.asSequence().distinct()
                    .filter {
                        it !in excluded && now - seen.optLong(it) > week &&
                            (!store.versions.isSyntheticTrackId(it) || store.catalogEnabled) &&
                            store.allowsPluginAudio(uid, it)
                    }
                    .mapNotNull { store.versions.synthetic(it) }
                    .take(150).toList()
            } else emptyList()

            (own.map { it to true } + catalog.map { it to false }) to allHistory
        }
        if (store.profile.value?.uid != uid || candidates.isEmpty()) return incoming

        store.audioProfiles.ensureAnalyzed(candidates.map { it.first.id }, store.scope) { id ->
            optional { store.files.resolve(uid, id, false) }
        }

        val scored = candidates.mapNotNull { (track, isUgc) ->
            val score = store.waveTaste.scoreWaveCandidate(
                track = track,
                likedTrack = track.id in likes,
                inHistory = track.id in historySet,
                isUgc = isUgc,
                profile = store.audioProfiles.get(track.id),
                ctx = ctx,
                recentArtistIds = recentArtistIds,
                recentArtistNames = recentArtistNames,
                likedArtistIds = likedArtists,
            ) ?: return@mapNotNull null
            if (score < WaveTaste.WAVE_PASS) return@mapNotNull null
            track to score.coerceAtLeast(0.1)
        }
        if (scored.isEmpty()) return incoming
        val chosenIdx = WaveTaste.pickWeighted(scored.map { it.second }, Random.nextDouble()) ?: return incoming
        val chosen = scored[chosenIdx].first

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

