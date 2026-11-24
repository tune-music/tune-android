package music.tune.android

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URI
import java.util.Locale

internal class AlternateVersions(context: Context, private val enabled: () -> Boolean) {
    private val cache = AtomicFile(File(context.cacheDir, "alternate-versions.json"))
    private val restoredCache = AtomicFile(File(context.cacheDir, "restored-tracks.json"))
    internal data class Catalog(
        val tracks: Map<String, JSONObject>,
        val albums: Map<String, JSONObject>,
        val audio: Map<String, String>,
        val artistInsertions: Map<String, Pair<List<String>, List<String>>>,
        val trackAlbums: Map<String, JSONObject>,
        val byArtist: Map<String, Pair<List<String>, List<String>>> = emptyMap(),
        val signature: String = "",
    )

    @Volatile private var catalog: Catalog? = null
    @Volatile private var preferredMirror = MIRRORS.first()
    @Volatile private var retryAfter = 0L
    private var refreshedAt = 0L
    @Volatile private var restored: Map<String, Track>? = null
    @Volatile private var restoredSignature: String? = null
    @Volatile private var restoredAt = 0L
    @Volatile private var restoredRetryAfter = 0L

    fun ensureLoaded(network: Boolean): Boolean {
        if (!enabled()) return false
        if (catalog != null) return true
        return refresh(network)
    }

    fun hasRestoredIndex(): Boolean =
        !enabled() || (active()?.let { restored != null && restoredSignature == it.signature } == true)

    @Synchronized
    fun ensureRestored(network: Boolean, fetchRawTracks: ((List<String>) -> List<JSONObject>)? = null): Boolean {
        if (!enabled()) return false
        ensureLoaded(network)
        val list = active() ?: return false
        if ((restored == null || restoredSignature != list.signature) && restoredCache.baseFile.isFile) {
            optional {
                val saved = JSONObject(restoredCache.readFully().toString(Charsets.UTF_8))
                if (saved.text("signature") == list.signature) {
                    val parsed = saved.arr("tracks").objects().mapNotNull { Track.parse(it) }.associateBy { it.id }
                    restored = parsed
                    restoredSignature = list.signature
                    restoredAt = saved.optLong("at", restoredCache.baseFile.lastModified())
                }
            }
        }
        val now = System.currentTimeMillis()
        val fresh = restored != null && restoredSignature == list.signature && now - restoredAt in 0L until 86_400_000L
        if (fresh || !network || fetchRawTracks == null || now < restoredRetryAfter) {
            return restored != null && restoredSignature == list.signature
        }
        val ids = (list.audio.keys + list.tracks.keys).distinct()
        val raw = try {
            ids.chunked(250).flatMap(fetchRawTracks)
        } catch (_: IOException) {
            restoredRetryAfter = System.currentTimeMillis() + 60_000L
            return restored != null && restoredSignature == list.signature
        }
        val resolved = resolveSearchableTracks(list, raw)
        val updatedAt = System.currentTimeMillis()
        restored = resolved
        restoredSignature = list.signature
        restoredAt = updatedAt
        optional {
            restoredCache.writeText(
                JSONObject().put("signature", list.signature).put("at", updatedAt)
                    .put("tracks", JSONArray(resolved.values.map(Track::stored))).toString()
            )
        }
        return true
    }

    @Synchronized
    fun refresh(network: Boolean): Boolean {
        if (!enabled()) return false
        if (catalog == null && cache.baseFile.isFile) {
            catalog = runCatching { parse(JSONObject(cache.readFully().toString(Charsets.UTF_8))) }.getOrNull()
            if (catalog != null) refreshedAt = cache.baseFile.lastModified()
        }
        val now = System.currentTimeMillis()
        if (!network || now < retryAfter || (catalog != null && now - refreshedAt in 0L until 3_600_000L)) return catalog != null
        for (mirror in mirrorsInOrder()) {
            try {
                val connection = secureConnection(mirror + "list_v2.json")
                val raw = try {
                    connection.connectTimeout = 5_000
                    connection.readTimeout = 8_000
                    if (connection.responseCode != 200) continue
                    connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                } finally {
                    connection.disconnect()
                }
                val loaded = parse(JSONObject(raw))
                preferredMirror = mirror
                catalog = loaded
                refreshedAt = System.currentTimeMillis()
                optional { cache.writeText(raw) }
                return true
            } catch (_: IOException) {
                // Keep the last catalog when the source is temporarily unavailable.
            } catch (_: org.json.JSONException) {
                // Try the next mirror if the response is not a catalog.
            }
        }
        retryAfter = System.currentTimeMillis() + 30_000
        return catalog != null
    }

