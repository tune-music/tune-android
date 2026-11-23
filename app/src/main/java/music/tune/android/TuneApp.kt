package music.tune.android

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.KeyStore
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class TuneApp : Application() {
    internal lateinit var store: MusicStore
        private set

    override fun onCreate() {
        super.onCreate()
        store = MusicStore(this)
    }
}

internal data class SleepState(val stopAtMs: Long? = null, val afterTrack: Boolean = false) {
    val active get() = stopAtMs != null || afterTrack
}

internal class MusicStore(val context: Context) {
    val preferences: SharedPreferences = context.getSharedPreferences("tune", Context.MODE_PRIVATE)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val versions = AlternateVersions(context) { alternateEnabled }
    val overrides = MetadataOverrides(context)
    private val vault = TokenVault(context)
    private val updates = Mutex()
    private var automaticDownloads: kotlinx.coroutines.Job? = null
    private val _profile = MutableStateFlow<Profile?>(null)
    val profile = _profile.asStateFlow()
    private val _offline = MutableStateFlow(preferences.getBoolean("offline", false))
    val offlineMode = _offline.asStateFlow()
    private val _likes = MutableStateFlow<Set<String>>(emptySet())
    val likes = _likes.asStateFlow()
    private val _dislikes = MutableStateFlow<Set<String>>(emptySet())
    val dislikes = _dislikes.asStateFlow()
    private val _savedCards = MutableStateFlow<Set<String>>(emptySet())
    val savedCards = _savedCards.asStateFlow()
    private val _ownPlaylists = MutableStateFlow<List<Card>>(emptyList())
    val ownPlaylists = _ownPlaylists.asStateFlow()
    private val _savedPlaylists = MutableStateFlow<List<Card>>(emptyList())
    val savedPlaylists = _savedPlaylists.asStateFlow()
    private val _sleepTimer = MutableStateFlow(SleepState())
    val sleepTimer = _sleepTimer.asStateFlow()
    private val _message = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages = _message
    val files = MusicFiles(this)
    val waveExtras = WaveExtras(this)
    val listening = ListeningHistory(context)
    val ynison = Ynison(this)
    val uploads = UserUploads(this)
    val externalLyrics = ExternalLyrics(this)
    private val searchCache = SearchCache(context.cacheDir)
    @Volatile private var token: String? = preferences.getString("activeAccount", null)?.let(vault::read) ?: vault.read()

    init {
        if (token != null) {
            _profile.value = preferences.getString("profile", null)?.let { raw -> optional { decodeProfile(JSONObject(raw)) } }
            _profile.value?.let { user ->
                _likes.value = applyRestoredLikes(preferences.getStringSet("likes:${user.uid}", emptySet()).orEmpty(), pendingRestoredLikes(user.uid))
                _dislikes.value = preferences.getStringSet("dislikes:${user.uid}", emptySet()).orEmpty().toSet()
                _savedCards.value = preferences.getStringSet("cards:${user.uid}", emptySet()).orEmpty().toSet()
                loadSavedPlaylists(user.uid)
                files.switchUser(user.uid)
                waveExtras.resetUser(user.uid)
                uploads.resume()
            }
            scope.launch {
                try { refreshAccount() } catch (error: IOException) {
                    if (error is MusicError && error.status == 401) {
                        logout()
                        message("Войдите в аккаунт заново")
                    } else if (profile.value == null) message(error.message ?: "Нет связи с Яндексом")
                }
            }
        }
        scope.launch { refreshVersions() }
    }

    var offline: Boolean
        get() = _offline.value
        set(value) {
            _offline.value = value
            preferences.edit().putBoolean("offline", value).apply()
            files.updateNetworkPolicy()
            ynison.reconnect()
            if (!value) refreshAutomaticDownloads()
        }

    var alternateEnabled: Boolean
        get() = preferences.getBoolean("alternate", false)
        set(value) { preferences.edit().putBoolean("alternate", value).apply() }

    var wifiOnly: Boolean
        get() = preferences.getBoolean("wifiOnly", false)
        set(value) {
            if (value == wifiOnly) return
            preferences.edit().putBoolean("wifiOnly", value).apply()
            files.updateNetworkPolicy(restart = value)
        }

