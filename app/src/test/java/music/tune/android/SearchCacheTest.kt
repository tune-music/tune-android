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

    @Test
    fun restoresPodcastAndEpisodeSeparatelyFromMusicResults() {
        val podcast = Card(Kind.ALBUM, "101", "Подкаст", releaseType = "podcast")
        val single = Card(Kind.ALBUM, "102", "Сингл", releaseType = "single")
        val episode = Track("201", "Выпуск", album = podcast, mediaType = "podcast_episode")
        val song = Track("202", "Трек", album = single)
        val page = SearchResults(listOf(song, episode), listOf(podcast, single), SearchBest.Song(episode))
        val restored = SearchCache.decode(SearchCache.encode(page))

        assertEquals(listOf(podcast), restored.podcasts)
        assertEquals(listOf(episode), restored.episodes)
        assertEquals(listOf(song), restored.songs)
        assertEquals(SearchBest.Song(episode), restored.best)
    }
}
