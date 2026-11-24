package music.tune.android

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

internal fun JSONObject.obj(name: String) = optJSONObject(name) ?: JSONObject()
internal fun JSONObject.arr(name: String) = optJSONArray(name) ?: JSONArray()
internal fun JSONObject.text(name: String): String? = opt(name).asId()
internal fun JSONArray.items(): List<Any?> = List(length()) { opt(it) }
internal fun JSONArray.objects() = items().filterIsInstance<JSONObject>()
internal fun Any?.asId(): String? = when (this) {
    is String -> takeIf(String::isNotBlank)
    is Number -> toString()
    else -> null
}

internal data class Track(
    val id: String,
    val title: String,
    val artists: List<Card> = emptyList(),
    val cover: String? = null,
    val duration: Long = 0,
    val album: Card? = null,
    val unavailable: Boolean = false,
    val explicit: Boolean = false,
    val alternate: Boolean = false,
    val batch: String? = null,
    val loudness: Double? = null,
    val peak: Double? = null,
    val fade: FadePoints? = null,
    val mixFade: FadePoints? = null,
    val mediaType: String? = null,
    val originalAvailable: Boolean? = null,
    val historyDate: String? = null,
    val historySource: String? = null,
    val addedAt: Long? = null,
    val uploaded: Boolean = false,
) {
    val artistText get() = artists.joinToString(", ") { it.title }
    val isPodcastEpisode get() = mediaType == "podcast_episode" || album?.isPodcast == true
    val fullId get() = album?.let { "$id:${it.id}" } ?: id
    val url get() = album?.let { "https://music.yandex.ru/album/${it.id}/track/$id" }
        ?: "https://music.yandex.ru/track/$id"

    fun withUploadedMetadata(title: String, artist: String) = copy(title = title,
        artists = listOf(artist).filter(String::isNotBlank).map { Card(Kind.ARTIST, "", it) })

    fun stored() = JSONObject().put("id", id).put("title", title).put("coverUri", cover)
        .put("durationMs", duration).put("available", !unavailable)
        .put("contentWarning", if (explicit) "explicit" else null)
        .put("alternate", alternate).put("batch", batch)
        .put("r128", JSONObject().put("i", loudness).put("tp", peak))
        .put("fade", fade?.stored()).put("mixFade", mixFade?.stored()).put("type", mediaType)
        .put("originalAvailable", originalAvailable)
        .put("historyDate", historyDate).put("historySource", historySource)
        .put("addedAt", addedAt)
        .put("trackSource", if (uploaded) "UGC" else null)
        .put("artists", JSONArray(artists.map { it.stored() }))
        .put("albums", JSONArray(listOfNotNull(album?.stored())))

    companion object {
        fun parse(data: JSONObject, alternate: Boolean = data.optBoolean("alternate"), batch: String? = data.text("batch")): Track? {
            val id = data.text("id") ?: return null
            val name = data.text("title") ?: return null
            val album = data.arr("albums").optJSONObject(0)?.let { Card.album(it, requireTitle = false) }
            val version = data.text("version")
            return Track(
                id, if (version == null) name else "$name ($version)",
                data.arr("artists").objects().mapNotNull(Card::artist),
                data.text("coverUri") ?: album?.cover, data.optLong("durationMs").coerceAtLeast(0),
                album, !data.optBoolean("available", true) && !alternate,
                data.text("contentWarning") == "explicit", alternate, batch,
                data.obj("r128").optDouble("i").takeIf(Double::isFinite), data.obj("r128").optDouble("tp").takeIf(Double::isFinite),
                FadePoints.parse(data.obj("fade")), FadePoints.parse(data.obj("mixFade")), data.text("type"),
                data.opt("originalAvailable") as? Boolean,
                data.text("historyDate"), data.text("historySource"),
                data.optLong("addedAt", 0).takeIf { it > 0 },
                data.text("trackSource")?.let { it == "UGC" } ?: id.any { !it.isDigit() },
            )
        }
    }
}

internal data class FadePoints(val inStart: Double, val inStop: Double, val outStart: Double, val outStop: Double) {
    fun stored() = JSONObject().put("inStart", inStart).put("inStop", inStop).put("outStart", outStart).put("outStop", outStop)

