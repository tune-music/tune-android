package music.tune.android

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
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

    @Test
    fun syncPayloadMatchesSharedTestVectorAndRoundTripsAllSections() {
        val tsSec = 1_795_795_000L
        val tsMs = tsSec * 1000L
        val data = CatalogLibraryData()
        data.setTrackLiked("9000000046", true, nowMs = tsMs)
        data.setAlbumLiked("900000001", true, nowMs = tsMs)
        data.setArtistLiked("90000002", true, nowMs = tsMs)
        data.addPlaylistTracks("1005", listOf("9000000046", "9000000004"), nowMs = tsMs)

        val encoded = data.encodePayload(nowSec = tsSec)
        assertEquals("L:+1a@1l17w8;A:+1@1l17w8;R:+2@1l17w8;P:1005@1l17w8=1a.4", encoded)
        assertEquals(1_795_790_288L, TuneSync.decodeTs("1l149c"))

        val decoded = requireNotNull(CatalogLibraryData.decodePayload(encoded, uid = "42"))
        assertEquals(listOf("9000000046"), decoded.likedTracks)
        assertEquals(listOf("900000001"), decoded.likedAlbums)
        assertEquals(listOf("90000002"), decoded.likedArtists)
        assertEquals(listOf("9000000046", "9000000004"), decoded.playlistTracks["42:1005"])
        assertEquals(encoded, decoded.encodePayload(uid = "42", nowSec = tsSec))

        // Verify all remaining sections (D, B, Q, N, cleared P, ~ non-synthetic fallback IDs)
        val full = CatalogLibraryData()
        full.setTrackDisliked("12345", true, nowMs = tsMs)
        full.setArtistDisliked("90000005", true, nowMs = tsMs)
        full.setPlaylistLiked("9000003", true, nowMs = tsMs)
        full.addPlaylistTracks("42:1005", listOf("9000000001"), nowMs = tsMs)
        full.clearPlaylist("42:1005", nowMs = tsMs + 1000L)
        full.setPin("album:900000001", true, nowMs = tsMs)
        full.setPin("playlist:888", false, nowMs = tsMs)

        val fullEncoded = full.encodePayload(uid = "42", nowSec = tsSec + 1L)
        assertTrue(fullEncoded.contains("D:+~12345@1l17w8"))
        assertTrue(fullEncoded.contains("B:+5@1l17w8"))
        assertTrue(fullEncoded.contains("Q:+3@1l17w8"))
        assertTrue(fullEncoded.contains("P:1005@1l17w9="))
        assertTrue(fullEncoded.contains("N:+album:1@1l17w8,-playlist:~888@1l17w8"))

        val fullDecoded = requireNotNull(CatalogLibraryData.decodePayload(fullEncoded, uid = "42"))
        assertEquals(listOf("12345"), fullDecoded.dislikedTracks)
        assertEquals(listOf("90000005"), fullDecoded.dislikedArtists)
        assertEquals(listOf("9000003"), fullDecoded.likedPlaylists)
        assertNull(fullDecoded.playlistTracks["42:1005"])
        assertEquals(listOf("album:900000001"), fullDecoded.pins)
    }

    @Test
    fun syncShardsSplitUnder490CharsAndRejectPartialShards() {
        val tsSec = 1_795_795_000L
        val large = CatalogLibraryData()
        for (i in 1..140) {
            large.setTrackLiked((9_000_000_000L + i).toString(), true, nowMs = (tsSec + i) * 1000L)
        }
        large.addPlaylistTracks(
            "77:2001",
            (1..20).map { (9_000_000_000L + it).toString() },
            nowMs = tsSec * 1000L,
        )
        val payload = large.encodePayload(uid = "77", nowSec = tsSec + 200L)
        assertTrue(payload.length > 900)

        val shards = TuneSync.splitShards(payload)
        assertTrue(shards.size >= 3)
        assertEquals("Tune Sync", shards[0].title)
        assertEquals("Tune Sync 2", shards[1].title)
        assertEquals("Tune Sync 3", shards[2].title)
        assertTrue(shards[0].description.startsWith(TuneSync.shardHeader(0)))
        assertTrue(shards[1].description.startsWith("Служебный плейлист Tune (часть 2)"))
        for (shard in shards) {
            assertTrue("Shard too long: ${shard.description.length}", shard.description.length <= 490)
            assertTrue(TuneSync.isSyncPlaylist(shard.title, shard.description))
        }

        val reassembled = TuneSync.assembleShards(shards.reversed().map { it.description })
        assertEquals(payload, reassembled)

        // Missing or mismatched shards must return null (do not overwrite local state)
        assertNull(TuneSync.assembleShards(shards.dropLast(1).map { it.description }))
        assertNull(TuneSync.assembleShards(listOf(shards[0].description, "[tune-sync:v1:2/2:L:+1@1l17w8]")))
    }

    @Test
    fun syncLwwMergeHandlesConflictsMutualExclusionAndTombstoneTtl() {
        val devA = CatalogLibraryData()
        devA.setTrackLiked("9000000001", true, nowMs = 1_795_790_010_000L) // X @ 10
        devA.setTrackLiked("9000000003", true, nowMs = 1_795_790_011_000L) // Z @ 11
        devA.setTrackLiked("9000000004", true, nowMs = 1_795_790_010_000L) // T liked @ 10
        devA.addPlaylistTracks("77:1005", listOf("9000000001"), nowMs = 1_795_790_010_000L)

        val devB = CatalogLibraryData()
        devB.setTrackLiked("9000000002", true, nowMs = 1_795_790_012_000L) // Y @ 12
        devB.setTrackLiked("9000000003", false, nowMs = 1_795_790_015_000L) // Z removed @ 15
        devB.setTrackDisliked("9000000004", true, nowMs = 1_795_790_020_000L) // T disliked @ 20
        devB.addPlaylistTracks("77:1005", listOf("9000000001"), nowMs = 1_795_790_010_000L)
        devB.clearPlaylist("77:1005", nowMs = 1_795_790_018_000L)

        val mergedAB = devA.mergeWith(devB, uid = "77")
        val mergedBA = devB.mergeWith(devA, uid = "77")
        assertEquals(listOf("9000000002", "9000000001"), mergedAB.likedTracks)
        assertEquals(listOf("9000000004"), mergedAB.dislikedTracks)
        assertNull(mergedAB.playlistTracks["77:1005"])
        assertEquals(mergedAB.encodePayload("77", 1_795_790_025L), mergedBA.encodePayload("77", 1_795_790_025L))

        // Tombstone pruning: 30 days kept, > 30 days pruned, active kept forever
        val baseTs = 1_795_790_000L
        val ttlData = CatalogLibraryData()
        ttlData.setTrackLiked("9000000001", true, nowMs = baseTs * 1000L)
        ttlData.setTrackLiked("9000000002", false, nowMs = baseTs * 1000L)
        assertTrue(ttlData.encodePayload(nowSec = baseTs + TuneSync.TOMBSTONE_TTL_SEC).contains("-2@"))
        val afterTtl = ttlData.encodePayload(nowSec = baseTs + TuneSync.TOMBSTONE_TTL_SEC + 1L)
        assertFalse(afterTtl.contains("-2@"))
        assertTrue(afterTtl.contains("+1@"))
    }

    @Test
    fun syncPlaylistDetectionAndLegacyMigration() {
        assertTrue(TuneSync.isSyncPlaylist("Tune Sync", null))
        assertTrue(TuneSync.isSyncPlaylist("Tune Sync 2", null))
        assertTrue(TuneSync.isSyncPlaylist("Tune Sync 12", null))
        assertTrue(TuneSync.isSyncPlaylist("Renamed", "Header\n\n[tune-sync:v1:1/1:L:+1@1l17w8]"))
        assertFalse(TuneSync.isSyncPlaylist("Tune Sync 1", null))
        assertFalse(TuneSync.isSyncPlaylist("Tune Sync 0", null))
        assertFalse(TuneSync.isSyncPlaylist("Tune Sync Remix", null))
        assertFalse(TuneSync.isSyncPlaylist("Любимое", "Обычное описание"))

        val legacyJson = JSONObject("""
            {
              "likedTracks": ["9000000046", "9000000004"],
              "likedAlbums": ["900000001"],
              "likedArtists": ["90000002"],
              "playlistTracks": { "1005": ["9000000046"] }
            }
        """.trimIndent())
        val nowSec = 1_795_795_000L
        val migrated = CatalogLibraryData.fromJson(legacyJson, nowSec = nowSec)
        assertEquals(nowSec, migrated.likedTracksSync["9000000046"]?.ts)
        assertEquals(nowSec - 1L, migrated.likedTracksSync["9000000004"]?.ts)
        assertEquals(nowSec, migrated.playlistSync["1005"]?.ts)
        assertTrue(migrated.encodePayload(nowSec = nowSec).startsWith("L:+1a@1l17w8,+4@1l17w7"))

        // Store automatically migrates legacy file on load
        val dir = Files.createTempDirectory("tune-sync-legacy-test").toFile()
        try {
            val store = CatalogLibraryStore(dir)
            val file = dir.resolve("catalog-library-${digest("u1")}.json")
            file.writeText(legacyJson.toString(), Charsets.UTF_8)
            val snap = store.snapshot("u1")
            assertEquals(listOf("9000000046", "9000000004"), snap.likedTracks)
            assertTrue(JSONObject(file.readText(Charsets.UTF_8)).has("sync"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun syncHistoryCodecMergeCapAndDateMerge() {
        val tsSec = 1_795_795_000L
        val data = CatalogLibraryData()
        data.recordHistory("9000000046", nowMs = (tsSec - 10L) * 1000L)
        data.recordHistory("555", nowMs = tsSec * 1000L)
        val wire = data.encodePayload(uid = "42", nowSec = tsSec)
        assertEquals("H:~555@1l17w8,1a@1l17vy", wire)

        val decoded = requireNotNull(CatalogLibraryData.decodePayload(wire, uid = "42"))
        assertEquals(listOf("555" to tsSec, "9000000046" to (tsSec - 10L)), decoded.historyEntries())

        val older = CatalogLibraryData()
        older.recordHistory("9000000046", nowMs = (tsSec - 5L) * 1000L)
        older.recordHistory("555", nowMs = (tsSec - 100L) * 1000L)
        val merged = older.mergeWith(decoded, uid = "42", nowSec = tsSec)
        assertEquals(listOf("555" to tsSec, "9000000046" to (tsSec - 5L)), merged.historyEntries())

        val capped = CatalogLibraryData()
        for (i in 1..120) {
            capped.recordHistory((9_000_000_000L + i).toString(), nowMs = (tsSec + i) * 1000L)
        }
        val entries = capped.historyEntries()
        assertEquals(TuneSync.MAX_HISTORY_ENTRIES, entries.size)
        assertEquals("9000000120", entries.first().first)
        assertEquals("9000000021", entries.last().first)

        val jsonRoundtrip = CatalogLibraryData.fromJson(capped.toJson())
        assertEquals(entries, jsonRoundtrip.historyEntries())

        val zone = java.time.ZoneOffset.UTC
        val day28Ts = java.time.LocalDate.of(2026, 11, 28).atStartOfDay(zone).toEpochSecond() + 3600L
        val day26Ts = java.time.LocalDate.of(2026, 11, 26).atStartOfDay(zone).toEpochSecond() + 3600L
        val mergedHistory = mergeCatalogHistory(
            remote = listOf(
                Triple("100", "2026-11-28", "Поиск"),
                Triple("200", "2026-11-28", "Поиск"),
                Triple("300", "2026-11-27", "Моя волна"),
            ),
            catalog = listOf("9000000046" to day28Ts, "200" to day28Ts, "9000000099" to day26Ts),
            limit = 10,
            catalogSource = "Каталог Tune",
            zone = zone,
        )
        assertEquals(
            listOf("9000000046", "200", "100", "300", "9000000099"),
            mergedHistory.keys.toList(),
        )
        assertEquals("2026-11-28" to "Каталог Tune", mergedHistory["9000000046"])
        assertEquals("2026-11-28" to "Каталог Tune", mergedHistory["200"])
        assertEquals("2026-11-28" to "Поиск", mergedHistory["100"])
    }

    @Test
    fun timedMergeInterleavesLikesAndPlaylistsChronologically() {
        val localLikes = listOf("9001" to 2000L, "9002" to 1000L)
        val remoteLikes = listOf("1" to 3000L, "2" to 1500L, "3" to 500L)
        val mergedLikes = mergeTimedDesc(localLikes, remoteLikes) { it }
        assertEquals(listOf("1", "9001", "2", "9002", "3"), mergedLikes)

        val ascPlaylist = mergeTimedPlaylist(
            local = listOf("9001" to 2500L),
            remote = listOf("10" to 1000L, "20" to 2000L, "30" to 3000L),
            forceDesc = false,
        ) { it }
        assertEquals(listOf("10", "20", "9001", "30"), ascPlaylist)

        val likesPlaylist = mergeTimedPlaylist(
            local = listOf("9001" to 2500L),
            remote = listOf("30" to 3000L, "20" to 2000L, "10" to 1000L),
            forceDesc = true,
        ) { it }
        assertEquals(listOf("30", "9001", "20", "10"), likesPlaylist)
    }

    @Test
    fun audioAnalyzerAndWaveTasteFilterByMoodBassAndArtistName() {
        val sr = 16_000
        val calmAnalyzer = StreamingAudioAnalyzer(1, sr)
        for (i in 0 until 12 * sr) {
            calmAnalyzer.pushSample(0.05f * kotlin.math.sin(2.0 * Math.PI * 330.0 * i / sr).toFloat())
        }
        val calmProfile = requireNotNull(calmAnalyzer.finalizeProfile())
        assertTrue("Expected low bass on calm sine, got ${calmProfile.bass}", calmProfile.bass < 0.40f)
        assertTrue("Expected calm/sad mood, got ${calmProfile.mood}", calmProfile.mood == AudioMood.CALM || calmProfile.mood == AudioMood.SAD)

        val heavyAnalyzer = StreamingAudioAnalyzer(1, sr)
        for (i in 0 until 12 * sr) {
            val beat = 0.65f * kotlin.math.sin(2.0 * Math.PI * 65.0 * i / sr).toFloat()
            val edge = 0.15f * kotlin.math.sin(2.0 * Math.PI * 3200.0 * i / sr).toFloat()
            heavyAnalyzer.pushSample((beat + edge).coerceIn(-1f, 1f))
        }
        val heavyProfile = requireNotNull(heavyAnalyzer.finalizeProfile())
        assertTrue("Expected heavy bass, got ${heavyProfile.bass}", heavyProfile.bass > 0.50f)

        val dir = Files.createTempDirectory("tune-wave-taste").toFile()
        val taste = WaveTaste(dir)
        val sadCtx = WaveContext.fromSeeds(listOf("settingMoodEnergy:sad"), listOf(calmProfile))
        assertEquals(AudioMood.SAD, sadCtx.targetMood)

        // Heavy bass track is filtered out in SAD wave, calm track passes if liked
        assertNull(
            taste.scoreWaveCandidateLocked(
                at = 10_000L,
                trackId = "ugc-bass",
                artistIdList = listOf("a1"),
                artistNameList = listOf("DJ Bass"),
                likedTrack = true,
                inHistory = false,
                isUgc = true,
                profile = heavyProfile,
                ctx = sadCtx,
                recentArtistIds = emptySet(),
                recentArtistNames = emptySet(),
                likedArtistIds = emptyList(),
            )
        )
        val calmScore = taste.scoreWaveCandidateLocked(
            at = 10_000L,
            trackId = "ugc-calm",
            artistIdList = listOf("a2"),
            artistNameList = listOf("Acoustic"),
            likedTrack = true,
            inHistory = false,
            isUgc = true,
            profile = calmProfile,
            ctx = sadCtx,
            recentArtistIds = emptySet(),
            recentArtistNames = emptySet(),
            likedArtistIds = emptyList(),
        )
        assertTrue("Expected calm liked track to pass SAD wave, got $calmScore", (calmScore ?: 0.0) >= WaveTaste.WAVE_PASS)

        // Disliked artist by normalized name blocks track even when artist ID is synthetic/missing
        taste.setScoreForTest(artistName = "Ёлка", score = -4.0)
        assertNull(
            taste.scoreWaveCandidateLocked(
                at = 10_000L,
                trackId = "9000000100",
                artistIdList = emptyList(),
                artistNameList = listOf("Елка"),
                likedTrack = true,
                inHistory = false,
                isUgc = false,
                profile = calmProfile,
                ctx = WaveContext.fromSeeds(emptyList(), emptyList()),
                recentArtistIds = emptySet(),
                recentArtistNames = emptySet(),
                likedArtistIds = emptyList(),
            )
        )
    }
}
