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
    internal data class Catalog(
        val tracks: Map<String, JSONObject>,
        val albums: Map<String, JSONObject>,
        val audio: Map<String, String>,
        val artistInsertions: Map<String, Pair<List<String>, List<String>>>,
        val trackAlbums: Map<String, JSONObject>,
    )

    @Volatile private var catalog: Catalog? = null
    @Volatile private var preferredMirror = MIRRORS.first()
    @Volatile private var retryAfter = 0L
    private var refreshedAt = 0L

    fun ensureLoaded(network: Boolean): Boolean {
        if (!enabled()) return false
        if (catalog != null) return true
        return refresh(network)
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
        val patched = JSONObject(value.toString())
        fields.keys().forEach { key ->
            if (key !in SKIP && !fields.isNull(key) && (key != "artists" || fields.arr(key).length() > 0)) {
                patched.put(key, fields.get(key))
            }
        }
        return patched
    }

    fun albumTracks(id: String) = active()?.albums?.get(id)?.arr("volumes")?.items()?.filterIsInstance<JSONArray>()
        ?.flatMap { disk -> disk.items().mapNotNull(::idOf) }.orEmpty()

    fun catalogAlbum(id: String): JSONObject? = active()?.albums?.get(id)?.let { JSONObject(it.toString()).put("id", id) }

    fun artistInsertions(id: String): Pair<List<String>, List<String>> =
        active()?.artistInsertions?.get(id) ?: (emptyList<String>() to emptyList())

    fun searchAlbums(query: String): List<JSONObject> {
        val words = query.lowercase(Locale.ROOT).split(Regex("\\s+")).filter(String::isNotBlank)
        if (words.isEmpty()) return emptyList()
        return active()?.albums?.keys.orEmpty().asSequence().mapNotNull(::catalogAlbum).filter { album ->
            val names = album.arr("artists").objects().mapNotNull { it.text("name") }.joinToString(" ")
            val content = (album.text("title").orEmpty() + " " + names).lowercase(Locale.ROOT)
            words.all { it in content }
        }.take(15).toList()
    }

    fun synthetic(id: String, album: Card? = null): Track? {
        val list = active() ?: return null
        if (id !in list.audio) return null
        val fields = list.tracks[id] ?: return null
        val value = JSONObject(fields.toString()).put("id", id)
        list.trackAlbums[id]?.let { release ->
            if (value.arr("albums").length() == 0) value.put("albums", JSONArray().put(release))
            if (value.arr("artists").length() == 0) value.put("artists", release.arr("artists"))
        }
        return Track.parse(value, alternate = true)?.let {
            it.copy(album = album ?: it.album, cover = it.cover ?: album?.cover, originalAvailable = false)
        }
    }

    fun search(query: String): List<Track> {
        val words = query.lowercase(Locale.ROOT).split(Regex("\\s+")).filter(String::isNotBlank)
        if (words.isEmpty()) return emptyList()
        return active()?.audio?.keys.orEmpty().asSequence().mapNotNull { synthetic(it) }.filter { track ->
            val text = "${track.title} ${track.artistText}".lowercase(Locale.ROOT)
            words.all { it in text }
        }.take(30).toList()
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
            return Catalog(tracks, albums, audio, insertions, trackAlbums)
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