    companion object {
        fun parse(json: JSONObject): FadePoints? {
            val values = listOf("inStart", "inStop", "outStart", "outStop").map { json.optDouble(it) }
            if (values.any { !it.isFinite() || it < 0.0 } || values[1] < values[0] || values[3] <= values[2]) return null
            return FadePoints(values[0], values[1], values[2], values[3])
        }
    }
}

internal enum class Kind(val label: String) {
    ALBUM("Альбом"), ARTIST("Исполнитель"), PLAYLIST("Плейлист"), WAVE("Волна"), CLIP("Клип"), CONCERT("Концерт")
}

internal data class Card(
    val kind: Kind,
    val id: String,
    val title: String,
    val subtitle: String = "",
    val cover: String? = null,
    val owner: String? = null,
    val seeds: List<String> = emptyList(),
    val link: String? = null,
    val releaseType: String? = null,
    val eventDate: String? = null,
    val clipPlayerId: String? = null,
    val color: String? = null,
    val trackCount: Int = 0,
) {
    val key get() = "${kind.name}:${owner.orEmpty()}:$id"
    val isPodcast get() = kind == Kind.ALBUM && releaseType == "podcast"
    val url: String? get() = when (kind) {
        Kind.ALBUM -> "https://music.yandex.ru/album/$id"
        Kind.ARTIST -> "https://music.yandex.ru/artist/$id"
        Kind.PLAYLIST -> "https://music.yandex.ru/users/$owner/playlists/$id"
        Kind.CLIP -> link ?: "https://music.yandex.ru/video?ids=$id"
        else -> link
    }

    fun stored() = JSONObject().put("id", id).put("title", title).put("name", title)
        .put("coverUri", cover).put("subtitle", subtitle).put("type", releaseType)

    fun cached() = stored().put("kind", kind.name).put("owner", owner)
        .put("seeds", JSONArray(seeds)).put("link", link).put("eventDate", eventDate)
        .put("clipPlayerId", clipPlayerId).put("color", color).put("trackCount", trackCount)

    companion object {
        fun fromCache(data: JSONObject): Card? {
            val kind = Kind.entries.firstOrNull { it.name == data.text("kind") } ?: return null
            val id = data.text("id") ?: return null
            val title = data.text("title") ?: return null
            return Card(kind, id, title, data.text("subtitle").orEmpty(), data.text("coverUri"),
                data.text("owner"), data.arr("seeds").items().mapNotNull(Any?::asId), data.text("link"), data.text("type"),
                data.text("eventDate"), data.text("clipPlayerId"), data.text("color"), data.optInt("trackCount", 0))
        }

        fun album(value: JSONObject, requireTitle: Boolean = true): Card? {
            val id = value.text("id") ?: return null
            val title = value.text("title") ?: if (requireTitle) return null else "Альбом"
            val subtitle = value.text("subtitle") ?: (
                value.arr("artists").objects().mapNotNull { it.text("name") } + listOfNotNull(value.text("year"))
            ).joinToString(" · ")
            return Card(Kind.ALBUM, id, title, subtitle, value.text("coverUri") ?: value.obj("cover").text("uri"),
                releaseType = value.text("type"))
        }

        fun artist(value: JSONObject): Card? {
            val name = value.text("name") ?: return null
            return Card(Kind.ARTIST, value.text("id").orEmpty(), name, "Исполнитель",
                value.obj("cover").text("uri") ?: value.text("coverUri") ?: value.text("ogImage"))
        }

        fun playlist(value: JSONObject, fallbackOwner: String? = null): Card? {
            val id = value.text("kind") ?: return null
            val title = value.text("title") ?: return null
            val owner = value.obj("owner").text("uid") ?: value.text("uid") ?: fallbackOwner ?: return null
            val cover = value.obj("cover")
            return Card(Kind.PLAYLIST, id, title, value.obj("owner").text("name") ?: "Плейлист",
                cover.text("uri") ?: cover.arr("itemsUri").opt(0).asId() ?: value.text("ogImage"), owner,
                trackCount = value.optInt("trackCount", 0))
        }

        fun wave(value: JSONObject): Card? {
            val wave = value.obj("wave")
            val seeds = (value.optJSONArray("seeds") ?: wave.arr("seeds")).items().mapNotNull(Any?::asId)
            val title = value.text("title") ?: wave.text("name") ?: return null
            if (seeds.isEmpty()) return null
            val color = value.obj("colors").text("average")
                ?: value.obj("agent").obj("cover").text("color")
                ?: wave.obj("colors").text("average")
                ?: value.obj("icon").text("backgroundColor")
                ?: value.text("backgroundColor")
            val cover = value.text("compactImageUrl")
                ?: value.obj("agent").obj("cover").text("uri")
                ?: value.text("imageUrl")
                ?: value.text("backgroundImageUrl")
            return Card(Kind.WAVE, seeds.joinToString(","), title, "", cover, seeds = seeds, color = color)
        }
    }
}

