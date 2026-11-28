package music.tune.android

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.net.URL
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal fun <T> mergeTimedOrdered(
    local: List<Pair<T, Long?>>,
    remote: List<Pair<T, Long?>>,
    ascending: Boolean,
    idOf: (T) -> String,
): List<T> {
    val seen = HashSet<String>(local.size + remote.size)
    val out = ArrayList<T>(local.size + remote.size)
    var li = 0
    var ri = 0
    var lastRemoteTs: Long? = null
    fun push(item: T) {
        if (seen.add(idOf(item))) out.add(item)
    }
    while (li < local.size && ri < remote.size) {
        val localTs = local[li].second ?: if (ascending) Long.MAX_VALUE else Long.MIN_VALUE
        val remoteTs = remote[ri].second ?: lastRemoteTs
        val takeRemote = remoteTs != null && if (ascending) remoteTs < localTs else remoteTs > localTs
        if (takeRemote) {
            val (item, ts) = remote[ri++]
            if (ts != null) lastRemoteTs = ts
            push(item)
        } else {
            push(local[li++].first)
        }
    }
    while (li < local.size) push(local[li++].first)
    while (ri < remote.size) push(remote[ri++].first)
    return out
}

internal fun <T> mergeTimedDesc(
    local: List<Pair<T, Long?>>,
    remote: List<Pair<T, Long?>>,
    idOf: (T) -> String,
): List<T> {
    val sortedLocal = local.sortedWith(
        compareByDescending<Pair<T, Long?>> { it.second ?: Long.MIN_VALUE }.thenBy { idOf(it.first) }
    )
    return mergeTimedOrdered(sortedLocal, remote, ascending = false, idOf = idOf)
}

internal fun <T> mergeTimedPlaylist(
    local: List<Pair<T, Long?>>,
    remote: List<Pair<T, Long?>>,
    forceDesc: Boolean,
    idOf: (T) -> String,
): List<T> {
    val firstTs = remote.firstOrNull { it.second != null }?.second
    val lastTs = remote.lastOrNull { it.second != null }?.second
    val ascending = !forceDesc && firstTs != null && lastTs != null && firstTs < lastTs
    val sortedLocal = if (ascending) {
        local.sortedWith(compareBy<Pair<T, Long?>> { it.second ?: Long.MAX_VALUE }.thenBy { idOf(it.first) })
    } else {
        local.sortedWith(compareByDescending<Pair<T, Long?>> { it.second ?: Long.MIN_VALUE }.thenBy { idOf(it.first) })
    }
    return mergeTimedOrdered(sortedLocal, remote, ascending = ascending, idOf = idOf)
}

internal class MusicError(message: String, val status: Int = 0, cause: Throwable? = null, val details: JSONObject? = null) :
    IOException(message, cause)

internal fun secureConnection(address: String, network: android.net.Network? = null): HttpURLConnection {
    val url = URL(address)
    if (url.protocol != "https" || url.host.isBlank()) throw MusicError("Неверный адрес файла")
    return ((network?.openConnection(url) ?: url.openConnection()) as HttpURLConnection).apply {
        connectTimeout = 12_000
        readTimeout = 20_000
    }
}

