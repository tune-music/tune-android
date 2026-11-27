package music.tune.android

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

internal data class SyncEntry(val ts: Long, val active: Boolean)

internal data class PlaylistSyncEntry(val ts: Long, val tracks: List<String>)

internal data class SyncShardSpec(val index: Int, val title: String, val description: String)

internal data class RemoteSyncShard(
    val kind: String,
    val title: String,
    val description: String?,
    val hasCustomCover: Boolean,
    val isPrivate: Boolean,
)

internal object TuneSync {
    const val TRACK_BASE = 9_000_000_000L
    const val ALBUM_BASE = 900_000_000L
    const val ARTIST_BASE = 90_000_000L
    const val PLAYLIST_BASE = 9_000_000L
    const val MAX_SYNTHETIC_OFFSET = 999_999_999L
    const val TS_EPOCH_OFFSET = 1_700_000_000L
    const val TOMBSTONE_TTL_SEC = 2_592_000L // 30 days
    const val MAX_DESCRIPTION_LEN = 490
    const val TITLE_PRIMARY = "Tune Sync"

    private const val HEADER_PRIMARY =
        "Служебный плейлист Tune — синхронизация лайков и медиатеки Каталога Tune между устройствами. Не удаляйте его (в Tune он скрыт)."
    private val TITLE_SHARD_REGEX = Regex("^Tune Sync ([2-9]|[1-9]\\d+)$")
    private val ENVELOPE_REGEX = Regex("""\[tune-sync:v1:(\d+)/(\d+):([A-Za-z0-9_+@.,=:;~-]*)]""")

    fun isSyncPlaylist(title: String?, description: String? = null): Boolean {
        if (description?.contains("[tune-sync:v1:") == true) return true
        return title != null && (title == TITLE_PRIMARY || TITLE_SHARD_REGEX.matches(title))
    }

    fun shardTitle(index: Int): String = if (index <= 0) TITLE_PRIMARY else "Tune Sync ${index + 1}"

    fun shardHeader(index: Int): String = if (index <= 0) {
        HEADER_PRIMARY
    } else {
        "Служебный плейлист Tune (часть ${index + 1}) — синхронизация Каталога Tune. Не удаляйте."
    }

    fun encodeId(raw: String, base: Long): String {
        val n = raw.toLongOrNull()
        return if (n != null && n.toString() == raw && n > base && n <= base + MAX_SYNTHETIC_OFFSET) {
            (n - base).toString(36)
        } else {
            "~$raw"
        }
    }

    fun decodeId(tok: String, base: Long): String? {
        if (tok.isEmpty()) return null
        if (tok.startsWith("~")) return tok.substring(1).takeIf(String::isNotEmpty)
        val delta = tok.toLongOrNull(36) ?: return null
        if (delta <= 0L || delta > MAX_SYNTHETIC_OFFSET) return null
        return (base + delta).toString()
    }

    fun encodeTs(epochSec: Long): String = (epochSec - TS_EPOCH_OFFSET).coerceAtLeast(0L).toString(36)

    fun decodeTs(tok: String): Long? {
        if (tok.isEmpty()) return null
        val offset = tok.toLongOrNull(36) ?: return null
        if (offset < 0L) return null
        return TS_EPOCH_OFFSET + offset
    }

    fun baseForPinKind(pinKind: String): Long = when (pinKind.lowercase()) {
        "track" -> TRACK_BASE
        "album" -> ALBUM_BASE
        "artist" -> ARTIST_BASE
        "playlist" -> PLAYLIST_BASE
        else -> 0L
    }

    fun splitShards(payload: String, maxLength: Int = MAX_DESCRIPTION_LEN): List<SyncShardSpec> {
        if (payload.isEmpty()) return emptyList()
        var total = 1
        while (total <= 10_000) {
            var offset = 0
            val built = mutableListOf<SyncShardSpec>()
            for (part in 1..total) {
                val index = part - 1
                val header = shardHeader(index)
                val prefix = "$header\n\n[tune-sync:v1:$part/$total:"
                val avail = maxLength - prefix.length - 1
                if (avail <= 0) {
                    offset = -1
                    break
                }
                val end = (offset + avail).coerceAtMost(payload.length)
                val chunk = payload.substring(offset, end)
                val desc = "$prefix$chunk]"
                built += SyncShardSpec(index = index, title = shardTitle(index), description = desc)
                offset = end
            }
            if (offset == payload.length) return built
            total++
        }
        throw IllegalStateException("Sync payload too large to shard")
    }

    internal data class ParsedEnvelope(val part: Int, val total: Int, val chunk: String)

    internal fun parseEnvelope(description: String?): ParsedEnvelope? {
        val text = description ?: return null
        val match = ENVELOPE_REGEX.find(text) ?: return null
        val part = match.groupValues[1].toIntOrNull() ?: return null
        val total = match.groupValues[2].toIntOrNull() ?: return null
        if (total < 1 || part < 1 || part > total) return null
        return ParsedEnvelope(part, total, match.groupValues[3])
    }

