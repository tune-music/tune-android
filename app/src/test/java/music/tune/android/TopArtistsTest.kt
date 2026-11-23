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

    @Test
    fun prefersSoloTrackCoverOverFeatureCoverInListeningSummary() {
        val feat = Track(
            id = "1",
            title = "Фит",
            artists = listOf(Card(Kind.ARTIST, "a1", "Артист 1"), Card(Kind.ARTIST, "a2", "Артист 2")),
            cover = "feat-cover",
            duration = 120_000,
        )
        val solo = Track(
            id = "2",
            title = "Соло",
            artists = listOf(Card(Kind.ARTIST, "a1", "Артист 1")),
            cover = "solo-cover",
            duration = 120_000,
        )

        val summary = summarizeListening(sequenceOf(feat to 120_000L, feat to 120_000L, solo to 120_000L))
        val byId = summary.artists.associate { it.first.id to it.first.cover }
        assertEquals("solo-cover", byId["a1"])
        assertEquals("feat-cover", byId["a2"])
    }
}
