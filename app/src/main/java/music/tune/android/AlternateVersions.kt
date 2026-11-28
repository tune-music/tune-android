package music.tune.android

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URI
import java.util.Locale

internal class AlternateVersions(
    context: Context,
    private val enabled: () -> Boolean,
    private val catalogEnabled: () -> Boolean = { true },
) {
    private val cache = AtomicFile(File(context.cacheDir, "alternate-versions.json"))
    private val catalogCache = AtomicFile(File(context.cacheDir, "tune-catalog.json"))
    private val restoredCache = AtomicFile(File(context.cacheDir, "restored-tracks.json"))

    internal data class Catalog(
        val tracks: Map<String, JSONObject>,
        val albums: Map<String, JSONObject>,
        val audio: Map<String, String>,
        val artistInsertions: Map<String, Pair<List<String>, List<String>>>,
        val trackAlbums: Map<String, JSONObject>,
        val byArtist: Map<String, Pair<List<String>, List<String>>> = emptyMap(),
        val signature: String = "",
        val artists: Map<String, JSONObject> = emptyMap(),
        val playlists: Map<String, JSONObject> = emptyMap(),
    )

    @Volatile private var fckRaw: JSONObject? = null
    @Volatile private var tuneRaw: JSONObject? = null
    @Volatile private var fckCatalog: Catalog? = null
    @Volatile private var tuneCatalog: Catalog? = null
    @Volatile private var mergedCatalog: Catalog? = null
    @Volatile private var fckDiskLoaded = false
    @Volatile private var tuneDiskLoaded = false

    @Volatile private var preferredFckMirror = MIRRORS.first()
    @Volatile private var preferredCatalogMirror = CATALOG_MIRRORS.first()
    @Volatile private var retryAfter = 0L
    @Volatile private var refreshedAt = 0L
    @Volatile private var catalogRetryAfter = 0L
    @Volatile private var catalogRefreshedAt = 0L

    @Volatile private var restored: Map<String, Track>? = null
    @Volatile private var restoredSignature: String? = null
    @Volatile private var restoredAt = 0L
    @Volatile private var restoredRetryAfter = 0L

    fun hasFckLoaded(): Boolean = fckCatalog != null
    fun hasCatalogLoaded(): Boolean = tuneCatalog != null

    fun catalogSignature(): String = buildString {
        if (enabled()) append(fckCatalog?.signature.orEmpty())
        append('|')
        if (catalogEnabled()) append(tuneCatalog?.signature.orEmpty())
    }

    fun isSyntheticTrackId(id: String?): Boolean = Companion.isSyntheticTrackId(id)
    fun isSyntheticAlbumId(id: String?): Boolean = Companion.isSyntheticAlbumId(id)
    fun isSyntheticArtistId(id: String?): Boolean = Companion.isSyntheticArtistId(id)
    fun isSyntheticPlaylistId(id: String?, owner: String? = null): Boolean = Companion.isSyntheticPlaylistId(id, owner)

    fun ensureLoaded(network: Boolean): Boolean {
        if (!enabled() && !catalogEnabled()) return false
        loadDiskCaches()
        val now = System.currentTimeMillis()
        val needFck = enabled() && (fckCatalog == null || (network && now - refreshedAt !in 0L until FCK_REFRESH_MS))
        val needTune = catalogEnabled() && (tuneCatalog == null || (network && (catalogRefreshedAt == 0L || now - catalogRefreshedAt !in 0L until CATALOG_REFRESH_MS)))
        if (!needFck && !needTune) return active() != null
        return refresh(network)
    }

    fun hasRestoredIndex(): Boolean =
        !enabled() || (fckCatalog?.let { restored != null && restoredSignature == it.signature } == true)

    @Synchronized
    fun ensureRestored(network: Boolean, fetchRawTracks: ((List<String>) -> List<JSONObject>)? = null): Boolean {
        if (!enabled()) return false
        ensureLoaded(network)
        val list = fckCatalog ?: return false
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
        val ids = (list.audio.keys + list.tracks.keys).filterNot(::isSyntheticTrackId).distinct()
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
    private fun loadDiskCaches() {
        var changed = false
        if (!fckDiskLoaded) {
            fckDiskLoaded = true
            if (cache.baseFile.isFile) {
                val parsed = runCatching { JSONObject(cache.readFully().toString(Charsets.UTF_8)) }.getOrNull()
                if (parsed != null && runCatching { parse(parsed) }.isSuccess) {
                    fckRaw = parsed
                    refreshedAt = cache.baseFile.lastModified()
                    changed = true
                }
            }
        }
        if (!tuneDiskLoaded) {
            tuneDiskLoaded = true
            if (catalogCache.baseFile.isFile) {
                val parsed = runCatching { JSONObject(catalogCache.readFully().toString(Charsets.UTF_8)) }.getOrNull()
                if (parsed != null && runCatching { parseCatalog(parsed) }.isSuccess) {
                    tuneRaw = parsed
                    changed = true
                }
            }
        }
        if (changed) rebuildLists()
    }

    @Synchronized
    private fun rebuildLists() {
        val fck = fckRaw?.let { runCatching { parse(it) }.getOrNull() }
        val tune = tuneRaw?.let { runCatching { parseCatalog(it) }.getOrNull() }
        fckCatalog = fck
        tuneCatalog = tune
        mergedCatalog = when {
            tuneRaw != null && fckRaw != null -> runCatching { mergeCatalogs(tuneRaw!!, fckRaw!!) }.getOrNull() ?: tune ?: fck
            tune != null -> tune
            else -> fck
        }
    }

    @Synchronized
    fun refresh(network: Boolean, forceCatalog: Boolean = false): Boolean {
        if (!enabled() && !catalogEnabled()) return false
        loadDiskCaches()
        val now = System.currentTimeMillis()
        if (network && enabled() && now >= retryAfter && (fckCatalog == null || now - refreshedAt !in 0L until FCK_REFRESH_MS)) {
            val fetched = fetchSource(
                mirrors = fckMirrorsInOrder(),
                bustCache = false,
                validate = { parse(it) },
            )
            if (fetched != null) {
                preferredFckMirror = fetched.first
                fckRaw = fetched.second
                refreshedAt = System.currentTimeMillis()
                optional { cache.writeText(fetched.third) }
                rebuildLists()
            } else {
                retryAfter = System.currentTimeMillis() + FCK_RETRY_MS
            }
        }
        if (network && catalogEnabled()) {
            refreshCatalogLocked(force = forceCatalog)
        }
        return active() != null
    }

    @Synchronized
    fun refreshCatalog(network: Boolean, force: Boolean = false): Pair<Boolean, Boolean> {
        loadDiskCaches()
        val beforeSig = tuneCatalog?.signature.orEmpty()
        if (network && catalogEnabled()) {
            refreshCatalogLocked(force = force)
        }
        val afterSig = tuneCatalog?.signature.orEmpty()
        return (tuneCatalog != null) to (beforeSig != afterSig)
    }

    private fun refreshCatalogLocked(force: Boolean) {
        val now = System.currentTimeMillis()
        val due = force || catalogRefreshedAt == 0L || now - catalogRefreshedAt !in 0L until CATALOG_REFRESH_MS
        if (!due || (!force && now < catalogRetryAfter)) return
        val fetched = fetchSource(
            mirrors = catalogMirrorsInOrder(),
            bustCache = true,
            validate = { parseCatalog(it) },
        )
        if (fetched != null) {
            preferredCatalogMirror = fetched.first
            tuneRaw = fetched.second
            catalogRefreshedAt = System.currentTimeMillis()
            catalogRetryAfter = 0L
            optional { catalogCache.writeText(fetched.third) }
            rebuildLists()
        } else {
            catalogRetryAfter = System.currentTimeMillis() + CATALOG_RETRY_MS
        }
    }

    private fun fetchSource(
        mirrors: List<String>,
        bustCache: Boolean,
        validate: (JSONObject) -> Catalog,
    ): Triple<String, JSONObject, String>? {
        for (mirror in mirrors) {
            try {
                val baseUrl = mirror + "list_v2.json"
                val url = if (bustCache && baseUrl.startsWith("https://raw.githubusercontent.com/")) {
                    "$baseUrl?t=${System.currentTimeMillis() / 1000}"
                } else {
                    baseUrl
                }
                val connection = secureConnection(url)
                val raw = try {
                    connection.connectTimeout = 5_000
                    connection.readTimeout = 8_000
                    if (bustCache) connection.setRequestProperty("Cache-Control", "no-cache")
                    if (connection.responseCode != 200) continue
                    connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                } finally {
                    connection.disconnect()
                }
                val json = JSONObject(raw)
                validate(json)
                return Triple(mirror, json, raw)
            } catch (_: IOException) {
                // Keep the last catalog when the source is temporarily unavailable.
            } catch (_: org.json.JSONException) {
                // Try the next mirror if the response is not a catalog.
            }
        }
        return null
    }

    private fun active(): Catalog? = when {
        enabled() && catalogEnabled() -> mergedCatalog ?: tuneCatalog ?: fckCatalog
        enabled() -> fckCatalog
        catalogEnabled() -> tuneCatalog
        else -> null
    }

    private fun catalogOnly(): Catalog? = tuneCatalog.takeIf { catalogEnabled() }

    /**
     * Critical badge rule: `T` badge appears ONLY when Tune Catalog is enabled
     * AND `tuneCatalog.audio` contains the track ID (NEVER `tracks.containsKey(id)`).
     */
    fun isCatalogTrack(id: String?): Boolean = isCatalogTrack(tuneCatalog, catalogEnabled(), id)

    /** FckCensor badge is shown ONLY when FckCensor is enabled, has audio, and track is NOT a Tune Catalog track. */
    fun hasFckAudio(id: String?): Boolean = hasFckBadge(fckCatalog, enabled(), tuneCatalog, catalogEnabled(), id)

    fun hasAudio(id: String?) = id != null && active()?.audio?.containsKey(id) == true

    fun audio(id: String): List<String> {
        val url = active()?.audio?.get(id) ?: return emptyList()
        val fckPath = MIRRORS.firstOrNull { url.startsWith(it) }?.let(url::removePrefix)
        if (fckPath != null) return fckMirrorsInOrder().map { it + fckPath }
        val catalogPath = CATALOG_MIRRORS.firstOrNull { url.startsWith(it) }?.let(url::removePrefix)
        if (catalogPath != null) return catalogMirrorsInOrder().map { it + catalogPath }
        return listOf(url)
    }

    private fun fckMirrorsInOrder(): List<String> = listOf(preferredFckMirror) + MIRRORS.filterNot { it == preferredFckMirror }
    private fun catalogMirrorsInOrder(): List<String> = listOf(preferredCatalogMirror) + CATALOG_MIRRORS.filterNot { it == preferredCatalogMirror }

    fun patch(value: JSONObject, kind: Kind, track: Boolean = false): JSONObject {
        val id = value.text("id") ?: return value
        val list = active() ?: return value
        val fields = when {
            track -> list.tracks[id]
            kind == Kind.ALBUM -> list.albums[id]
            kind == Kind.ARTIST -> list.artists[id]
            else -> null
        } ?: return value
        return applySpoof(value, fields)
    }

    fun albumTracks(id: String): List<String> = albumVolumes(id).flatten()

    fun albumVolumes(id: String): List<List<String>> = active()?.albums?.get(id)?.arr("volumes")?.items()
        ?.filterIsInstance<JSONArray>()
        ?.map { disk -> disk.items().mapNotNull(::idOf) }
        ?.filter { it.isNotEmpty() }
        .orEmpty()

    fun catalogAlbum(id: String): JSONObject? = (active() ?: catalogOnly())?.albums?.get(id)?.let { JSONObject(it.toString()).put("id", id) }

    fun catalogArtist(id: String): Triple<JSONObject, List<String>, List<String>>? {
        val list = active() ?: catalogOnly() ?: return null
        val fields = list.artists[id] ?: return null
        val obj = JSONObject(fields.toString()).put("id", id)
        val seenTracks = linkedSetOf<String>()
        val seenAlbums = linkedSetOf<String>()
        list.artistInsertions[id]?.let { (insTracks, insAlbums) ->
            seenTracks.addAll(insTracks)
            seenAlbums.addAll(insAlbums)
        }
        list.byArtist[id]?.let { (catTracks, catAlbums) ->
            seenTracks.addAll(catTracks)
            seenAlbums.addAll(catAlbums)
        }
        return Triple(obj, seenTracks.toList(), seenAlbums.toList())
    }

    fun catalogPlaylist(kind: String): Pair<JSONObject, List<String>>? {
        val list = active() ?: catalogOnly() ?: return null
        val fields = list.playlists[kind] ?: return null
        val trackIds = fields.arr("tracks").items().mapNotNull(::idOf)
        return JSONObject(fields.toString()).put("id", kind).put("kind", kind) to trackIds
    }

    fun artistInsertions(id: String): Pair<List<String>, List<String>> {
        val list = active() ?: return emptyList<String>() to emptyList()
        val extra = list.artistInsertions[id] ?: (emptyList<String>() to emptyList())
        val catalog = list.byArtist[id] ?: (emptyList<String>() to emptyList())
        val resolvedTracks = restored?.takeIf { restoredSignature == fckCatalog?.signature && enabled() }?.values
            ?.filter { it.originalAvailable == false && it.artists.any { artist -> artist.id == id } }
            ?.map { it.id }.orEmpty()
        return (extra.first + catalog.first + resolvedTracks).distinct() to (extra.second + catalog.second).distinct()
    }

    fun searchAlbums(query: String): List<JSONObject> = active()?.let { searchCatalogAlbums(it, query) }.orEmpty()

    fun searchArtists(query: String): List<JSONObject> = catalogOnly()?.let { searchCatalogArtists(it, query) }.orEmpty()

    fun searchPlaylists(query: String): List<Pair<String, JSONObject>> = catalogOnly()?.let { searchCatalogPlaylists(it, query) }.orEmpty()

    fun catalogWaveTrackIds(): List<String> =
        catalogOnly()?.audio?.keys?.toList().orEmpty()

    fun catalogAudioProfile(id: String): AudioProfile? {
        val cat = catalogOnly() ?: return null
        return AudioProfile.fromJson(cat.tracks[id]?.optJSONObject("audioProfile"))
    }

    fun synthetic(id: String, album: Card? = null): Track? {
        val list = active() ?: return null
        val isCatalog = isCatalogTrack(id)
        val isFck = hasFckAudio(id)
        if (!isCatalog && !isFck && !(isSyntheticTrackId(id) && id in list.tracks)) return null
        val resolved = if (!isCatalog && isFck) {
            restored?.takeIf { restoredSignature == fckCatalog?.signature }?.get(id)?.takeIf { it.alternate }
        } else null
        return (resolved ?: catalogSynthetic(id, list, alternate = isFck, catalog = isCatalog))?.let {
            it.copy(album = album ?: it.album, cover = it.cover ?: album?.cover)
        }
    }

    fun search(query: String): List<Track> {
        val list = active() ?: return emptyList()
        val restoredMap = restored?.takeIf { enabled() && restoredSignature == fckCatalog?.signature }
        return searchCatalogTracks(
            list = list,
            restored = restoredMap,
            query = query,
            isCatalog = ::isCatalogTrack,
            isFck = ::hasFckAudio,
        )
    }

    companion object {
        const val CATALOG_OWNER = "tune-catalog"
        private const val SYNTHETIC_TRACK_BASE = 9_000_000_000L
        private const val SYNTHETIC_ALBUM_BASE = 900_000_000L
        private const val SYNTHETIC_ARTIST_BASE = 90_000_000L
        private const val SYNTHETIC_PLAYLIST_BASE = 9_000_000L
        private const val FCK_REFRESH_MS = 3_600_000L
        private const val FCK_RETRY_MS = 30_000L
        private const val CATALOG_REFRESH_MS = 120_000L
        private const val CATALOG_RETRY_MS = 60_000L

        internal fun isSyntheticTrackId(id: String?): Boolean =
            id?.toLongOrNull()?.let { it > SYNTHETIC_TRACK_BASE } == true

        internal fun isSyntheticAlbumId(id: String?): Boolean =
            id?.toLongOrNull()?.let { it in (SYNTHETIC_ALBUM_BASE + 1) until SYNTHETIC_TRACK_BASE } == true

        internal fun isSyntheticArtistId(id: String?): Boolean =
            id?.toLongOrNull()?.let { it in (SYNTHETIC_ARTIST_BASE + 1) until SYNTHETIC_ALBUM_BASE } == true

        internal fun isSyntheticPlaylistId(id: String?, owner: String? = null): Boolean =
            owner == CATALOG_OWNER || id?.toLongOrNull()?.let { it in (SYNTHETIC_PLAYLIST_BASE + 1) until SYNTHETIC_ARTIST_BASE } == true

        internal fun isCatalogTrack(catalog: Catalog?, catalogEnabled: Boolean, id: String?): Boolean =
            id != null && catalogEnabled && catalog?.audio?.containsKey(id) == true

        internal fun hasFckBadge(
            fck: Catalog?,
            fckEnabled: Boolean,
            catalog: Catalog?,
            catalogEnabled: Boolean,
            id: String?,
        ): Boolean = id != null && fckEnabled && !isCatalogTrack(catalog, catalogEnabled, id) && fck?.audio?.containsKey(id) == true

        internal fun parse(root: JSONObject): Catalog =
            parseSources(listOf(root), listOf(root), requireAudio = true)

        internal fun parseCatalog(root: JSONObject): Catalog =
            parseSources(listOf(root), listOf(root), requireAudio = false)

        internal fun mergeCatalogs(catalogRoot: JSONObject?, fckRoot: JSONObject?): Catalog? = when {
            catalogRoot != null && fckRoot != null -> parseSources(
                metadataRoots = listOf(catalogRoot, fckRoot),
                audioRoots = listOf(fckRoot, catalogRoot),
                requireAudio = false,
            )
            catalogRoot != null -> parseCatalog(catalogRoot)
            fckRoot != null -> parse(fckRoot)
            else -> null
        }

        private fun parseSources(
            metadataRoots: List<JSONObject>,
            audioRoots: List<JSONObject>,
            requireAudio: Boolean,
        ): Catalog {
            val tracks = linkedMapOf<String, JSONObject>()
            val albums = linkedMapOf<String, JSONObject>()
            val artists = linkedMapOf<String, JSONObject>()
            val playlists = linkedMapOf<String, JSONObject>()
            val audio = linkedMapOf<String, String>()
            val durations = mutableMapOf<String, Long>()
            val insertions = mutableMapOf<String, Pair<MutableList<String>, MutableList<String>>>()
            val trackAlbums = mutableMapOf<String, JSONObject>()

            for (root in metadataRoots) {
                val sources = root.optJSONArray("sources") ?: throw org.json.JSONException("Missing sources")
                for (source in sources.objects()) {
                    val artistsIns = source.obj("artistsInsertions")
                    artistsIns.keys().forEach { id ->
                        val entry = artistsIns.optJSONObject(id)
                        if (entry != null && id.matches(ID)) {
                            val pair = insertions.getOrPut(id) { mutableListOf<String>() to mutableListOf() }
                            entry.arr("tracks").items().mapNotNull(::idOf).forEach { tid ->
                                if (tid !in pair.first) pair.first.add(tid)
                            }
                            entry.arr("albums").items().mapNotNull(::idOf).forEach { aid ->
                                if (aid !in pair.second) pair.second.add(aid)
                            }
                        }
                    }
                    for ((key, target) in listOf(
                        "tracks" to tracks,
                        "albums" to albums,
                        "artists" to artists,
                        "playlists" to playlists,
                    )) {
                        val list = source.obj(key)
                        list.keys().forEach { id ->
                            if (id.matches(ID) && id !in target) {
                                list.optJSONObject(id)?.let { target[id] = JSONObject(it.toString()) }
                            }
                        }
                    }
                }
            }

            for (root in audioRoots) {
                val sources = root.optJSONArray("sources") ?: throw org.json.JSONException("Missing sources")
                for (source in sources.objects()) {
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
            }

            if (requireAudio && audio.isEmpty()) throw org.json.JSONException("Missing audio entries")
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
            val frozenInsertions = insertions.mapValues { (_, pair) -> pair.first.toList() to pair.second.toList() }
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
                append('|')
                append(artists.keys.sorted().joinToString(","))
                append('|')
                append(playlists.keys.sorted().joinToString(","))
            })
            return Catalog(tracks, albums, audio, frozenInsertions, trackAlbums, byArtist, signature, artists, playlists)
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

        internal fun catalogSynthetic(
            id: String,
            list: Catalog,
            album: Card? = null,
            alternate: Boolean = true,
            catalog: Boolean = false,
        ): Track? {
            if (id !in list.audio && !(isSyntheticTrackId(id) && id in list.tracks)) return null
            val fields = list.tracks[id] ?: return null
            val value = patchTrackObject(JSONObject().put("id", id), id, list)
            if (fields.text("title").isNullOrBlank()) return null
            return Track.parse(value, alternate = alternate, catalog = catalog)?.let {
                it.copy(
                    album = album ?: it.album,
                    cover = it.cover ?: album?.cover,
                    originalAvailable = false,
                )
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

        internal fun searchCatalogTracks(
            list: Catalog,
            restored: Map<String, Track>?,
            query: String,
        ): List<Track> = searchCatalogTracks(
            list = list,
            restored = restored,
            query = query,
            isCatalog = { false },
            isFck = { id -> id in list.audio },
        )

        internal fun searchCatalogTracks(
            list: Catalog,
            restored: Map<String, Track>?,
            query: String,
            isCatalog: (String) -> Boolean,
            isFck: (String) -> Boolean,
        ): List<Track> {
            val terms = searchTokens(query)
            if (terms.isEmpty()) return emptyList()
            val allowPrefix = terms.sumOf(String::length) >= 3
            val candidates = LinkedHashMap<String, Track>()
            restored?.forEach { (id, track) ->
                if (!isCatalog(id)) candidates[id] = track
            }
            for (id in (list.audio.keys + list.tracks.keys)) {
                if (id in candidates) continue
                val fields = list.tracks[id] ?: continue
                val hasAudio = id in list.audio
                if (!hasAudio && fields.arr("artists").length() == 0) continue
                val cat = isCatalog(id)
                val fck = !cat && (isFck(id) || (hasAudio && !cat))
                val value = patchTrackObject(JSONObject().put("id", id), id, list)
                val parsed = Track.parse(value, alternate = fck, catalog = cat)?.let {
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

        internal fun searchCatalogArtists(list: Catalog, query: String): List<JSONObject> {
            val terms = searchTokens(query)
            if (terms.isEmpty()) return emptyList()
            val allowPrefix = terms.sumOf(String::length) >= 3
            data class Hit(val artist: JSONObject, val rank: Int, val name: String, val id: String)
            val hits = ArrayList<Hit>()
            for ((id, fields) in list.artists) {
                val name = fields.text("name")?.takeIf(String::isNotBlank) ?: continue
                val tokens = searchTokens(name)
                if (!terms.all { matchesTerm(it, tokens, allowPrefix) }) continue
                val rank = if (tokens == terms) 0 else 1
                hits += Hit(JSONObject(fields.toString()).put("id", id), rank, name.lowercase(Locale.ROOT), id)
            }
            return hits.sortedWith(compareBy<Hit> { it.rank }.thenBy { it.name }.thenBy { it.id })
                .take(15).map { it.artist }
        }

        internal fun searchCatalogPlaylists(list: Catalog, query: String): List<Pair<String, JSONObject>> {
            val terms = searchTokens(query)
            if (terms.isEmpty()) return emptyList()
            val allowPrefix = terms.sumOf(String::length) >= 3
            data class Hit(val id: String, val fields: JSONObject, val rank: Int, val title: String)
            val hits = ArrayList<Hit>()
            for ((id, fields) in list.playlists) {
                val title = fields.text("title")?.takeIf(String::isNotBlank) ?: continue
                val desc = fields.text("description").orEmpty()
                val titleTokens = searchTokens(title)
                val allTokens = titleTokens + searchTokens(desc)
                if (!terms.all { matchesTerm(it, allTokens, allowPrefix) }) continue
                val rank = when {
                    titleTokens == terms -> 0
                    terms.all { matchesTerm(it, titleTokens, allowPrefix) } -> 1
                    else -> 2
                }
                hits += Hit(id, JSONObject(fields.toString()).put("id", id).put("kind", id), rank, title.lowercase(Locale.ROOT))
            }
            return hits.sortedWith(compareBy<Hit> { it.rank }.thenBy { it.title }.thenBy { it.id })
                .take(15).map { it.id to it.fields }
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
        private val MIRRORS = listOf(
            "https://raw.githubusercontent.com/Hazzz895/FckCensorData/refs/heads/main/",
            "https://cdn.jsdelivr.net/gh/Hazzz895/FckCensorData@main/",
        )
        private val CATALOG_MIRRORS = listOf(
            "https://raw.githubusercontent.com/tune-music/tune-catalog-data/main/",
            "https://cdn.jsdelivr.net/gh/tune-music/tune-catalog-data@main/",
        )
        private val CUPSIZE_TITLES = mapOf(
            "110945082" to "ДАВАЙ ТРАХАТЬСЯ В МАШИНЕ", "110945083" to "люби меня, алина",
            "110945084" to "ГИДРОПОН", "110945085" to "Папик", "110945087" to "вайфуу",
            "110945088" to "я схавал опиат", "110945089" to "Вирус", "110945090" to "МОЯ МАМА ПЬЕТ",
            "110945091" to "Ты любишь травку", "110945092" to "Забуду", "110945093" to "мне похуй"
        )
    }
}