    var streamQuality: String
        get() = preferences.getString("streamQuality", "nq") ?: "nq"
        set(value) { preferences.edit().putString("streamQuality", value).apply() }

    var downloadQuality: String
        get() = preferences.getString("downloadQuality", "lossless") ?: "lossless"
        set(value) { preferences.edit().putString("downloadQuality", value).apply() }

    fun message(text: String) { _message.tryEmit(text) }
    fun setSleepState(state: SleepState) { _sleepTimer.value = state }

    suspend fun refreshVersions(): Boolean = withContext(Dispatchers.IO) { versions.refresh(network = !offline) }

    fun allowsAlternate(uid: String, id: String) = alternateEnabled && overrides.read(uid, "track", id).usePlugin

    fun api(): MusicApi {
        if (offline) throw MusicError("Включён офлайн-режим")
        val uid = profile.value?.uid
        return MusicApi(token ?: throw MusicError("Войдите через Яндекс", 401), versions) { type, id ->
            uid?.let { overrides.read(it, type, id) } ?: MetadataOverride()
        }
    }

    fun requireUser() = profile.value ?: throw MusicError("Войдите через Яндекс", 401)
    fun ynisonToken(): String? = token.takeUnless { offline }

    suspend fun <T> online(block: MusicApi.() -> T): T = withContext(Dispatchers.IO) {
        val client = api()
        versions.ensureLoaded(network = !offline)
        client.block()
    }

    suspend fun login(value: String) {
        val account = withContext(Dispatchers.IO) { MusicApi(value, versions).profile() }
        updates.withLock {
            val old = profile.value
            val current = token
            withContext(Dispatchers.IO) {
                if (old != null && current != null && old.uid != account.uid && vault.read(old.uid) == null) vault.write(current, old.uid)
                vault.write(value, account.uid)
                vault.clear()
            }
            activate(account, value)
        }
        refreshInBackground()
    }

    fun accounts(): List<Profile> {
        val saved = optional { JSONArray(preferences.getString("accounts", "[]")).objects().mapNotNull(::decodeProfile) }.orEmpty()
        return (saved + listOfNotNull(profile.value)).distinctBy { it.uid }
    }

    suspend fun switchAccount(account: Profile) {
        updates.withLock {
            if (account.uid == profile.value?.uid) return
            val value = withContext(Dispatchers.IO) { vault.read(account.uid) }
                ?: throw MusicError("Войдите в этот аккаунт заново")
            activate(account, value)
        }
        refreshInBackground()
    }

    private fun activate(account: Profile, value: String) {
        automaticDownloads?.cancel()
        token = value
        setProfile(account)
        offline = false
        ynison.reconnect()
    }

    private fun refreshInBackground() {
        scope.launch {
            try { refreshAccount() } catch (error: IOException) {
                if (error is MusicError && error.status == 401) logout()
                message(error.message ?: "Коллекция пока недоступна")
            }
        }
    }

    fun logout() {
        val current = profile.value?.uid
        val remaining = accounts().filterNot { it.uid == current }
        token = null
        automaticDownloads?.cancel()
        vault.clear()
        current?.let(vault::clear)
        preferences.edit().putString("accounts", JSONArray(remaining.map(::encodeProfile)).toString()).apply()
        for (account in remaining) {
            val credential = vault.read(account.uid) ?: continue
            activate(account, credential)
            refreshInBackground()
            return
        }
        files.switchUser(null)
        waveExtras.resetUser(null)
        uploads.resume()
        offline = false
        preferences.edit().remove("profile").remove("activeAccount").apply()
        _profile.value = null
        _likes.value = emptySet()
        _dislikes.value = emptySet()
        _savedCards.value = emptySet()
        _ownPlaylists.value = emptyList()
        _savedPlaylists.value = emptyList()
    }

    private fun encodeProfile(profile: Profile) = JSONObject().put("uid", profile.uid).put("name", profile.name)
        .put("plus", profile.plus).put("login", profile.login)

    private fun decodeProfile(value: JSONObject): Profile? {
        val uid = value.text("uid") ?: return null
        return Profile(uid, value.text("name") ?: "Яндекс", value.optBoolean("plus"), value.text("login").orEmpty())
    }