internal class MusicApi(
    private val token: String,
    private val versions: AlternateVersions,
    private val catalogLibrary: CatalogLibraryStore? = null,
    private val activeUid: String? = null,
    private val catalogEnabled: () -> Boolean = { true },
    private val onSyncShards: ((String, List<RemoteSyncShard>) -> Unit)? = null,
    private val metadata: (String, String) -> MetadataOverride = { _, _ -> MetadataOverride() },
) {
    private fun request(
        path: String,
        params: Map<String, String> = emptyMap(),
        body: String? = null,
        type: String = "application/x-www-form-urlencoded",
        method: String = if (body == null) "GET" else "POST",
        bytes: ByteArray? = null,
    ): Any {
        val uri = URI(path.trimStart('/'))
        if (uri.isAbsolute || uri.rawAuthority != null || uri.rawPath.isNullOrBlank()) throw MusicError("Неверный адрес запроса")
        val query = params.entries.joinToString("&") { (key, value) -> "${encode(key)}=${encode(value)}" }
        val suffix = if (query.isEmpty()) "" else if (uri.rawQuery == null) "?$query" else "&$query"
        val connection = secureConnection("$BASE/$uri$suffix")
        try {
            connection.instanceFollowRedirects = false
            connection.requestMethod = method
            connection.setRequestProperty("Authorization", "OAuth $token")
            connection.setRequestProperty("X-Yandex-Music-Client", CLIENT)
            val payload = bytes ?: body?.toByteArray(Charsets.UTF_8)
            if (payload != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", type)
                connection.setFixedLengthStreamingMode(payload.size)
                connection.outputStream.use { it.write(payload) }
            }
            val status = connection.responseCode
            if (status !in 200..299) {
                val details = if (path.startsWith("wave/rooms")) optional {
                    connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { JSONObject(it.readText()) }
                } else null
                throw MusicError(when (status) {
                    401 -> "Войдите в аккаунт заново"
                    403 -> "Яндекс ограничил доступ к этому разделу"
                    404 -> "Яндекс не нашёл этот раздел или трек"
                    429 -> "Слишком много запросов. Попробуйте немного позже"
                    else -> "Яндекс ответил с ошибкой ($status)"
                }, status, details = details)
            }
            val text = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            if (text.isBlank()) return JSONObject()
            val json = JSONTokener(text).nextValue()
            if (json is JSONObject && json.has("error") && !json.isNull("error")) {
                throw MusicError("Яндекс не выполнил запрос", status)
            }
            return (json as? JSONObject)?.opt("result")?.takeUnless { it == JSONObject.NULL } ?: json
        } catch (error: MusicError) {
            throw error
        } catch (error: IOException) {
            throw MusicError("Нет связи с Яндексом", cause = error)
        } finally {
            connection.disconnect()
        }
    }

    private fun get(path: String, vararg params: Pair<String, String>) = request(path, params.toMap())
    private fun post(path: String, vararg params: Pair<String, String>) = request(path, params.toMap(), "")
    private fun form(path: String, vararg values: Pair<String, String>) =
        request(path, body = values.joinToString("&") { (key, value) -> "${encode(key)}=${encode(value)}" })
    private fun json(path: String, values: JSONObject, vararg params: Pair<String, String>) =
        request(path, params.toMap(), values.toString(), "application/json")

    private fun track(value: JSONObject, batch: String? = null): Track? {
        val id = value.text("id")
        val custom = id?.let { metadata("track", it) } ?: MetadataOverride()
        val isCatalog = custom.usePlugin && versions.isCatalogTrack(id)
        val isFck = custom.usePlugin && versions.hasFckAudio(id)
        val parsed = if (custom.usePlugin) {
            Track.parse(versions.patch(value, Kind.ALBUM, track = true), alternate = isFck, batch = batch, catalog = isCatalog)
        } else {
            Track.parse(value, alternate = false, batch = batch, catalog = false)
        }
        if (parsed == null) return null
        return overrideTrack(parsed.copy(originalAvailable = value.opt("available") as? Boolean))
    }

    private fun overrideTrack(track: Track): Track {
        val custom = metadata("track", track.id)
        return track.copy(title = custom.title.ifBlank { track.title },
            artists = if (custom.artist.isBlank()) track.artists else listOf(Card(Kind.ARTIST, track.artists.firstOrNull()?.id.orEmpty(), custom.artist)),
            cover = custom.cover.ifBlank { track.cover ?: "" }.takeIf(String::isNotBlank))
    }

    private fun restoredTrack(id: String, album: Card? = null): Track? {
        if (!metadata("track", id).usePlugin) return null
        return versions.synthetic(id, album)?.let(::overrideTrack)
    }

    private fun overrideCard(card: Card, type: String): Card {
        val custom = metadata(type, card.id)
        return card.copy(title = custom.title.ifBlank { card.title }, cover = custom.cover.ifBlank { card.cover ?: "" }.takeIf(String::isNotBlank))
    }

    private fun album(value: JSONObject): Card? {
        val usePlugin = value.text("id")?.let { metadata("album", it).usePlugin } ?: true
        return Card.album(if (usePlugin) versions.patch(value, Kind.ALBUM) else value)?.let { overrideCard(it, "album") }
    }
    private fun artistCard(value: JSONObject) = Card.artist(value)?.let { overrideCard(it, "artist") }
    private fun Any.objectValue(): JSONObject = this as? JSONObject ?: JSONObject()
    private fun Any.objectList() = (this as? JSONArray)?.objects().orEmpty()

    fun profile(): Profile {
        val value = get("account/status").objectValue()
        val account = value.obj("account")
        val uid = account.text("uid") ?: throw MusicError("Яндекс не сообщил номер аккаунта")
        val login = account.text("login").orEmpty()
        return Profile(uid, account.text("displayName") ?: login.ifBlank { "Яндекс" }, value.obj("plus").optBoolean("hasPlus"), login)
    }

    fun reduceAi(): Boolean = get("account/settings").objectValue().optBoolean("aiContentReductionEnabled")

    fun setReduceAi(value: Boolean) {
        post("account/settings", "aiContentReductionEnabled" to value.toString())
    }

    internal fun rawTracks(ids: List<String>): List<JSONObject> {
        val yandexIds = ids.filterNot(versions::isSyntheticTrackId)
        return if (yandexIds.isEmpty()) emptyList() else form("tracks", "track-ids" to yandexIds.joinToString(",")).objectList()
    }

    fun tracks(ids: List<String>): List<Track> = ids.chunked(100).flatMap { chunk ->
        val yandexIds = chunk.map { it.substringBefore(':') }.filterNot(versions::isSyntheticTrackId).distinct()
        val found = if (yandexIds.isEmpty()) emptyMap() else {
            form("tracks", "track-ids" to yandexIds.joinToString(",")).objectList()
                .mapNotNull { track(it) }.associateBy { it.id }
        }
        chunk.mapNotNull { reference ->
            val id = reference.substringBefore(':')
            found[id] ?: restoredTrack(id)
        }
    }

    fun libraryIds(uid: String, dislike: Boolean = false): List<String> = libraryEntries(uid, dislike).map { it.first }

    fun likedEntries(uid: String): List<Pair<String, Long?>> = libraryEntries(uid, false)

    private fun entryDate(value: String?): Long? = value?.let { optional { Instant.parse(it).toEpochMilli() } }

    fun fullLikedEntries(): List<Pair<String, Long?>> {
        val body = JSONObject().put("likedTracks", JSONObject().put("revision", 0))
        val entries = json("collection/sync", body).objectValue().obj("values").obj("likedTracks").obj("values")
            .optJSONArray("liked") ?: throw MusicError("Яндекс не прислал полный список лайков")
        return entries.objects().mapNotNull {
            (it.text("trackId") ?: it.text("id"))?.let { id -> id to entryDate(it.text("timestamp")) }
        }
    }

    private fun libraryEntries(uid: String, dislike: Boolean): List<Pair<String, Long?>> {
        val local = if (catalogEnabled()) {
            if (dislike) catalogLibrary?.dislikedTracks(uid).orEmpty().map { it to null }
            else catalogLibrary?.likedTrackEntries(uid).orEmpty()
        } else emptyList()
        if (!dislike) {
            val full = optional { fullLikedEntries() }
            if (full != null) {
                var enriched = full
                if (full.isNotEmpty() && full.all { it.second == null }) {
                    val fallback = optional {
                        get("users/${segment(uid)}/likes/tracks").objectValue()
                            .obj("library").arr("tracks").objects()
                            .mapNotNull { it.text("id")?.let { id -> id to entryDate(it.text("timestamp")) } }
                    }.orEmpty().mapNotNull { (id, ts) -> ts?.let { id to it } }.toMap()
                    if (fallback.isNotEmpty()) {
                        enriched = full.map { (id, _) -> id to fallback[id] }
                    }
                }
                val tsMap = HashMap<String, Long>()
                (enriched + local).forEach { (id, ts) -> if (ts != null) tsMap.putIfAbsent(id, ts) }
                return mergeTimedDesc(local, enriched) { it }.map { it to tsMap[it] }
            }
        }
        val list = if (dislike) "dislikes" else "likes"
        val remote = get("users/${segment(uid)}/$list/tracks").objectValue()
            .obj("library").arr("tracks").objects().mapNotNull { it.text("id")?.let { id -> id to entryDate(it.text("timestamp")) } }
        if (dislike) return (local + remote).distinctBy { it.first }
        val tsMap = HashMap<String, Long>()
        (remote + local).forEach { (id, ts) -> if (ts != null) tsMap.putIfAbsent(id, ts) }
        return mergeTimedDesc(local, remote) { it }.map { it to tsMap[it] }
    }

    private fun catalogPlaylistCard(kind: String): Card? {
        val (fields, trackIds) = versions.catalogPlaylist(kind) ?: return null
        val title = fields.text("title") ?: return null
        return Card(
            Kind.PLAYLIST,
            kind,
            title,
            "Каталог Tune",
            fields.text("coverUri"),
            AlternateVersions.CATALOG_OWNER,
            trackCount = trackIds.size,
        )
    }

    fun dislikedArtists(uid: String): List<Card> {
        val local = if (catalogEnabled()) {
            catalogLibrary?.dislikedArtists(uid).orEmpty().mapNotNull { id ->
                versions.catalogArtist(id)?.first?.let(::artistCard)
            }
        } else emptyList()
        val remote = get("users/${segment(uid)}/dislikes/artists").objectList().mapNotNull {
            Card.artist(it.optJSONObject("artist") ?: it)
        }
        return (local + remote).distinctBy { it.key }
    }

    fun setArtistHidden(uid: String, id: String, hide: Boolean) {
        if (versions.isSyntheticArtistId(id)) {
            catalogLibrary?.mutate(uid) { it.setArtistDisliked(id, hide) }
            return
        }
        val path = "users/${segment(uid)}/dislikes/artists"
        if (hide) post("$path/add", "artist-id" to id) else post("$path/${segment(id)}/remove")
    }

    fun setTrackRating(uid: String, id: String, disliked: Boolean, add: Boolean) {
        if (versions.isSyntheticTrackId(id)) {
            catalogLibrary?.mutate(uid) {
                if (disliked) it.setTrackDisliked(id, add) else it.setTrackLiked(id, add)
            }
            return
        }
        val kind = if (disliked) "dislikes" else "likes"
        val path = "users/${segment(uid)}/$kind/tracks"
        if (add) post("$path/add", "track-id" to id) else post("$path/${segment(id)}/remove")
    }

    fun playlistsWithSync(uid: String): Pair<List<Card>, List<RemoteSyncShard>> {
        val raw = get("users/${segment(uid)}/playlists/list").objectList()
        val syncShards = mutableListOf<RemoteSyncShard>()
        val cards = mutableListOf<Card>()
        for (item in raw) {
            val obj = item.optJSONObject("playlist") ?: item
            val title = obj.text("title")
            val description = obj.text("description")
            val kind = obj.text("kind") ?: obj.text("id")
            if (TuneSync.isSyncPlaylist(title, description)) {
                if (kind != null && title != null) {
                    val cover = obj.optJSONObject("cover")
                    val hasCustomCover = cover != null && (
                        cover.optBoolean("custom", false) ||
                            (cover.text("type") == "pic" && !cover.text("uri").isNullOrBlank())
                        )
                    syncShards += RemoteSyncShard(
                        kind = kind,
                        title = title,
                        description = description,
                        hasCustomCover = hasCustomCover,
                        isPrivate = obj.text("visibility") == "private",
                    )
                }
                continue
            }
            Card.playlist(obj, uid)?.let(cards::add)
        }
        val libUid = activeUid ?: uid
        val visible = cards.map { card ->
            val extraCount = if (catalogEnabled()) catalogLibrary?.playlistTracks(libUid, card.owner ?: uid, card.id)?.size ?: 0 else 0
            if (extraCount > 0) card.copy(trackCount = card.trackCount + extraCount) else card
        }
        return visible to syncShards
    }

    fun playlists(uid: String, liked: Boolean = false): List<Card> {
        if (liked) {
            val remote = get("users/${segment(uid)}/likes/playlists").objectList().mapNotNull { item ->
                val obj = item.optJSONObject("playlist") ?: item
                if (TuneSync.isSyncPlaylist(obj.text("title"), obj.text("description"))) return@mapNotNull null
                Card.playlist(obj, null)
            }
            val local = if (catalogEnabled()) catalogLibrary?.likedPlaylists(uid).orEmpty().mapNotNull(::catalogPlaylistCard) else emptyList()
            return (local + remote).distinctBy { it.key }
        }
        val (visible, syncShards) = playlistsWithSync(uid)
        onSyncShards?.invoke(uid, syncShards)
        return visible
    }

    fun likedCards(uid: String, kind: Kind): List<Card> = when (kind) {
        Kind.PLAYLIST -> playlists(uid, liked = true)
        Kind.ALBUM -> {
            val localIds = if (catalogEnabled()) catalogLibrary?.likedAlbums(uid).orEmpty() else emptyList()
            val local = localIds.mapNotNull { id -> versions.catalogAlbum(id)?.let(::album) }
            val remote = get("users/${segment(uid)}/likes/albums", "rich" to "true").objectList()
                .mapNotNull { album(it.optJSONObject("album") ?: it) }
            (local + remote).distinctBy { it.key }
        }
        Kind.ARTIST -> {
            val localIds = if (catalogEnabled()) catalogLibrary?.likedArtists(uid).orEmpty() else emptyList()
            val local = localIds.mapNotNull { id -> versions.catalogArtist(id)?.first?.let(::artistCard) }
            val remote = get("users/${segment(uid)}/likes/artists", "with-timestamps" to "false").objectList()
                .mapNotNull { artistCard(it.optJSONObject("artist") ?: it) }
            (local + remote).distinctBy { it.key }
        }
        else -> emptyList()
    }

    fun topArtists(): List<Card> = parseTopArtists(get("personal/top/artists/month").objectValue(), ::artistCard)

    fun artistCover(id: String): String? {
        if (versions.isSyntheticArtistId(id)) return versions.catalogArtist(id)?.first?.let(::artistCard)?.cover
        return artistCard(get("artists/${segment(id)}/brief-info").objectValue().obj("artist"))?.cover
    }

    fun setCardLiked(uid: String, card: Card, add: Boolean) {
        when {
            card.kind == Kind.PLAYLIST && versions.isSyntheticPlaylistId(card.id, card.owner) -> {
                catalogLibrary?.mutate(uid) { it.setPlaylistLiked(card.id, add) }
                return
            }
            card.kind == Kind.ALBUM && versions.isSyntheticAlbumId(card.id) -> {
                catalogLibrary?.mutate(uid) { it.setAlbumLiked(card.id, add) }
                return
            }
            card.kind == Kind.ARTIST && versions.isSyntheticArtistId(card.id) -> {
                catalogLibrary?.mutate(uid) { it.setArtistLiked(card.id, add) }
                return
            }
        }
        val users = "users/${segment(uid)}/likes"
        when (card.kind) {
            Kind.PLAYLIST -> {
                val owner = card.owner ?: return
                if (add) post("$users/playlists/add", "owner-uid" to owner, "kind" to card.id)
                else post("$users/playlists/${segment(owner)}-${segment(card.id)}/remove")
            }
            Kind.ALBUM, Kind.ARTIST -> {
                val kind = card.kind.name.lowercase(java.util.Locale.ROOT)
                if (add) post("$users/${kind}s/add", "$kind-id" to card.id)
                else post("$users/${kind}s/${segment(card.id)}/remove")
            }
            else -> Unit
        }
    }

    private fun playlistPath(card: Card) = "users/${segment(requireNotNull(card.owner))}/playlists/${segment(card.id)}"

    fun playlist(card: Card): MusicPage {
        if (versions.isSyntheticPlaylistId(card.id, card.owner)) {
            val (fields, trackIds) = versions.catalogPlaylist(card.id) ?: throw MusicError("Плейлист не найден", 404)
            val resolved = tracks(trackIds)
            val parsed = Card(
                Kind.PLAYLIST,
                card.id,
                fields.text("title") ?: card.title,
                "Каталог Tune",
                fields.text("coverUri") ?: card.cover,
                AlternateVersions.CATALOG_OWNER,
                trackCount = resolved.size,
            )
            return MusicPage(parsed, resolved, description = fields.text("description"), publicPlaylist = true)
        }
        val value = get(playlistPath(card), "rich-tracks" to "true").objectValue()
        val parsed = Card.playlist(value, card.owner) ?: card
        val libUid = activeUid ?: card.owner
        val localTimed = if (catalogEnabled() && libUid != null && card.owner != null) {
            val playlistTimed = catalogLibrary?.playlistTrackEntries(libUid, card.owner, card.id).orEmpty()
            if (card.id == "3" && card.owner == libUid) {
                (catalogLibrary?.likedTrackEntries(libUid).orEmpty() + playlistTimed).distinctBy { it.first }
            } else {
                playlistTimed
            }
        } else emptyList()
        val localTsMap = localTimed.toMap()
        val localTracks = if (localTimed.isEmpty()) emptyList() else {
            optional { tracks(localTimed.map { it.first }) }.orEmpty()
                .map { t -> t.copy(addedAt = localTsMap[t.id]) to localTsMap[t.id] }
        }
        val remoteTracks = value.arr("tracks").objects().mapNotNull { item ->
            val source = item.optJSONObject("track") ?: item
            val restored = track(source) ?: (source.text("id") ?: item.text("id") ?: item.text("trackId"))?.let { restoredTrack(it) }
            val date = entryDate(item.text("timestamp") ?: item.text("addedAt"))
            restored?.copy(addedAt = date)?.let { it to date }
        }
        val mergedTracks = mergeTimedPlaylist(localTracks, remoteTracks, forceDesc = (card.id == "3" && card.owner == libUid)) { it.id }
        return MusicPage(
            parsed.copy(trackCount = mergedTracks.size),
            mergedTracks,
            description = value.text("description"),
            publicPlaylist = value.text("visibility")?.let { it == "public" },
        )
    }

    fun createPlaylist(uid: String, name: String) =
        Card.playlist(post("users/${segment(uid)}/playlists/create", "title" to name, "visibility" to "private").objectValue(), uid)
            ?: throw MusicError("Не удалось создать плейлист")

    fun renamePlaylist(card: Card, name: String) {
        post("${playlistPath(card)}/name", "value" to name)
    }

    fun editPlaylistDetails(card: Card, description: String) {
        post("${playlistPath(card)}/description", "value" to description)
    }

    fun setPlaylistPublic(card: Card, open: Boolean) {
        post("${playlistPath(card)}/visibility", "value" to if (open) "public" else "private")
    }

    fun uploadPlaylistCover(card: Card, jpeg: ByteArray): String {
        val boundary = "Tune-" + java.util.UUID.randomUUID()
        val start = "--$boundary\r\nContent-Disposition: form-data; name=\"image\"; filename=\"cover.jpg\"\r\nContent-Type: image/jpeg\r\n\r\n"
        val end = "\r\n--$boundary--\r\n"
        val value = request("${playlistPath(card)}/cover/upload", method = "POST", type = "multipart/form-data; boundary=$boundary",
            bytes = start.toByteArray() + jpeg + end.toByteArray()).objectValue()
        return value.obj("cover").text("uri") ?: playlist(card).card.cover ?: throw MusicError("Яндекс не вернул новую обложку")
    }

    fun uploadTarget(card: Card, name: String): Pair<String, String> {
        val value = post("loader/upload-url", "uid" to requireNotNull(card.owner),
            "playlist-id" to "${card.owner}:${card.id}", "path" to name)
        if (value == "TOO_MANY_FILES") throw MusicError("Превышен лимит загруженных треков")
        val target = value.objectValue().text("post-target") ?: throw MusicError("Яндекс не дал ссылку для загрузки")
        val id = value.objectValue().text("ugc-track-id") ?: throw MusicError("Яндекс не прислал номер трека")
        val address = URI(target)
        if (address.scheme != "https" || address.host == null || address.rawUserInfo != null) throw MusicError("Неверная ссылка для загрузки")
        return target to id
    }

    fun changeUploadedTrack(id: String, title: String, artist: String) {
        json("ugc/tracks/${segment(id)}/change", JSONObject().put("title", title).put("artist", artist))
    }

    fun readyUploads(ids: List<String>) = form("tracks", "track-ids" to ids.joinToString(","), "withProgress" to "true")
        .objectList().filter { it.text("state") == "playable" }.mapNotNull { it.text("id") }

    fun deletePlaylist(card: Card) {
        post("${playlistPath(card)}/delete")
        val libUid = activeUid ?: card.owner
        if (libUid != null && card.owner != null) {
            catalogLibrary?.mutate(libUid) { it.clearPlaylist("${card.owner}:${card.id}") }
        }
    }

    fun deletePlaylistRaw(uid: String, kind: String) {
        post("users/${segment(uid)}/playlists/${segment(kind)}/delete")
    }

    fun editPlaylist(card: Card, tracks: List<Track>, remove: Boolean = false) {
        if (tracks.isEmpty()) return
        val (synthetic, native) = tracks.partition { versions.isSyntheticTrackId(it.id) }
        val libUid = activeUid ?: card.owner
        if (synthetic.isNotEmpty() && libUid != null && card.owner != null) {
            val key = "${card.owner}:${card.id}"
            catalogLibrary?.mutate(libUid) { data ->
                if (remove) data.removePlaylistTracks(key, synthetic.map { it.id }.toSet())
                else data.addPlaylistTracks(key, synthetic.map { it.id })
            }
        }
        if (native.isEmpty()) return
        val path = playlistPath(card)
        val value = get(path, "rich-tracks" to "true").objectValue()
        val operations = if (remove) {
            val wanted = native.associateBy { it.id }.toMutableMap()
            val rows = value.arr("tracks").objects().mapIndexedNotNull { index, row ->
                val track = wanted.remove((row.optJSONObject("track") ?: row).text("id")) ?: return@mapIndexedNotNull null
                JSONObject().put("op", "delete").put("from", index).put("to", index + 1)
                    .put("tracks", JSONArray().put(trackReference(track)))
            }
            rows.asReversed()
        } else listOf(JSONObject().put("op", "insert").put("at", 0).put("tracks", JSONArray(native.map(::trackReference))))
        if (operations.isEmpty()) return
        changePlaylist(path, value, operations)
    }

    fun movePlaylistTrack(card: Card, expected: List<Track>, from: Int, to: Int) {
        if (from !in expected.indices || to !in expected.indices || from == to) return
        val moving = expected[from]
        val libUid = activeUid ?: card.owner
        if (versions.isSyntheticTrackId(moving.id)) {
            if (libUid != null && card.owner != null) {
                catalogLibrary?.mutate(libUid) { it.movePlaylistTrack("${card.owner}:${card.id}", moving.id, from, to) }
            }
            return
        }
        val path = playlistPath(card)
        val value = get(path, "rich-tracks" to "true").objectValue()
        val ids = value.arr("tracks").objects().map { row ->
            row.optJSONObject("track")?.text("id") ?: row.text("id")
        }
        val expectedNative = expected.map(Track::id).filterNot(versions::isSyntheticTrackId)
        if (ids != expectedNative) throw MusicError("Порядок плейлиста изменился. Повторите перестановку")
        val localPrefix = if (catalogEnabled() && libUid != null && card.owner != null) {
            catalogLibrary?.playlistTracks(libUid, card.owner, card.id)?.size ?: 0
        } else 0
        val nativeFrom = expected.take(from).count { !versions.isSyntheticTrackId(it.id) }
        val nativeTo = (to - localPrefix).coerceIn(0, (ids.size - 1).coerceAtLeast(0))
        if (nativeFrom == nativeTo) return
        val reference = JSONArray().put(trackReference(moving))
        changePlaylist(path, value, listOf(
            JSONObject().put("op", "delete").put("from", nativeFrom).put("to", nativeFrom + 1).put("tracks", reference),
            JSONObject().put("op", "insert").put("at", nativeTo).put("tracks", reference)
        ))
    }

    private fun trackReference(track: Track) = JSONObject().put("id", track.id).put("albumId", track.album?.id)

    private fun changePlaylist(path: String, value: JSONObject, operations: List<JSONObject>) {
        val revision = value.text("revision") ?: throw MusicError("Яндекс не прислал версию плейлиста")
        form("$path/change-relative", "diff" to JSONArray(operations).toString(), "revision" to revision)
    }

    private fun catalogAlbumPage(card: Card): MusicPage? {
        val value = versions.catalogAlbum(card.id) ?: return null
        val parsed = (album(value) ?: return null).let {
            if (card.isPodcast) it.copy(releaseType = "podcast") else it
        }
        val volumes = versions.albumVolumes(card.id)
        val flat = volumes.flatten()
        val resolved = if (flat.isEmpty()) emptyMap() else tracks(flat).associateBy { it.id }
        val orderedDisks = volumes.map { disk ->
            disk.mapNotNull { id ->
                (resolved[id] ?: restoredTrack(id, parsed))?.let {
                    if (it.album == null) it.copy(album = parsed) else it
                }
            }
        }.filter { it.isNotEmpty() }
        val byArtist = value.arr("artists").objects().firstNotNullOfOrNull(::artistCard)
        val more = byArtist?.let { author ->
            if (versions.isSyntheticArtistId(author.id)) {
                versions.catalogArtist(author.id)?.third?.filterNot { it == card.id }?.let(::albumCards).orEmpty()
            } else {
                optional {
                    get("artists/${segment(author.id)}/brief-info").objectValue().arr("albums").objects()
                        .mapNotNull(::album).filterNot { it.id == card.id }.take(12)
                }.orEmpty()
            }
        }.orEmpty()
        val label = if (parsed.isPodcast) value.text("shortDescription") ?: value.text("description")
            else value.arr("labels").opt(0).let { item -> (item as? JSONObject)?.text("name") ?: item.asId() }
        return MusicPage(parsed, orderedDisks.flatten(), listOf(Shelf("Ещё от исполнителя", more)), label, disks = orderedDisks.map(List<Track>::size))
    }

    fun album(card: Card): MusicPage {
        if (versions.isSyntheticAlbumId(card.id)) {
            return catalogAlbumPage(card) ?: throw MusicError("Альбом не найден", 404)
        }
        val value = try { get("albums/${segment(card.id)}/with-tracks").objectValue() }
        catch (error: MusicError) {
            if (error.status in setOf(401, 429)) throw error
            return catalogAlbumPage(card) ?: throw error
        }
        if (value.text("error") == "not-found") {
            return catalogAlbumPage(card) ?: throw MusicError("Альбом не найден", 404)
        }
        val parsed = (album(value) ?: throw MusicError("Альбом не найден")).let {
            if (card.isPodcast) it.copy(releaseType = "podcast") else it
        }
        val disks = value.arr("volumes").items().filterIsInstance<JSONArray>().map { disk ->
            disk.objects().mapNotNull { track(it) }.map {
                when {
                    parsed.isPodcast -> it.copy(album = parsed, mediaType = "podcast_episode")
                    it.album == null -> it.copy(album = parsed)
                    else -> it
                }
            }
        }
        val available = disks.flatten()
        val custom = metadata("album", parsed.id).tracks
        val want = when {
            custom.isNotEmpty() -> listOf(custom)
            metadata("album", card.id).usePlugin -> versions.albumVolumes(card.id)
            else -> emptyList()
        }
        val versionsList = value.arr("duplicates").objects().mapNotNull(::album).filterNot { it.id == card.id }
        val byArtist = value.arr("artists").objects().firstNotNullOfOrNull(::artistCard)
        val more = byArtist?.let { author -> optional {
            get("artists/${segment(author.id)}/brief-info").objectValue().arr("albums").objects()
                .mapNotNull(::album).filterNot { it.id == card.id }.take(12)
        } }.orEmpty()
        val shelves = listOf(Shelf("Другие версии", versionsList), Shelf("Ещё от исполнителя", more))
        val label = if (parsed.isPodcast) value.text("shortDescription") ?: value.text("description")
            else value.arr("labels").opt(0).let { item -> (item as? JSONObject)?.text("name") ?: item.asId() }
        if (want.isEmpty()) return MusicPage(parsed, available, shelves, label, disks = disks.map(List<Track>::size))
        val known = available.associateByTo(LinkedHashMap()) { it.id }
        val missing = want.flatten().filterNot(known::containsKey)
        if (missing.isNotEmpty()) {
            (try { tracks(missing) } catch (_: IOException) { emptyList() }).forEach { known.putIfAbsent(it.id, it) }
        }
        val mergedDisks = want.map { disk ->
            disk.mapNotNull { id ->
                (known.remove(id) ?: restoredTrack(id, parsed))?.let {
                    if (it.album == null) it.copy(album = parsed) else it
                }
            }.toMutableList()
        }.toMutableList()
        if (custom.isEmpty() && known.isNotEmpty()) {
            if (mergedDisks.isEmpty()) mergedDisks.add(known.values.toMutableList())
            else mergedDisks.last().addAll(known.values)
        }
        val nonEmptyDiskSizes = mergedDisks.map { it.size }.filter { it > 0 }
        return MusicPage(parsed, mergedDisks.flatten(), shelves, label, disks = nonEmptyDiskSizes)
    }

    private fun catalogArtistPage(id: String): MusicPage? {
        val (raw, trackIds, albumIds) = versions.catalogArtist(id) ?: return null
        val parsed = artistCard(raw) ?: return null
        val custom = metadata("artist", id)
        val popular = tracks((custom.tracks + trackIds).distinct())
        val allReleases = albumCards((custom.albums + albumIds).distinct())
        val (singles, albums) = allReleases.partition { it.releaseType == "single" }
        return MusicPage(
            parsed,
            popular,
            listOf(Shelf("Альбомы", albums), Shelf("Синглы и EP", singles)),
            raw.text("description"),
            latestRelease = allReleases.firstOrNull(),
        )
    }

    fun artist(card: Card): MusicPage {
        if (versions.isSyntheticArtistId(card.id)) {
            return catalogArtistPage(card.id) ?: throw MusicError("Исполнитель не найден", 404)
        }
        val value = try { get("artists/${segment(card.id)}/brief-info").objectValue() }
        catch (error: MusicError) {
            if (error.status in setOf(401, 429)) throw error
            return catalogArtistPage(card.id) ?: throw error
        }
        val artist = value.obj("artist")
        if (artist.text("error") == "not-found") {
            return catalogArtistPage(card.id) ?: throw MusicError("Исполнитель не найден", 404)
        }
        val parsed = artistCard(artist) ?: return catalogArtistPage(card.id) ?: throw MusicError("Исполнитель не найден")
        val listeners = value.obj("stats").optLong("lastMonthListeners").takeIf { it > 0 }
        val custom = metadata("artist", card.id)
        val insertions = versions.artistInsertions(card.id)
        val added = optional { tracks((custom.tracks + insertions.first).distinct()) }.orEmpty()
        val popular = value.arr("popularTracks").objects().mapNotNull { track(it) }.distinctBy { it.id }
        val popularIds = popular.map { it.id }.toSet()
        val extraTracks = added.filterNot { it.id in popularIds }
        val releases = value.arr("albums").objects()
        val addedAlbums = albumCards((custom.albums + insertions.second).distinct())
        val allReleases = (addedAlbums + releases.mapNotNull(::album)).distinctBy { it.id }
        val (singles, albums) = allReleases.partition { it.releaseType == "single" }
        val also = value.arr("alsoAlbums").objects().mapNotNull(::album)
        val playlists = value.arr("playlists").objects()
            .filterNot { TuneSync.isSyncPlaylist(it.text("title"), it.text("description")) }
            .mapNotNull { Card.playlist(it) }
        val similar = value.arr("similarArtists").objects().mapNotNull(::artistCard)
        val today = LocalDate.now(ZoneOffset.UTC).toString()
        val latest = value.arr("lastReleases").objects().firstNotNullOfOrNull(::album)
            ?: releases.mapNotNull { release ->
                val day = release.text("releaseDate")?.take(10)?.takeIf { it.length == 10 && it <= today }
                if (day == null) null else album(release)?.let { day to it }
            }.maxByOrNull { it.first }?.second
        val path = "artists/${segment(card.id)}/blocks"
        val clips = optional { get("$path/artist-clips", "page" to "0", "pageSize" to "30").objectValue() }
            ?.arr("items")?.objects()?.mapNotNull(::clip).orEmpty().ifEmpty {
                value.arr("clips").objects().mapNotNull(::clip).ifEmpty {
                    value.arr("videos").objects().mapNotNull { video ->
                        val title = video.text("title") ?: return@mapNotNull null
                        val url = video.text("embedUrl")?.takeIf { address ->
                            runCatching { URI(address) }.getOrNull()?.let { it.scheme == "https" && it.host != null } == true
                        } ?: return@mapNotNull null
                        Card(Kind.CLIP, url, title, cover = video.text("cover"), link = url,
                            clipPlayerId = legacyClipPlayerId(url, video.text("providerVideoId")))
                    }
                }
            }.distinctBy { it.id }
        val concerts = optional { get("$path/artist-concerts").objectValue() }
            ?.arr("concerts")?.objects()?.mapNotNull(::concert).orEmpty().distinctBy { it.id }
        val subtitle = listeners?.let { "${java.text.NumberFormat.getIntegerInstance(java.util.Locale.forLanguageTag("ru")).format(it)} слушателей за месяц" }.orEmpty()
        return MusicPage(parsed.copy(subtitle = subtitle), popular, listOf(Shelf("Альбомы", albums), Shelf("Синглы и EP", singles),
            Shelf("Сборники и участие", also), Shelf("Плейлисты", playlists), Shelf("Клипы", clips), Shelf("Концерты", concerts),
            Shelf("Похожие исполнители", similar)), artist.obj("description").text("text"), latestRelease = latest, extraTracks = extraTracks)
    }

    fun similarArtistIds(id: String): List<String> {
        if (versions.isSyntheticArtistId(id)) return emptyList()
        return get("artists/${segment(id)}/similar").objectValue()
            .arr("similarArtists").objects().mapNotNull { it.text("id") }
    }

    private fun albumCards(ids: List<String>): List<Card> = ids.chunked(100).flatMap { part ->
        val yandexPart = part.filterNot(versions::isSyntheticAlbumId)
        val remote = if (yandexPart.isEmpty()) emptyMap() else {
            optional { form("albums", "album-ids" to yandexPart.joinToString(",")).objectList().mapNotNull(::album) }.orEmpty()
                .associateBy { it.id }
        }
        part.mapNotNull { id -> remote[id] ?: versions.catalogAlbum(id)?.let(::album) }
    }

    fun artistTracks(id: String): List<Track> {
        val inserted = metadata("artist", id).tracks + versions.artistInsertions(id).first
        if (versions.isSyntheticArtistId(id)) {
            val catalogIds = versions.catalogArtist(id)?.second.orEmpty()
            return tracks((inserted + catalogIds).distinct())
        }
        val standard = get("artists/${segment(id)}/tracks", "page-size" to "200").objectValue()
            .arr("tracks").objects().mapNotNull { track(it) }
        return (standard + optional { tracks(inserted.distinct()) }.orEmpty()).distinctBy { it.id }
    }

    fun trackFilters(ids: List<String>): List<TrackFilter> {
        val catalogIds = ids.asSequence()
            .filter { it.isNotBlank() && it.all(Char::isDigit) && !versions.isSyntheticTrackId(it) }
            .distinct().toList()
        if (catalogIds.size < 4) return emptyList()
        val value = json("filters/tracks", JSONObject().put("trackIds", JSONArray(catalogIds))).objectValue()
        val parsed = value.arr("filters").objects().mapNotNull { filter ->
            val id = filter.text("id") ?: return@mapNotNull null
            val name = filter.text("name") ?: return@mapNotNull null
            val trackIds = filter.arr("tracks").items().mapNotNull { it.asId() }.toSet()
            TrackFilter(id, name, trackIds)
        }
        return selectTrackFilters(parsed, catalogIds.toSet())
    }

    fun artistFamiliar(id: String, wave: Boolean): List<Track> {
        if (versions.isSyntheticArtistId(id)) return emptyList()
        val block = if (wave) "wave" else "collection"
        val response = get("artists/${segment(id)}/familiar-you",
            "waveTracksLimit" to if (wave) "50" else "0", "collectionTracksLimit" to if (wave) "0" else "50",
            "collectionAlbumsLimit" to "0").objectValue()
        return response.obj(block).arr("tracks").items().mapNotNull { entry ->
            val row = entry as? JSONObject ?: return@mapNotNull entry.asId()?.let { restoredTrack(it) }
            val source = row.optJSONObject("track") ?: row
            val trackId = source.text("id") ?: row.text("trackId")
            track(source) ?: trackId?.let { restoredTrack(it) }
        }
    }

    fun suggest(text: String) = get("search/suggest", "part" to text).objectValue()
        .arr("suggestions").items().mapNotNull(Any?::asId)

    fun search(text: String, type: String, page: Int): SearchResults {
        val isFirst = page == 0 && type != "podcast"
        val includeTracks = isFirst && (type.contains(',') || type == "track")
        val includeAlbums = isFirst && (type.contains(',') || type == "album")
        val includeArtists = isFirst && (type.contains(',') || type == "artist")
        val includePlaylists = isFirst && (type.contains(',') || type == "playlist")
        if (includeTracks || includeAlbums) versions.ensureRestored(network = true, ::rawTracks)
        val localTracks = if (includeTracks) {
            versions.search(text).filter { metadata("track", it.id).usePlugin }.map(::overrideTrack)
        } else emptyList()
        val localAlbums = if (includeAlbums) {
            versions.searchAlbums(text).filter { it.text("id")?.let { id -> metadata("album", id).usePlugin } ?: true }.mapNotNull(::album)
        } else emptyList()
        val localArtists = if (includeArtists) {
            versions.searchArtists(text).mapNotNull(::artistCard)
        } else emptyList()
        val localPlaylists = if (includePlaylists) {
            versions.searchPlaylists(text).mapNotNull { (kind, fields) ->
                val title = fields.text("title") ?: return@mapNotNull null
                Card(
                    Kind.PLAYLIST,
                    kind,
                    title,
                    "Каталог Tune",
                    fields.text("coverUri"),
                    AlternateVersions.CATALOG_OWNER,
                    trackCount = fields.arr("tracks").length(),
                )
            }
        } else emptyList()
        val filter = if (type == "podcast") arrayOf("filter" to "podcast") else emptyArray()
        val types = if (type == "podcast") "album,artist,playlist,track,wave,podcast,podcast_episode" else type
        val value = try {
            get("search/instant/mixed", "text" to text, "type" to types, "page" to page.toString(),
                "pageSize" to "36", *filter).objectValue()
        } catch (error: MusicError) {
            if (error.status != 401 && (localTracks.isNotEmpty() || localAlbums.isNotEmpty() || localArtists.isNotEmpty() || localPlaylists.isNotEmpty())) {
                return mergeSearchResults(text, emptyList(), emptyList(), null, false, localTracks, localAlbums, localArtists, localPlaylists)
            }
            throw error
        }
        val tracks = mutableListOf<Track>()
        val cards = mutableListOf<Card>()
        var best: SearchBest? = null
        for (item in value.arr("results").objects()) {
            val kind = item.text("type")
            if (kind == "track" || kind == "podcast_episode") {
                val parsed = track(item.obj(kind)) ?: continue
                val track = if (kind == "podcast_episode" || type == "podcast") parsed.copy(mediaType = "podcast_episode") else parsed
                tracks += track
                if (best == null) best = SearchBest.Song(track)
            } else {
                val card = when (kind) {
                    "album" -> album(item.obj("album"))
                    "artist" -> artistCard(item.obj("artist"))
                    "playlist" -> item.obj("playlist")
                        .takeUnless { TuneSync.isSyncPlaylist(it.text("title"), it.text("description")) }
                        ?.let { Card.playlist(it) }
                    "clip" -> clip(item.obj("clip"))
                    "podcast" -> album(item.obj("podcast"))?.copy(releaseType = "podcast")
                    else -> null
                } ?: continue
                cards += card
                if (best == null) best = SearchBest.Entry(card)
            }
        }
        val hasMore = if (value.has("lastPage")) !value.optBoolean("lastPage") && value.arr("results").length() > 0 else null
        return mergeSearchResults(text, tracks, cards, best, hasMore, localTracks, localAlbums, localArtists, localPlaylists)
    }

    fun history(limit: Int = 200, includeSources: Boolean = limit > 20): List<Track> {
        val catalogEntries = if (catalogEnabled()) activeUid?.let { catalogLibrary?.historyEntries(it) }.orEmpty() else emptyList()
        val remoteEntries = mutableListOf<Triple<String, String?, String?>>()
        try {
            val value = get("music-history", "fullModelsCount" to if (includeSources) limit.toString() else "0").objectValue()
            for (tab in value.arr("historyTabs").objects()) {
                val date = tab.text("date")?.take(10)
                for (item in tab.arr("items").objects()) {
                    val context = item.obj("context")
                    val full = context.obj("data").obj("fullModel")
                    val source = when (val kind = context.text("type")) {
                        "wave", "playlist", "album" -> full.obj(kind).text("title") ?: when (kind) {
                            "wave" -> "Моя волна"; "playlist" -> "Плейлист"; else -> "Альбом"
                        }
                        "artist" -> full.obj(kind).text("name") ?: "Исполнитель"
                        "search" -> "Поиск"
                        else -> null
                    }
                    item.arr("tracks").objects().forEach { row ->
                        val id = row.obj("data").obj("itemId").text("trackId") ?: return@forEach
                        remoteEntries += Triple(id, date, source)
                    }
                }
            }
        } catch (error: MusicError) {
            if (error.status == 401 || catalogEntries.isEmpty()) throw error
        }
        val entries = mergeCatalogHistory(
            remote = remoteEntries,
            catalog = catalogEntries,
            limit = limit,
            catalogSource = if (includeSources) "Каталог Tune" else null,
        )
        return tracks(entries.keys.toList()).map { track ->
            val (date, source) = entries[track.id] ?: (null to null)
            track.copy(historyDate = date, historySource = source)
        }
    }

    fun homeSources(kids: Boolean = false): List<List<Pair<String, String>>> {
        val value = get(if (kids) "landing/skeleton/kids" else "landing/skeleton/main").objectValue()
        val tabs = value.arr("blocks").objects().firstNotNullOfOrNull { it.obj("data").optJSONArray("tabs") }
        val blocks = if (tabs == null) listOf(value.arr("blocks").objects()) else tabs.objects().map { it.arr("blocks").objects() }
        return blocks.map { entries ->
            entries.mapNotNull { block ->
                if (block.text("type") in setOf("LIKES_AND_HISTORY", "SPECIAL", "CONCERTS_PERSONAL", "DONATIONS")) return@mapNotNull null
                val data = block.obj("data")
                val uri = data.obj("source").text("uri")?.takeUnless { it.startsWith("//") || "://" in it } ?: return@mapNotNull null
                (data.text("title") ?: if (block.text("type") == "WAVES") "Волны" else "Подборки") to uri
            }.distinct().take(15)
        }
    }

    fun stations(): List<Shelf> {
        val groups = get("rotor/stations/list", "language" to "ru").objectList().mapNotNull { item ->
            val station = item.obj("station")
            val type = station.obj("id").text("type") ?: return@mapNotNull null
            val tag = station.obj("id").text("tag") ?: return@mapNotNull null
            val name = station.text("name") ?: return@mapNotNull null
            val seed = "$type:$tag"
            val icon = station.obj("icon")
            type to Card(Kind.WAVE, seed, name, cover = icon.text("imageUrl"), seeds = listOf(seed),
                color = icon.text("backgroundColor"))
        }.groupBy({ it.first }, { it.second })
        return listOf("genre" to "Жанры", "mood" to "Настроение", "activity" to "Занятие", "epoch" to "Эпоха", "local" to "Местное")
            .mapNotNull { (id, title) -> groups[id]?.let { Shelf(title, it) } }
    }

    fun homeShelf(title: String, path: String): Shelf {
        val value = get(path).objectValue()
        val items = value.arr("items").objects() +
            listOf("waves", "inStyleTabs").flatMap { group ->
                value.arr(group).objects().flatMap { it.arr("items").objects() }
            }
        val cards = items.mapNotNull { item ->
            val data = item.optJSONObject("data") ?: item
            val pl = data.obj("playlist").takeUnless { TuneSync.isSyncPlaylist(it.text("title"), it.text("description")) }
            (pl?.let { Card.playlist(it) }) ?: artistCard(data.obj("artist")) ?:
                album(data.obj("album")) ?: Card.wave(data) ?: clip(data.obj("clip"))
        }
        val releases = value.arr("newReleases").objects().mapNotNull { item ->
            album(item.obj("album"))?.let { card ->
                card.copy(subtitle = item.arr("artists").objects().mapNotNull { it.text("name") }.joinToString(", ").ifBlank { card.subtitle })
            }
        }
        return Shelf(title, (releases + cards).distinctBy { it.key })
    }

    fun clips(): List<Card> = get("clips/will/like", "page" to "0", "pageSize" to "50").objectValue()
        .arr("clips").objects().mapNotNull(::clip)

    fun clipStreams(card: Card): List<ClipSource> {
        val player = validatedClipPlayerId(card.clipPlayerId)
            ?: card.id.takeUnless { "://" in it }?.let { clipPlayerId(get("clips", "clipIds" to it)) }
            ?: throw MusicError("Яндекс не прислал адрес клипа")
        val connection = secureConnection("https://frontend.vh.yandex.ru/player/${segment(player)}.json?service=ya-music&from=ya-music")
        try {
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Authorization", "OAuth $token")
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/130.0.0.0 Mobile Safari/537.36")
            connection.setRequestProperty("Referer", "https://music.yandex.ru/")
            connection.setRequestProperty("Origin", "https://music.yandex.ru")
            val status = connection.responseCode
            val input = if (status in 200..299) connection.inputStream else connection.errorStream
            val video = input?.bufferedReader(Charsets.UTF_8)?.use { reader ->
                optional { JSONObject(reader.readText()) }
            } ?: JSONObject()
            return clipSources(video, status)
        } finally {
            connection.disconnect()
        }
    }

    private fun clip(value: JSONObject): Card? {
        val data = value.obj("data").optJSONObject("clip") ?: value
        if (!data.obj("contentRestrictions").optBoolean("available", true)) return null
        val id = data.text("clipId") ?: data.text("id") ?: return null
        val title = data.text("title") ?: return null
        val subtitle = data.arr("artists").objects().mapNotNull { it.text("name") }.joinToString(", ")
        return Card(Kind.CLIP, id, title, subtitle, data.text("thumbnail") ?: data.obj("cover").text("uri"),
            clipPlayerId = validatedClipPlayerId(data.text("playerId")))
    }

    fun concerts(city: String?): List<Card> {
        val params = city?.let { arrayOf("locations" to it) } ?: emptyArray()
        return get("concerts/feed", *params).objectValue().arr("items").objects()
            .filter { it.text("type") == "concert_item" }.mapNotNull { concert(it.obj("data")) }.distinctBy { it.id }
    }

    private fun concert(value: JSONObject): Card? {
        val item = value.optJSONObject("concert") ?: value
        val id = item.text("concertId") ?: item.text("id") ?: return null
        val title = item.text("concertTitle") ?: return null
        val subtitle = listOfNotNull(item.text("city"), item.text("place")).joinToString(" · ")
        val tickets = item.text("dataSessionId")?.takeIf { it.matches(CONCERT_SESSION) }?.let {
            "https://widget.afisha.yandex.ru/w/sessions/${segment(it)}?clientKey=0d8ab1a6-1667-42cb-a98f-2fe7eff4b137"
        }
        return Card(Kind.CONCERT, id, title, subtitle, item.obj("cover").text("uri"), link = tickets, eventDate = item.text("datetime"))
    }

    fun cities() = get("concerts/locations").objectValue().arr("locations").objects().mapNotNull { city ->
        val id = city.text("id") ?: return@mapNotNull null
        val name = city.text("name") ?: return@mapNotNull null
        id to name
    }.distinctBy { it.first }

    private fun sequence(value: JSONObject) = value.arr("sequence").objects().mapNotNull {
        track(it.obj("track"), value.text("batchId"))
    }.filterNot { it.unavailable }

    fun startWave(seeds: List<String>): Wave {
        val value = json("rotor/session/new", JSONObject().put("seeds", JSONArray(seeds))
            .put("includeTracksInResponse", true).put("allowExplicit", true)).objectValue()
        val session = value.text("radioSessionId") ?: throw MusicError("Не удалось запустить волну")
        return Wave(session, sequence(value))
    }

    fun waveRooms() = get("wave/rooms").objectList().mapNotNull(WaveRoom::parse)

    fun waveRoom(id: String) = WaveRoom.parse(get("wave/rooms/${segment(id)}").objectValue())
        ?: throw MusicError("Приглашение больше не действует")

    fun createWaveRoom(): WaveRoom {
        val value = json("wave/rooms", JSONObject()).objectValue()
        return WaveRoom.parse(value) ?: throw MusicError("Не удалось создать приглашение")
    }

    fun enterWaveRoom(id: String, followDuplicate: Boolean = true): WaveRoom {
        val value = try { request("wave/rooms/${segment(id)}/enter", body = "{}", type = "application/json", method = "PUT").objectValue() }
        catch (error: MusicError) {
            val result = error.details ?: throw error
            val name = result.obj("error").text("name") ?: result.text("error")
            val other = result.obj("error").obj("details").text("correctRoomId") ?: result.obj("details").text("correctRoomId")
            if (followDuplicate && name?.contains("ROOM_DUPLICATION") == true && other != null && other != id) {
                return enterWaveRoom(other, followDuplicate = false)
            }
            throw error
        }
        return WaveRoom.parse(value) ?: waveRoom(id)
    }

    fun exitWaveRoom(id: String) {
        request("wave/rooms/${segment(id)}/exit", body = "{}", type = "application/json", method = "PUT")
    }

    fun waveMore(session: String, seen: List<String>) = sequence(json("rotor/session/${segment(session)}/tracks",
        JSONObject().put("queue", JSONArray(seen)).put("allowExplicit", true)).objectValue())

    fun wheel(seeds: List<String>, feedback: List<JSONObject>): Wheel {
        val context = JSONObject().put("type", "WAVE").put("data", JSONObject().put("seeds", JSONArray(seeds)))
        val result = json("wheel/new", JSONObject().put("context", context).put("feedbacks", JSONArray(feedback))).objectValue()
        val id = result.text("wheelId") ?: throw MusicError("Яндекс не прислал колесо волны")
        val items = result.arr("items").objects().mapNotNull { row ->
            val type = row.text("type") ?: return@mapNotNull null
            val itemId = row.text("id") ?: return@mapNotNull null
            val data = row.obj("data")
            val card = when (type) {
                "WAVE" -> Card.wave(data)
                "ALBUM" -> album(data.obj("album"))
                "SETTING" -> null
                else -> return@mapNotNull null
            }
            val label = card?.title ?: data.text("title") ?: return@mapNotNull null
            val color = data.obj("agent").obj("cover").text("color") ?: data.obj("album").obj("cover").text("color")
            WheelItem(type, itemId, label, card, color)
        }
        return Wheel(id, items)
    }

    fun wheelFeedbacks(feedback: List<JSONObject>) {
        json("wheel/feedbacks", JSONObject().put("feedbacks", JSONArray(feedback)))
    }

    fun wordsCards(ids: List<String>, viewed: List<String>, stories: Map<String, String>): Map<String, WordCard> {
        val viewedBig = stories.map { (card, story) -> JSONObject().put("cardId", card).put("bigCardIds", JSONArray(listOf(story))) }
        val body = JSONObject().put("trackIds", JSONArray(ids)).put("viewedCards", JSONArray(viewed))
            .put("viewedBigCards", JSONArray(viewedBig))
        val result = json("words/cards", body)
        return parseWords(result as? JSONArray ?: JSONArray())
    }

    fun wordsStory(track: String, card: String, big: String): WaveStory {
        val context = JSONObject().put("bigCardId", big).put("trackId", track).put("cardId", card)
        val value = json("words/big-cards", JSONObject().put("bigCardIds", JSONArray(listOf(big)))
            .put("bigCardContexts", JSONArray().put(context))).objectList().firstOrNull()
        val text = value?.text("text")?.trim()?.takeIf(String::isNotEmpty) ?: throw MusicError("Яндекс не рассказал подробнее")
        val action = value.arr("actions").objects().firstNotNullOfOrNull { item ->
            val title = item.text("title") ?: return@firstNotNullOfOrNull null
            val url = item.text("url")?.takeIf { it.startsWith("https://") } ?: return@firstNotNullOfOrNull null
            title to url
        }
        return WaveStory(text, action)
    }

    fun wordsFeedback(card: String, state: String) {
        val entry = JSONObject().put("id", card).put("feedbackState", state)
        val body = JSONObject().put("feedback", JSONArray().put(entry))
        request("words/cards/feedback", body = body.toString(), type = "application/json", method = "PUT")
    }

    fun waveFeedback(session: String, kind: String, track: Track? = null, played: Double? = null) {
        if (track != null && versions.isSyntheticTrackId(track.id)) return
        val event = JSONObject().put("type", kind).put("timestamp", now())
        if (track == null) event.put("from", FROM)
        else event.put("trackId", track.fullId).put("totalPlayedSeconds", played)
        json("rotor/session/${segment(session)}/feedback/",
            JSONObject().put("from", FROM).put("event", event).put("batchId", track?.batch))
    }

    fun report(play: JSONObject) {
        if (versions.isSyntheticTrackId(play.text("trackId"))) return
        json("plays", JSONObject().put("plays", JSONArray().put(play)), "clientNow" to now())
    }

    fun file(track: String, quality: String, transport: String): Stream {
        if (versions.isSyntheticTrackId(track)) throw MusicError("Трек из каталога Tune недоступен")
        val stamp = Instant.now().epochSecond.toString()
        val codecs = listOf("flac-mp4", "aac-mp4", "he-aac-mp4", "mp3")
        val sign = sign("$stamp$track$quality${codecs.joinToString("")}$transport").removeSuffix("=")
        val info = get("get-file-info", "ts" to stamp, "trackId" to track, "quality" to quality,
            "codecs" to codecs.joinToString(","), "transports" to transport, "sign" to sign).objectValue().obj("downloadInfo")
        val urls = (listOfNotNull(info.text("url")) + info.arr("urls").items().mapNotNull(Any?::asId)).distinct()
        if (urls.isEmpty()) throw MusicError("Яндекс не дал файл трека", 200)
        return Stream(urls, info.text("key"), info.text("codec") ?: "mp3")
    }

    fun lyrics(track: String): LyricsText {
        if (versions.isSyntheticTrackId(track)) return LyricsText()
        for (format in listOf("LRC", "TEXT")) {
            val stamp = Instant.now().epochSecond.toString()
            val value = try {
                get("tracks/${segment(track)}/lyrics", "format" to format, "timeStamp" to stamp,
                    "sign" to sign("$track$stamp")).objectValue()
            } catch (error: MusicError) {
                if (error.status in setOf(400, 403, 404)) continue
                throw error
            }
            val link = value.text("downloadUrl") ?: continue
            val connection = secureConnection(link)
            try {
                if (connection.responseCode != 200) continue
                val lines = connection.inputStream.bufferedReader(Charsets.UTF_8).use { parseLyrics(it.readText()) }
                if (lines.any { it.text.isNotBlank() }) {
                    val authors = value.arr("writers").items().filterIsInstance<String>().map(String::trim).filter(String::isNotEmpty)
                    return LyricsText(lines, authors, LyricsSource.YANDEX)
                }
            } finally {
                connection.disconnect()
            }
        }
        return LyricsText()
    }

    companion object {
        private val CONCERT_SESSION = Regex("[a-zA-Z0-9_-]+")
        private const val BASE = "https://api.music.yandex.net"
        private const val CLIENT = "YandexMusicDesktopAppWindows/5.120.0"
        const val FROM = "tune-android"
        fun now(): String = Instant.now().toString()
        private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")
        private fun segment(value: String) = encode(value).replace("+", "%20")
        private fun sign(value: String): String {
            val hmac = Mac.getInstance("HmacSHA256")
            hmac.init(SecretKeySpec("kzqU4XhfCaY6B6JTHODeq5".toByteArray(), "HmacSHA256"))
            return Base64.getEncoder().encodeToString(hmac.doFinal(value.toByteArray()))
        }

        internal fun formatListenTime(seconds: Double): String? {
            if (!seconds.isFinite() || seconds <= 0.0) return null
            val minutes = seconds / 60.0
            if (minutes <= 45.0) {
                val rounded = Math.round(minutes)
                return if (rounded > 0L) "$rounded мин" else null
            }
            val halfHours = Math.round(minutes / 60.0 * 2.0)
            return if (halfHours % 2L == 0L) "${halfHours / 2L} ч" else "${halfHours / 2L},5 ч"
        }

        internal fun parseTopArtists(value: JSONObject, mapArtist: (JSONObject) -> Card? = Card::artist): List<Card> =
            value.arr("artists").objects().mapNotNull { item ->
                val card = mapArtist(item.obj("artist")) ?: return@mapNotNull null
                val place = item.obj("top").optLong("position").takeIf { it > 0L }?.let { "$it место" }
                val time = formatListenTime(item.optDouble("listenTimeSeconds", 0.0))
                val subtitle = listOfNotNull(place, time).joinToString(" · ")
                card.copy(subtitle = subtitle)
            }
    }
}

