package music.tune.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SearchCacheTest {
    @Test
    fun cacheKeysSeparateAccountFilterAndUncensoredMode() {
        val key = SearchCache.Key("42", "Тест", "track", false)
        assertEquals(key.fileName(), key.copy(query = "ТЕСТ").fileName())
        assertNotEquals(key.fileName(), key.copy(account = "43").fileName())
        assertNotEquals(key.fileName(), key.copy(type = "album").fileName())
        assertNotEquals(key.fileName(), key.copy(uncensored = true).fileName())
    }

    @Test
    fun restoresBothBestResultTypesAndKeepsPagination() {
        val track = Track("100", "Трек", explicit = true, originalAvailable = false, alternate = true)
        val card = Card(Kind.PLAYLIST, "3", "Плейлист", "Автор", owner = "42")
        for (best in listOf(SearchBest.Song(track), SearchBest.Entry(card), null)) {
            val result = SearchResults(listOf(track), listOf(card), best, hasMore = true)
            assertEquals(result, SearchCache.decode(SearchCache.encode(result)))
        }
    }
}