    private fun setProfile(account: Profile) {
        if (_profile.value?.uid != account.uid) {
            _likes.value = applyRestoredLikes(preferences.getStringSet("likes:${account.uid}", emptySet()).orEmpty(), pendingRestoredLikes(account.uid))
            _dislikes.value = preferences.getStringSet("dislikes:${account.uid}", emptySet()).orEmpty().toSet()
            _savedCards.value = preferences.getStringSet("cards:${account.uid}", emptySet()).orEmpty().toSet()
            loadSavedPlaylists(account.uid)
        }
        _profile.value = account
        files.switchUser(account.uid)
        waveExtras.resetUser(account.uid)
        val saved = accounts().filterNot { it.uid == account.uid } + account
        preferences.edit().putString("profile", encodeProfile(account).toString()).putString("activeAccount", account.uid)
            .putString("accounts", JSONArray(saved.map(::encodeProfile)).toString()).apply()
    }

    suspend fun refreshAccount() = updates.withLock {
        if (offline) return@withLock
        val credential = token ?: return@withLock
        val account = online { profile() }
        if (token != credential) return@withLock
        setProfile(account)
        coroutineScope {
            val liked = async { optional { online { libraryIds(account.uid) }.toSet() } }
            val disliked = async { optional { online { libraryIds(account.uid, dislike = true) }.toSet() } }
            val playlists = async { optional { online { playlists(account.uid) } } }
            val cards = listOf(Kind.ALBUM, Kind.ARTIST, Kind.PLAYLIST).map { kind ->
                async { kind to optional { online { likedCards(account.uid, kind) } } }
            }
            val likedIds = liked.await()
            val dislikedIds = disliked.await()
            val cardSets = cards.awaitAll()
            val own = playlists.await()
            if (token != credential) return@coroutineScope
            val syncedLikes = syncRestoredLikes(account.uid, credential)
            if (token != credential) return@coroutineScope
            (syncedLikes ?: likedIds)?.let { _likes.value = applyRestoredLikes(it, pendingRestoredLikes(account.uid)) }
            dislikedIds?.let { _dislikes.value = it }
            _savedCards.value = cardSets.fold(_savedCards.value) { existing, (kind, entries) ->
                if (entries == null) existing else existing.filterNot { it.startsWith(kind.name + ":") }.toSet() + entries.map { it.key }
            }
            own?.let { _ownPlaylists.value = it }
            cardSets.firstOrNull { it.first == Kind.PLAYLIST }?.second?.let {
                _savedPlaylists.value = it.filterNot { card -> card.owner == account.uid }
            }
            savePlaylists(account.uid)
            saveRatings(account.uid)
            refreshAutomaticDownloads()
            uploads.resume()
        }
    }

    suspend fun rate(track: Track, dislike: Boolean = false): Boolean = updates.withLock {
        val account = requireUser()
        val state = if (dislike) _dislikes else _likes
        val add = track.id !in state.value
        val credential = token ?: throw MusicError("Войдите через Яндекс", 401)
        val pending = pendingRestoredLikes(account.uid)
        if (!dislike && (track.id in pending || track.alternate && track.originalAvailable == false)) {
            val date = if (add) System.currentTimeMillis() else 0L
            saveRestoredLikes(account.uid, pending + (track.id to date))
            _likes.value = applyRestoredLikes(_likes.value, mapOf(track.id to date))
            saveRatings(account.uid)
            scope.launch {
                updates.withLock sync@{
                    if (token != credential) return@sync
                    val confirmed = syncRestoredLikes(account.uid, credential)
                    if (confirmed != null && token == credential) {
                        _likes.value = applyRestoredLikes(confirmed, pendingRestoredLikes(account.uid))
                        saveRatings(account.uid)
                    }
                }
                if (add && token == credential) refreshAutomaticDownloads()
            }
            return@withLock add
        }
        online { setTrackRating(account.uid, track.id, dislike, add) }
        if (token == credential) {
            state.value = if (add) state.value + track.id else state.value - track.id
            if (add) {
                val other = if (dislike) _likes else _dislikes
                if (track.id in other.value && optional {
                    online { setTrackRating(account.uid, track.id, !dislike, false) }
                    true
                } == true && token == credential) other.value = other.value - track.id
            }
            if (token == credential) saveRatings(account.uid)
        }
        if (!dislike && add) refreshAutomaticDownloads()
        add
    }

