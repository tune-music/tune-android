package music.tune.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LyricsCacheTest {
    private val lines = listOf(LyricsLine(500, "First line"), LyricsLine(null, "Untimed line"))

    @Test
    fun preservesAuthorsSourceAndTiming() {
        val lyrics = LyricsText(lines, listOf("First Writer", "Second Writer"), LyricsSource.YANDEX)
        assertEquals(lyrics, LyricsText.fromCache(lyrics.toCache("current"), "current", anySource = false))
    }

    @Test
    fun readsBothLegacyFormatsAndRespectsOfflinePolicy() {
        val raw = """[{"position":500,"text":"First line"},{"text":"Untimed line"}]"""
        assertEquals(LyricsText(lines), LyricsText.fromCache(raw, "current", anySource = false))
        val previous = """{"settings":"previous","lines":$raw}"""
        assertNull(LyricsText.fromCache(previous, "current", anySource = false))
        assertEquals(LyricsText(lines), LyricsText.fromCache(previous, "current", anySource = true))
    }
}
