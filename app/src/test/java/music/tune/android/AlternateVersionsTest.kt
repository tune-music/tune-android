package music.tune.android

import org.json.JSONException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AlternateVersionsTest {
    @Test
    fun readsTheSchemesAndNumberedStorageEntriesUsedByTheCatalog() {
        val catalog = AlternateVersions.parse(JSONObject("""
            {"sources":[{"tracksStorages":[
              {"urlTemplate":"raw.githubusercontent.com/example/data/%%.mp3",
               "trackIds":[1,{"id":2,"durationMs":4500}],
               "tracks":{"3":55,"4":{"url":"other.example.org/audio.mp3"},"5":"https://media.example.org/5.mp3",
                         "6":"//media.example.org/6.mp3","7":"http://media.example.org/7.mp3","8":"file:///8.mp3"}},
              {"urlTemplate":"https://second.example.org/%%.mp3","trackIds":[1]}
            ]}]}
        """.trimIndent()))
        assertEquals(mapOf(
            "1" to "https://second.example.org/1.mp3",
            "2" to "https://raw.githubusercontent.com/example/data/2.mp3",
            "3" to "https://raw.githubusercontent.com/example/data/55.mp3",
            "4" to "https://other.example.org/audio.mp3",
            "5" to "https://media.example.org/5.mp3",
            "6" to "https://media.example.org/6.mp3"
        ), catalog.audio)
        assertEquals(4500L, catalog.tracks.getValue("2").optLong("durationMs"))
    }

    @Test
    fun rebuildsTheMetadataNeededToFindADeletedTrack() {
        val catalog = AlternateVersions.parse(JSONObject("""
            {"sources":[{
              "albums":{"24720439":{"coverUri":"example.org/cover/%%","volumes":[[110945084,110945085]]}},
              "tracksStorages":[{"urlTemplate":"media.example.org/%%.mp3","trackIds":[110945084]}]
            }]}
        """.trimIndent()))
        val track = catalog.tracks.getValue("110945084")
        assertEquals("ГИДРОПОН", track.text("title"))
        assertEquals("CUPSIZE", track.arr("artists").getJSONObject(0).text("name"))
        assertEquals("example.org/cover/%%", track.text("coverUri"))
        assertEquals("24720439", catalog.trackAlbums.getValue("110945084").text("id"))
        assertFalse(catalog.tracks.getValue("110945085").has("title"))
    }

    @Test
    fun rejectsAResponseWithoutUsableAudio() {
        val invalid = JSONObject("""{"sources":[{"tracksStorages":[{"tracks":{"1":"file:///music.mp3"}}]}]}""")
        assertThrows(JSONException::class.java) { AlternateVersions.parse(invalid) }
    }

    @Test
    fun autoPrefersUncensoredAudioOverOldDownloadsWhileOnline() {
        assertFalse(matchingDownload(AudioEdition.ALTERNATE, AudioEdition.ORIGINAL, automatic = true, offline = false))
        assertFalse(matchingDownload(AudioEdition.ALTERNATE, null, automatic = true, offline = false))
        assertTrue(matchingDownload(AudioEdition.ALTERNATE, AudioEdition.ALTERNATE, automatic = true, offline = false))
        assertTrue(matchingDownload(AudioEdition.ALTERNATE, null, automatic = true, offline = true))
        assertFalse(matchingDownload(AudioEdition.ALTERNATE, null, automatic = false, offline = true))
        assertFalse(matchingDownload(AudioEdition.ORIGINAL, AudioEdition.ALTERNATE, automatic = false, offline = true))
    }
}
