package music.tune.android

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

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
    )

    fun setTrackLiked(id: String, liked: Boolean, nowMs: Long = System.currentTimeMillis()) {
        if (liked) {
            dislikedTracks.removeAll { it == id }
            pushFront(likedTracks, id)
            likedTrackDates[id] = nowMs
        } else {
            likedTracks.removeAll { it == id }
            likedTrackDates.remove(id)
        }
    }

    fun setTrackDisliked(id: String, disliked: Boolean) {
        if (disliked) {
            likedTracks.removeAll { it == id }
            likedTrackDates.remove(id)
            pushFront(dislikedTracks, id)
        } else {
            dislikedTracks.removeAll { it == id }
        }
    }

    fun setAlbumLiked(id: String, liked: Boolean) {
        if (liked) pushFront(likedAlbums, id) else likedAlbums.removeAll { it == id }
    }

    fun setArtistLiked(id: String, liked: Boolean) {
        if (liked) {
            dislikedArtists.removeAll { it == id }
            pushFront(likedArtists, id)
        } else {
            likedArtists.removeAll { it == id }
        }
    }

    fun setArtistDisliked(id: String, disliked: Boolean) {
        if (disliked) {
            likedArtists.removeAll { it == id }
            pushFront(dislikedArtists, id)
        } else {
            dislikedArtists.removeAll { it == id }
        }
    }

    fun setPlaylistLiked(kind: String, liked: Boolean) {
        if (liked) pushFront(likedPlaylists, kind) else likedPlaylists.removeAll { it == kind }
    }

    fun addPlaylistTrack(key: String, trackId: String) {
        val list = playlistTracks.getOrPut(key) { mutableListOf() }
        pushFront(list, trackId)
    }

    fun addPlaylistTracks(key: String, trackIds: List<String>) {
        if (trackIds.isEmpty()) return
        val list = playlistTracks.getOrPut(key) { mutableListOf() }
        val distinctNew = trackIds.distinct()
        list.removeAll(distinctNew.toSet())
        list.addAll(0, distinctNew)
    }

    fun removePlaylistTrack(key: String, trackId: String) {
        val list = playlistTracks[key] ?: return
        list.removeAll { it == trackId }
        if (list.isEmpty()) playlistTracks.remove(key)
    }

    fun removePlaylistTracks(key: String, trackIds: Set<String>) {
        if (trackIds.isEmpty()) return
        val list = playlistTracks[key] ?: return
        list.removeAll { it in trackIds }
        if (list.isEmpty()) playlistTracks.remove(key)
    }

    fun movePlaylistTrack(key: String, trackId: String, from: Int, to: Int) {
        val list = playlistTracks[key] ?: return
        val at = list.indexOf(trackId)
        if (at < 0) return
        val target = (at + to - from).coerceIn(0, list.lastIndex)
        if (target != at) {
            val item = list.removeAt(at)
            list.add(target, item)
        }
    }

    fun clearPlaylist(key: String) {
        playlistTracks.remove(key)
    }

    fun setPin(key: String, pinned: Boolean) {
        if (pinned) pushFront(pins, key) else pins.removeAll { it == key }
    }

    fun toJson(): JSONObject {
        val dates = JSONObject()
        likedTrackDates.forEach { (id, ts) -> dates.put(id, ts) }
        val playlists = JSONObject()
        playlistTracks.forEach { (key, ids) ->
            if (ids.isNotEmpty()) playlists.put(key, JSONArray(ids))
        }
        return JSONObject()
            .put("likedTracks", JSONArray(likedTracks))
            .put("likedTrackDates", dates)
            .put("dislikedTracks", JSONArray(dislikedTracks))
            .put("likedAlbums", JSONArray(likedAlbums))
            .put("likedArtists", JSONArray(likedArtists))
            .put("dislikedArtists", JSONArray(dislikedArtists))
            .put("likedPlaylists", JSONArray(likedPlaylists))
            .put("playlistTracks", playlists)
            .put("pins", JSONArray(pins))
    }

    companion object {
        private fun pushFront(list: MutableList<String>, id: String) {
            list.removeAll { it == id }
            list.add(0, id)
        }

        private fun stringList(array: JSONArray?): MutableList<String> {
            if (array == null) return mutableListOf()
            val out = mutableListOf<String>()
            for (i in 0 until array.length()) {
                array.opt(i).asId()?.let { if (it !in out) out.add(it) }
            }
            return out
        }

        fun fromJson(value: JSONObject): CatalogLibraryData {
            val likedTracks = stringList(value.optJSONArray("likedTracks"))
            val datesObj = value.optJSONObject("likedTrackDates")
            val likedTrackDates = mutableMapOf<String, Long>()
            if (datesObj != null) {
                datesObj.keys().forEach { id ->
                    val ts = datesObj.optLong(id, 0L)
                    if (ts > 0L) likedTrackDates[id] = ts
                }
            }
            val playlistsObj = value.optJSONObject("playlistTracks")
            val playlistTracks = mutableMapOf<String, MutableList<String>>()
            if (playlistsObj != null) {
                playlistsObj.keys().forEach { key ->
                    val ids = stringList(playlistsObj.optJSONArray(key))
                    if (ids.isNotEmpty()) playlistTracks[key] = ids
                }
            }
            return CatalogLibraryData(
                likedTracks = likedTracks,
                likedTrackDates = likedTrackDates,
                dislikedTracks = stringList(value.optJSONArray("dislikedTracks")),
                likedAlbums = stringList(value.optJSONArray("likedAlbums")),
                likedArtists = stringList(value.optJSONArray("likedArtists")),
                dislikedArtists = stringList(value.optJSONArray("dislikedArtists")),
                likedPlaylists = stringList(value.optJSONArray("likedPlaylists")),
                playlistTracks = playlistTracks,
                pins = stringList(value.optJSONArray("pins")),
            )
        }
    }
}

internal class CatalogLibraryStore(private val directory: File) {
    private val cache = mutableMapOf<String, CatalogLibraryData>()

    private fun fileFor(uid: String): File = File(directory, "catalog-library-${digest(uid)}.json")

    @Synchronized
    private fun loadLocked(uid: String): CatalogLibraryData {
        cache[uid]?.let { return it }
        val file = fileFor(uid)
        val loaded = if (file.isFile) {
            optional { CatalogLibraryData.fromJson(JSONObject(file.readText(Charsets.UTF_8))) } ?: CatalogLibraryData()
        } else {
            CatalogLibraryData()
        }
        cache[uid] = loaded
        return loaded
    }

    @Synchronized
    fun snapshot(uid: String): CatalogLibraryData = loadLocked(uid).copyData()

    @Synchronized
    fun mutate(uid: String, block: (CatalogLibraryData) -> Unit): CatalogLibraryData {
        val data = loadLocked(uid)
        block(data)
        saveLocked(uid, data)
        return data.copyData()
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