    private fun active() = catalog.takeIf { enabled() }

    fun hasAudio(id: String?) = id != null && active()?.audio?.containsKey(id) == true

    fun audio(id: String): List<String> {
        val url = active()?.audio?.get(id) ?: return emptyList()
        val path = MIRRORS.firstOrNull { url.startsWith(it) }?.let(url::removePrefix)
        return if (path == null) listOf(url) else mirrorsInOrder().map { it + path }
    }

    private fun mirrorsInOrder(): List<String> = listOf(preferredMirror) + MIRRORS.filterNot { it == preferredMirror }

    fun patch(value: JSONObject, kind: Kind, track: Boolean = false): JSONObject {
        val id = value.text("id") ?: return value
        val list = active() ?: return value
        val fields = (if (track) list.tracks else if (kind == Kind.ALBUM) list.albums else emptyMap())[id] ?: return value
        return applySpoof(value, fields)
    }

    fun albumTracks(id: String) = active()?.albums?.get(id)?.arr("volumes")?.items()?.filterIsInstance<JSONArray>()
        ?.flatMap { disk -> disk.items().mapNotNull(::idOf) }.orEmpty()

    fun catalogAlbum(id: String): JSONObject? = active()?.albums?.get(id)?.let { JSONObject(it.toString()).put("id", id) }

    fun artistInsertions(id: String): Pair<List<String>, List<String>> {
        val list = active() ?: return emptyList<String>() to emptyList()
        val extra = list.artistInsertions[id] ?: (emptyList<String>() to emptyList())
        val catalog = list.byArtist[id] ?: (emptyList<String>() to emptyList())
        val resolvedTracks = restored?.takeIf { restoredSignature == list.signature }?.values
            ?.filter { it.originalAvailable == false && it.artists.any { artist -> artist.id == id } }
            ?.map { it.id }.orEmpty()
        return (extra.first + catalog.first + resolvedTracks).distinct() to (extra.second + catalog.second).distinct()
    }

    fun searchAlbums(query: String): List<JSONObject> = active()?.let { searchCatalogAlbums(it, query) }.orEmpty()

    fun synthetic(id: String, album: Card? = null): Track? {
        val list = active() ?: return null
        if (id !in list.audio) return null
        val resolved = restored?.takeIf { restoredSignature == list.signature }?.get(id)?.takeIf { it.alternate }
        return (resolved ?: catalogSynthetic(id, list))?.let {
            it.copy(album = album ?: it.album, cover = it.cover ?: album?.cover)
        }
    }

    fun search(query: String): List<Track> {
        val list = active() ?: return emptyList()
        return searchCatalogTracks(list, restored?.takeIf { restoredSignature == list.signature }, query)
    }

