package music.tune.android

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class TuneCatalogTest {
    @Test
    fun independentTogglesAndCatalogMergeRespectPriorities() {
        val fckJson = JSONObject("""
            {
              "sources": [{
                "tracks": {
                  "1": { "title": "Из Fck" },
                  "2": { "title": "Только Fck" }
                },
                "tracksStorages": [{
                  "urlTemplate": "https://fck.example.org/%%.mp3",
                  "trackIds": [1, 2]
                }]
              }]
            }
        """.trimIndent())

        val catalogJson = JSONObject("""
            {
              "sources": [{
                "tracks": {
                  "1": { "title": "Из Tune" },
                  "9000000001": {
                    "title": "Камен",
                    "artists": [{ "id": 90000001, "name": "Ёжик" }],
                    "durationMs": 186000
                  }
                },
                "albums": {
                  "900000001": {
                    "title": "Первый альбом",
                    "coverUri": "https://cdn.example.org/album.jpg",
                    "artists": [{ "id": 90000001, "name": "Ёжик" }],
                    "volumes": [["555", "9000000001"]]
                  }
                },
                "artists": {
                  "90000001": {
                    "name": "Ёжик",
                    "description": "Инди",
                    "coverUri": "https://cdn.example.org/artist.jpg"
                  }
                },
                "playlists": {
                  "9000001": {
                    "title": "Подборка дня",
                    "coverUri": "https://cdn.example.org/playlist.jpg",
                    "tracks": ["9000000001"]
                  }
                },
                "artistsInsertions": {
                  "90000001": { "tracks": ["9000000001"], "albums": ["900000001"] }
                },
                "tracksStorages": [{
                  "urlTemplate": "https://github.com/tune-music/tune-catalog-data/releases/download/audio-1/%%.mp3",
                  "tracks": {
                    "1": { "url": "1", "durationMs": 1000 },
                    "9000000001": { "url": "9000000001", "durationMs": 186000 }
                  }
                }]
              }]
            }
        """.trimIndent())

        val fck = AlternateVersions.parse(fckJson)
        val cat = AlternateVersions.parseCatalog(catalogJson)
        val merged = requireNotNull(AlternateVersions.mergeCatalogs(catalogJson, fckJson))

        // Tune Catalog metadata & audio override FckCensorData on merge.
        assertEquals("Из Tune", merged.tracks.getValue("1").text("title"))
        assertEquals(
            "https://github.com/tune-music/tune-catalog-data/releases/download/audio-1/1.mp3",
            merged.audio.getValue("1"),
        )
        assertEquals("https://fck.example.org/2.mp3", merged.audio.getValue("2"))
        assertEquals("900000001", merged.trackAlbums.getValue("9000000001").text("id"))

        // Both enabled: track 1 has T badge (not FckCensor lock), track 2 has FckCensor lock.
        assertTrue(AlternateVersions.isCatalogTrack(cat, true, "9000000001"))
        assertFalse(AlternateVersions.hasFckBadge(fck, true, cat, true, "9000000001"))
        assertTrue(AlternateVersions.isCatalogTrack(cat, true, "1"))
        assertFalse(AlternateVersions.hasFckBadge(fck, true, cat, true, "1"))
        assertFalse(AlternateVersions.isCatalogTrack(cat, true, "2"))
        assertTrue(AlternateVersions.hasFckBadge(fck, true, cat, true, "2"))

        // Catalog disabled, FckCensor enabled: track 1 falls back to FckCensor lock.
        assertFalse(AlternateVersions.isCatalogTrack(cat, false, "1"))
        assertTrue(AlternateVersions.hasFckBadge(fck, true, cat, false, "1"))
        assertFalse(AlternateVersions.isCatalogTrack(cat, false, "9000000001"))

        // Catalog enabled, FckCensor disabled: track 1 still has T, track 2 has no badge.
        assertTrue(AlternateVersions.isCatalogTrack(cat, true, "1"))
        assertFalse(AlternateVersions.hasFckBadge(fck, false, cat, true, "1"))
        assertFalse(AlternateVersions.hasFckBadge(fck, false, cat, true, "2"))

        // Both disabled: no badges.
        assertFalse(AlternateVersions.isCatalogTrack(cat, false, "1"))
        assertFalse(AlternateVersions.hasFckBadge(fck, false, cat, false, "1"))

        // Search covers synthetic tracks, albums, artists, and playlists.
        val foundTracks = AlternateVersions.searchCatalogTracks(
            cat, null, "ежик",
            isCatalog = { id -> AlternateVersions.isCatalogTrack(cat, true, id) },
            isFck = { false },
        )
        assertEquals(listOf("9000000001"), foundTracks.map { it.id })
        assertTrue(foundTracks.single().catalog)
        assertFalse(foundTracks.single().alternate)
        assertEquals(listOf("900000001"), AlternateVersions.searchCatalogAlbums(cat, "первый").map { it.text("id") })
        assertEquals(listOf("90000001"), AlternateVersions.searchCatalogArtists(cat, "ежик").map { it.text("id") })
        assertEquals(listOf("9000001"), AlternateVersions.searchCatalogPlaylists(cat, "подборка").map { it.first })
    }

    @Test
    fun isCatalogTrackRegressionExcludesNativeYandexTracksInSupplementedAlbum() {
        val catalogJson = JSONObject("""
            {
              "sources": [{
                "tracks": {
                  "555": { "title": "Родной трек без файла" },
                  "9000000001": { "title": "Новый трек" }
                },
                "albums": {
                  "123456": {
                    "title": "Дополненный альбом Яндекса",
                    "coverUri": "https://cdn.example.org/supplemented.jpg",
                    "volumes": [["555", "777", "9000000001"]]
                  }
                },
                "tracksStorages": [{
                  "urlTemplate": "https://github.com/tune-music/tune-catalog-data/releases/download/audio-1/%%.mp3",
                  "trackIds": [9000000001]
                }]
              }]
            }
        """.trimIndent())

        val catalog = AlternateVersions.parseCatalog(catalogJson)
        // Native Yandex track 555 (present in tracks + album volumes) and 777 (present in album volumes -> tracks getOrPut)
        // MUST NOT get the T badge because neither is in catalog.audio!
        assertTrue(catalog.tracks.containsKey("555"))
        assertTrue(catalog.tracks.containsKey("777"))
        assertFalse(AlternateVersions.isCatalogTrack(catalog, true, "555"))
        assertFalse(AlternateVersions.isCatalogTrack(catalog, true, "777"))
        assertTrue(AlternateVersions.isCatalogTrack(catalog, true, "9000000001"))
        assertFalse(AlternateVersions.isCatalogTrack(catalog, false, "9000000001"))
    }

    @Test
    fun coverUrlsPreserveFullHttpsUrlsAndExpandYandexTemplates() {
        assertEquals(
            "https://avatars.yandex.net/get-music-content/1/2/600x600",
            coverUrl("avatars.yandex.net/get-music-content/1/2/%%", 500),
        )
        assertEquals(
            "https://cdn.tune.org/covers/full.jpg?size=orig",
            coverUrl("https://cdn.tune.org/covers/full.jpg?size=orig", 800),
        )
        assertEquals(
            "https://cdn.tune.org/covers/upper.png",
            coverUrl("HTTPS://cdn.tune.org/covers/upper.png", 300),
        )
        assertEquals(
            "https://cdn.tune.org/covers/300x300",
            coverUrl("https://cdn.tune.org/covers/%%", 300),
        )
        assertEquals(
            "https://cdn.tune.org/covers/proto.webp",
            coverUrl("//cdn.tune.org/covers/proto.webp", 200),
        )
    }

    @Test
    fun syntheticIdsUse64BitRangesWithout32BitTruncation() {
        assertTrue(AlternateVersions.isSyntheticTrackId("9000000001"))
        assertTrue(AlternateVersions.isSyntheticTrackId("9999999999"))
        assertFalse(AlternateVersions.isSyntheticTrackId("9000000000"))
        assertFalse(AlternateVersions.isSyntheticTrackId("123456"))

        assertTrue(AlternateVersions.isSyntheticAlbumId("900000001"))
        assertTrue(AlternateVersions.isSyntheticAlbumId("8999999999"))
        assertFalse(AlternateVersions.isSyntheticAlbumId("900000000"))
        assertFalse(AlternateVersions.isSyntheticAlbumId("9000000001"))

        assertTrue(AlternateVersions.isSyntheticArtistId("90000001"))
        assertTrue(AlternateVersions.isSyntheticArtistId("899999999"))
        assertFalse(AlternateVersions.isSyntheticArtistId("90000000"))
        assertFalse(AlternateVersions.isSyntheticArtistId("900000001"))

        assertTrue(AlternateVersions.isSyntheticPlaylistId("9000001"))
        assertTrue(AlternateVersions.isSyntheticPlaylistId("1", AlternateVersions.CATALOG_OWNER))
        assertFalse(AlternateVersions.isSyntheticPlaylistId("3", "user123"))

        val track = requireNotNull(Track.parse(JSONObject("""
            {
              "id": 9000000001,
              "title": "64-битный трек",
              "artists": [{ "id": 90000001, "name": "Исполнитель" }],
              "albums": [{ "id": 900000001, "title": "Альбом", "coverUri": "https://cdn.example.org/c.jpg" }],
              "durationMs": 200000
            }
        """), catalog = true))
        assertEquals("9000000001", track.id)
        assertEquals("90000001", track.artists.single().id)
        assertEquals("900000001", track.album?.id)
        assertTrue(track.catalog)
        assertFalse(track.unavailable)
        assertEquals(track, Track.parse(track.stored()))
    }

    @Test
    fun localLibraryOperationsAndDiskPersistenceRoundTrip() {
        val dir = Files.createTempDirectory("tune-catalog-lib-test").toFile()
        try {
            val store = CatalogLibraryStore(dir)
            store.mutate("user-1") { lib ->
                lib.setTrackLiked("9000000001", true, nowMs = 100L)
                lib.setTrackLiked("9000000002", true, nowMs = 200L)
                assertEquals(listOf("9000000002", "9000000001"), lib.likedTracks)

                lib.setTrackDisliked("9000000002", true)
                assertEquals(listOf("9000000001"), lib.likedTracks)
                assertEquals(listOf("9000000002"), lib.dislikedTracks)

                lib.setArtistLiked("90000001", true)
                lib.setArtistDisliked("90000001", true)
                assertTrue(lib.likedArtists.isEmpty())
                assertEquals(listOf("90000001"), lib.dislikedArtists)

                lib.setAlbumLiked("900000001", true)
                lib.setPlaylistLiked("9000001", true)

                lib.addPlaylistTracks("user-1:3", listOf("9000000001"))
                lib.addPlaylistTracks("user-1:3", listOf("9000000002"))
                assertEquals(listOf("9000000002", "9000000001"), lib.playlistTracks["user-1:3"])

                lib.movePlaylistTrack("user-1:3", "9000000002", 0, 1)
                assertEquals(listOf("9000000001", "9000000002"), lib.playlistTracks["user-1:3"])

                lib.removePlaylistTracks("user-1:3", setOf("9000000001"))
                assertEquals(listOf("9000000002"), lib.playlistTracks["user-1:3"])

                lib.setPin("album:900000001", true)
            }

            val reloaded = CatalogLibraryStore(dir).snapshot("user-1")
            assertEquals(listOf("9000000001"), reloaded.likedTracks)
            assertEquals(100L, reloaded.likedTrackDates["9000000001"])
            assertEquals(listOf("9000000002"), reloaded.dislikedTracks)
            assertEquals(listOf("900000001"), reloaded.likedAlbums)
            assertEquals(listOf("90000001"), reloaded.dislikedArtists)
            assertEquals(listOf("9000001"), reloaded.likedPlaylists)
            assertEquals(listOf("9000000002"), reloaded.playlistTracks["user-1:3"])
            assertEquals(listOf("album:900000001"), reloaded.pins)

            val roundtrip = CatalogLibraryData.fromJson(reloaded.toJson())
            assertEquals(reloaded, roundtrip)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun searchCacheKeysInvalidateOnCatalogToggleOrRefresh() {
        val base = SearchCache.Key("42", "Тест", "track", uncensored = true, catalog = true, catalogVersion = "v1")
        assertNotEquals(base.fileName(), base.copy(catalog = false).fileName())
        assertNotEquals(base.fileName(), base.copy(catalogVersion = "v2").fileName())
    }
}
