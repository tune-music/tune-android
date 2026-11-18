package music.tune.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CrossfadeTest {
    @Test
    fun disablesForOffShortTracksAndSpokenAudio() {
        val a = Track("1", "Track A", duration = 180_000)
        val b = Track("2", "Track B", duration = 180_000)
        assertNull(fadeWindow(a, b, 180_000, setting = 0, mix = false))
        assertNull(fadeWindow(a.copy(duration = 25_000), b, 25_000, setting = 5, mix = false))
        assertNull(fadeWindow(a, b.copy(duration = 25_000), 180_000, setting = -1, mix = false))
        assertNull(fadeWindow(a.copy(mediaType = "podcast_episode"), b, 180_000, setting = -1, mix = false))
        assertNull(fadeWindow(a, b.copy(album = Card(Kind.ALBUM, "a1", "Book", releaseType = "audiobook")), 180_000, setting = 5, mix = false))
    }

    @Test
    fun manualDurationMatchesFadeInAndOutSpan() {
        val a = Track("1", "Track A", duration = 180_000, fade = FadePoints(0.0, 0.2, 179.5, 180.0))
        val b = Track("2", "Track B", duration = 200_000, fade = FadePoints(0.0, 0.1, 195.0, 200.0))
        val window = fadeWindow(a, b, 180_000, setting = 5, mix = false)
        assertEquals(FadeWindow(175_000, 180_000, 0, 5_000), window)
    }

    @Test
    fun autoModeWithoutMarkersUsesSixSecondEqualCrossfade() {
        val a = Track("1", "Track A", duration = 180_000)
        val b = Track("2", "Track B", duration = 200_000)
        assertEquals(FadeWindow(174_000, 180_000, 0, 6_000), fadeWindow(a, b, 180_000, setting = -1, mix = false))
    }

    @Test
    fun autoModeFixesColdEndingShortFadeAndLongOutroMarkers() {
        val coldOut = Track("1", "Cold", duration = 180_000, fade = FadePoints(0.0, 0.2, 178.6, 179.0))
        val coldIn = Track("2", "Sudden", duration = 200_000, fade = FadePoints(0.5, 0.7, 196.0, 200.0))
        assertEquals(FadeWindow(175_000, 179_000, 500, 4_500), fadeWindow(coldOut, coldIn, 180_000, setting = -1, mix = false))

        val longOutro = Track("3", "Long", duration = 180_000, fade = FadePoints(0.0, 0.5, 160.0, 174.0))
        assertEquals(FadeWindow(168_000, 174_000, 500, 6_500), fadeWindow(longOutro, coldIn, 180_000, setting = -1, mix = false))
    }

    @Test
    fun mixWaveRespectsMixFadePoints() {
        val a = Track(
            id = "1",
            title = "Mix A",
            duration = 200_000,
            mixFade = FadePoints(0.0, 5.0, 185.0, 195.0),
        )
        val b = Track(
            id = "2",
            title = "Mix B",
            duration = 210_000,
            mixFade = FadePoints(3.0, 11.0, 195.0, 205.0),
        )
        assertEquals(FadeWindow(185_000, 195_000, 3_000, 11_000), fadeWindow(a, b, 200_000, setting = -1, mix = true))

        val bShortIn = b.copy(mixFade = FadePoints(2.0, 2.3, 195.0, 205.0))
        assertEquals(FadeWindow(185_000, 195_000, 2_000, 12_000), fadeWindow(a, bShortIn, 200_000, setting = -1, mix = true))
    }
}
