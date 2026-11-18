package music.tune.android

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ClipSourcesTest {
    @Test
    fun resolvesTheSelectedClipInASharedList() {
        assertEquals("42", selectedClipId("11,42,43", "1"))
        assertEquals("43", selectedClipId("11,42,43", "9"))
        assertEquals("11", selectedClipId("11,42", "-1"))
        assertNull(selectedClipId("not-a-number", "1"))
    }

    @Test
    fun resolvesBothLookupShapesAndPreservesCardIdentifiers() {
        val items = JSONArray().put(JSONObject().put("playerId", "video_42"))
        assertEquals("video_42", clipPlayerId(items))
        assertEquals("video_42", clipPlayerId(JSONObject().put("clips", items)))
        assertNull(validatedClipPlayerId("video/42"))
        assertEquals("video_42", legacyClipPlayerId("https://frontend.vh.yandex.ru/embed/video_42", "video_42"))
        assertNull(legacyClipPlayerId("https://example.org/embed/video_42", "video_42"))
        val card = Card(Kind.CLIP, "42", "Клип", clipPlayerId = "video_42")
        assertEquals(card, Card.fromCache(card.cached()))
    }

    @Test
    fun prefersHlsAndRetainsTheValidDashAlternative() {
        val video = JSONObject("""{"content":{"streams":[
            {"stream_type":"HLS","url":"http://cdn.example.org/invalid.m3u8"},
            {"stream_type":"DASH","url":"https://cdn.example.org/valid.mpd"},
            {"stream_type":"HLS","url":"https://cdn.example.org/valid.m3u8"}
        ]}}""")
        assertEquals(listOf(
            ClipSource("https://cdn.example.org/valid.m3u8", "application/x-mpegURL"),
            ClipSource("https://cdn.example.org/valid.mpd", "application/dash+xml"),
        ), clipSources(video, 200))
    }

    @Test
    fun regionalUnavailabilityHasAnExplicitError() {
        val error = assertThrows(MusicError::class.java) {
            clipSources(JSONObject().put("error", "no_licenses"), 403)
        }
        assertEquals("Клип недоступен в вашем регионе", error.message)
    }
}