    private fun pendingRestoredLikes(uid: String): Map<String, Long> =
        preferences.getStringSet("restoredLikes:$uid", emptySet()).orEmpty().mapNotNull { entry ->
            val date = entry.substringAfterLast(':').toLongOrNull() ?: return@mapNotNull null
            entry.substringBeforeLast(':') to date
        }.toMap()

    private fun saveRestoredLikes(uid: String, pending: Map<String, Long>) {
        val entries = pending.mapTo(mutableSetOf()) { (id, date) -> "$id:$date" }
        preferences.edit().putStringSet("restoredLikes:$uid", entries).apply()
    }

    /** Called under updates so server verification cannot race another local rating. */
    private suspend fun syncRestoredLikes(uid: String, credential: String): Set<String>? {
        val pending = pendingRestoredLikes(uid)
        if (pending.isEmpty() || offline || token != credential) return null
        val result = optional {
            online {
                reconcileRestoredLikes(pending, read = { fullLikedEntries().map { it.first }.toSet() }) { id, liked ->
                    if (token == credential) setTrackRating(uid, id, disliked = false, add = liked)
                }
            }
        } ?: return null
        if (token != credential) return null
        val (confirmed, remaining) = result
        saveRestoredLikes(uid, remaining)
        return confirmed
    }

    suspend fun likedTracks(onPreview: ((List<Track>) -> Unit)? = null): List<Track> {
        val uid = requireUser().uid
        fun includePending(tracks: List<Track>): List<Track> {
            val pending = pendingRestoredLikes(uid)
            val byId = tracks.associateBy { it.id }
            val added = pending.entries.filter { it.value > 0 }.sortedByDescending { it.value }.mapNotNull { (id, date) ->
                val track = byId[id] ?: versions.synthetic(id)?.takeIf { allowsAlternate(uid, id) }
                track?.copy(addedAt = date)
            }
            return added + tracks.filterNot { it.id in pending }
        }
        var cacheHasTracks = false
        var firstPage: List<Track>? = null
        val onCached = onPreview?.let { display ->
            { tracks: List<Track> ->
                val merged = includePending(tracks)
                cacheHasTracks = merged.isNotEmpty()
                display(merged)
            }
        }
        return try {
            val tracks = cachedTracks("likes", onCached) {
                val entries = online { likedEntries(uid) }
                val firstPreview = onPreview?.takeUnless { cacheHasTracks }?.let { display ->
                    { page: List<Track> ->
                        firstPage = page
                        display(includePending(page))
                    }
                }
                loadLikedTrackPages(entries, firstPreview) { ids ->
                    if (profile.value?.uid != uid) throw CancellationException("Аккаунт сменился")
                    online { tracks(ids) }
                }
            }
            includePending(tracks.ifEmpty { firstPage.orEmpty() })
        } catch (error: IOException) {
            includePending(firstPage.orEmpty()).ifEmpty { throw error }
        }
    }

    suspend fun save(card: Card) = updates.withLock {
        val account = requireUser()
        val credential = token
        val add = card.key !in _savedCards.value
        online { setCardLiked(account.uid, card, add) }
        if (token == credential) {
            _savedCards.value = if (add) _savedCards.value + card.key else _savedCards.value - card.key
            if (card.kind == Kind.PLAYLIST) {
                _savedPlaylists.value = if (add) listOf(card) + _savedPlaylists.value else _savedPlaylists.value.filterNot { it.key == card.key }
                savePlaylists(account.uid)
            }
            saveRatings(account.uid)
        }
        add
    }

    private fun saveRatings(uid: String) {
        preferences.edit().putStringSet("likes:$uid", _likes.value).putStringSet("dislikes:$uid", _dislikes.value)
            .putStringSet("cards:$uid", _savedCards.value).apply()
    }

