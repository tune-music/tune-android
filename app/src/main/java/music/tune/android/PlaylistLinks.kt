package music.tune.android

internal fun playlistTrackIds(text: String): List<String> = Regex(
    """(?i)https?://music\.yandex\.(?:ru|com|kz|by|uz)/(?:album/\d+/)?track/(\d+)(?![a-z0-9_-])"""
).findAll(text).map { it.groupValues[1] }.distinct().take(1000).toList()

internal fun playlistM3u(tracks: List<Track>): String = buildString {
    appendLine("#EXTM3U")
    for (track in tracks) {
        val label = listOf(track.artistText, track.title).filter(String::isNotBlank).joinToString(" — ").replace(Regex("[\r\n]+"), " ")
        appendLine("#EXTINF:${track.duration / 1000},$label")
        appendLine(track.url)
    }
}
