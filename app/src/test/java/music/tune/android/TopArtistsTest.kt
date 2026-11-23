package music.tune.android

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class TopArtistsTest {
    @Test
    fun parsesTopArtistsWithRankAndListeningTime() {
        val payload = JSONObject("""
            {
              "artists": [
                {"artist": {"id": 5, "name": "Дора"}, "listenTimeSeconds": 13476, "top": {"position": 4, "progress": "up"}},
                {"artist": {"id": 6, "name": "ЛСП"}, "listenTimeSeconds": 2700, "top": {"position": 2}},
                {"artist": {"id": 7, "name": "Кино"}, "listenTimeSeconds": 30, "top": {"position": 5}},
                {"artist": {"id": 8, "name": "Земфира"}, "listenTimeSeconds": 600}
              ]
            }
        """.trimIndent())

        val parsed = MusicApi.parseTopArtists(payload)
        assertEquals(listOf("Дора", "ЛСП", "Кино", "Земфира"), parsed.map { it.title })
        assertEquals(
            listOf("4 место · 3 ч", "2 место · 45 мин", "5 место", "10 мин"),
            parsed.map { it.subtitle }
        )
    }
}