internal fun mergeCatalogHistory(
    remote: List<Triple<String, String?, String?>>,
    catalog: List<Pair<String, Long>>,
    limit: Int,
    catalogSource: String?,
    zone: java.time.ZoneId = java.time.ZoneId.systemDefault(),
): LinkedHashMap<String, Pair<String?, String?>> {
    val entries = linkedMapOf<String, Pair<String?, String?>>()
    if (limit <= 0) return entries
    if (catalog.isEmpty()) {
        for ((id, date, source) in remote) {
            if (entries.size >= limit) break
            entries.putIfAbsent(id, date to source)
        }
        return entries
    }
    val catalogByDate = linkedMapOf<String, MutableList<String>>()
    for ((id, tsSec) in catalog) {
        val date = java.time.Instant.ofEpochSecond(tsSec).atZone(zone).toLocalDate().toString()
        catalogByDate.getOrPut(date, ::mutableListOf).add(id)
    }
    val remoteByDate = linkedMapOf<String, MutableList<Pair<String, String?>>>()
    val undated = mutableListOf<Pair<String, String?>>()
    for ((id, date, source) in remote) {
        if (date.isNullOrBlank()) undated += id to source
        else remoteByDate.getOrPut(date, ::mutableListOf).add(id to source)
    }
    val dates = (catalogByDate.keys + remoteByDate.keys).sortedDescending()
    for (date in dates) {
        catalogByDate[date]?.forEach { id ->
            if (entries.size >= limit) return entries
            entries.putIfAbsent(id, date to catalogSource)
        }
        remoteByDate[date]?.forEach { (id, source) ->
            if (entries.size >= limit) return entries
            entries.putIfAbsent(id, date to source)
        }
    }
    for ((id, source) in undated) {
        if (entries.size >= limit) break
        entries.putIfAbsent(id, null to source)
    }
    return entries
}

