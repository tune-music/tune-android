package music.tune.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackFiltersTest {
    @Test
    fun largeListRequiresEightTracksAndCapsAtSeven() {
        val catalog = (1..50).map { it.toString() }.toSet()
        val raw = listOf(
            TrackFilter("all", "Всё", catalog),
            TrackFilter("full", "Весь список", catalog),
            TrackFilter("too_small", "Мало", (1..7).map { it.toString() }.toSet()),
        ) + (1..8).map { index ->
            TrackFilter("f$index", "Фильтр $index", (1..10).map { it.toString() }.toSet())
        }

        val selected = selectTrackFilters(raw, catalog)

        assertEquals(7, selected.size)
        assertEquals((1..7).map { "f$it" }, selected.map { it.id })
    }

    @Test
    fun mediumListRequiresThreeTracks() {
        val catalog = (1..20).map { it.toString() }.toSet()
        val raw = listOf(
            TrackFilter("two", "Два", setOf("1", "2")),
            TrackFilter("happy", "Весёлое", setOf("1", "2", "3", "99")),
            TrackFilter("sad", "Грустное", setOf("4", "5", "6")),
        )

        val selected = selectTrackFilters(raw, catalog)

        assertEquals(listOf("happy", "sad"), selected.map { it.id })
        assertEquals(setOf("1", "2", "3"), selected.first().trackIds)
    }

    @Test
    fun smallAlbumOrArtistListRequiresTwoTracksAndExcludesFullCoverage() {
        val catalog = (1..10).map { it.toString() }.toSet()
        val raw = listOf(
            TrackFilter("all_genre", "Поп", catalog),
            TrackFilter("one", "Один", setOf("1")),
            TrackFilter("chill", "Спокойное", setOf("1", "2")),
            TrackFilter("energy", "Бодрое", setOf("3", "4", "5")),
        )

        val selected = selectTrackFilters(raw, catalog)

        assertEquals(listOf("chill", "energy"), selected.map { it.id })
    }

    @Test
    fun returnsEmptyWhenFewerThanTwoFiltersSurvive() {
        val catalog = (1..10).map { it.toString() }.toSet()
        val raw = listOf(
            TrackFilter("only", "Единственный", setOf("1", "2", "3")),
            TrackFilter("too_few", "Мало", setOf("4")),
        )

        assertTrue(selectTrackFilters(raw, catalog).isEmpty())
        assertTrue(selectTrackFilters(raw, setOf("1", "2", "3")).isEmpty())
    }
}