    fun changeLocalPlaylist(card: Card, remove: Boolean = false) {
        val uid = _profile.value?.uid ?: return
        if (card.owner != uid) return
        val index = _ownPlaylists.value.indexOfFirst { it.key == card.key }
        _ownPlaylists.value = _ownPlaylists.value.toMutableList().apply {
            when {
                remove && index >= 0 -> removeAt(index)
                !remove && index >= 0 -> set(index, card)
                !remove -> add(0, card)
            }
        }
        savePlaylists(uid)
    }

    private fun loadSavedPlaylists(uid: String) {
        fun read(key: String): List<Card> = optional {
            JSONArray(preferences.getString("$key:$uid", "[]")).objects().mapNotNull(Card::fromCache)
        }.orEmpty()
        _ownPlaylists.value = read("ownPlaylists")
        _savedPlaylists.value = read("savedPlaylists")
    }

    private fun savePlaylists(uid: String) {
        preferences.edit().putString("ownPlaylists:$uid", JSONArray(_ownPlaylists.value.map(Card::cached)).toString())
            .putString("savedPlaylists:$uid", JSONArray(_savedPlaylists.value.map(Card::cached)).toString()).apply()
    }

    private fun automaticKey() = profile.value?.uid?.let { "automaticDownloads:$it" }

    fun autoDownload(card: Card? = null): Boolean =
        automaticKey()?.let { preferences.getStringSet(it, emptySet()).orEmpty().contains(card?.key ?: "likes") } == true

    fun setAutoDownload(card: Card? = null, enabled: Boolean) {
        val key = automaticKey() ?: return
        val selected = preferences.getStringSet(key, emptySet()).orEmpty().toMutableSet()
        val target = card?.key ?: "likes"
        if (enabled) selected.add(target) else selected.remove(target)
        preferences.edit().putStringSet(key, selected).apply()
        refreshAutomaticDownloads()
    }

    fun refreshAutomaticDownloads() {
        automaticDownloads?.cancel()
        val user = profile.value ?: return
        if (offline) return
        val targets = preferences.getStringSet("automaticDownloads:${user.uid}", emptySet()).orEmpty()
        if (targets.isEmpty()) return
        automaticDownloads = scope.launch {
            if ("likes" in targets) {
                val tracks = optional {
                    online {
                        val ids = applyRestoredLikes(libraryIds(user.uid).toSet(), pendingRestoredLikes(user.uid))
                        tracks(ids.filterNot(files::hasId))
                    }
                }
                if (profile.value?.uid == user.uid && tracks != null && !offline) optional { files.enqueue(tracks) }
            }
            for (card in (ownPlaylists.value + savedPlaylists.value).filter { it.key in targets }) {
                val tracks = optional { online { playlist(card).tracks } }
                if (profile.value?.uid != user.uid || offline) break
                if (tracks != null) optional { files.enqueue(tracks) }
            }
        }
    }

    suspend fun cachedTracks(key: String, onCached: ((List<Track>) -> Unit)? = null, load: suspend () -> List<Track>): List<Track> {
        val uid = requireUser().uid
        val cache = AtomicFile(File(context.cacheDir, "tracks-${digest("$uid:$key")}.json"))
        suspend fun readTracks() = readCache(cache) { raw ->
            val tracks = JSONArray(raw).objects().mapNotNull { Track.parse(it) }
            when (key) {
                "likes" -> tracks.filter { it.id in likes.value }
                "dislikes" -> tracks.filter { it.id in dislikes.value }
                else -> tracks
            }
        }
        val saved = if (onCached != null) readTracks() else null
        if (saved != null) onCached?.invoke(saved)
        if (offline && saved != null) return saved
        try {
            val tracks = load()
            withContext(Dispatchers.IO) {
                optional { cache.writeText(JSONArray(tracks.map { it.stored() }).toString()) }
            }
            return tracks
        } catch (error: IOException) {
            if (error is MusicError && error.status in 400..499 && error.status != 429) throw error
            return saved ?: readTracks() ?: throw error
        }
    }

    private suspend fun <T> readCache(file: AtomicFile, decode: (String) -> T?): T? = withContext(Dispatchers.IO) {
        optional { decode(file.readFully().toString(Charsets.UTF_8)) }
    }