    fun assembleShards(descriptions: List<String?>): String? {
        val parsed = descriptions.mapNotNull(::parseEnvelope)
        if (parsed.isEmpty()) return null
        val totals = parsed.map { it.total }.toSet()
        if (totals.size != 1) return null
        val total = totals.first()
        val byPart = mutableMapOf<Int, String>()
        for (env in parsed) {
            val existing = byPart[env.part]
            if (existing != null && existing != env.chunk) return null
            byPart[env.part] = env.chunk
        }
        if (byPart.size != total) return null
        val out = StringBuilder()
        for (part in 1..total) {
            out.append(byPart[part] ?: return null)
        }
        return out.toString()
    }
}

internal data class CatalogLibraryData(
    val likedTracks: MutableList<String> = mutableListOf(),
    val likedTrackDates: MutableMap<String, Long> = mutableMapOf(),
    val dislikedTracks: MutableList<String> = mutableListOf(),
    val likedAlbums: MutableList<String> = mutableListOf(),
    val likedArtists: MutableList<String> = mutableListOf(),
    val dislikedArtists: MutableList<String> = mutableListOf(),
    val likedPlaylists: MutableList<String> = mutableListOf(),
    val playlistTracks: MutableMap<String, MutableList<String>> = mutableMapOf(),
    val pins: MutableList<String> = mutableListOf(),
    val likedTracksSync: MutableMap<String, SyncEntry> = mutableMapOf(),
    val dislikedTracksSync: MutableMap<String, SyncEntry> = mutableMapOf(),
    val likedAlbumsSync: MutableMap<String, SyncEntry> = mutableMapOf(),
    val likedArtistsSync: MutableMap<String, SyncEntry> = mutableMapOf(),
    val dislikedArtistsSync: MutableMap<String, SyncEntry> = mutableMapOf(),
    val likedPlaylistsSync: MutableMap<String, SyncEntry> = mutableMapOf(),
    val playlistSync: MutableMap<String, PlaylistSyncEntry> = mutableMapOf(),
    val pinsSync: MutableMap<String, SyncEntry> = mutableMapOf(),
) {
    fun copyData(): CatalogLibraryData = CatalogLibraryData(
        likedTracks = likedTracks.toMutableList(),
        likedTrackDates = likedTrackDates.toMutableMap(),
        dislikedTracks = dislikedTracks.toMutableList(),
        likedAlbums = likedAlbums.toMutableList(),
        likedArtists = likedArtists.toMutableList(),
        dislikedArtists = dislikedArtists.toMutableList(),
        likedPlaylists = likedPlaylists.toMutableList(),
        playlistTracks = playlistTracks.mapValues { (_, v) -> v.toMutableList() }.toMutableMap(),
        pins = pins.toMutableList(),
        likedTracksSync = likedTracksSync.toMutableMap(),
        dislikedTracksSync = dislikedTracksSync.toMutableMap(),
        likedAlbumsSync = likedAlbumsSync.toMutableMap(),
        likedArtistsSync = likedArtistsSync.toMutableMap(),
        dislikedArtistsSync = dislikedArtistsSync.toMutableMap(),
        likedPlaylistsSync = likedPlaylistsSync.toMutableMap(),
        playlistSync = playlistSync.mapValues { (_, v) -> PlaylistSyncEntry(v.ts, v.tracks.toList()) }.toMutableMap(),
        pinsSync = pinsSync.toMutableMap(),
    )

    fun setTrackLiked(id: String, liked: Boolean, nowMs: Long = System.currentTimeMillis()) {
        val tsSec = nowMs / 1000L
        if (liked) {
            if (dislikedTracks.removeAll { it == id } || id in dislikedTracksSync) {
                dislikedTracksSync[id] = SyncEntry(tsSec, false)
            }
            pushFront(likedTracks, id)
            likedTrackDates[id] = nowMs
            likedTracksSync[id] = SyncEntry(tsSec, true)
        } else {
            likedTracks.removeAll { it == id }
            likedTrackDates.remove(id)
            likedTracksSync[id] = SyncEntry(tsSec, false)
        }
    }

    fun setTrackDisliked(id: String, disliked: Boolean, nowMs: Long = System.currentTimeMillis()) {
        val tsSec = nowMs / 1000L
        if (disliked) {
            if (likedTracks.removeAll { it == id } || id in likedTracksSync) {
                likedTrackDates.remove(id)
                likedTracksSync[id] = SyncEntry(tsSec, false)
            }
            pushFront(dislikedTracks, id)
            dislikedTracksSync[id] = SyncEntry(tsSec, true)
        } else {
            dislikedTracks.removeAll { it == id }
            dislikedTracksSync[id] = SyncEntry(tsSec, false)
        }
    }

    fun setAlbumLiked(id: String, liked: Boolean, nowMs: Long = System.currentTimeMillis()) {
        val tsSec = nowMs / 1000L
        if (liked) {
            pushFront(likedAlbums, id)
            likedAlbumsSync[id] = SyncEntry(tsSec, true)
        } else {
            likedAlbums.removeAll { it == id }
            likedAlbumsSync[id] = SyncEntry(tsSec, false)
        }
    }

    fun setArtistLiked(id: String, liked: Boolean, nowMs: Long = System.currentTimeMillis()) {
        val tsSec = nowMs / 1000L
        if (liked) {
            if (dislikedArtists.removeAll { it == id } || id in dislikedArtistsSync) {
                dislikedArtistsSync[id] = SyncEntry(tsSec, false)
            }
            pushFront(likedArtists, id)
            likedArtistsSync[id] = SyncEntry(tsSec, true)
        } else {
            likedArtists.removeAll { it == id }
            likedArtistsSync[id] = SyncEntry(tsSec, false)
        }
    }

    fun setArtistDisliked(id: String, disliked: Boolean, nowMs: Long = System.currentTimeMillis()) {
        val tsSec = nowMs / 1000L
        if (disliked) {
            if (likedArtists.removeAll { it == id } || id in likedArtistsSync) {
                likedArtistsSync[id] = SyncEntry(tsSec, false)
            }
            pushFront(dislikedArtists, id)
            dislikedArtistsSync[id] = SyncEntry(tsSec, true)
        } else {
            dislikedArtists.removeAll { it == id }
            dislikedArtistsSync[id] = SyncEntry(tsSec, false)
        }
    }

    fun setPlaylistLiked(kind: String, liked: Boolean, nowMs: Long = System.currentTimeMillis()) {
        val tsSec = nowMs / 1000L
        if (liked) {
            pushFront(likedPlaylists, kind)
            likedPlaylistsSync[kind] = SyncEntry(tsSec, true)
        } else {
            likedPlaylists.removeAll { it == kind }
            likedPlaylistsSync[kind] = SyncEntry(tsSec, false)
        }
    }

    fun addPlaylistTrack(key: String, trackId: String, nowMs: Long = System.currentTimeMillis()) {
        addPlaylistTracks(key, listOf(trackId), nowMs)
    }

    fun addPlaylistTracks(key: String, trackIds: List<String>, nowMs: Long = System.currentTimeMillis()) {
        if (trackIds.isEmpty()) return
        val list = playlistTracks.getOrPut(key) { mutableListOf() }
        val distinctNew = trackIds.distinct()
        list.removeAll(distinctNew.toSet())
        list.addAll(0, distinctNew)
        playlistSync[key] = PlaylistSyncEntry(nowMs / 1000L, list.toList())
    }

    fun removePlaylistTrack(key: String, trackId: String, nowMs: Long = System.currentTimeMillis()) {
        removePlaylistTracks(key, setOf(trackId), nowMs)
    }

    fun removePlaylistTracks(key: String, trackIds: Set<String>, nowMs: Long = System.currentTimeMillis()) {
        if (trackIds.isEmpty()) return
        val list = playlistTracks[key] ?: return
        val changed = list.removeAll { it in trackIds }
        if (list.isEmpty()) playlistTracks.remove(key)
        if (changed) playlistSync[key] = PlaylistSyncEntry(nowMs / 1000L, list.toList())
    }

    fun movePlaylistTrack(key: String, trackId: String, from: Int, to: Int, nowMs: Long = System.currentTimeMillis()) {
        val list = playlistTracks[key] ?: return
        val at = list.indexOf(trackId)
        if (at < 0) return
        val target = (at + to - from).coerceIn(0, list.lastIndex)
        if (target != at) {
            val item = list.removeAt(at)
            list.add(target, item)
            playlistSync[key] = PlaylistSyncEntry(nowMs / 1000L, list.toList())
        }
    }

    fun clearPlaylist(key: String, nowMs: Long = System.currentTimeMillis()) {
        val hadLocal = playlistTracks.remove(key) != null
        if (hadLocal || key in playlistSync) {
            playlistSync[key] = PlaylistSyncEntry(nowMs / 1000L, emptyList())
        }
    }

    fun setPin(key: String, pinned: Boolean, nowMs: Long = System.currentTimeMillis()) {
        val tsSec = nowMs / 1000L
        if (pinned) {
            pushFront(pins, key)
            pinsSync[key] = SyncEntry(tsSec, true)
        } else {
            pins.removeAll { it == key }
            pinsSync[key] = SyncEntry(tsSec, false)
        }
    }

    fun encodePayload(uid: String? = null, nowSec: Long = System.currentTimeMillis() / 1000L): String {
        val sections = mutableListOf<String>()
        encodeSetSection("L", likedTracksSync, TuneSync.TRACK_BASE, nowSec)?.let(sections::add)
        encodeSetSection("D", dislikedTracksSync, TuneSync.TRACK_BASE, nowSec)?.let(sections::add)
        encodeSetSection("A", likedAlbumsSync, TuneSync.ALBUM_BASE, nowSec)?.let(sections::add)
        encodeSetSection("R", likedArtistsSync, TuneSync.ARTIST_BASE, nowSec)?.let(sections::add)
        encodeSetSection("B", dislikedArtistsSync, TuneSync.ARTIST_BASE, nowSec)?.let(sections::add)
        encodeSetSection("Q", likedPlaylistsSync, TuneSync.PLAYLIST_BASE, nowSec)?.let(sections::add)
        encodePlaylistSection(uid, nowSec)?.let(sections::add)
        encodePinSection(nowSec)?.let(sections::add)
        return sections.joinToString(";")
    }

    private fun encodeSetSection(
        code: String,
        map: Map<String, SyncEntry>,
        base: Long,
        nowSec: Long,
    ): String? {
        val entries = map.entries
            .filter { (_, entry) -> entry.active || nowSec - entry.ts <= TuneSync.TOMBSTONE_TTL_SEC }
            .sortedWith(compareByDescending<Map.Entry<String, SyncEntry>> { it.value.ts }.thenBy { it.key })
            .map { (id, entry) ->
                val sign = if (entry.active) "+" else "-"
                "$sign${TuneSync.encodeId(id, base)}@${TuneSync.encodeTs(entry.ts)}"
            }
        return if (entries.isEmpty()) null else "$code:${entries.joinToString(",")}"
    }

    private fun encodePlaylistSection(uid: String?, nowSec: Long): String? {
        val wireEntries = linkedMapOf<String, PlaylistSyncEntry>()
        for ((key, entry) in playlistSync) {
            val kind = localKeyToWireKind(key, uid) ?: continue
            if (entry.tracks.isEmpty() && nowSec - entry.ts > TuneSync.TOMBSTONE_TTL_SEC) continue
            val existing = wireEntries[kind]
            if (existing == null || entry.ts > existing.ts || (entry.ts == existing.ts && entry.tracks.isNotEmpty() && existing.tracks.isEmpty())) {
                wireEntries[kind] = entry
            }
        }
        val encoded = wireEntries.entries
            .sortedBy { it.key }
            .map { (kind, entry) ->
                val ids = entry.tracks.joinToString(".") { TuneSync.encodeId(it, TuneSync.TRACK_BASE) }
                "$kind@${TuneSync.encodeTs(entry.ts)}=$ids"
            }
        return if (encoded.isEmpty()) null else "P:${encoded.joinToString(",")}"
    }

    private fun encodePinSection(nowSec: Long): String? {
        val entries = pinsSync.entries
            .filter { (_, entry) -> entry.active || nowSec - entry.ts <= TuneSync.TOMBSTONE_TTL_SEC }
            .sortedWith(compareByDescending<Map.Entry<String, SyncEntry>> { it.value.ts }.thenBy { it.key })
            .mapNotNull { (key, entry) ->
                val sep = key.indexOf(':')
                val (pinKind, rawId) = if (sep > 0) {
                    key.substring(0, sep) to key.substring(sep + 1)
                } else {
                    "item" to key
                }
                if (rawId.isEmpty()) return@mapNotNull null
                val sign = if (entry.active) "+" else "-"
                val encodedId = TuneSync.encodeId(rawId, TuneSync.baseForPinKind(pinKind))
                "$sign$pinKind:$encodedId@${TuneSync.encodeTs(entry.ts)}"
            }
        return if (entries.isEmpty()) null else "N:${entries.joinToString(",")}"
    }

    fun mergeWith(remote: CatalogLibraryData, uid: String? = null, nowSec: Long = System.currentTimeMillis() / 1000L): CatalogLibraryData {
        val merged = CatalogLibraryData()
        mergeSet(likedTracksSync, remote.likedTracksSync, merged.likedTracksSync)
        mergeSet(dislikedTracksSync, remote.dislikedTracksSync, merged.dislikedTracksSync)
        mergeSet(likedAlbumsSync, remote.likedAlbumsSync, merged.likedAlbumsSync)
        mergeSet(likedArtistsSync, remote.likedArtistsSync, merged.likedArtistsSync)
        mergeSet(dislikedArtistsSync, remote.dislikedArtistsSync, merged.dislikedArtistsSync)
        mergeSet(likedPlaylistsSync, remote.likedPlaylistsSync, merged.likedPlaylistsSync)
        mergeSet(pinsSync, remote.pinsSync, merged.pinsSync)

        resolveMutualExclusion(merged.likedTracksSync, merged.dislikedTracksSync)
        resolveMutualExclusion(merged.likedArtistsSync, merged.dislikedArtistsSync)

        // Materialize active lists sorted by ts desc then id asc
        materializeList(merged.likedTracksSync, merged.likedTracks)
        materializeList(merged.dislikedTracksSync, merged.dislikedTracks)
        materializeList(merged.likedAlbumsSync, merged.likedAlbums)
        materializeList(merged.likedArtistsSync, merged.likedArtists)
        materializeList(merged.dislikedArtistsSync, merged.dislikedArtists)
        materializeList(merged.likedPlaylistsSync, merged.likedPlaylists)
        materializeList(merged.pinsSync, merged.pins)

        // Preserve local ms timestamps for liked tracks if matching epoch second
        for (id in merged.likedTracks) {
            val tsSec = merged.likedTracksSync[id]?.ts ?: nowSec
            val localMs = likedTrackDates[id]
            val remoteMs = remote.likedTrackDates[id]
            merged.likedTrackDates[id] = when {
                localMs != null && localMs / 1000L == tsSec -> localMs
                remoteMs != null && remoteMs / 1000L == tsSec -> remoteMs
                else -> tsSec * 1000L
            }
        }

        // Merge playlists (normalizing local uid:kind <-> wire kind)
        val mergedByKey = linkedMapOf<String, PlaylistSyncEntry>()
        for ((key, entry) in playlistSync) {
            val normalizedKey = normalizePlaylistKey(key, uid)
            mergedByKey[normalizedKey] = entry
        }
        for ((key, entry) in remote.playlistSync) {
            val normalizedKey = normalizePlaylistKey(key, uid)
            val existing = mergedByKey[normalizedKey]
            val chooseRemote = when {
                existing == null -> true
                entry.ts > existing.ts -> true
                entry.ts < existing.ts -> false
                else -> entry.tracks.isNotEmpty() && existing.tracks.isEmpty()
            }
            if (chooseRemote) mergedByKey[normalizedKey] = entry
        }
        for ((key, entry) in mergedByKey) {
            merged.playlistSync[key] = entry
            if (entry.tracks.isNotEmpty()) {
                merged.playlistTracks[key] = entry.tracks.toMutableList()
            }
        }
        return merged
    }

    fun toJson(): JSONObject {
        val dates = JSONObject()
        likedTrackDates.forEach { (id, ts) -> dates.put(id, ts) }
        val playlists = JSONObject()
        playlistTracks.forEach { (key, ids) ->
            if (ids.isNotEmpty()) playlists.put(key, JSONArray(ids))
        }
        val syncObj = JSONObject()
            .put("L", encodeSyncMapJson(likedTracksSync))
            .put("D", encodeSyncMapJson(dislikedTracksSync))
            .put("A", encodeSyncMapJson(likedAlbumsSync))
            .put("R", encodeSyncMapJson(likedArtistsSync))
            .put("B", encodeSyncMapJson(dislikedArtistsSync))
            .put("Q", encodeSyncMapJson(likedPlaylistsSync))
            .put("N", encodeSyncMapJson(pinsSync))
            .put("P", encodePlaylistSyncJson(playlistSync))
        return JSONObject()
            .put("syncVersion", 1)
            .put("likedTracks", JSONArray(likedTracks))
            .put("likedTrackDates", dates)
            .put("dislikedTracks", JSONArray(dislikedTracks))
            .put("likedAlbums", JSONArray(likedAlbums))
            .put("likedArtists", JSONArray(likedArtists))
            .put("dislikedArtists", JSONArray(dislikedArtists))
            .put("likedPlaylists", JSONArray(likedPlaylists))
            .put("playlistTracks", playlists)
            .put("pins", JSONArray(pins))
            .put("sync", syncObj)
    }

    companion object {
        private fun pushFront(list: MutableList<String>, id: String) {
            list.removeAll { it == id }
            list.add(0, id)
        }

        private fun localKeyToWireKind(key: String, uid: String?): String? {
            if (uid != null && key.startsWith("$uid:")) return key.removePrefix("$uid:").takeIf(String::isNotEmpty)
            return if (':' !in key) key.takeIf(String::isNotEmpty) else null
        }

        private fun normalizePlaylistKey(key: String, uid: String?): String =
            if (uid != null && ':' !in key) "$uid:$key" else key

        private fun mergeSet(
            a: Map<String, SyncEntry>,
            b: Map<String, SyncEntry>,
            out: MutableMap<String, SyncEntry>,
        ) {
            val keys = LinkedHashSet<String>(a.size + b.size)
            keys.addAll(a.keys)
            keys.addAll(b.keys)
            for (k in keys) {
                val ea = a[k]
                val eb = b[k]
                out[k] = when {
                    ea == null -> eb!!
                    eb == null -> ea
                    ea.ts > eb.ts -> ea
                    eb.ts > ea.ts -> eb
                    else -> SyncEntry(ea.ts, ea.active || eb.active)
                }
            }
        }

        private fun resolveMutualExclusion(
            likes: MutableMap<String, SyncEntry>,
            dislikes: MutableMap<String, SyncEntry>,
        ) {
            for ((id, likeEntry) in likes) {
                val dislikeEntry = dislikes[id] ?: continue
                if (likeEntry.active && dislikeEntry.active) {
                    if (likeEntry.ts >= dislikeEntry.ts) {
                        dislikes[id] = SyncEntry(likeEntry.ts, false)
                    } else {
                        likes[id] = SyncEntry(dislikeEntry.ts, false)
                    }
                }
            }
        }

        private fun materializeList(syncMap: Map<String, SyncEntry>, out: MutableList<String>) {
            out.clear()
            syncMap.entries
                .filter { it.value.active }
                .sortedWith(compareByDescending<Map.Entry<String, SyncEntry>> { it.value.ts }.thenBy { it.key })
                .mapTo(out) { it.key }
        }

        private fun encodeSyncMapJson(map: Map<String, SyncEntry>): JSONObject {
            val obj = JSONObject()
            for ((id, entry) in map) {
                obj.put(id, JSONObject().put("ts", entry.ts).put("a", entry.active))
            }
            return obj
        }

        private fun decodeSyncMapJson(obj: JSONObject?): MutableMap<String, SyncEntry> {
            val out = mutableMapOf<String, SyncEntry>()
            if (obj == null) return out
            obj.keys().forEach { id ->
                val row = obj.optJSONObject(id) ?: return@forEach
                val ts = row.optLong("ts", -1L)
                if (ts >= 0L) {
                    out[id] = SyncEntry(ts, row.optBoolean("a", false))
                }
            }
            return out
        }

        private fun encodePlaylistSyncJson(map: Map<String, PlaylistSyncEntry>): JSONObject {
            val obj = JSONObject()
            for ((key, entry) in map) {
                obj.put(key, JSONObject().put("ts", entry.ts).put("t", JSONArray(entry.tracks)))
            }
            return obj
        }

        private fun decodePlaylistSyncJson(obj: JSONObject?): MutableMap<String, PlaylistSyncEntry> {
            val out = mutableMapOf<String, PlaylistSyncEntry>()
            if (obj == null) return out
            obj.keys().forEach { key ->
                val row = obj.optJSONObject(key) ?: return@forEach
                val ts = row.optLong("ts", -1L)
                if (ts >= 0L) {
                    out[key] = PlaylistSyncEntry(ts, stringList(row.optJSONArray("t")))
                }
            }
            return out
        }

        private fun stringList(array: JSONArray?): MutableList<String> {
            if (array == null) return mutableListOf()
            val out = mutableListOf<String>()
            for (i in 0 until array.length()) {
                array.opt(i).asId()?.let { if (it !in out) out.add(it) }
            }
            return out
        }

        private fun seedLegacySync(ids: List<String>, nowSec: Long, out: MutableMap<String, SyncEntry>) {
            ids.forEachIndexed { index, id ->
                out[id] = SyncEntry(nowSec - index, true)
            }
        }

        fun decodePayload(payload: String, uid: String? = null): CatalogLibraryData? {
            val data = CatalogLibraryData()
            if (payload.isEmpty()) return data
            for (section in payload.split(';')) {
                if (section.isEmpty()) continue
                val colon = section.indexOf(':')
                if (colon <= 0) return null
                val code = section.substring(0, colon)
                val body = section.substring(colon + 1)
                if (body.isEmpty()) continue
                when (code) {
                    "L" -> if (!parseSetSection(body, TuneSync.TRACK_BASE, data.likedTracksSync)) return null
                    "D" -> if (!parseSetSection(body, TuneSync.TRACK_BASE, data.dislikedTracksSync)) return null
                    "A" -> if (!parseSetSection(body, TuneSync.ALBUM_BASE, data.likedAlbumsSync)) return null
                    "R" -> if (!parseSetSection(body, TuneSync.ARTIST_BASE, data.likedArtistsSync)) return null
                    "B" -> if (!parseSetSection(body, TuneSync.ARTIST_BASE, data.dislikedArtistsSync)) return null
                    "Q" -> if (!parseSetSection(body, TuneSync.PLAYLIST_BASE, data.likedPlaylistsSync)) return null
                    "P" -> if (!parsePlaylistSection(body, uid, data.playlistSync, data.playlistTracks)) return null
                    "N" -> if (!parsePinSection(body, data.pinsSync)) return null
                    else -> Unit // ignore unknown forward-compatible section codes
                }
            }
            materializeList(data.likedTracksSync, data.likedTracks)
            materializeList(data.dislikedTracksSync, data.dislikedTracks)
            materializeList(data.likedAlbumsSync, data.likedAlbums)
            materializeList(data.likedArtistsSync, data.likedArtists)
            materializeList(data.dislikedArtistsSync, data.dislikedArtists)
            materializeList(data.likedPlaylistsSync, data.likedPlaylists)
            materializeList(data.pinsSync, data.pins)
            for (id in data.likedTracks) {
                data.likedTrackDates[id] = (data.likedTracksSync[id]?.ts ?: 0L) * 1000L
            }
            return data
        }

        private fun parseSetSection(body: String, base: Long, out: MutableMap<String, SyncEntry>): Boolean {
            for (item in body.split(',')) {
                if (item.isEmpty()) continue
                val sign = item[0]
                if (sign != '+' && sign != '-') return false
                val rest = item.substring(1)
                val at = rest.lastIndexOf('@')
                if (at <= 0 || at >= rest.lastIndex) return false
                val idTok = rest.substring(0, at)
                val tsTok = rest.substring(at + 1)
                val id = TuneSync.decodeId(idTok, base) ?: return false
                val ts = TuneSync.decodeTs(tsTok) ?: return false
                val candidate = SyncEntry(ts, sign == '+')
                val prev = out[id]
                if (prev == null || candidate.ts > prev.ts || (candidate.ts == prev.ts && candidate.active && !prev.active)) {
                    out[id] = candidate
                }
            }
            return true
        }

        private fun parsePlaylistSection(
            body: String,
            uid: String?,
            syncOut: MutableMap<String, PlaylistSyncEntry>,
            tracksOut: MutableMap<String, MutableList<String>>,
        ): Boolean {
            for (item in body.split(',')) {
                if (item.isEmpty()) continue
                val eq = item.indexOf('=')
                if (eq <= 0) return false
                val head = item.substring(0, eq)
                val tail = item.substring(eq + 1)
                val at = head.lastIndexOf('@')
                if (at <= 0 || at >= head.lastIndex) return false
                val kind = head.substring(0, at)
                val ts = TuneSync.decodeTs(head.substring(at + 1)) ?: return false
                val trackIds = if (tail.isEmpty()) {
                    emptyList()
                } else {
                    tail.split('.').map { TuneSync.decodeId(it, TuneSync.TRACK_BASE) ?: return false }
                }
                val localKey = if (uid != null && ':' !in kind) "$uid:$kind" else kind
                val prev = syncOut[localKey]
                if (prev == null || ts > prev.ts || (ts == prev.ts && trackIds.isNotEmpty() && prev.tracks.isEmpty())) {
                    syncOut[localKey] = PlaylistSyncEntry(ts, trackIds)
                    if (trackIds.isNotEmpty()) {
                        tracksOut[localKey] = trackIds.toMutableList()
                    } else {
                        tracksOut.remove(localKey)
                    }
                }
            }
            return true
        }

        private fun parsePinSection(body: String, out: MutableMap<String, SyncEntry>): Boolean {
            for (item in body.split(',')) {
                if (item.isEmpty()) continue
                val sign = item[0]
                if (sign != '+' && sign != '-') return false
                val rest = item.substring(1)
                val at = rest.lastIndexOf('@')
                val colon = rest.indexOf(':')
                if (colon <= 0 || at <= colon + 1 || at >= rest.lastIndex) return false
                val pinKind = rest.substring(0, colon)
                val idTok = rest.substring(colon + 1, at)
                val tsTok = rest.substring(at + 1)
                val decodedId = TuneSync.decodeId(idTok, TuneSync.baseForPinKind(pinKind)) ?: return false
                val ts = TuneSync.decodeTs(tsTok) ?: return false
                val key = if (pinKind == "item") decodedId else "$pinKind:$decodedId"
                val candidate = SyncEntry(ts, sign == '+')
                val prev = out[key]
                if (prev == null || candidate.ts > prev.ts || (candidate.ts == prev.ts && candidate.active && !prev.active)) {
                    out[key] = candidate
                }
            }
            return true
        }

        fun fromJson(value: JSONObject, nowSec: Long = System.currentTimeMillis() / 1000L): CatalogLibraryData {
            val likedTracks = stringList(value.optJSONArray("likedTracks"))
            val datesObj = value.optJSONObject("likedTrackDates")
            val likedTrackDates = mutableMapOf<String, Long>()
            if (datesObj != null) {
                datesObj.keys().forEach { id ->
                    val ts = datesObj.optLong(id, 0L)
                    if (ts > 0L) likedTrackDates[id] = ts
                }
            }
            val dislikedTracks = stringList(value.optJSONArray("dislikedTracks"))
            val likedAlbums = stringList(value.optJSONArray("likedAlbums"))
            val likedArtists = stringList(value.optJSONArray("likedArtists"))
            val dislikedArtists = stringList(value.optJSONArray("dislikedArtists"))
            val likedPlaylists = stringList(value.optJSONArray("likedPlaylists"))
            val playlistsObj = value.optJSONObject("playlistTracks")
            val playlistTracks = mutableMapOf<String, MutableList<String>>()
            if (playlistsObj != null) {
                playlistsObj.keys().forEach { key ->
                    val ids = stringList(playlistsObj.optJSONArray(key))
                    if (ids.isNotEmpty()) playlistTracks[key] = ids
                }
            }
            val pins = stringList(value.optJSONArray("pins"))

            val syncObj = value.optJSONObject("sync")
            val likedTracksSync: MutableMap<String, SyncEntry>
            val dislikedTracksSync: MutableMap<String, SyncEntry>
            val likedAlbumsSync: MutableMap<String, SyncEntry>
            val likedArtistsSync: MutableMap<String, SyncEntry>
            val dislikedArtistsSync: MutableMap<String, SyncEntry>
            val likedPlaylistsSync: MutableMap<String, SyncEntry>
            val pinsSync: MutableMap<String, SyncEntry>
            val playlistSync: MutableMap<String, PlaylistSyncEntry>

            if (syncObj != null) {
                likedTracksSync = decodeSyncMapJson(syncObj.optJSONObject("L"))
                dislikedTracksSync = decodeSyncMapJson(syncObj.optJSONObject("D"))
                likedAlbumsSync = decodeSyncMapJson(syncObj.optJSONObject("A"))
                likedArtistsSync = decodeSyncMapJson(syncObj.optJSONObject("R"))
                dislikedArtistsSync = decodeSyncMapJson(syncObj.optJSONObject("B"))
                likedPlaylistsSync = decodeSyncMapJson(syncObj.optJSONObject("Q"))
                pinsSync = decodeSyncMapJson(syncObj.optJSONObject("N"))
                playlistSync = decodePlaylistSyncJson(syncObj.optJSONObject("P"))
            } else {
                likedTracksSync = mutableMapOf<String, SyncEntry>().also { seedLegacySync(likedTracks, nowSec, it) }
                dislikedTracksSync = mutableMapOf<String, SyncEntry>().also { seedLegacySync(dislikedTracks, nowSec, it) }
                likedAlbumsSync = mutableMapOf<String, SyncEntry>().also { seedLegacySync(likedAlbums, nowSec, it) }
                likedArtistsSync = mutableMapOf<String, SyncEntry>().also { seedLegacySync(likedArtists, nowSec, it) }
                dislikedArtistsSync = mutableMapOf<String, SyncEntry>().also { seedLegacySync(dislikedArtists, nowSec, it) }
                likedPlaylistsSync = mutableMapOf<String, SyncEntry>().also { seedLegacySync(likedPlaylists, nowSec, it) }
                pinsSync = mutableMapOf<String, SyncEntry>().also { seedLegacySync(pins, nowSec, it) }
                playlistSync = mutableMapOf<String, PlaylistSyncEntry>().also { out ->
                    playlistTracks.forEach { (key, ids) -> out[key] = PlaylistSyncEntry(nowSec, ids.toList()) }
                }
            }

            return CatalogLibraryData(
                likedTracks = likedTracks,
                likedTrackDates = likedTrackDates,
                dislikedTracks = dislikedTracks,
                likedAlbums = likedAlbums,
                likedArtists = likedArtists,
                dislikedArtists = dislikedArtists,
                likedPlaylists = likedPlaylists,
                playlistTracks = playlistTracks,
                pins = pins,
                likedTracksSync = likedTracksSync,
                dislikedTracksSync = dislikedTracksSync,
                likedAlbumsSync = likedAlbumsSync,
                likedArtistsSync = likedArtistsSync,
                dislikedArtistsSync = dislikedArtistsSync,
                likedPlaylistsSync = likedPlaylistsSync,
                playlistSync = playlistSync,
                pinsSync = pinsSync,
            )
        }
    }
}

