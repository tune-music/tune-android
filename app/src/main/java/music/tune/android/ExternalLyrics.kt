package music.tune.android

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.net.URI
import java.net.URLEncoder
import java.util.Locale
import kotlin.math.abs

internal data class LyricsHit(val id: String?, val title: String, val artist: String, val seconds: Int)

internal class ExternalLyrics(private val store: MusicStore) {
    private val prefs get() = store.preferences
    private val enabled get() = prefs.getBoolean("lyricsExternal", true)
    private val netEase get() = enabled && prefs.getBoolean("lyricsNetEase", true)
    private val lrclib get() = enabled && prefs.getBoolean("lyricsLRCLIB", true)
    private val configuredMirror get() = prefs.getString("lyricsMirror", null)?.let(::lyricsMirror)
    private val always get() = enabled && prefs.getBoolean("lyricsAlways", false)
    val cacheKey get() = listOf(netEase, lrclib, prefs.getBoolean("lyricsNetEaseFirst", false), configuredMirror, always).joinToString(":")

    fun find(track: Track, yandex: () -> LyricsText): LyricsText {
        var plain = LyricsText()
        var failure: Exception? = null
        val sources = buildList<() -> LyricsText> {
            val firstNetEase = prefs.getBoolean("lyricsNetEaseFirst", false)
            add(yandex)
            if (firstNetEase && netEase) add { LyricsText(netEase(track), source = LyricsSource.NETEASE) }
            if (lrclib) add { LyricsText(lrclib(track), source = LyricsSource.LRCLIB) }
            if (!firstNetEase && netEase) add { LyricsText(netEase(track), source = LyricsSource.NETEASE) }
        }
        for ((index, source) in sources.withIndex()) {
            val result = try { source() }
            catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                failure = failure ?: error
                continue
            }
            val lines = result.lines.filterNot {
                it.text.contains("текст доступен по подписке", ignoreCase = true) ||
                    (index > 0 && isCredit(it.text))
            }
            if (lines.none { it.text.isNotBlank() }) continue
            if (lines.any { it.position != null } && (index != 0 || !always)) return result.copy(lines = lines)
            if (plain.lines.isEmpty()) plain = result.copy(lines = lines)
        }
        if (plain.lines.isEmpty() && failure is MusicError && failure.status == 401) throw failure
        return plain
    }

    private fun isCredit(line: String): Boolean {
        val text = line.trim()
        val separator = text.indexOfFirst { it == ':' || it == '：' }
        if (separator !in 1..24) return false
        val label = text.take(separator).trim().lowercase(Locale.ROOT)
        return label.any { it in '\u4E00'..'\u9FFF' } || CREDIT_LABELS.any(label::startsWith)
    }

    fun searchByLine(query: String): List<LyricsHit> =
        if (!netEase || query.trim().split(Regex("\\s+")).size < 2) emptyList() else searchNetEase(query, 1006)

    private fun get(hosts: List<String>, path: String, netease: Boolean = false): Any? {
        for (host in hosts) {
            val connection = optional { secureConnection(host + path) } ?: continue
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 5_000
                connection.readTimeout = 6_000
                connection.setRequestProperty("User-Agent", "Tune Android/${BuildConfig.VERSION_NAME}")
                if (netease) connection.setRequestProperty("Referer", "https://music.163.com/")
                if (connection.responseCode in setOf(403, 429) || connection.responseCode >= 500) continue
                if (connection.responseCode !in 200..299) return null
                val text = connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                    val buffer = CharArray(8192)
                    val out = StringBuilder()
                    while (out.length <= 512 * 1024) {
                        val size = reader.read(buffer)
                        if (size < 0) break
                        out.append(buffer, 0, size)
                    }
                    out.toString().takeIf { it.length <= 512 * 1024 }
                } ?: continue
                return optional { JSONTokener(text).nextValue() }
            } catch (_: java.io.IOException) {
                // Try the provider's next endpoint.
            } finally {
                connection.disconnect()
            }
        }
        return null
    }

    private fun lrclib(track: Track): List<LyricsLine> {
        val hosts = (listOfNotNull(configuredMirror) + "https://lrclib.net").distinct()
        val artist = track.artists.firstOrNull()?.title.orEmpty()
        val base = "track_name=${encode(track.title)}&artist_name=${encode(artist)}"
        val duration = (track.duration / 1000).toInt()
        val params = if (duration > 0) "$base&duration=$duration" else base
        val query = track.album?.title?.takeIf(String::isNotBlank)?.let { "$params&album_name=${encode(it)}" } ?: params
        val exact = get(hosts, "/api/get?$query") as? JSONObject
        exact?.text("syncedLyrics")?.let { return parseLyrics(it) }
        val matches = (get(hosts, "/api/search?$base") as? JSONArray)?.objects().orEmpty().filter {
            lyricsMatch(track, LyricsHit(it.text("id"), it.text("trackName").orEmpty(), it.text("artistName").orEmpty(), it.optInt("duration")))
        }
        val value = matches.firstOrNull { it.text("syncedLyrics") != null } ?: exact ?: matches.firstOrNull()
        return (value?.text("syncedLyrics") ?: value?.text("plainLyrics"))?.let(::parseLyrics).orEmpty()
    }

    private fun netEase(track: Track): List<LyricsLine> {
        val query = "${track.artists.firstOrNull()?.title.orEmpty()} ${track.title}".trim()
        val candidates = searchNetEase(query, 1).filter { lyricsMatch(track, it) }.take(3)
        for (hit in candidates) {
            val id = hit.id?.takeIf { it.all(Char::isDigit) } ?: continue
            val result = get(NETEASE, "/api/song/lyric?id=$id&lv=1", true) as? JSONObject ?: continue
            val lines = result.obj("lrc").text("lyric")?.let(::parseLyrics).orEmpty()
            if (lines.count { it.position != null && it.text.isNotBlank() } >= 4) return lines
        }
        return emptyList()
    }

    private fun searchNetEase(query: String, type: Int): List<LyricsHit> {
        val payload = get(NETEASE, "/api/search/get?s=${encode(query)}&type=$type&limit=8", true) as? JSONObject
            ?: return emptyList()
        return payload.obj("result").arr("songs").objects().mapNotNull { song ->
            val title = song.text("name") ?: return@mapNotNull null
            val artist = song.arr("artists").objects().mapNotNull { it.text("name") }.joinToString(" ")
            if (artist.isBlank()) return@mapNotNull null
            LyricsHit(song.text("id"), title, artist, (song.optLong("duration") / 1000).toInt())
        }
    }

    private fun encode(text: String) = URLEncoder.encode(text, "UTF-8")

    companion object {
        private val CREDIT_LABELS = listOf(
            "lyrics", "lyricist", "composer", "composed", "written", "producer", "produced",
            "arranger", "arranged", "mixing", "mixed", "mastering", "текст", "слова", "музыка",
            "автор", "аранжировка", "сведение", "мастеринг"
        )
        private val NETEASE = listOf("https://music.163.com", "https://interface.music.163.com", "https://interface3.music.163.com")
    }
}

internal fun lyricsMirror(input: String): String? {
    val clean = input.trim().trimEnd('/').takeIf(String::isNotEmpty) ?: return null
    val uri = optional { URI(if ("://" in clean) clean else "https://$clean") } ?: return null
    if (uri.scheme != "https" || uri.host == null || uri.rawUserInfo != null || uri.query != null || uri.fragment != null) return null
    return uri.toString()
}

internal fun lyricsMatch(track: Track, hit: LyricsHit): Boolean {
    fun clean(text: String) = text.lowercase(Locale.ROOT).replace('ё', 'е').replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
    val duration = track.duration / 1000
    val title = clean(track.title)
    if (title.isBlank() || title != clean(hit.title)) return false
    if (duration > 0 && hit.seconds > 0 && abs(duration - hit.seconds) > 8) return false
    val name = clean(hit.artist)
    return track.artists.any { artist -> clean(artist.title).let { it.isNotBlank() && (" $it " in " $name " || " $name " in " $it ") } }
}