    private suspend fun <T> cached(
        key: String,
        encode: (T) -> JSONObject,
        decode: (JSONObject) -> T?,
        onCached: ((T) -> Unit)? = null,
        load: suspend () -> T
    ): T {
        val uid = requireUser().uid
        val file = dataCache(uid, key)
        suspend fun readPage() = readCache(file) { decode(JSONObject(it)) }
        val saved = if (onCached != null) readPage() else null
        if (saved != null) onCached?.invoke(saved)
        if (offline && saved != null) return saved
        try {
            val fresh = load()
            withContext(Dispatchers.IO) { optional { file.writeText(encode(fresh).toString()) } }
            return fresh
        } catch (error: java.io.IOException) {
            if (error is MusicError && error.status in setOf(401, 403, 404)) throw error
            return saved ?: readPage() ?: throw error
        }
    }

    private fun dataCache(uid: String, key: String) =
        AtomicFile(File(context.cacheDir, "data-${digest("$uid:$key")}.json"))

    suspend fun collection(kind: Kind, onCached: ((List<Card>) -> Unit)? = null): List<Card> {
        val uid = requireUser().uid
        return cached("collection:${kind.name}", { JSONObject().put("cards", JSONArray(it.map(Card::cached))) },
            { it.arr("cards").objects().mapNotNull(Card::fromCache) }, onCached) { online { likedCards(uid, kind) } }
    }

    suspend fun topArtists(onCached: ((List<Card>) -> Unit)? = null): List<Card> {
        requireUser()
        return cached("collection:top-artists", { JSONObject().put("cards", JSONArray(it.map(Card::cached))) },
            { it.arr("cards").objects().mapNotNull(Card::fromCache) }, onCached) { online { topArtists() } }
    }

    suspend fun cachedSearch(query: String, type: String): SearchResults? {
        val key = SearchCache.Key(requireUser().uid, query, type, alternateEnabled)
        return withContext(Dispatchers.IO) { searchCache.read(key) }
    }

    suspend fun searchPage(query: String, type: String, page: Int): SearchResults {
        val key = SearchCache.Key(requireUser().uid, query, type, alternateEnabled)
        return online {
            search(query, type, page).also { results ->
                if (page == 0 && type != "lyrics" && profile.value?.uid == key.account && alternateEnabled == key.uncensored) {
                    searchCache.write(key, results)
                }
            }
        }
    }

    private fun encodePage(page: MusicPage) =
        JSONObject().put("card", page.card.cached()).put("tracks", JSONArray(page.tracks.map(Track::stored)))
            .put("description", page.description).put("disks", JSONArray(page.disks)).put("publicPlaylist", page.publicPlaylist)
            .put("latestRelease", page.latestRelease?.cached())
            .put("shelves", encodeShelves(page.shelves))

    private fun encodeShelves(shelves: List<Shelf>) = JSONArray(shelves.map { shelf ->
        JSONObject().put("title", shelf.title).put("cards", JSONArray(shelf.cards.map(Card::cached)))
    })

    private fun decodeShelves(data: JSONArray) = data.objects().mapNotNull { shelf ->
        shelf.text("title")?.let { Shelf(it, shelf.arr("cards").objects().mapNotNull(Card::fromCache)) }
    }

    suspend fun movePlaylistTrack(before: MusicPage, after: MusicPage, from: Int, to: Int) {
        val uid = requireUser().uid
        val client = api()
        withContext(Dispatchers.IO) {
            client.movePlaylistTrack(before.card, before.tracks, from, to)
            optional { dataCache(uid, "page:${before.card.key}").writeText(encodePage(after).toString()) }
        }
    }

    suspend fun details(card: Card, onCached: ((MusicPage) -> Unit)? = null): MusicPage = cached("page:${card.key}", ::encodePage, { value ->
        val savedCard = Card.fromCache(value.obj("card")) ?: return@cached null
        val tracks = value.arr("tracks").objects().mapNotNull { Track.parse(it) }
        val shelves = decodeShelves(value.arr("shelves"))
        MusicPage(savedCard, tracks, shelves, value.text("description"), value.arr("disks").items().mapNotNull { (it as? Number)?.toInt() },
            value.opt("publicPlaylist") as? Boolean, value.optJSONObject("latestRelease")?.let(Card::fromCache))
    }, onCached) {
        online {
            when (card.kind) {
                Kind.ALBUM -> album(card)
                Kind.PLAYLIST -> playlist(card)
                Kind.ARTIST -> artist(card)
                else -> MusicPage(card, emptyList())
            }
        }
    }