internal class CatalogLibraryStore(private val directory: File) {
    private val cache = mutableMapOf<String, CatalogLibraryData>()
    var onLocalMutate: ((String) -> Unit)? = null

    private fun fileFor(uid: String): File = File(directory, "catalog-library-${digest(uid)}.json")

    @Synchronized
    private fun loadLocked(uid: String): CatalogLibraryData {
        cache[uid]?.let { return it }
        val file = fileFor(uid)
        var migrated = false
        val loaded = if (file.isFile) {
            optional {
                val obj = JSONObject(file.readText(Charsets.UTF_8))
                migrated = !obj.has("sync")
                CatalogLibraryData.fromJson(obj)
            } ?: CatalogLibraryData()
        } else {
            CatalogLibraryData()
        }
        cache[uid] = loaded
        if (migrated) saveLocked(uid, loaded)
        return loaded
    }

    @Synchronized
    fun snapshot(uid: String): CatalogLibraryData = loadLocked(uid).copyData()

    fun mutate(uid: String, block: (CatalogLibraryData) -> Unit): CatalogLibraryData {
        val updated = synchronized(this) {
            val data = loadLocked(uid)
            block(data)
            saveLocked(uid, data)
            data.copyData()
        }
        onLocalMutate?.invoke(uid)
        return updated
    }