internal fun mergeSearchResults(
    query: String,
    remoteTracks: List<Track>,
    remoteCards: List<Card>,
    remoteBest: SearchBest?,
    hasMore: Boolean?,
    localTracks: List<Track>,
    localAlbums: List<Card>,
    localArtists: List<Card> = emptyList(),
    localPlaylists: List<Card> = emptyList(),
): SearchResults {
    val remoteTrackIds = remoteTracks.mapTo(HashSet()) { it.id }
    val remoteCardKeys = remoteCards.mapTo(HashSet()) { it.key }
    val addedTracks = localTracks.filterNot { it.id in remoteTrackIds }
    val addedCards = (localArtists + localAlbums + localPlaylists).filterNot { it.key in remoteCardKeys }
    val tracks = (addedTracks + remoteTracks).distinctBy { it.id }
    val cards = (addedCards + remoteCards).distinctBy { it.key }
    val localBest = addedTracks.firstOrNull()?.let(SearchBest::Song) ?: addedCards.firstOrNull()?.let(SearchBest::Entry)
    val best = when {
        localBest == null -> remoteBest
        remoteBest == null -> localBest
        else -> {
            val terms = AlternateVersions.searchTokens(query)
            if (terms.isEmpty()) remoteBest
            else {
                val allowPrefix = terms.sumOf(String::length) >= 3
                val remoteMatchesAll = terms.all { term ->
                    AlternateVersions.matchesTerm(term, bestAllTokens(remoteBest), allowPrefix)
                }
                when {
                    !remoteMatchesAll -> localBest
                    bestExactTitle(localBest, terms) && !bestExactTitle(remoteBest, terms) -> localBest
                    else -> remoteBest
                }
            }
        }
    }
    return SearchResults(tracks, cards, best, hasMore)
}

private fun bestAllTokens(item: SearchBest): List<String> = when (item) {
    is SearchBest.Song -> AlternateVersions.searchTokens(item.track.title) +
        AlternateVersions.searchTokens(item.track.artistText) +
        AlternateVersions.searchTokens(item.track.album?.title.orEmpty())
    is SearchBest.Entry -> AlternateVersions.searchTokens(item.card.title) +
        AlternateVersions.searchTokens(item.card.subtitle)
}

private fun bestExactTitle(item: SearchBest, terms: List<String>): Boolean = when (item) {
    is SearchBest.Song -> AlternateVersions.searchTokens(item.track.title) == terms
    is SearchBest.Entry -> AlternateVersions.searchTokens(item.card.title) == terms
}