    suspend fun localLyricsSearch(query: String, queue: List<Track>): List<Track> = withContext(Dispatchers.IO) {
        fun clean(text: String) = text.lowercase(java.util.Locale.ROOT).replace('ё', 'е').replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
        val phrase = clean(query)
        if (phrase.isBlank()) return@withContext emptyList()
        val uid = profile.value?.uid
        (queue + files.downloaded()).distinctBy { it.id }.filter { track ->
            val pinned = uid?.let { files.savedLyrics(it, track.id) }
            val entry = pinned?.let { readLyrics(it, anySource = true) }
                ?: readLyrics(lyricsCache(track.id), anySource = true) ?: return@filter false
            val lyrics = entry.text.lines.joinToString(" ") { it.text }
            " $phrase " in " ${clean(lyrics)} "
        }
    }

    private fun homeFile(uid: String) = AtomicFile(File(context.cacheDir, "home-${digest(uid)}.json"))

    suspend fun cachedHome(): HomePage = withContext(Dispatchers.IO) {
        val cache = homeFile(requireUser().uid)
        optional {
            val raw = org.json.JSONTokener(cache.readFully().toString(Charsets.UTF_8)).nextValue()
            when (raw) {
                is JSONObject -> HomePage(decodeShelves(raw.arr("personal")), decodeShelves(raw.arr("trends")),
                    raw.arr("recent").objects().mapNotNull { Track.parse(it) })
                is JSONArray -> HomePage(decodeShelves(raw))
                else -> HomePage()
            }
        } ?: HomePage()
    }

    private suspend fun loadShelves(sources: List<Pair<String, String>>, reportFailure: Boolean = false) = coroutineScope {
        val results = sources.map { (name, uri) ->
            async {
                try {
                    Result.success(online { homeShelf(name, uri) })
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    Result.failure(error)
                }
            }
        }.awaitAll()
        val shelves = results.mapNotNull { it.getOrNull() }.filter { it.cards.isNotEmpty() }
        if (reportFailure && shelves.isEmpty()) {
            results.firstNotNullOfOrNull { it.exceptionOrNull() }?.let { throw it }
        }
        shelves
    }

    suspend fun kids(onCached: ((List<Shelf>) -> Unit)? = null): List<Shelf> = cached(
        "kids", { JSONObject().put("shelves", encodeShelves(it)) }, { decodeShelves(it.arr("shelves")) }, onCached,
    ) {
        val sources = online { homeSources(kids = true) }.flatten().distinct()
        loadShelves(sources, reportFailure = true).distinctBy { shelf -> shelf.cards.map(Card::key) }
    }

    suspend fun concerts(city: String?, onCached: ((List<Card>) -> Unit)? = null): List<Card> = cached(
        "concerts:${city.orEmpty()}", { JSONObject().put("cards", JSONArray(it.map(Card::cached))) },
        { it.arr("cards").objects().mapNotNull(Card::fromCache) }, onCached,
    ) { online { concerts(city) } }

    suspend fun concertCities(): List<Pair<String, String>> = cached(
        "concert-cities",
        { entries -> JSONObject().put("cities", JSONArray(entries.map { (id, name) -> JSONObject().put("id", id).put("name", name) })) },
        { data -> data.arr("cities").objects().mapNotNull { city ->
            val id = city.text("id") ?: return@mapNotNull null
            val name = city.text("name") ?: return@mapNotNull null
            id to name
        } },
    ) { online { cities() } }

    suspend fun home(): HomePage = coroutineScope {
        val uid = requireUser().uid
        val sources = online { homeSources() }
        val personal = async { loadShelves(sources.getOrNull(0).orEmpty()) }
        val trends = async { loadShelves(sources.getOrNull(1).orEmpty()) }
        val recent = async { optional { online { history(limit = 8) } }.orEmpty() }
        val page = HomePage(personal.await(), trends.await(), recent.await())
        if (page.hasContent) withContext(Dispatchers.IO) {
            optional {
                homeFile(uid).writeText(JSONObject().put("personal", encodeShelves(page.personal)).put("trends", encodeShelves(page.trends))
                    .put("recent", JSONArray(page.recent.map(Track::stored))).toString())
            }
        }
        page
    }

