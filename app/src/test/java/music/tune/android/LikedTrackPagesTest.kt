package music.tune.android

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class LikedTrackPagesTest {
    @Test
    fun showsFirstPageBeforeLoadingRemainingIdsAndPreservesServerOrder() = runBlocking {
        val entries = (1..161).map { it.toString() to it.toLong() } + ("1" to 999L)
        val requests = mutableListOf<List<String>>()
        val previews = mutableListOf<List<Track>>()
        val result = loadLikedTrackPages(entries, onFirstPage = { page ->
            assertEquals(1, requests.size)
            previews.add(page)
        }) { ids ->
            requests.add(ids)
            ids.asReversed().filterNot { it == "2" }.map { Track(it, "Трек $it") }
        }

        assertEquals(listOf(60, 100, 1), requests.map { it.size })
        assertEquals(listOf(result.take(59)), previews)
        assertEquals((1..161).filterNot { it == 2 }.map(Int::toString), result.map { it.id })
        assertEquals(999L, result.first().addedAt)
        assertEquals(161L, result.last().addedAt)
    }

    @Test
    fun cancellationWhileShowingThePreviewPreventsFurtherRequests() = runBlocking {
        val entries = (1..161).map { it.toString() to null }
        val requests = mutableListOf<List<String>>()
        val job = launch {
            val owner = coroutineContext[kotlinx.coroutines.Job]!!
            loadLikedTrackPages(entries, onFirstPage = { owner.cancel(CancellationException()) }) { ids ->
                requests.add(ids)
                ids.map { Track(it, "Трек $it") }
            }
        }
        job.join()

        assertEquals(listOf(60), requests.map { it.size })
    }
}