    companion object {
        internal fun parse(root: JSONObject): Catalog {
            val tracks = linkedMapOf<String, JSONObject>()
            val albums = linkedMapOf<String, JSONObject>()
            val audio = linkedMapOf<String, String>()
            val durations = mutableMapOf<String, Long>()
            val insertions = mutableMapOf<String, Pair<List<String>, List<String>>>()
            val trackAlbums = mutableMapOf<String, JSONObject>()
            val sources = root.optJSONArray("sources") ?: throw org.json.JSONException("Missing sources")
            for (source in sources.objects()) {
                val artists = source.obj("artistsInsertions")
                artists.keys().forEach { id ->
                    val entry = artists.optJSONObject(id)
                    if (entry != null && id.matches(ID) && !insertions.containsKey(id)) {
                        insertions[id] = entry.arr("tracks").items().mapNotNull(::idOf) to entry.arr("albums").items().mapNotNull(::idOf)
                    }
                }
                for ((key, target) in listOf("tracks" to tracks, "albums" to albums)) {
                    val list = source.obj(key)
                    list.keys().forEach { id ->
                        if (id.matches(ID) && id !in target) list.optJSONObject(id)?.let { target[id] = it }
                    }
                }
                for (storage in source.arr("tracksStorages").objects()) {
                    val template = storage.text("urlTemplate").orEmpty()
                    fun add(id: String, address: String, fields: JSONObject?) {
                        if (!id.matches(ID)) return
                        val url = httpsAddress(address) ?: return
                        audio[id] = url
                        fields?.optLong("durationMs")?.takeIf { it > 0 }?.let { durations[id] = it }
                    }
                    if (template.isNotBlank()) storage.arr("trackIds").items().forEach { row ->
                        idOf(row)?.let { add(it, template.replace("%%", it), row as? JSONObject) }
                    }
                    val entries = storage.obj("tracks")
                    entries.keys().forEach { id ->
                        val fields = entries.optJSONObject(id)
                        val address = (fields?.text("url") ?: entries.opt(id).asId()) ?: return@forEach
                        val absolute = httpsAddress(address)
                        if (absolute != null) add(id, absolute, fields)
                        else if (template.isNotEmpty() && !address.contains("://") && !address.contains('/')) {
                            add(id, template.replace("%%", address), fields)
                        }
                    }
                }
            }
            if (audio.isEmpty()) throw org.json.JSONException("Missing audio entries")
            fillCupsizeMetadata(albums, tracks, audio.keys)
            durations.forEach { (id, duration) -> tracks.getOrPut(id, ::JSONObject).put("durationMs", duration) }
            for ((id, album) in albums.toSortedMap()) {
                val cover = album.text("coverUri")
                val release = JSONObject(album.toString()).put("id", id).apply { remove("volumes") }
                album.arr("volumes").items().filterIsInstance<JSONArray>().flatMap { it.items() }.mapNotNull(::idOf).forEach { trackId ->
                    trackAlbums.putIfAbsent(trackId, release)
                    val track = tracks.getOrPut(trackId, ::JSONObject)
                    if (cover != null && track.text("coverUri") == null) track.put("coverUri", cover)
                }
            }
            val artistTracks = linkedMapOf<String, MutableSet<String>>()
            val artistAlbums = linkedMapOf<String, MutableSet<String>>()
            fun artistIds(fields: JSONObject?): List<String> =
                fields?.arr("artists")?.objects()?.mapNotNull { it.text("id")?.takeIf { id -> id.matches(ID) } }.orEmpty()
            for ((albumId, album) in albums.toSortedMap()) {
                for (artistId in artistIds(album)) artistAlbums.getOrPut(artistId, ::linkedSetOf).add(albumId)
            }
            for (trackId in (audio.keys + tracks.keys).toSortedSet()) {
                val owners = artistIds(tracks[trackId]) + artistIds(trackAlbums[trackId])
                for (artistId in owners) artistTracks.getOrPut(artistId, ::linkedSetOf).add(trackId)
            }
            val byArtist = (artistTracks.keys + artistAlbums.keys).associateWith { artistId ->
                artistTracks[artistId]?.toList().orEmpty() to artistAlbums[artistId]?.toList().orEmpty()
            }
            val signature = digest(buildString {
                append(audio.keys.sorted().joinToString(","))
                append('|')
                tracks.keys.sorted().forEach { id ->
                    val entry = tracks.getValue(id)
                    append(id).append(':').append(entry.text("title").orEmpty()).append(':')
                    append(entry.arr("artists").objects().mapNotNull { it.text("name") }.joinToString(",")).append(';')
                }
                append('|')
                append(albums.keys.sorted().joinToString(","))
            })
            return Catalog(tracks, albums, audio, insertions, trackAlbums, byArtist, signature)
        }

        private fun applySpoof(value: JSONObject, fields: JSONObject): JSONObject {
            val patched = JSONObject(value.toString())
            fields.keys().forEach { key ->
                if (key !in SKIP && !fields.isNull(key) && (key != "artists" || fields.arr(key).length() > 0)) {
                    patched.put(key, fields.get(key))
                }
            }
            return patched
        }

        internal fun patchTrackObject(value: JSONObject, id: String, list: Catalog): JSONObject {
            val patched = list.tracks[id]?.let { applySpoof(value, it) } ?: JSONObject(value.toString())
            list.trackAlbums[id]?.let { release ->
                if (patched.arr("albums").length() == 0) patched.put("albums", JSONArray().put(release))
                if (patched.arr("artists").length() == 0 && release.arr("artists").length() > 0) {
                    patched.put("artists", release.arr("artists"))
                }
            }
            return patched
        }

        internal fun catalogSynthetic(id: String, list: Catalog, album: Card? = null): Track? {
            if (id !in list.audio) return null
            val fields = list.tracks[id] ?: return null
            val value = patchTrackObject(JSONObject().put("id", id), id, list)
            if (fields.text("title").isNullOrBlank()) return null
            return Track.parse(value, alternate = true)?.let {
                it.copy(album = album ?: it.album, cover = it.cover ?: album?.cover, originalAvailable = false)
            }
        }

        internal fun resolveSearchableTracks(list: Catalog, rawTracks: List<JSONObject>): Map<String, Track> {
            val rawById = LinkedHashMap<String, JSONObject>(rawTracks.size)
            for (obj in rawTracks) {
                val id = obj.text("id") ?: continue
                rawById.putIfAbsent(id, obj)
            }
            val resolved = LinkedHashMap<String, Track>()
            for (id in (list.audio.keys + list.tracks.keys).distinct()) {
                val hasAudio = id in list.audio
                val raw = rawById[id]
                val track = if (raw != null) {
                    val available = raw.optBoolean("available", true)
                    val spoof = list.tracks[id]
                    val hasSpoofedMeta = spoof != null && (!spoof.text("title").isNullOrBlank() || spoof.arr("artists").length() > 0)
                    if (!((!available && hasAudio) || (hasSpoofedMeta && (available || hasAudio)))) continue
                    val patched = patchTrackObject(raw, id, list)
                    Track.parse(patched, alternate = hasAudio)?.copy(originalAvailable = available)
                } else {
                    catalogSynthetic(id, list)
                }
                if (track != null) resolved[id] = track
            }
            return resolved
        }

        internal fun searchTokens(text: String): List<String> =
            text.lowercase(Locale.ROOT).replace('ё', 'е').split(NON_ALNUM).filter(String::isNotEmpty)

        internal fun matchesTerm(term: String, tokens: List<String>, allowPrefix: Boolean): Boolean =
            if (allowPrefix) tokens.any { it.startsWith(term) } else term in tokens

        internal fun searchCatalogTracks(list: Catalog, restored: Map<String, Track>?, query: String): List<Track> {
            val terms = searchTokens(query)
            if (terms.isEmpty()) return emptyList()
            val allowPrefix = terms.sumOf(String::length) >= 3
            val candidates = LinkedHashMap<String, Track>()
            restored?.forEach { (id, track) -> candidates[id] = track }
            for (id in (list.audio.keys + list.tracks.keys)) {
                if (id in candidates) continue
                val fields = list.tracks[id] ?: continue
                val hasAudio = id in list.audio
                if (!hasAudio && fields.arr("artists").length() == 0) continue
                val value = patchTrackObject(JSONObject().put("id", id), id, list)
                val parsed = Track.parse(value, alternate = hasAudio)?.let {
                    if (hasAudio) it.copy(originalAvailable = false) else it
                } ?: continue
                candidates[id] = parsed
            }
            data class Hit(val track: Track, val rank: Int)
            val hits = ArrayList<Hit>()
            for (track in candidates.values) {
                val rawTitle = track.title.substringBefore(" (")
                val rawTitleTokens = searchTokens(rawTitle)
                val titleTokens = searchTokens(track.title)
                val artistTokens = searchTokens(track.artistText)
                val albumTokens = searchTokens(track.album?.title.orEmpty())
                val allTokens = titleTokens + artistTokens + albumTokens
                if (!terms.all { matchesTerm(it, allTokens, allowPrefix) }) continue
                val rank = when {
                    rawTitleTokens == terms || titleTokens == terms -> 0
                    terms.all { matchesTerm(it, titleTokens, allowPrefix) } -> 1
                    terms.any { matchesTerm(it, titleTokens, allowPrefix) } &&
                        terms.any { matchesTerm(it, artistTokens, allowPrefix) } -> 2
                    artistTokens == terms -> 3
                    else -> 4
                }
                hits += Hit(track, rank)
            }
            return hits.sortedWith(
                compareBy<Hit> { it.rank }.thenBy { it.track.title.lowercase(Locale.ROOT) }.thenBy { it.track.id }
            ).take(30).map { it.track }
        }

        internal fun searchCatalogAlbums(list: Catalog, query: String): List<JSONObject> {
            val terms = searchTokens(query)
            if (terms.isEmpty()) return emptyList()
            val allowPrefix = terms.sumOf(String::length) >= 3
            data class Hit(val album: JSONObject, val rank: Int, val title: String, val id: String)
            val hits = ArrayList<Hit>()
            for ((id, fields) in list.albums) {
                val title = fields.text("title")?.takeIf(String::isNotBlank) ?: continue
                val titleTokens = searchTokens(title)
                val artistNames = fields.arr("artists").objects().mapNotNull { it.text("name") }.joinToString(" ")
                val artistTokens = searchTokens(artistNames)
                val allTokens = titleTokens + artistTokens
                if (!terms.all { matchesTerm(it, allTokens, allowPrefix) }) continue
                val rank = when {
                    titleTokens == terms -> 0
                    terms.all { matchesTerm(it, titleTokens, allowPrefix) } -> 1
                    else -> 2
                }
                hits += Hit(JSONObject(fields.toString()).put("id", id), rank, title.lowercase(Locale.ROOT), id)
            }
            return hits.sortedWith(compareBy<Hit> { it.rank }.thenBy { it.title }.thenBy { it.id })
                .take(15).map { it.album }
        }

        private fun httpsAddress(raw: String): String? {
            val address = raw.trim()
            val full = when {
                address.startsWith("https://", ignoreCase = true) -> address
                address.startsWith("//") -> "https:$address"
                '/' in address && '.' in address.substringBefore('/') && ':' !in address.substringBefore('/') -> "https://$address"
                else -> return null
            }
            val uri = runCatching { URI(full) }.getOrNull() ?: return null
            return full.takeIf { uri.scheme.equals("https", ignoreCase = true) && uri.host != null && uri.userInfo == null }
        }

        // The catalog supplies the audio and track order for this release. Tune iOS uses the ID3 titles.
        private fun fillCupsizeMetadata(
            albums: Map<String, JSONObject>, tracks: MutableMap<String, JSONObject>, audioIds: Set<String>
        ) {
            val album = albums["24720439"] ?: return
            val artist = JSONObject().put("id", 10946087).put("name", "CUPSIZE")
            val artists = JSONArray().put(artist)
            if (album.text("title") == null) album.put("title", "Еби меня, малышка")
            if (album.arr("artists").length() == 0) album.put("artists", artists)
            if (album.isNull("year")) album.put("year", 2023)
            for ((id, title) in CUPSIZE_TITLES) {
                if (id !in audioIds) continue
                val track = tracks.getOrPut(id, ::JSONObject)
                if (track.text("title") == null) track.put("title", title)
                if (track.arr("artists").length() == 0) {
                    val credits = if (id == "110945085") JSONArray().put(artist).put(JSONObject().put("name", "17 SEVENTEEN")) else artists
                    track.put("artists", credits)
                }
                if (track.isNull("contentWarning")) track.put("contentWarning", "explicit")
            }
        }

        private fun idOf(value: Any?): String? = ((value as? JSONObject)?.let { it.text("releaseId") ?: it.text("id") }
            ?: value.asId())?.takeIf { it.matches(ID) }

        private val ID = Regex("[0-9]+")
        private val NON_ALNUM = Regex("[^\\p{L}\\p{N}]+")
        private val SKIP = setOf("id", "audio", "insert", "volumes", "__fckCensor", "noAuto")
        private val MIRRORS = listOf("https://raw.githubusercontent.com/Hazzz895/FckCensorData/refs/heads/main/",
            "https://cdn.jsdelivr.net/gh/Hazzz895/FckCensorData@main/")
        private val CUPSIZE_TITLES = mapOf(
            "110945082" to "ДАВАЙ ТРАХАТЬСЯ В МАШИНЕ", "110945083" to "люби меня, алина",
            "110945084" to "ГИДРОПОН", "110945085" to "Папик", "110945087" to "вайфуу",
            "110945088" to "я схавал опиат", "110945089" to "Вирус", "110945090" to "МОЯ МАМА ПЬЕТ",
            "110945091" to "Ты любишь травку", "110945092" to "Забуду", "110945093" to "мне похуй"
        )
    }
}
