package music.tune.android

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal suspend fun loadLikedTrackPages(
    entries: List<Pair<String, Long?>>,
    onFirstPage: ((List<Track>) -> Unit)?,
    fetch: suspend (List<String>) -> List<Track>,
): List<Track> {
    val dates = entries.toMap()
    val ids = dates.keys.toList()
    val first = ids.take(if (onFirstPage != null) 60 else 100)
    val remaining = ids.drop(first.size)
    val pages = listOf(first) + remaining.chunked(100)
    return buildList {
        for ((index, page) in pages.withIndex()) {
            if (page.isEmpty()) continue
            currentCoroutineContext().ensureActive()
            val tracks = fetch(page).associateBy { it.id }
            currentCoroutineContext().ensureActive()
            val ordered = page.mapNotNull { id -> tracks[id]?.copy(addedAt = dates[id]) }
            addAll(ordered)
            if (index == 0 && remaining.isNotEmpty() && ordered.isNotEmpty()) onFirstPage?.invoke(ordered)
        }
    }
}