    @Synchronized
    fun applyMerged(uid: String, transform: (CatalogLibraryData) -> CatalogLibraryData?): Boolean {
        val current = loadLocked(uid)
        val updated = transform(current.copyData()) ?: return false
        if (updated == current) return false
        cache[uid] = updated
        saveLocked(uid, updated)
        return true
    }

    private fun saveLocked(uid: String, data: CatalogLibraryData) {
        if (!directory.isDirectory) directory.mkdirs()
        val target = fileFor(uid)
        val temp = File(directory, ".${target.name}.${UUID.randomUUID()}.tmp")
        try {
            temp.writeText(data.toJson().toString(), Charsets.UTF_8)
            try {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: Exception) {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temp.delete()
        }
    }

    fun likedTrackEntries(uid: String): List<Pair<String, Long?>> = snapshot(uid).let { data ->
        data.likedTracks.map { id -> id to data.likedTrackDates[id] }
    }

    fun likedTracks(uid: String): List<String> = snapshot(uid).likedTracks

    fun dislikedTracks(uid: String): List<String> = snapshot(uid).dislikedTracks

    fun likedAlbums(uid: String): List<String> = snapshot(uid).likedAlbums

    fun likedArtists(uid: String): List<String> = snapshot(uid).likedArtists

    fun dislikedArtists(uid: String): List<String> = snapshot(uid).dislikedArtists

    fun likedPlaylists(uid: String): List<String> = snapshot(uid).likedPlaylists

    fun playlistTracks(uid: String, owner: String, kind: String): List<String> =
        snapshot(uid).playlistTracks["$owner:$kind"].orEmpty()
}