    private data class CachedLyrics(val text: LyricsText, val raw: String)
    private val lyricsFiles = Any()

    private fun lyricsCache(id: String) = AtomicFile(File(context.cacheDir, "lyrics-${digest(id)}.json"))

    private fun readLyrics(file: AtomicFile, anySource: Boolean): CachedLyrics? = synchronized(lyricsFiles) {
        optional {
            val raw = file.readFully().toString(Charsets.UTF_8)
            LyricsText.fromCache(raw, externalLyrics.cacheKey, anySource)?.let { CachedLyrics(it, raw) }
        }
    }

    suspend fun lyrics(track: Track): LyricsText = withContext(Dispatchers.IO) {
        val uid = profile.value?.uid
        val file = lyricsCache(track.id)
        val pinned = uid?.let { files.savedLyrics(it, track.id) }
        val saved = pinned?.let { readLyrics(it, anySource = offline) }
        if (saved != null && (offline || saved.text.source != null)) return@withContext saved.text
        val cached = readLyrics(file, anySource = offline)
        if (cached != null && (offline || cached.text.source != null)) {
            if (uid != null) synchronized(lyricsFiles) { optional { files.saveDownloadLyrics(uid, track.id, cached.raw) } }
            return@withContext cached.text
        }
        if (offline) throw MusicError("Включён офлайн-режим")
        val previous = saved ?: cached
        val settings = externalLyrics.cacheKey
        val text = if (previous == null) externalLyrics.find(track) { api().lyrics(track.id) }
        else optional { externalLyrics.find(track) { api().lyrics(track.id) } } ?: return@withContext previous.text
        if (text.lines.isNotEmpty()) {
            val raw = text.toCache(settings)
            synchronized(lyricsFiles) {
                optional { file.writeText(raw) }
                if (uid != null) optional { files.saveDownloadLyrics(uid, track.id, raw) }
            }
        }
        if (text.lines.isEmpty() && previous != null) previous.text else text
    }

    suspend fun lyricsMatches(query: String): List<Track> = coroutineScope {
        if (offline) return@coroutineScope emptyList()
        val hits = withContext(Dispatchers.IO) { externalLyrics.searchByLine(query) }
        hits.map { hit ->
            async { optional { online { search("${hit.artist} ${hit.title}", "track", 0).tracks.firstOrNull { lyricsMatch(it, hit) } } } }
        }.awaitAll().filterNotNull().distinctBy { it.id }
    }
}

internal inline fun <T> optional(block: () -> T): T? = try { block() } catch (error: Exception) {
    if (error is CancellationException) throw error
    null
}

internal fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

internal fun AtomicFile.writeText(text: String) {
    val out = startWrite()
    try {
        out.write(text.toByteArray(Charsets.UTF_8))
        finishWrite(out)
    } catch (error: IOException) {
        failWrite(out)
        throw error
    }
}

private class TokenVault(context: Context) {
    private val settings = context.getSharedPreferences("account", Context.MODE_PRIVATE)
    private fun field(uid: String?) = if (uid == null) "token" else "token:${digest(uid)}"
    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    fun read(uid: String? = null): String? = optional {
        val encoded = settings.getString(field(uid), null) ?: return null
        val (iv, data) = encoded.split(":", limit = 2)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.getDecoder().decode(iv)))
        }
        cipher.doFinal(Base64.getDecoder().decode(data)).toString(Charsets.UTF_8)
    }

    fun write(token: String, uid: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val base64 = Base64.getEncoder()
        val iv = base64.encodeToString(cipher.iv)
        val data = base64.encodeToString(cipher.doFinal(token.toByteArray()))
        if (!settings.edit().putString(field(uid), "$iv:$data").commit()) throw MusicError("Не удалось сохранить вход")
    }

    fun clear(uid: String? = null) { settings.edit().remove(field(uid)).apply() }

    private companion object { const val ALIAS = "tune-oauth" }
}
