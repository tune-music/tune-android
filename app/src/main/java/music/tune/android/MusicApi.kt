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
        val custom = value.text("id")?.let { metadata("track", it) } ?: MetadataOverride()
        val parsed = if (custom.usePlugin) Track.parse(versions.patch(value, Kind.ALBUM, track = true), versions.hasAudio(value.text("id")), batch)
            else Track.parse(value, alternate = false, batch = batch)
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

    fun tracks(ids: List<String>): List<Track> = ids.chunked(100).flatMap { chunk ->
        val found = form("tracks", "track-ids" to chunk.joinToString(",")).objectList()
            .mapNotNull { track(it) }.associateBy { it.id }
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
        if (!dislike) optional { fullLikedEntries() }?.let { return it }
        val list = if (dislike) "dislikes" else "likes"
        return get("users/${segment(uid)}/$list/tracks").objectValue()
            .obj("library").arr("tracks").objects().mapNotNull { it.text("id")?.let { id -> id to entryDate(it.text("timestamp")) } }
    }

    fun dislikedArtists(uid: String): List<Card> = get("users/${segment(uid)}/dislikes/artists").objectList().mapNotNull {
        Card.artist(it.optJSONObject("artist") ?: it)
    }

    fun setArtistHidden(uid: String, id: String, hide: Boolean) {
        val path = "users/${segment(uid)}/dislikes/artists"
        if (hide) post("$path/add", "artist-id" to id) else post("$path/${segment(id)}/remove")
    }

    fun setTrackRating(uid: String, id: String, disliked: Boolean, add: Boolean) {
        val kind = if (disliked) "dislikes" else "likes"
        val path = "users/${segment(uid)}/$kind/tracks"
        if (add) post("$path/add", "track-id" to id) else post("$path/${segment(id)}/remove")
    }

    fun playlists(uid: String, liked: Boolean = false): List<Card> {
        val path = if (liked) "likes/playlists" else "playlists/list"
        return get("users/${segment(uid)}/$path").objectList().mapNotNull {
            Card.playlist(it.optJSONObject("playlist") ?: it, if (liked) null else uid)
        }
    }

    fun likedCards(uid: String, kind: Kind): List<Card> = when (kind) {
        Kind.PLAYLIST -> playlists(uid, liked = true)
        Kind.ALBUM -> get("users/${segment(uid)}/likes/albums", "rich" to "true").objectList()
            .mapNotNull { album(it.optJSONObject("album") ?: it) }
        Kind.ARTIST -> get("users/${segment(uid)}/likes/artists", "with-timestamps" to "false").objectList()
            .mapNotNull { artistCard(it.optJSONObject("artist") ?: it) }
        else -> emptyList()
    }

    fun setCardLiked(uid: String, card: Card, add: Boolean) {
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
        val value = get(playlistPath(card), "rich-tracks" to "true").objectValue()
        val parsed = Card.playlist(value, card.owner) ?: card
        val tracks = value.arr("tracks").objects().mapNotNull { item ->
            val source = item.optJSONObject("track") ?: item
            val restored = track(source) ?: (source.text("id") ?: item.text("id") ?: item.text("trackId"))?.let { restoredTrack(it) }
            restored?.copy(addedAt = entryDate(item.text("timestamp") ?: item.text("addedAt")))
        }
        return MusicPage(parsed, tracks, description = value.text("description"),
            publicPlaylist = value.text("visibility")?.let { it == "public" })
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
    }

    fun editPlaylist(card: Card, tracks: List<Track>, remove: Boolean = false) {
        if (tracks.isEmpty()) return
        val path = playlistPath(card)
        val value = get(path, "rich-tracks" to "true").objectValue()
        val operations = if (remove) {
            val wanted = tracks.associateBy { it.id }.toMutableMap()
            val rows = value.arr("tracks").objects().mapIndexedNotNull { index, row ->
                val track = wanted.remove((row.optJSONObject("track") ?: row).text("id")) ?: return@mapIndexedNotNull null
                JSONObject().put("op", "delete").put("from", index).put("to", index + 1)
                    .put("tracks", JSONArray().put(trackReference(track)))
            }
            rows.asReversed()
        } else listOf(JSONObject().put("op", "insert").put("at", 0).put("tracks", JSONArray(tracks.map(::trackReference))))
        if (operations.isEmpty()) return
        changePlaylist(path, value, operations)
    }

    fun movePlaylistTrack(card: Card, expected: List<Track>, from: Int, to: Int) {
        val path = playlistPath(card)
        val value = get(path, "rich-tracks" to "true").objectValue()
        val ids = value.arr("tracks").objects().map { row ->
            row.optJSONObject("track")?.text("id") ?: row.text("id")
        }
        if (ids != expected.map(Track::id)) throw MusicError("Порядок плейлиста изменился. Повторите перестановку")
        val reference = JSONArray().put(trackReference(expected[from]))
        changePlaylist(path, value, listOf(
            JSONObject().put("op", "delete").put("from", from).put("to", from + 1).put("tracks", reference),
            JSONObject().put("op", "insert").put("at", to).put("tracks", reference)
        ))
    }

    private fun trackReference(track: Track) = JSONObject().put("id", track.id).put("albumId", track.album?.id)

    private fun changePlaylist(path: String, value: JSONObject, operations: List<JSONObject>) {
        val revision = value.text("revision") ?: throw MusicError("Яндекс не прислал версию плейлиста")
        form("$path/change-relative", "diff" to JSONArray(operations).toString(), "revision" to revision)
    }

    fun album(card: Card): MusicPage {
        val value = try { get("albums/${segment(card.id)}/with-tracks").objectValue() }
        catch (error: MusicError) {
            if (error.status in setOf(401, 429)) throw error
            versions.catalogAlbum(card.id) ?: throw error
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
        val order = custom.ifEmpty { if (metadata("album", card.id).usePlugin) versions.albumTracks(card.id) else emptyList() }
        val versions = value.arr("duplicates").objects().mapNotNull(::album).filterNot { it.id == card.id }
        val byArtist = value.arr("artists").objects().firstNotNullOfOrNull(::artistCard)
        val more = byArtist?.let { author -> optional {
            get("artists/${segment(author.id)}/brief-info").objectValue().arr("albums").objects()
                .mapNotNull(::album).filterNot { it.id == card.id }.take(12)
        } }.orEmpty()
        val shelves = listOf(Shelf("Другие версии", versions), Shelf("Ещё от исполнителя", more))
        val label = if (parsed.isPodcast) value.text("shortDescription") ?: value.text("description")
            else value.arr("labels").opt(0).let { item -> (item as? JSONObject)?.text("name") ?: item.asId() }
        if (order.isEmpty()) return MusicPage(parsed, available, shelves, label, disks = disks.map(List<Track>::size))
        val known = available.associateBy { it.id }
        val missing = order.filterNot(known::containsKey)
        val extra = try { tracks(missing) } catch (_: IOException) { emptyList() }
        val all = known + extra.associateBy { it.id }
        val reordered = order.mapNotNull { all[it] ?: restoredTrack(it, parsed) }
        return MusicPage(parsed, reordered + if (custom.isEmpty()) available.filterNot { it.id in order } else emptyList(), shelves, label)
    }

    fun artist(card: Card): MusicPage {
        val value = get("artists/${segment(card.id)}/brief-info").objectValue()
        val artist = value.obj("artist")
        val parsed = artistCard(artist) ?: throw MusicError("Исполнитель не найден")
        val listeners = value.obj("stats").optLong("lastMonthListeners").takeIf { it > 0 }
        val custom = metadata("artist", card.id)
        val insertions = versions.artistInsertions(card.id)
        val added = optional { tracks((custom.tracks + insertions.first).distinct()) }.orEmpty()
        val popular = (added + value.arr("popularTracks").objects().mapNotNull { track(it) }).distinctBy { it.id }
        val releases = value.arr("albums").objects()
        val addedAlbums = albumCards((custom.albums + insertions.second).distinct())
        val allReleases = (addedAlbums + releases.mapNotNull(::album)).distinctBy { it.id }
        val (singles, albums) = allReleases.partition { it.releaseType == "single" }
        val also = value.arr("alsoAlbums").objects().mapNotNull(::album)
        val playlists = value.arr("playlists").objects().mapNotNull { Card.playlist(it) }
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
            Shelf("Похожие исполнители", similar)), artist.obj("description").text("text"), latestRelease = latest)
    }

    private fun albumCards(ids: List<String>): List<Card> = ids.chunked(100).flatMap { part ->
        val remote = optional { form("albums", "album-ids" to part.joinToString(",")).objectList().mapNotNull(::album) }.orEmpty()
            .associateBy { it.id }
        part.mapNotNull { id -> remote[id] ?: versions.catalogAlbum(id)?.let(::album) }
    }

    fun artistTracks(id: String): List<Track> {
        val inserted = metadata("artist", id).tracks + versions.artistInsertions(id).first
        val standard = get("artists/${segment(id)}/tracks", "page-size" to "200").objectValue()
            .arr("tracks").objects().mapNotNull { track(it) }
        return ((optional { tracks(inserted.distinct()) }.orEmpty()) + standard).distinctBy { it.id }
    }

    fun artistFamiliar(id: String, wave: Boolean): List<Track> {
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
        val filter = if (type == "podcast") arrayOf("filter" to "podcast") else emptyArray()
        val types = if (type == "podcast") "album,artist,playlist,track,wave,podcast,podcast_episode" else type
        val value = get("search/instant/mixed", "text" to text, "type" to types, "page" to page.toString(),
            "pageSize" to "36", *filter).objectValue()
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
                    "playlist" -> Card.playlist(item.obj("playlist"))
                    "clip" -> clip(item.obj("clip"))
                    "podcast" -> album(item.obj("podcast"))?.copy(releaseType = "podcast")
                    else -> null
                } ?: continue
                cards += card
                if (best == null) best = SearchBest.Entry(card)
            }
        }
        if (page == 0 && (type.contains(',') || type == "track")) {
            tracks += versions.search(text).filter { metadata("track", it.id).usePlugin }.map(::overrideTrack)
        }
        if (page == 0 && (type.contains(',') || type == "album")) cards += versions.searchAlbums(text).mapNotNull(::album)
        val hasMore = if (value.has("lastPage")) !value.optBoolean("lastPage") && value.arr("results").length() > 0 else null
        return SearchResults(tracks.distinctBy { it.id }, cards.distinctBy { it.key }, best, hasMore)
    }

    fun history(limit: Int = 200, includeSources: Boolean = limit > 20): List<Track> {
        val value = get("music-history", "fullModelsCount" to if (includeSources) limit.toString() else "0").objectValue()
        val entries = linkedMapOf<String, Pair<String?, String?>>()
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
                    if (entries.size < limit) entries.putIfAbsent(id, date to source)
                }
            }
        }
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
            Card.playlist(data.obj("playlist")) ?: artistCard(data.obj("artist")) ?:
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
        val event = JSONObject().put("type", kind).put("timestamp", now())
        if (track == null) event.put("from", FROM)
        else event.put("trackId", track.fullId).put("totalPlayedSeconds", played)
        json("rotor/session/${segment(session)}/feedback/",
            JSONObject().put("from", FROM).put("event", event).put("batchId", track?.batch))
    }

    fun report(play: JSONObject) {
        json("plays", JSONObject().put("plays", JSONArray().put(play)), "clientNow" to now())
    }

    fun file(track: String, quality: String, transport: String): Stream {
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
    }
}
