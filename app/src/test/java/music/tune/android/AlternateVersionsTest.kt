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

    @Test
    fun resolvesDeletedTracksWithoutCatalogTitlesAndRoundTripsStoredJson() {
        val catalog = AlternateVersions.parse(JSONObject("""
            {"sources":[{
              "tracks":{
                "102":{"title":"natura","artists":[{"id":2,"name":"тёмный принц"}]},
                "103":{"title":"ты уебалась головой"}
              },
              "albums":{"24720439":{"coverUri":"example.org/cover/%%","volumes":[[110945084]]}},
              "tracksStorages":[{"urlTemplate":"media.example.org/%%.mp3","trackIds":[100,101,110945084]}]
            }]}
        """.trimIndent()))
        val rawTracks = listOf(
            JSONObject("""{"id":100,"title":"Удалённый хит","artists":[{"id":1,"name":"Артист"}],"available":false,"durationMs":180000,"albums":[{"id":50,"title":"Альбом"}]}"""),
            JSONObject("""{"id":101,"title":"Живой трек","artists":[{"id":1,"name":"Артист"}],"available":true,"durationMs":150000}"""),
            JSONObject("""{"id":102,"title":"Другое имя","artists":[{"id":2,"name":"Кто-то"}],"available":true,"durationMs":120000}"""),
            JSONObject("""{"id":103,"title":"Ты *** головой","artists":[{"id":3,"name":"Исполнитель"}],"available":false,"durationMs":110000}""")
        )
        val resolved = AlternateVersions.resolveSearchableTracks(catalog, rawTracks)

        assertTrue(resolved.containsKey("100"))
        assertEquals("Удалённый хит", resolved.getValue("100").title)
        assertTrue(resolved.getValue("100").alternate)
        assertEquals(false, resolved.getValue("100").originalAvailable)

        assertFalse(resolved.containsKey("101"))

        assertTrue(resolved.containsKey("102"))
        assertEquals("natura", resolved.getValue("102").title)
        assertEquals("тёмный принц", resolved.getValue("102").artistText)
        assertFalse(resolved.getValue("102").alternate)

        // Unavailable track without FckCensor audio is excluded.
        assertFalse(resolved.containsKey("103"))

        // Purged track (missing from /tracks) still resolves from catalog metadata.
        assertTrue(resolved.containsKey("110945084"))
        assertEquals("ГИДРОПОН", resolved.getValue("110945084").title)

        // Round-trip through stored() / Track.parse() for restored-tracks.json cache.
        val restoredTrack = resolved.getValue("100")
        assertEquals(restoredTrack, Track.parse(restoredTrack.stored()))
    }

    @Test
    fun searchesWithNormalizationPunctuationAlbumMatchingAndExactRanking() {
        val catalog = AlternateVersions.parse(JSONObject("""
            {"sources":[{
              "tracks":{
                "102":{"title":"natura","artists":[{"id":2,"name":"тёмный принц"}]},
                "201":{"title":"Ещё одна песня","artists":[{"id":4,"name":"Ёжик"}]},
                "202":{"title":"Ёжик","artists":[{"id":4,"name":"Другой"}]}
              },
              "albums":{"24720439":{"coverUri":"example.org/cover/%%","volumes":[[110945084]]}},
              "tracksStorages":[{"urlTemplate":"media.example.org/%%.mp3","trackIds":[110945084,201,202]}]
            }]}
        """.trimIndent()))
        val restored = mapOf(
            "110945084" to Track("110945084", "ГИДРОПОН", listOf(Card(Kind.ARTIST, "10946087", "CUPSIZE")),
                album = Card(Kind.ALBUM, "24720439", "Еби меня, малышка"), alternate = true, originalAvailable = false),
            "201" to Track("201", "Ещё одна песня", listOf(Card(Kind.ARTIST, "4", "Ёжик")), alternate = true, originalAvailable = false),
            "202" to Track("202", "Ёжик", listOf(Card(Kind.ARTIST, "4", "Другой")), alternate = true, originalAvailable = false),
        )

        assertEquals(listOf("110945084"), AlternateVersions.searchCatalogTracks(catalog, restored, "CUPSIZE - ГИДРОПОН").map { it.id })
        assertEquals(listOf("110945084"), AlternateVersions.searchCatalogTracks(catalog, restored, "еби меня малышка").map { it.id })
        assertEquals(listOf("102"), AlternateVersions.searchCatalogTracks(catalog, restored, "тёмный принц natura").map { it.id })

        // Exact title match "Ёжик" ranks before artist match "Ещё одна песня", and ё == е.
        assertEquals(listOf("202", "201"), AlternateVersions.searchCatalogTracks(catalog, restored, "ежик").map { it.id })

        // Short query (< 3 chars) requires exact token match, not prefix.
        assertEquals(emptyList<String>(), AlternateVersions.searchCatalogTracks(catalog, restored, "ги").map { it.id })
        assertEquals(listOf("24720439"), AlternateVersions.searchCatalogAlbums(catalog, "еби меня, малышка").map { it.text("id") })
    }

    @Test
    fun mergeSearchResultsPrependsRestoredItemsAndPromotesBestWhenAppropriate() {
        val localTrack = Track("100", "ГИДРОПОН", listOf(Card(Kind.ARTIST, "1", "CUPSIZE")), alternate = true, originalAvailable = false)
        val remoteArtist = Card(Kind.ARTIST, "1", "CUPSIZE")
        val remoteTracks = (1..6).map { i -> Track("r$i", "Другой трек $i", listOf(remoteArtist)) }

        // Searching by artist name keeps Yandex's exact artist card as best, while placing restored track at the top.
        val artistQuery = mergeSearchResults(
            "CUPSIZE", remoteTracks, listOf(remoteArtist), SearchBest.Entry(remoteArtist), true,
            listOf(localTrack), emptyList()
        )
        assertEquals(SearchBest.Entry(remoteArtist), artistQuery.best)
        assertEquals("100", artistQuery.tracks.first().id)

        // Searching by deleted track title promotes restored track to best when Yandex's best doesn't match all terms.
        val fuzzyRemoteTrack = Track("r9", "ГИДРОПОН (кавер)", listOf(Card(Kind.ARTIST, "9", "Кто-то")))
        val exactQuery = mergeSearchResults(
            "ГИДРОПОН", listOf(fuzzyRemoteTrack) + remoteTracks, emptyList(), SearchBest.Song(fuzzyRemoteTrack), false,
            listOf(localTrack), emptyList()
        )
        assertEquals(SearchBest.Song(localTrack), exactQuery.best)

        // Duplicate IDs from Yandex are not duplicated.
        val deduped = mergeSearchResults(
            "ГИДРОПОН", listOf(localTrack), emptyList(), SearchBest.Song(localTrack), false,
            listOf(localTrack), emptyList()
        )
        assertEquals(1, deduped.tracks.size)
    }
}
