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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
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
    val versions = AlternateVersions(context, { alternateEnabled }, { catalogEnabled })
    val catalogLibrary = CatalogLibraryStore(context.filesDir)
    val overrides = MetadataOverrides(context)
    private val vault = TokenVault(context)
    private val updates = Mutex()
    private val syncMutex = Mutex()
    private val syncStateLock = Any()
    private var syncDirtyUid: String? = null
    private var syncJob: kotlinx.coroutines.Job? = null
    private val tuneCoverJpeg: ByteArray? by lazy {
        optional {
            val bitmap = android.graphics.BitmapFactory.decodeResource(context.resources, R.drawable.tune_icon)
                ?: return@optional null
            try {
                java.io.ByteArrayOutputStream().use { out ->
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, out)
                    out.toByteArray()
                }
            } finally {
                bitmap.recycle()
            }
        }
    }
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
    private val _catalogVersion = MutableStateFlow(0L)
    val catalogVersion = _catalogVersion.asStateFlow()
    private var baseLikes = emptySet<String>()
    private var baseDislikes = emptySet<String>()
    private var baseSavedCards = emptySet<String>()
    private var baseOwnPlaylists = emptyList<Card>()
    private var baseSavedPlaylists = emptyList<Card>()
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
    private val artistCoverCache = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val trackFiltersMemory = java.util.concurrent.ConcurrentHashMap<String, List<TrackFilter>>()
    @Volatile private var artistCoverCacheLoaded = false
    @Volatile private var token: String? = preferences.getString("activeAccount", null)?.let(vault::read) ?: vault.read()

    init {
        catalogLibrary.onLocalMutate = { uid -> scheduleCatalogSync(uid) }
        if (token != null) {
            _profile.value = preferences.getString("profile", null)?.let { raw -> optional { decodeProfile(JSONObject(raw)) } }
            _profile.value?.let { user ->
                loadUserBaseState(user.uid)
                applyCatalogLibraryOverlay(user.uid)
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
        scope.launch {
            refreshVersions(forceCatalog = true)
            while (isActive) {
                delay(120_000L)
                refreshCatalogPeriodic()
            }
        }
    }

    var offline: Boolean
        get() = _offline.value
        set(value) {
            _offline.value = value
            preferences.edit().putBoolean("offline", value).apply()
            files.updateNetworkPolicy()
            ynison.reconnect()
            if (!value) {
                refreshAutomaticDownloads()
                profile.value?.uid?.let(::scheduleCatalogSync)
            }
        }

    var alternateEnabled: Boolean
        get() = preferences.getBoolean("alternate", false)
        set(value) {
            if (value == alternateEnabled) return
            preferences.edit().putBoolean("alternate", value).apply()
            _catalogVersion.value++
        }

    var catalogEnabled: Boolean
        get() = preferences.getBoolean("catalog", true)
        set(value) {
            if (value == catalogEnabled) return
            preferences.edit().putBoolean("catalog", value).apply()
            profile.value?.uid?.let(::applyCatalogLibraryOverlay)
            _catalogVersion.value++
        }

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

    suspend fun refreshVersions(forceCatalog: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        val beforeSig = versions.catalogSignature()
        val ready = versions.refresh(network = !offline, forceCatalog = forceCatalog)
        if (ready && !offline && alternateEnabled) token?.let { credential ->
            val client = MusicApi(credential, versions)
            versions.ensureRestored(network = true, client::rawTracks)
        }
        val afterSig = versions.catalogSignature()
        if (beforeSig != afterSig) {
            profile.value?.uid?.let(::applyCatalogLibraryOverlay)
            _catalogVersion.value++
        }
        ready
    }

    private suspend fun refreshCatalogPeriodic() = withContext(Dispatchers.IO) {
        if (offline || !catalogEnabled) return@withContext
        val (_, changed) = versions.refreshCatalog(network = true, force = false)
        if (changed) {
            profile.value?.uid?.let(::applyCatalogLibraryOverlay)
            _catalogVersion.value++
        }
    }

    fun allowsAlternate(uid: String, id: String) = alternateEnabled && overrides.read(uid, "track", id).usePlugin
    fun allowsCatalog(uid: String, id: String) = catalogEnabled && overrides.read(uid, "track", id).usePlugin
    fun allowsPluginAudio(uid: String, id: String) = (alternateEnabled || catalogEnabled) && overrides.read(uid, "track", id).usePlugin

    private fun rawApi(credential: String, triggerSync: Boolean): MusicApi {
        val uid = profile.value?.uid
        return MusicApi(
            token = credential,
            versions = versions,
            catalogLibrary = catalogLibrary,
            activeUid = uid,
            catalogEnabled = { catalogEnabled },
            onSyncShards = if (triggerSync) ({ shardUid, shards -> onRemoteSyncShards(shardUid, shards) }) else null,
        ) { type, id ->
            uid?.let { overrides.read(it, type, id) } ?: MetadataOverride()
        }
    }

    fun api(): MusicApi {
        if (offline) throw MusicError("Включён офлайн-режим")
        val credential = token ?: throw MusicError("Войдите через Яндекс", 401)
        return rawApi(credential, triggerSync = true)
    }

    private fun shardSlot(shard: RemoteSyncShard): Int {
        TuneSync.parseEnvelope(shard.description)?.let { return it.part - 1 }
        if (shard.title == TuneSync.TITLE_PRIMARY) return 0
        val num = shard.title.removePrefix("Tune Sync ").toIntOrNull()
        return if (num != null && num >= 2) num - 1 else Int.MAX_VALUE
    }

    private fun decodeRemoteShards(shards: List<RemoteSyncShard>, uid: String): Pair<CatalogLibraryData?, Boolean> {
        if (shards.isEmpty()) return null to false
        val descriptions = shards.map { it.description }
        val envelopes = descriptions.mapNotNull(TuneSync::parseEnvelope)
        if (envelopes.isEmpty()) return null to false
        val assembled = TuneSync.assembleShards(descriptions)
        if (assembled != null) {
            val decoded = CatalogLibraryData.decodePayload(assembled, uid)
            return if (decoded != null) decoded to false else null to true
        }
        if (envelopes.any { it.total > 1 }) return null to true
        var combined: CatalogLibraryData? = null
        for (env in envelopes) {
            val decoded = CatalogLibraryData.decodePayload(env.chunk, uid) ?: return null to true
            combined = combined?.mergeWith(decoded, uid) ?: decoded
        }
        return combined to false
    }

    private fun onRemoteSyncShards(uid: String, shards: List<RemoteSyncShard>) {
        if (offline || profile.value?.uid != uid) return
        val (remoteData, partialOrCorrupted) = decodeRemoteShards(shards, uid)
        if (!partialOrCorrupted && remoteData != null) {
            val changed = catalogLibrary.applyMerged(uid) { local -> local.mergeWith(remoteData, uid) }
            if (changed) {
                scope.launch {
                    updates.withLock {
                        applyCatalogLibraryOverlay(uid)
                        _catalogVersion.value++
                    }
                }
            }
        }
        val nowSec = System.currentTimeMillis() / 1000L
        val payload = catalogLibrary.snapshot(uid).encodePayload(uid, nowSec)
        val needsPush = if (payload.isEmpty()) {
            !partialOrCorrupted && shards.isNotEmpty()
        } else {
            val expected = TuneSync.splitShards(payload)
            if (shards.size != expected.size) {
                true
            } else {
                val bySlot = shards.associateBy(::shardSlot)
                expected.indices.any { idx ->
                    val existing = bySlot[idx]
                    existing == null ||
                        existing.title != expected[idx].title ||
                        !existing.isPrivate ||
                        !existing.hasCustomCover ||
                        existing.description != expected[idx].description
                }
            }
        }
        if (needsPush) scheduleCatalogSync(uid)
    }

    internal fun scheduleCatalogSync(uid: String) {
        if (offline) return
        val credential = token ?: return
        if (profile.value?.uid != uid) return
        synchronized(syncStateLock) {
            syncDirtyUid = uid
            if (syncJob?.isActive == true) return
            syncJob = scope.launch(Dispatchers.IO) {
                runPendingCatalogSync(credential)
            }
        }
    }

    private suspend fun runPendingCatalogSync(initialCredential: String) {
        while (true) {
            val uid = synchronized(syncStateLock) {
                val target = syncDirtyUid
                syncDirtyUid = null
                if (target == null) {
                    syncJob = null
                    return
                }
                target
            }
            if (offline || token != initialCredential || profile.value?.uid != uid) {
                synchronized(syncStateLock) { syncJob = null }
                return
            }
            delay(150L)
            syncMutex.withLock {
                if (!offline && token == initialCredential && profile.value?.uid == uid) {
                    optional { performCatalogSyncPush(uid, initialCredential) }
                }
            }
        }
    }

    private suspend fun performCatalogSyncPush(uid: String, credential: String) {
        versions.ensureLoaded(network = true)
        val client = rawApi(credential, triggerSync = false)
        val (_, initialShards) = client.playlistsWithSync(uid)
        val (remoteData, partialOrCorrupted) = decodeRemoteShards(initialShards, uid)
        if (!partialOrCorrupted && remoteData != null) {
            val changed = catalogLibrary.applyMerged(uid) { local -> local.mergeWith(remoteData, uid) }
            if (changed) {
                scope.launch {
                    updates.withLock {
                        applyCatalogLibraryOverlay(uid)
                        _catalogVersion.value++
                    }
                }
            }
        }
        if (offline || token != credential || profile.value?.uid != uid) return
        val nowSec = System.currentTimeMillis() / 1000L
        val payload = catalogLibrary.snapshot(uid).encodePayload(uid, nowSec)
        val targetShards = TuneSync.splitShards(payload)
        val sortedShards = initialShards.sortedWith(
            compareBy<RemoteSyncShard> { it.kind.toLongOrNull() ?: Long.MAX_VALUE }.thenBy { it.kind },
        )
        if (targetShards.isEmpty()) {
            if (!partialOrCorrupted || initialShards.all { TuneSync.parseEnvelope(it.description) == null }) {
                for (shard in sortedShards) {
                    optional { client.deletePlaylistRaw(uid, shard.kind) }
                }
            }
            return
        }
        val pool = sortedShards.toMutableList()
        val assigned = arrayOfNulls<RemoteSyncShard>(targetShards.size)
        for (i in targetShards.indices) {
            val idx = pool.indexOfFirst { shardSlot(it) == i }
            if (idx >= 0) assigned[i] = pool.removeAt(idx)
        }
        for (i in targetShards.indices) {
            if (assigned[i] == null && pool.isNotEmpty()) {
                assigned[i] = pool.removeAt(0)
            }
        }
        for ((i, spec) in targetShards.withIndex()) {
            val existing = assigned[i]
            val card = if (existing != null) {
                Card(Kind.PLAYLIST, existing.kind, existing.title, owner = uid)
            } else {
                client.createPlaylist(uid, spec.title)
            }
            if (existing != null && existing.title != spec.title) {
                client.renamePlaylist(card, spec.title)
            }
            if (existing != null && !existing.isPrivate) {
                client.setPlaylistPublic(card, false)
            }
            if (existing == null || !existing.hasCustomCover) {
                val jpeg = tuneCoverJpeg
                if (jpeg != null) {
                    try {
                        client.uploadPlaylistCover(card, jpeg)
                    } catch (error: Throwable) {
                        android.util.Log.w("TuneSync", "Failed to upload cover for ${card.id}: ${error.message}")
                    }
                }
            }
            if (existing == null || existing.description != spec.description) {
                client.editPlaylistDetails(card, spec.description)
            }
        }
        for (extra in pool) {
            optional { client.deletePlaylistRaw(uid, extra.kind) }
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

    private fun cancelPendingCatalogSync() {
        synchronized(syncStateLock) {
            syncDirtyUid = null
            syncJob?.cancel()
            syncJob = null
        }
    }

    private fun activate(account: Profile, value: String) {
        automaticDownloads?.cancel()
        cancelPendingCatalogSync()
        trackFiltersMemory.clear()
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
        cancelPendingCatalogSync()
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
        trackFiltersMemory.clear()
        uploads.resume()
        offline = false
        preferences.edit().remove("profile").remove("activeAccount").apply()
        _profile.value = null
        baseLikes = emptySet()
        baseDislikes = emptySet()
        baseSavedCards = emptySet()
        baseOwnPlaylists = emptyList()
        baseSavedPlaylists = emptyList()
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

    private fun isSyntheticCardKey(key: String): Boolean = when {
        key.startsWith("ALBUM::") -> versions.isSyntheticAlbumId(key.removePrefix("ALBUM::"))
        key.startsWith("ARTIST::") -> versions.isSyntheticArtistId(key.removePrefix("ARTIST::"))
        key.startsWith("PLAYLIST:${AlternateVersions.CATALOG_OWNER}:") -> true
        else -> false
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

    private fun loadUserBaseState(uid: String) {
        baseLikes = applyRestoredLikes(
            preferences.getStringSet("likes:$uid", emptySet()).orEmpty().filterNot(versions::isSyntheticTrackId).toSet(),
            pendingRestoredLikes(uid),
        )
        baseDislikes = preferences.getStringSet("dislikes:$uid", emptySet()).orEmpty()
            .filterNot(versions::isSyntheticTrackId).toSet()
        baseSavedCards = preferences.getStringSet("cards:$uid", emptySet()).orEmpty()
            .filterNot(::isSyntheticCardKey).toSet()
        loadSavedPlaylists(uid)
    }

    private fun applyCatalogLibraryOverlay(uid: String) {
        if (profile.value?.uid != uid) return
        if (!catalogEnabled) {
            _likes.value = baseLikes
            _dislikes.value = baseDislikes
            _savedCards.value = baseSavedCards
            _ownPlaylists.value = baseOwnPlaylists
            _savedPlaylists.value = baseSavedPlaylists
            return
        }
        val lib = catalogLibrary.snapshot(uid)
        _likes.value = baseLikes + lib.likedTracks
        _dislikes.value = baseDislikes + lib.dislikedTracks
        val localKeys = buildSet {
            lib.likedAlbums.forEach { add("ALBUM::$it") }
            lib.likedArtists.forEach { add("ARTIST::$it") }
            lib.likedPlaylists.forEach { add("PLAYLIST:${AlternateVersions.CATALOG_OWNER}:$it") }
        }
        _savedCards.value = baseSavedCards + localKeys
        _ownPlaylists.value = baseOwnPlaylists.map { card ->
            val extra = lib.playlistTracks["${card.owner}:${card.id}"]?.size ?: 0
            if (extra > 0) card.copy(trackCount = card.trackCount + extra) else card
        }
        val localPlaylists = lib.likedPlaylists.mapNotNull(::catalogPlaylistCard)
        _savedPlaylists.value = (localPlaylists + baseSavedPlaylists).distinctBy { it.key }
    }

    private fun setProfile(account: Profile) {
        if (_profile.value?.uid != account.uid) {
            loadUserBaseState(account.uid)
        }
        _profile.value = account
        applyCatalogLibraryOverlay(account.uid)
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
            (syncedLikes ?: likedIds)?.let {
                baseLikes = applyRestoredLikes(it.filterNot(versions::isSyntheticTrackId).toSet(), pendingRestoredLikes(account.uid))
            }
            dislikedIds?.let { baseDislikes = it.filterNot(versions::isSyntheticTrackId).toSet() }
            baseSavedCards = cardSets.fold(baseSavedCards) { existing, (kind, entries) ->
                if (entries == null) existing
                else existing.filterNot { it.startsWith(kind.name + ":") }.toSet() +
                    entries.map { it.key }.filterNot(::isSyntheticCardKey)
            }
            own?.let { serverOwned ->
                val lib = catalogLibrary.snapshot(account.uid)
                baseOwnPlaylists = serverOwned.map { card ->
                    val extra = if (catalogEnabled) lib.playlistTracks["${card.owner}:${card.id}"]?.size ?: 0 else 0
                    if (extra > 0) card.copy(trackCount = (card.trackCount - extra).coerceAtLeast(0)) else card
                }
            }
            cardSets.firstOrNull { it.first == Kind.PLAYLIST }?.second?.let {
                baseSavedPlaylists = it.filterNot { card -> card.owner == account.uid || card.owner == AlternateVersions.CATALOG_OWNER }
            }
            applyCatalogLibraryOverlay(account.uid)
            savePlaylists(account.uid)
            saveRatings(account.uid)
            refreshAutomaticDownloads()
            uploads.resume()
        }
    }

    suspend fun rate(track: Track, dislike: Boolean = false): Boolean = updates.withLock {
        val account = requireUser()
        if (versions.isSyntheticTrackId(track.id)) {
            val state = if (dislike) _dislikes else _likes
            val add = track.id !in state.value
            catalogLibrary.mutate(account.uid) {
                if (dislike) it.setTrackDisliked(track.id, add) else it.setTrackLiked(track.id, add)
            }
            applyCatalogLibraryOverlay(account.uid)
            if (!dislike && add) refreshAutomaticDownloads()
            return@withLock add
        }
        val state = if (dislike) _dislikes else _likes
        val add = track.id !in state.value
        val credential = token ?: throw MusicError("Войдите через Яндекс", 401)
        val pending = pendingRestoredLikes(account.uid)
        if (!dislike && (track.id in pending || track.alternate && track.originalAvailable == false)) {
            val date = if (add) System.currentTimeMillis() else 0L
            saveRestoredLikes(account.uid, pending + (track.id to date))
            baseLikes = applyRestoredLikes(baseLikes, mapOf(track.id to date))
            applyCatalogLibraryOverlay(account.uid)
            saveRatings(account.uid)
            scope.launch {
                updates.withLock sync@{
                    if (token != credential) return@sync
                    val confirmed = syncRestoredLikes(account.uid, credential)
                    if (confirmed != null && token == credential) {
                        baseLikes = applyRestoredLikes(confirmed.filterNot(versions::isSyntheticTrackId).toSet(), pendingRestoredLikes(account.uid))
                        applyCatalogLibraryOverlay(account.uid)
                        saveRatings(account.uid)
                    }
                }
                if (add && token == credential) refreshAutomaticDownloads()
            }
            return@withLock add
        }
        online { setTrackRating(account.uid, track.id, dislike, add) }
        if (token == credential) {
            if (dislike) {
                baseDislikes = if (add) baseDislikes + track.id else baseDislikes - track.id
            } else {
                baseLikes = if (add) baseLikes + track.id else baseLikes - track.id
            }
            if (add) {
                val otherIsLikes = dislike
                val otherSet = if (otherIsLikes) baseLikes else baseDislikes
                if (track.id in otherSet && optional {
                    online { setTrackRating(account.uid, track.id, !dislike, false) }
                    true
                } == true && token == credential) {
                    if (otherIsLikes) baseLikes = baseLikes - track.id else baseDislikes = baseDislikes - track.id
                }
            }
            if (token == credential) {
                applyCatalogLibraryOverlay(account.uid)
                saveRatings(account.uid)
            }
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
            val localEntries = if (catalogEnabled) catalogLibrary.likedTrackEntries(uid) else emptyList()
            val localIds = localEntries.mapTo(HashSet()) { it.first }
            val byId = tracks.associateBy { it.id }
            val localAdded = localEntries.mapNotNull { (id, date) ->
                val track = byId[id] ?: versions.synthetic(id)
                track?.copy(addedAt = date)
            }
            val pendingAdded = pending.entries.filter { it.value > 0 && it.key !in localIds }.sortedByDescending { it.value }.mapNotNull { (id, date) ->
                val track = byId[id] ?: versions.synthetic(id)?.takeIf { allowsAlternate(uid, id) }
                track?.copy(addedAt = date)
            }
            val filtered = tracks.filterNot {
                it.id in pending || it.id in localIds || (!catalogEnabled && versions.isSyntheticTrackId(it.id))
            }
            return localAdded + pendingAdded + filtered
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

    suspend fun dislikedTracksList(): List<Track> {
        val uid = requireUser().uid
        val localIds = if (catalogEnabled) catalogLibrary.dislikedTracks(uid) else emptyList()
        val remote = cachedTracks("dislikes") {
            online { tracks(libraryIds(uid, dislike = true)) }
        }
        if (!catalogEnabled) return remote.filterNot { versions.isSyntheticTrackId(it.id) }
        val byId = remote.associateBy { it.id }
        val local = localIds.mapNotNull { id -> byId[id] ?: versions.synthetic(id) }
        return (local + remote).distinctBy { it.id }
    }

    suspend fun save(card: Card) = updates.withLock {
        val account = requireUser()
        val add = card.key !in _savedCards.value
        when {
            card.kind == Kind.ALBUM && versions.isSyntheticAlbumId(card.id) -> {
                catalogLibrary.mutate(account.uid) { it.setAlbumLiked(card.id, add) }
                applyCatalogLibraryOverlay(account.uid)
                return@withLock add
            }
            card.kind == Kind.ARTIST && versions.isSyntheticArtistId(card.id) -> {
                catalogLibrary.mutate(account.uid) { it.setArtistLiked(card.id, add) }
                applyCatalogLibraryOverlay(account.uid)
                return@withLock add
            }
            card.kind == Kind.PLAYLIST && versions.isSyntheticPlaylistId(card.id, card.owner) -> {
                catalogLibrary.mutate(account.uid) { it.setPlaylistLiked(card.id, add) }
                applyCatalogLibraryOverlay(account.uid)
                return@withLock add
            }
        }
        val credential = token
        online { setCardLiked(account.uid, card, add) }
        if (token == credential) {
            baseSavedCards = if (add) baseSavedCards + card.key else baseSavedCards - card.key
            if (card.kind == Kind.PLAYLIST) {
                baseSavedPlaylists = if (add) listOf(card) + baseSavedPlaylists else baseSavedPlaylists.filterNot { it.key == card.key }
                savePlaylists(account.uid)
            }
            applyCatalogLibraryOverlay(account.uid)
            saveRatings(account.uid)
        }
        add
    }

    suspend fun hideArtist(id: String, hide: Boolean) = updates.withLock {
        val account = requireUser()
        if (versions.isSyntheticArtistId(id)) {
            catalogLibrary.mutate(account.uid) { it.setArtistDisliked(id, hide) }
            applyCatalogLibraryOverlay(account.uid)
            return@withLock
        }
        online { setArtistHidden(account.uid, id, hide) }
        if (hide) {
            val key = Card(Kind.ARTIST, id, "").key
            if (key in baseSavedCards) {
                baseSavedCards = baseSavedCards - key
                saveRatings(account.uid)
                applyCatalogLibraryOverlay(account.uid)
            }
        }
    }

    suspend fun editPlaylistTracks(card: Card, tracks: List<Track>, remove: Boolean = false) {
        val uid = requireUser().uid
        val (synthetic, native) = tracks.partition { versions.isSyntheticTrackId(it.id) }
        if (native.isNotEmpty()) {
            online { editPlaylist(card, native, remove) }
        }
        if (synthetic.isNotEmpty() && card.owner != null) {
            val key = "${card.owner}:${card.id}"
            catalogLibrary.mutate(uid) { data ->
                if (remove) data.removePlaylistTracks(key, synthetic.map { it.id }.toSet())
                else data.addPlaylistTracks(key, synthetic.map { it.id })
            }
        }
        if (native.isNotEmpty()) {
            val delta = if (remove) -native.size else native.size
            val idx = baseOwnPlaylists.indexOfFirst { it.key == card.key }
            if (idx >= 0) {
                val updated = baseOwnPlaylists.toMutableList()
                updated[idx] = updated[idx].copy(trackCount = (updated[idx].trackCount + delta).coerceAtLeast(0))
                baseOwnPlaylists = updated
                savePlaylists(uid)
            }
        }
        applyCatalogLibraryOverlay(uid)
    }

    suspend fun deleteOwnedPlaylist(card: Card) {
        val uid = requireUser().uid
        online { deletePlaylist(card) }
        changeLocalPlaylist(card, remove = true)
    }

    private fun saveRatings(uid: String) {
        preferences.edit().putStringSet("likes:$uid", baseLikes).putStringSet("dislikes:$uid", baseDislikes)
            .putStringSet("cards:$uid", baseSavedCards).apply()
    }

    fun changeLocalPlaylist(card: Card, remove: Boolean = false) {
        val uid = _profile.value?.uid ?: return
        if (card.owner != uid) return
        val index = baseOwnPlaylists.indexOfFirst { it.key == card.key }
        val localCount = catalogLibrary.playlistTracks(uid, uid, card.id).size
        val baseCard = if (catalogEnabled && localCount > 0) {
            card.copy(trackCount = (card.trackCount - localCount).coerceAtLeast(0))
        } else card
        baseOwnPlaylists = baseOwnPlaylists.toMutableList().apply {
            when {
                remove && index >= 0 -> removeAt(index)
                !remove && index >= 0 -> set(index, baseCard)
                !remove -> add(0, baseCard)
            }
        }
        if (remove) catalogLibrary.mutate(uid) { it.clearPlaylist("${card.owner}:${card.id}") }
        savePlaylists(uid)
        applyCatalogLibraryOverlay(uid)
    }

    private fun loadSavedPlaylists(uid: String) {
        fun read(key: String): List<Card> = optional {
            JSONArray(preferences.getString("$key:$uid", "[]")).objects().mapNotNull(Card::fromCache)
        }.orEmpty()
        baseOwnPlaylists = read("ownPlaylists").filterNot { TuneSync.isSyncPlaylist(it.title, null) }
        baseSavedPlaylists = read("savedPlaylists").filterNot {
            it.owner == AlternateVersions.CATALOG_OWNER || TuneSync.isSyncPlaylist(it.title, null)
        }
    }

    private fun savePlaylists(uid: String) {
        preferences.edit().putString("ownPlaylists:$uid", JSONArray(baseOwnPlaylists.map(Card::cached)).toString())
            .putString("savedPlaylists:$uid", JSONArray(baseSavedPlaylists.map(Card::cached)).toString()).apply()
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
                        val local = if (catalogEnabled) catalogLibrary.likedTracks(user.uid) else emptyList()
                        val ids = (local + applyRestoredLikes(libraryIds(user.uid).toSet(), pendingRestoredLikes(user.uid))).distinct()
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

    private fun trackFiltersFile(uid: String, place: String) =
        AtomicFile(File(context.cacheDir, "filters-${digest("$uid:$place")}.json"))

    fun cachedTrackFilters(place: String): List<TrackFilter>? {
        val uid = profile.value?.uid ?: return null
        val key = "$uid:$place"
        trackFiltersMemory[key]?.let { return it }
        val file = trackFiltersFile(uid, place)
        val loaded = optional {
            JSONArray(file.readFully().toString(Charsets.UTF_8)).objects().mapNotNull { entry ->
                val id = entry.text("id") ?: return@mapNotNull null
                val name = entry.text("name") ?: return@mapNotNull null
                val ids = entry.arr("trackIds").items().mapNotNull(Any?::asId).toSet()
                TrackFilter(id, name, ids)
            }
        } ?: return null
        trackFiltersMemory[key] = loaded
        return loaded
    }

    fun saveTrackFilters(place: String, filters: List<TrackFilter>) {
        val uid = profile.value?.uid ?: return
        trackFiltersMemory["$uid:$place"] = filters
        val encoded = JSONArray(filters.map { filter ->
            JSONObject().put("id", filter.id).put("name", filter.name).put("trackIds", JSONArray(filter.trackIds.toList()))
        }).toString()
        val file = trackFiltersFile(uid, place)
        scope.launch(Dispatchers.IO) { optional { file.writeText(encoded) } }
    }

    suspend fun collection(kind: Kind, onCached: ((List<Card>) -> Unit)? = null): List<Card> {
        val uid = requireUser().uid
        fun withLocal(cards: List<Card>): List<Card> {
            val nonSynthetic = cards.filterNot { card ->
                (kind == Kind.ALBUM && versions.isSyntheticAlbumId(card.id)) ||
                    (kind == Kind.ARTIST && versions.isSyntheticArtistId(card.id))
            }
            if (!catalogEnabled) return nonSynthetic
            val local = when (kind) {
                Kind.ALBUM -> catalogLibrary.likedAlbums(uid).mapNotNull { id -> versions.catalogAlbum(id)?.let { Card.album(it) } }
                Kind.ARTIST -> catalogLibrary.likedArtists(uid).mapNotNull { id -> versions.catalogArtist(id)?.first?.let(Card::artist) }
                else -> emptyList()
            }
            return (local + nonSynthetic).distinctBy { it.key }
        }
        return withLocal(cached("collection:${kind.name}", { JSONObject().put("cards", JSONArray(it.map(Card::cached))) },
            { it.arr("cards").objects().mapNotNull(Card::fromCache) },
            onCached?.let { display -> { cachedCards -> display(withLocal(cachedCards)) } }) {
            online { likedCards(uid, kind) }
        })
    }

    suspend fun topArtists(onCached: ((List<Card>) -> Unit)? = null): List<Card> {
        requireUser()
        return cached("collection:top-artists", { JSONObject().put("cards", JSONArray(it.map(Card::cached))) },
            { it.arr("cards").objects().mapNotNull(Card::fromCache) }, onCached) { online { topArtists() } }
    }

    private fun artistCoverFile() = AtomicFile(File(context.cacheDir, "artist-covers.json"))

    private suspend fun ensureArtistCoverCache() {
        if (artistCoverCacheLoaded) return
        val loaded = readCache(artistCoverFile()) { raw -> JSONObject(raw) }
        if (loaded != null) {
            for (key in loaded.keys()) {
                val value = loaded.optString(key)
                if (value.isNotBlank()) artistCoverCache.putIfAbsent(key, value)
            }
        }
        artistCoverCacheLoaded = true
    }

    suspend fun cachedArtistCovers(ids: List<String>): Map<String, String> {
        ensureArtistCoverCache()
        return ids.mapNotNull { id -> artistCoverCache[id]?.let { id to it } }.toMap()
    }

    suspend fun loadMissingArtistCovers(ids: List<String>): Map<String, String> {
        ensureArtistCoverCache()
        val missing = ids.filter { it.isNotBlank() && artistCoverCache[it] == null }.distinct()
        if (missing.isEmpty() || offline) return emptyMap()
        val fetched = coroutineScope {
            missing.map { id ->
                async {
                    val cover = optional { online { artistCover(id) } }
                    if (!cover.isNullOrBlank()) id to cover else null
                }
            }.awaitAll().filterNotNull().toMap()
        }
        if (fetched.isNotEmpty()) {
            artistCoverCache.putAll(fetched)
            val snapshot = JSONObject(artistCoverCache.toMap())
            withContext(Dispatchers.IO) { optional { artistCoverFile().writeText(snapshot.toString()) } }
        }
        return fetched
    }

    suspend fun cachedSearch(query: String, type: String): SearchResults? {
        val key = SearchCache.Key(requireUser().uid, query, type, alternateEnabled, catalogEnabled, versions.catalogSignature())
        return withContext(Dispatchers.IO) { searchCache.read(key) }
    }

    suspend fun searchPage(query: String, type: String, page: Int): SearchResults {
        val key = SearchCache.Key(requireUser().uid, query, type, alternateEnabled, catalogEnabled, versions.catalogSignature())
        return online {
            search(query, type, page).also { results ->
                if (page == 0 && type != "lyrics" && profile.value?.uid == key.account && alternateEnabled == key.uncensored &&
                    catalogEnabled == key.catalog && versions.catalogSignature() == key.catalogVersion &&
                    (!key.uncensored || versions.hasRestoredIndex())) {
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