internal fun parseHexColor(raw: String?): Int? {
    val text = raw?.trim()?.takeIf(String::isNotEmpty) ?: return null
    val digits = text.removePrefix("#")
    return when (digits.length) {
        6 -> digits.toLongOrNull(16)?.let { (0xff000000L or it).toInt() }
        8 -> digits.toLongOrNull(16)?.toInt()
        else -> optional { android.graphics.Color.parseColor(text) }
    }
}

internal data class Shelf(val title: String, val cards: List<Card>)
internal fun formatConcertDate(value: String): String? {
    val day = optional { java.time.LocalDate.parse(value.substringBefore('T')) } ?: return null
    val date = day.format(java.time.format.DateTimeFormatter.ofPattern("d MMMM uuuu", Locale.forLanguageTag("ru")))
    val time = value.substringAfter('T', "").take(5)
        .takeIf { candidate -> optional { java.time.LocalTime.parse(candidate) } != null }
    return if (time == null) date else "$date · $time"
}
internal data class HomePage(val personal: List<Shelf> = emptyList(), val trends: List<Shelf> = emptyList(), val recent: List<Track> = emptyList()) {
    val hasContent get() = personal.isNotEmpty() || trends.isNotEmpty() || recent.isNotEmpty()
}
internal data class MusicPage(
    val card: Card,
    val tracks: List<Track>,
    val shelves: List<Shelf> = emptyList(),
    val description: String? = null,
    val disks: List<Int> = emptyList(),
    val publicPlaylist: Boolean? = null,
    val latestRelease: Card? = null,
)
internal sealed interface SearchBest {
    data class Song(val track: Track) : SearchBest
    data class Entry(val card: Card) : SearchBest
}
internal data class SearchResults(
    val tracks: List<Track>,
    val cards: List<Card>,
    val best: SearchBest? = null,
    val hasMore: Boolean? = null,
) {
    val episodes get() = tracks.filter(Track::isPodcastEpisode)
    val songs get() = tracks.filterNot(Track::isPodcastEpisode)
    val podcasts get() = cards.filter(Card::isPodcast)
}
internal data class Profile(val uid: String, val name: String, val plus: Boolean, val login: String = "")
internal data class Wave(val session: String, val tracks: List<Track>)
internal data class WaveRoom(val id: String, val name: String, val status: String, val people: List<String>, val seeds: List<String>) {
    val link get() = "https://music.yandex.ru/multivibe/$id"
    val canListen get() = status == "ENABLED" && seeds.isNotEmpty()

    companion object {
        fun parse(value: JSONObject): WaveRoom? {
            val id = value.text("id")?.takeIf { it.matches(Regex("[a-zA-Z0-9_-]{1,150}")) } ?: return null
            val root = value.obj("wave")
            val wave = if (root.has("seeds")) root else root.obj("wave")
            val names = listOfNotNull(value.obj("owner").text("name")) +
                value.arr("members").objects().mapNotNull { it.text("name") }
            return WaveRoom(id, value.text("name") ?: wave.text("name") ?: "Моя волна на двоих",
                value.text("status") ?: "DRAFT", names.distinct(), wave.arr("seeds").items().mapNotNull(Any?::asId))
        }
    }
}
internal data class LyricsLine(val position: Long?, val text: String)

internal enum class LyricsSource(val label: String?) {
    YANDEX(null), LRCLIB("LRCLIB"), NETEASE("NetEase")
}

