package music.tune.android

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaFormatsTest {
    @Test
    fun coverUrlsUseSizesServedByTheMusicCdn() {
        val template = "avatars.yandex.net/get-music-content/collection/cover/%%"
        assertEquals("https://avatars.yandex.net/get-music-content/collection/cover/600x600", coverUrl(template, 500))
        assertEquals("https://avatars.yandex.net/get-music-content/collection/cover/800x800", coverUrl(template, 640))
        assertEquals("https://example.org/cover.jpg", coverUrl("https://example.org/cover.jpg", 500))
    }

    @Test
    fun queueRoundTripPreservesTrackContext() {
        val track = requireNotNull(Track.parse(JSONObject("""
            {
                "id": 17, "title": "Song", "version": "Live", "durationMs": 123456,
                "artists": [{"id": 3, "name": "Artist"}],
                "albums": [{"id": 23, "title": "Album", "coverUri": "example.com/%%"}],
                "contentWarning": "explicit", "available": false
            }
        """), alternate = true, batch = "batch-1"))
        assertEquals("17:23", track.fullId)
        assertEquals("Song (Live)", track.title)
        assertEquals("Artist", track.artistText)
        assertEquals("https://example.com/100x100", coverUrl(track.cover, 100))
        assertTrue(track.explicit)
        assertFalse(track.unavailable)
        assertEquals(track, Track.parse(JSONObject(track.stored().toString())))
    }

    @Test
    fun storedCardsPreservePlaylistOwnerAndStationSeeds() {
        val cards = listOf(
            Card(Kind.PLAYLIST, "4", "Playlist", "Author", owner = "88"),
            Card(Kind.WAVE, "user:onyourwave", "Моя волна", seeds = listOf("user:onyourwave", "settingMoodEnergy:calm")),
        )
        cards.forEach { card ->
            assertEquals(card, Card.fromCache(JSONObject(card.cached().toString())))
        }
    }

    @Test
    fun sharedWaveReadsNestedSeedsAndInvitedListeners() {
        val room = WaveRoom.parse(JSONObject("""
            {"id":"room-1","status":"ENABLED","owner":{"name":"Анна"},"members":[{"name":"Анна"},{"name":"Игорь"}],
             "wave":{"wave":{"name":"Анна и Игорь","seeds":["multivibe:room-1"]}}}
        """))
        assertEquals(listOf("Анна", "Игорь"), room?.people)
        assertEquals(listOf("multivibe:room-1"), room?.seeds)
        assertEquals("https://music.yandex.ru/multivibe/room-1", room?.link)
        assertTrue(room?.canListen == true)
    }

    @Test
    fun synchronizedLyricsUseOffsetsAndKeepRepeatedTimestamps() {
        assertEquals(
            listOf(LyricsLine(200L, "Начало"), LyricsLine(2320L, "Припев"),
                LyricsLine(60_650L, ""), LyricsLine(62_350L, "Припев")),
            parseLyrics("""
                [ar:Исполнитель]
                [offset:+150]
                [01:02.200][00:02.17]Припев
                [01:00,500]
                [00:00.050]Начало
            """.trimIndent()),
        )
    }

    @Test
    fun plainLyricsKeepLineOrder() {
        assertEquals(listOf(LyricsLine(null, "Строка"), LyricsLine(null, "Вторая строка")),
            parseLyrics("Строка\r\n\r\nВторая строка"))
    }

    @Test
    fun audioContainerIsDetectedFromBytes() {
        assertEquals("mp3", audioExtension("ID3\u0003".toByteArray()))
        assertEquals("flac", audioExtension("fLaC".toByteArray()))
        assertEquals("m4a", audioExtension(byteArrayOf(0, 0, 0, 20) + "ftyp".toByteArray()))
        assertEquals("aac", audioExtension(byteArrayOf(0xff.toByte(), 0xf1.toByte(), 0, 0)))
        assertEquals("wav", audioExtension("RIFF1234WAVE".toByteArray()))
        assertNull(audioExtension("<html>Error".toByteArray()))
    }

    @Test
    fun encryptedStreamsUseAesCtrWithZeroCounter() {
        val encrypted = "66e94bd4ef8a2c3b884cfa59ca342b2e".chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        assertArrayEquals(ByteArray(16), decrypt("0".repeat(32)).doFinal(encrypted))
        assertThrows(MusicError::class.java) { decrypt("not-a-key") }
    }

    @Test
    fun waveWordsIgnoreEmptyCardsAndKeepStoryAndRating() {
        val cards = parseWords(org.json.JSONArray("""
            [
              {"id": 123, "cards": [{"feedbackState": "like", "card": {"id": "x", "text": "  Факт  ", "bigCardIds": ["y"]}}]},
              {"id": 124, "cards": [{"card": {"id": "z", "text": " "}}]},
              {"id": 125, "cards": []}
            ]
        """))
        assertEquals(mapOf("123" to WordCard("x", "Факт", "y", "like")), cards)
    }

    @Test
    fun ynisonRoundTripPreservesSourceOrderAndSelectedDuplicate() {
        val first = YnisonSong("1", "11", "Первый", null)
        val second = YnisonSong("2", "22", "Второй", null)
        val playback = YnisonPlayback(listOf(first, second, first), 2, "PLAYLIST", "7:22",
            duration = 200_000L, position = 123_456L, paused = false, speed = 1.5f)
        val encoded = YnisonProtocol.player(playback, "device")
        val result = requireNotNull(YnisonProtocol.state(JSONObject().put("player_state", encoded).put("active_device_id_optional", "device")))
        assertEquals(playback, result.playback)
        assertEquals(126_456L, result.playback.predicted(2_000))
        assertEquals("device", result.statusBy)
        assertThrows(MusicError::class.java) { YnisonProtocol.url("ynison.yandex.ru.example.org", YnisonProtocol.HUB) }
        assertThrows(MusicError::class.java) { YnisonProtocol.url("bad@yandex.ru", YnisonProtocol.HUB) }
    }

    @Test
    fun playlistRecognizesOnlyTrackLinksAndPreservesOrder() {
        val original = playlistM3u(listOf(Track("17", "Первый"), Track("42", "Второй")))
        assertEquals(listOf("17", "42"), playlistTrackIds(original + """
            https://music.yandex.ru/album/5/track/17?x=y
            https://music.yandex.ru/track/57x
            https://example.org/track/99
        """.trimIndent()))
    }

    @Test
    fun normalizationAttenuatesLoudTracksWithoutAmplifyingQuietTracks() {
        assertEquals(1f, SoundSettings.gain(Track("1", "Quiet", loudness = -25.0)), .0001f)
        assertEquals(.50118f, SoundSettings.gain(Track("2", "Loud", loudness = -8.0, peak = -2.0)), .0001f)
        assertEquals(.89125f, SoundSettings.gain(Track("3", "Peak", loudness = -15.0, peak = 0.0)), .0001f)
    }

    @Test
    fun crossfadePreservesMixWindowsButLimitsOrdinaryTransitions() {
        val marks = FadePoints(1.0, 8.0, 100.0, 115.0)
        val first = Track("1", "First", duration = 120_000L, fade = marks, mixFade = marks)
        val next = Track("2", "Second", duration = 90_000L, fade = marks, mixFade = marks)
        assertEquals(FadeWindow(109_000L, 115_000L, 1_000L, 8_000L), fadeWindow(first, next, 120_000L, -1, false))
        assertEquals(FadeWindow(100_000L, 115_000L, 1_000L, 8_000L), fadeWindow(first, next, 120_000L, -1, true))
        assertEquals(FadeWindow(116_000L, 120_000L, 0L, 4_000L), fadeWindow(first, next, 120_000L, 4, false))
        assertNull(fadeWindow(first, next.copy(mediaType = "podcast_episode"), 120_000L, 4, false))
    }
}
