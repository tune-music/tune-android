package music.tune.android

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.Locale

internal data class ClipSource(val url: String, val mime: String)

internal fun selectedClipId(rawIds: String?, activeIndex: String?): String? {
    val ids = rawIds.orEmpty().split(',').filter { it.matches(NUMERIC_CLIP_ID) }
    if (ids.isEmpty()) return null
    val index = activeIndex?.toIntOrNull()?.coerceIn(0, ids.lastIndex) ?: 0
    return ids[index]
}

internal fun validatedClipPlayerId(value: String?): String? = value?.takeIf { it.matches(CLIP_ID) }

internal fun legacyClipPlayerId(url: String, value: String?): String? =
    validatedClipPlayerId(value)?.takeIf { safeClipUri(url)?.host.equals("frontend.vh.yandex.ru", ignoreCase = true) }

internal fun clipPlayerId(lookup: Any): String? {
    val rows = when (lookup) {
        is JSONArray -> lookup.objects()
        is JSONObject -> lookup.arr("clips").objects().ifEmpty { listOf(lookup) }
        else -> emptyList()
    }
    return rows.firstNotNullOfOrNull { validatedClipPlayerId(it.text("playerId")) }
}

internal fun clipSources(video: JSONObject, status: Int): List<ClipSource> {
    if (video.text("error") == "no_licenses") throw MusicError("Клип недоступен в вашем регионе")
    if (status !in 200..299) throw MusicError("Клип недоступен")
    val streams = video.obj("content").arr("streams").objects()
    return listOf("HLS" to "application/x-mpegURL", "DASH" to "application/dash+xml").flatMap { (kind, mime) ->
        streams.mapNotNull { stream ->
            if (stream.text("stream_type")?.uppercase(Locale.ROOT) != kind) return@mapNotNull null
            val address = stream.text("url") ?: return@mapNotNull null
            safeClipUri(address)?.let { ClipSource(address, mime) }
        }
    }.distinctBy(ClipSource::url).ifEmpty { throw MusicError("Яндекс не прислал видеопоток") }
}

private val CLIP_ID = Regex("[a-zA-Z0-9_-]+")
private val NUMERIC_CLIP_ID = Regex("[0-9]+")

private fun safeClipUri(value: String): URI? = optional { URI(value) }
    ?.takeIf { it.scheme.equals("https", ignoreCase = true) && !it.host.isNullOrBlank() && it.rawUserInfo == null }