internal data class LyricsText(
    val lines: List<LyricsLine> = emptyList(),
    val authors: List<String> = emptyList(),
    val source: LyricsSource? = null
) {
    fun toCache(settings: String): String {
        val rows = JSONArray(lines.map { JSONObject().put("text", it.text).put("position", it.position) })
        return JSONObject().put("lines", rows).put("settings", settings)
            .put("authors", JSONArray(authors)).put("source", source?.name).toString()
    }

    companion object {
        fun fromCache(raw: String, settings: String?, anySource: Boolean): LyricsText? {
            val value = org.json.JSONTokener(raw).nextValue()
            val metadata = value as? JSONObject
            if (metadata != null && !anySource && metadata.text("settings") != settings) return null
            val rows = (value as? JSONArray) ?: metadata?.optJSONArray("lines") ?: return null
            val lines = rows.objects().map {
                LyricsLine(it.optLong("position", -1).takeIf { position -> position >= 0 }, it.optString("text"))
            }
            val authors = metadata?.arr("authors")?.items()?.filterIsInstance<String>().orEmpty()
            val source = LyricsSource.entries.firstOrNull { it.name == metadata?.text("source") }
            return LyricsText(lines, authors, source)
        }
    }
}
internal data class Stream(val urls: List<String>, val key: String? = null, val codec: String = "mp3")
internal data class PlayContext(val kind: String = "various", val item: String? = null, val name: String = "", val wave: String? = null)
internal data class TrackFilter(val id: String, val name: String, val trackIds: Set<String>)

internal fun selectTrackFilters(raw: List<TrackFilter>, catalogIds: Set<String>): List<TrackFilter> {
    if (catalogIds.size < 4) return emptyList()
    val minTracks = when {
        catalogIds.size >= 40 -> 8
        catalogIds.size >= 16 -> 3
        else -> 2
    }
    val selected = raw.mapNotNull { filter ->
        if (filter.id == "all" || filter.id.isBlank() || filter.name.isBlank()) return@mapNotNull null
        val matched = filter.trackIds.intersect(catalogIds)
        if (matched.size < minTracks || matched.size >= catalogIds.size) return@mapNotNull null
        TrackFilter(filter.id, filter.name, matched)
    }.take(7)
    return if (selected.size >= 2) selected else emptyList()
}

internal fun coverUrl(cover: String?, size: Int): String? {
    if (cover.isNullOrBlank()) return null
    if (cover.startsWith("tune-cover:")) return null
    val side = when {
        size <= 100 -> 100
        size <= 200 -> 200
        size <= 300 -> 300
        size <= 400 -> 400
        size <= 600 -> 600
        size <= 800 -> 800
        else -> 1000
    }
    val url = cover.replace("%%", "${side}x$side").removePrefix("https://").removePrefix("http://").removePrefix("//")
    return "https://$url"
}

internal fun timeLabel(ms: Long): String {
    val seconds = ms.coerceAtLeast(0) / 1000
    return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)
}

internal fun parseLyrics(text: String): List<LyricsLine> {
    val stamps = Regex("""\[(\d+):([0-5]?\d(?:[.,]\d+)?)\]""")
    val offset = Regex("""(?im)^\[offset:([+-]?\d+)\]""").find(text)?.groupValues?.get(1)?.toLongOrNull() ?: 0
    val lines = text.lineSequence().map(String::trim).filter(String::isNotEmpty).flatMap { line ->
        val matches = stamps.findAll(line).toList()
        when {
            matches.isNotEmpty() -> {
                val words = line.substring(matches.last().range.last + 1).trim()
                matches.asSequence().map { match ->
                    val minutes = match.groupValues[1].toLongOrNull() ?: 0
                    val seconds = match.groupValues[2].replace(',', '.').toDouble()
                    LyricsLine((minutes * 60_000 + (seconds * 1000).toLong() + offset).coerceAtLeast(0), words)
                }
            }
            line.startsWith("[") && line.endsWith("]") && ':' in line -> emptySequence()
            else -> sequenceOf(LyricsLine(null, line))
        }
    }.toList()
    return if (lines.all { it.position == null }) lines else lines.sortedBy { it.position ?: Long.MAX_VALUE }
}
