package music.tune.android

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.SystemClock
import android.util.AtomicFile
import androidx.core.content.ContextCompat
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

internal data class DownloadStatus(val track: Track, val percent: Int? = null, val error: String? = null)

internal enum class DownloadWait(val label: String) {
    WIFI("Ждём Wi‑Fi"),
    INTERNET("Ждём интернет"),
}

internal enum class AudioEdition { ORIGINAL, ALTERNATE, LOCAL }

internal fun matchingDownload(requested: AudioEdition, saved: AudioEdition?, automatic: Boolean, offline: Boolean): Boolean {
    if (requested == saved || (automatic && offline)) return true
    return requested == AudioEdition.ORIGINAL && saved == null
}

internal class MusicFiles(private val store: MusicStore) {
    private val context get() = store.context
    private val _status = MutableStateFlow<Map<String, DownloadStatus>>(emptyMap())
    val status = _status.asStateFlow()
    private val _revision = MutableStateFlow(0)
    val revision = _revision.asStateFlow()
    private val _paused = MutableStateFlow(false)
    val paused = _paused.asStateFlow()
    private val _networkWait = MutableStateFlow<DownloadWait?>(null)
    val networkWait = _networkWait.asStateFlow()
    private var job: Job? = null
    private var user: String? = null
    private val pending = linkedMapOf<String, Track>()
    private val downloadAssets = Semaphore(2)
    private val activeAssets = mutableSetOf<Pair<String, String>>()
    private val streamCache = ConcurrentHashMap<String, Pair<Long, Stream>>()
    @Volatile private var coverIndexUid: String? = null
    @Volatile private var coverFolder: File? = null
    private val savedCoverNames = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var coverIndexReady = false

    private fun accountFolder(uid: String) = File(context.filesDir, "music/${digest(uid)}")
    private fun directory(uid: String) = accountFolder(uid).apply { mkdirs() }
    private fun streamDirectory(uid: String) = File(context.cacheDir, "audio/${digest(uid)}").apply { mkdirs() }

    private fun existing(directory: File, key: String): File? =
        EXTENSIONS.firstNotNullOfOrNull { ext -> File(directory, "$key.$ext").takeIf(File::isFile) }

    private fun key(id: String, variant: String = "") = digest("$id:$variant")
    private fun local(uid: String, id: String) = existing(directory(uid), key(id, "local"))
    private fun downloaded(uid: String, id: String) = existing(directory(uid), key(id))

    private fun artwork(uid: String, cover: String) = File(accountFolder(uid), "covers/${digest(cover)}.jpg")
    private fun words(uid: String, trackId: String) = AtomicFile(File(accountFolder(uid), "lyrics/${digest(trackId)}.json"))

    fun savedCover(cover: String): File? {
        val uid = store.profile.value?.uid ?: return null
        if (coverIndexUid == uid && coverIndexReady) {
            if (savedCoverNames.isEmpty()) return null
            val name = "${digest(cover)}.jpg"
            return if (name in savedCoverNames) coverFolder?.let { File(it, name) } else null
        }
        return artwork(uid, cover).takeIf(File::isFile)
    }

    fun savedLyrics(uid: String, trackId: String): AtomicFile? = words(uid, trackId).takeIf { it.baseFile.exists() }

    fun saveDownloadLyrics(uid: String, trackId: String, data: String) = synchronized(this) {
        if (downloaded(uid, trackId) != null) words(uid, trackId).writeText(data)
    }

    private fun pendingAssets(uid: String): Set<String> =
        store.preferences.getStringSet("downloadAssets:$uid", emptySet()).orEmpty()

    private fun prepareDownloadAssets(uid: String, track: Track) {
        val key = uid to track.id
        if (track.id !in pendingAssets(uid) || !activeAssets.add(key)) return
        store.scope.launch {
            try {
                downloadAssets.withPermit {
                    if (store.profile.value?.uid != uid || store.offline || !has(track) || downloadConnection().wait != null) return@withPermit
                    val covered = optional { saveDownloadCover(uid, track) } == true
                    if (store.profile.value?.uid != uid || store.offline || downloadConnection().wait != null) return@withPermit
                    val lyricsChecked = optional { store.lyrics(track) } != null
                    if (covered && lyricsChecked) synchronized(this@MusicFiles) {
                        store.preferences.edit().putStringSet("downloadAssets:$uid", pendingAssets(uid) - track.id).apply()
                    }
                }
            } finally {
                activeAssets.remove(key)
            }
        }
    }

    private fun resumeDownloadAssets() {
        val uid = user ?: return
        if (store.offline) return
        val remaining = pendingAssets(uid)
        if (remaining.isEmpty()) return
        index(uid).filter { it.id in remaining }.forEach { prepareDownloadAssets(uid, it) }
    }

    private suspend fun saveDownloadCover(uid: String, track: Track): Boolean = withContext(Dispatchers.IO) {
        val cover = track.cover ?: return@withContext true
        val url = coverUrl(cover, 800) ?: return@withContext true
        val file = artwork(uid, cover)
        if (file.isFile) return@withContext true
        val request = ImageRequest.Builder(context).data(url).size(800).allowHardware(false).build()
        val result = context.imageLoader.execute(request) as? SuccessResult ?: return@withContext false
        val bitmap = (result.drawable as? BitmapDrawable)?.bitmap ?: return@withContext false
        val bytes = ByteArrayOutputStream()
        if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 92, bytes)) return@withContext false
        synchronized(this@MusicFiles) {
            if (file.isFile || downloaded(uid, track.id) == null) return@synchronized
            val output = AtomicFile(file)
            val stream = output.startWrite()
            try {
                bytes.writeTo(stream)
                output.finishWrite(stream)
                if (coverIndexUid == uid) savedCoverNames.add(file.name)
            } catch (error: Exception) {
                output.failWrite(stream)
                throw error
            }
        }
        true
    }

    fun hasId(id: String): Boolean = store.profile.value?.uid?.let { downloaded(it, id) != null } == true
    fun has(track: Track): Boolean = hasId(track.id)
    fun hasReplacement(track: Track): Boolean = store.profile.value?.uid?.let { local(it, track.id) != null } == true
    fun selectedVersion(uid: String, id: String) = store.preferences.getString("edition:${digest("$uid:$id")}", "auto") ?: "auto"

    fun selectVersion(track: Track, value: String) {
        val uid = store.requireUser().uid
        val key = "edition:${digest("$uid:${track.id}")}"
        if (value == "auto") store.preferences.edit().remove(key).apply()
        else store.preferences.edit().putString(key, value).apply()
    }

    fun downloaded(): List<Track> {
        val uid = store.profile.value?.uid ?: return emptyList()
        return synchronized(this) {
            index(uid).mapNotNull { track -> downloaded(uid, track.id)?.let { track.copy(addedAt = it.lastModified().takeIf { time -> time > 0 }) } }
        }
    }

    suspend fun diskUsage(): List<Pair<Track, Long>> = withContext(Dispatchers.IO) {
        val uid = store.requireUser().uid
        val countedCovers = hashSetOf<String>()
        downloaded().mapNotNull { track ->
            downloaded(uid, track.id)?.let { file ->
                val coverSize = track.cover?.takeIf(countedCovers::add)?.let { artwork(uid, it).length() } ?: 0
                track to (file.length() + words(uid, track.id).baseFile.length() + coverSize)
            }
        }
    }

    private fun streamCacheFiles() = File(context.cacheDir, "audio").listFiles().orEmpty()
        .flatMap { dir -> dir.listFiles { file -> file.extension in EXTENSIONS }.orEmpty().toList() }

    suspend fun streamCacheSize() = withContext(Dispatchers.IO) { streamCacheFiles().sumOf(File::length) }

    suspend fun clearStreamCache() = withContext(Dispatchers.IO) {
        streamCache.clear()
        streamCacheFiles().forEach { it.delete() }
    }

    private fun downloadEntries(uid: String): List<JSONObject> = optional {
        JSONArray(store.preferences.getString("downloads:$uid", "[]")).objects()
    }.orEmpty()

    private fun index(uid: String) = downloadEntries(uid).mapNotNull { Track.parse(it) }

    fun updateUploadedMetadata(uid: String, id: String, title: String, artist: String) = synchronized(this) {
        val entries = downloadEntries(uid)
        val entry = entries.firstOrNull { it.text("id") == id } ?: return@synchronized
        val track = Track.parse(entry)?.withUploadedMetadata(title, artist) ?: return@synchronized
        entry.put("title", track.title).remove("version")
        entry.put("artists", track.stored().getJSONArray("artists"))
        store.preferences.edit().putString("downloads:$uid", JSONArray(entries).toString()).apply()
        _revision.value++
    }

    private fun downloadEdition(uid: String, id: String): AudioEdition? {
        val value = downloadEntries(uid).firstOrNull { it.text("id") == id }?.text("audioEdition")
        return AudioEdition.entries.firstOrNull { it.name == value }
    }

    private fun updateIndex(uid: String, track: Track, edition: AudioEdition?) = synchronized(this) {
        val others = downloadEntries(uid).filterNot { it.text("id") == track.id }
        val tracks = if (edition == null) others else listOf(track.stored().put("audioEdition", edition.name)) + others
        val assets = if (edition == null) pendingAssets(uid) - track.id else pendingAssets(uid) + track.id
        store.preferences.edit().putString("downloads:$uid", JSONArray(tracks).toString())
            .putStringSet("downloadAssets:$uid", assets).apply()
    }

    fun enqueue(tracks: List<Track>) {
        store.requireUser()
        if (store.offline) throw MusicError("Выключите офлайн-режим, чтобы скачать музыку")
        tracks.distinctBy { it.id }.filterNot { it.unavailable || has(it) }.forEach { track ->
            if (pending.putIfAbsent(track.id, track) == null) setStatus(track, null)
        }
        saveQueue()
        startQueue()
    }

    fun switchUser(uid: String?) {
        if (user == uid) return
        stopQueue()
        pending.clear()
        streamCache.clear()
        _status.value = emptyMap()
        user = uid
        coverIndexReady = false
        savedCoverNames.clear()
        coverIndexUid = uid
        val folder = uid?.let { File(accountFolder(it), "covers") }
        coverFolder = folder
        if (uid != null && folder != null) {
            store.scope.launch(Dispatchers.IO) {
                val names = folder.list()
                if (coverIndexUid == uid) {
                    if (names != null) savedCoverNames.addAll(names.asList())
                    coverIndexReady = true
                }
            }
        }
        _paused.value = uid != null && store.preferences.getBoolean("downloadsPaused:$uid", false)
        if (uid != null) {
            val queue = optional { JSONArray(store.preferences.getString("downloadsQueue:$uid", "[]")).objects() }.orEmpty()
            queue.forEach { row ->
                val track = Track.parse(row.obj("track")) ?: return@forEach
                if (downloaded(uid, track.id) == null) {
                    val error = row.text("error")
                    if (error == null) pending[track.id] = track
                    setStatus(track, null, error)
                }
            }
            startQueue()
        }
    }

    fun setPaused(value: Boolean) {
        _paused.value = value
        user?.let { store.preferences.edit().putBoolean("downloadsPaused:$it", value).apply() }
        if (value) stopQueue() else startQueue()
    }

    fun updateNetworkPolicy(restart: Boolean = false) {
        if (store.offline || restart) stopQueue()
        if (!store.offline) {
            startQueue()
            resumeDownloadAssets()
        }
    }

    fun resumeForegroundQueue() {
        resumeDownloadAssets()
        if (pending.isEmpty() || _paused.value || store.offline) return
        if (job?.isActive == true) startDownloadService() else startQueue()
    }

    private fun startDownloadService() {
        optional { ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java)) }
    }

    private fun startQueue() {
        val uid = user ?: return
        if (job?.isActive == true || pending.isEmpty() || _paused.value || store.offline) return
        startDownloadService()
        val previous = job
        job = store.scope.launch {
            previous?.join()
            while (isActive && user == uid && pending.isNotEmpty()) {
                val track = pending.values.first()
                if (downloaded(uid, track.id) != null) {
                    pending.remove(track.id)
                    _status.value = _status.value - track.id
                    saveQueue()
                    _revision.value++
                    prepareDownloadAssets(uid, track)
                    continue
                }
                val connection = downloadConnection()
                _networkWait.value = connection.wait
                if (connection.wait != null) {
                    delay(2_000)
                    continue
                }
                try {
                    setStatus(track, 0)
                    val quality = store.downloadQuality
                    val context = currentCoroutineContext()
                    withContext(Dispatchers.IO) {
                        val target = directory(uid)
                        val id = key(track.id)
                        val replacement = local(uid, track.id)
                        val alternatives = if (replacement == null) alternateSources(uid, track.id) else emptyList()
                        val edition = when {
                            replacement != null -> AudioEdition.LOCAL
                            alternatives.isNotEmpty() -> AudioEdition.ALTERNATE
                            else -> AudioEdition.ORIGINAL
                        }
                        if (replacement != null) {
                            install(target, id) { out -> replacement.inputStream().use { it.copyTo(out) } }
                        } else {
                            val stream = if (alternatives.isNotEmpty()) Stream(alternatives) else originalSource(uid, track.id, quality)
                            fetch(stream, target, id, connection.wifi) { percent ->
                                context.ensureActive()
                                setStatus(track, percent)
                            }
                        }
                        // Keep a complete file and its index entry even if cancellation races with the final move.
                        updateIndex(uid, track, edition)
                    }
                    _status.value = _status.value - track.id
                    _revision.value++
                    prepareDownloadAssets(uid, track)
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    val wait = if (error is IOException) downloadConnection().wait else null
                    if (wait != null) {
                        _networkWait.value = wait
                        setStatus(track, null)
                        continue
                    }
                    setStatus(track, null, error.message ?: "Не удалось скачать трек")
                }
                pending.remove(track.id)
                saveQueue()
            }
        }
    }

    private fun stopQueue() {
        job?.cancel()
        _networkWait.value = null
        _status.update { old -> old.mapValues { (_, state) -> if (state.percent == null) state else state.copy(percent = null) } }
    }

    fun cancelDownloads() {
        stopQueue()
        pending.clear()
        _status.value = emptyMap()
        _paused.value = false
        user?.let { store.preferences.edit().remove("downloadsPaused:$it").apply() }
        saveQueue()
    }

    fun pauseForSystemTimeout() {
        stopQueue()
        _paused.value = true
        user?.let { store.preferences.edit().putBoolean("downloadsPaused:$it", true).apply() }
    }

    private fun saveQueue() {
        val uid = user ?: return
        val failed = _status.value.values.filter { it.error != null }
        val queue = pending.values.map { DownloadStatus(it) } + failed
        store.preferences.edit().putString("downloadsQueue:$uid", JSONArray(queue.map { entry ->
            JSONObject().put("track", entry.track.stored()).put("error", entry.error)
        }).toString()).apply()
    }

    private fun setStatus(track: Track, percent: Int?, error: String? = null) {
        val value = DownloadStatus(track, percent, error)
        _status.update { it + (track.id to value) }
    }

    private data class DownloadConnection(val wifi: Network? = null, val wait: DownloadWait? = null)

    private fun downloadConnection(): DownloadConnection {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val active = manager.activeNetwork
        val capabilities = manager.getNetworkCapabilities(active)
        val hasInternet = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
            !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)
        if (store.wifiOnly) {
            val wifi = hasInternet && capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            return if (wifi) DownloadConnection(wifi = active) else DownloadConnection(wait = DownloadWait.WIFI)
        }
        return DownloadConnection(wait = if (hasInternet) null else DownloadWait.INTERNET)
    }

    suspend fun remove(track: Track) {
        val uid = store.requireUser().uid
        withContext(Dispatchers.IO) {
            synchronized(this@MusicFiles) {
                val cover = index(uid).firstOrNull { it.id == track.id }?.cover ?: track.cover
                downloaded(uid, track.id)?.let { if (!it.delete()) throw MusicError("Не удалось удалить загрузку") }
                updateIndex(uid, track, edition = null)
                words(uid, track.id).delete()
                if (cover != null && index(uid).none { it.cover == cover }) {
                    val artFile = artwork(uid, cover)
                    artFile.delete()
                    if (coverIndexUid == uid && !artFile.isFile) savedCoverNames.remove(artFile.name)
                }
            }
        }
        _revision.value++
    }

    suspend fun replace(track: Track, uri: Uri) {
        val uid = store.requireUser().uid
        withContext(Dispatchers.IO) {
            install(directory(uid), key(track.id, "local")) { out ->
                val input = context.contentResolver.openInputStream(uri) ?: throw MusicError("Не удалось открыть аудиофайл")
                input.use { it.copyTo(out) }
            }
        }
        _revision.value++
    }

    suspend fun removeReplacement(track: Track) {
        val uid = store.requireUser().uid
        withContext(Dispatchers.IO) {
            local(uid, track.id)?.let { if (!it.delete()) throw MusicError("Не удалось удалить подмену") }
        }
        _revision.value++
    }

    suspend fun export(track: Track, target: Uri) {
        val uid = store.requireUser().uid
        withContext(Dispatchers.IO) {
            val file = local(uid, track.id) ?: downloaded(uid, track.id) ?: throw MusicError("Сначала скачайте трек")
            val out = context.contentResolver.openOutputStream(target, "wt") ?: throw MusicError("Не удалось открыть файл")
            out.use { output -> file.inputStream().use { it.copyTo(output) } }
        }
    }

    fun extension(track: Track): String? {
        val uid = store.profile.value?.uid ?: return null
        return (local(uid, track.id) ?: downloaded(uid, track.id))?.extension
    }

    // Called by ExoPlayer on its loader thread, including when it preloads a new item.
    fun resolve(uid: String, track: String, forceFile: Boolean): Uri {
        if (store.profile.value?.uid != uid) throw MusicError("Войдите через Яндекс", 401)
        val savedPreference = selectedVersion(uid, track)
        val preference = if (savedPreference == "alternate" && !store.allowsAlternate(uid, track)) "auto" else savedPreference
        val offline = store.offline
        if (preference == "auto" || preference == "local") local(uid, track)?.let { return Uri.fromFile(it) }
        val alternatives = if (preference == "original") emptyList() else alternateSources(uid, track)
        val edition = if (preference == "alternate" || alternatives.isNotEmpty()) AudioEdition.ALTERNATE else AudioEdition.ORIGINAL
        downloaded(uid, track)?.takeIf {
            matchingDownload(edition, downloadEdition(uid, track),
                automatic = preference != "original" && preference != "alternate", offline = offline)
        }?.let { return Uri.fromFile(it) }
        val quality = store.streamQuality
        val directory = streamDirectory(uid)
        val id = key(track, if (edition == AudioEdition.ALTERNATE) "alternate" else quality)
        existing(directory, id)?.let {
            it.setLastModified(System.currentTimeMillis())
            return Uri.fromFile(it)
        }
        if (store.offline) throw MusicError("Включён офлайн-режим")
        if (edition == AudioEdition.ALTERNATE && alternatives.isEmpty()) throw MusicError("Версия без цензуры недоступна")
        val info = if (edition == AudioEdition.ALTERNATE) Stream(alternatives) else originalSource(uid, track, quality)
        if (!forceFile && edition == AudioEdition.ORIGINAL && info.key == null) {
            val address = info.urls.firstOrNull { it.startsWith("https://") } ?: throw MusicError("Неверная ссылка на трек")
            return Uri.parse(address)
        }
        val file = fetch(info, directory, id)
        trim(directory, file)
        return Uri.fromFile(file)
    }

    private fun alternateSources(uid: String, id: String): List<String> {
        if (!store.allowsAlternate(uid, id)) return emptyList()
        store.versions.ensureLoaded(network = !store.offline)
        return store.versions.audio(id)
    }

    private fun originalSource(uid: String, id: String, quality: String): Stream {
        if (store.profile.value?.uid != uid) throw MusicError("Войдите через Яндекс", 401)
        val cacheKey = "$uid:$id:$quality"
        val now = SystemClock.elapsedRealtime()
        streamCache[cacheKey]?.takeIf { now - it.first < 90_000L }?.second?.let { return it }
        val api = store.api()
        val resolved = try {
            api.file(id, quality, "raw")
        } catch (error: MusicError) {
            if (error.status == 0 || error.status in setOf(401, 429)) throw error
            api.file(id, quality, "encraw")
        }
        streamCache[cacheKey] = SystemClock.elapsedRealtime() to resolved
        return resolved
    }

    private fun fetch(source: Stream, directory: File, id: String, wifi: Network? = null, progress: (Int) -> Unit = {}): File {
        var last: IOException? = null
        for (address in source.urls) {
            try {
                val connection = secureConnection(address, wifi)
                try {
                    connection.readTimeout = 40_000
                    if (connection.responseCode !in 200..299) throw MusicError("Сервер не отдал аудиофайл")
                    val total = connection.contentLengthLong
                    if (total > MAX_FILE_SIZE) throw MusicError("Аудиофайл слишком большой")
                    val input = connection.inputStream
                    val audio = try { source.key?.let { CipherInputStream(input, decrypt(it)) } ?: input }
                    catch (error: Exception) { input.close(); throw error }
                    return audio.use {
                        install(directory, id) { output ->
                            val buffer = ByteArray(64 * 1024)
                            var read = 0L
                            while (true) {
                                val count = it.read(buffer)
                                if (count == -1) break
                                read += count
                                if (read > MAX_FILE_SIZE) throw MusicError("Аудиофайл слишком большой")
                                output.write(buffer, 0, count)
                                progress(if (total > 0) (100 * read / total).toInt().coerceIn(0, 99) else 0)
                            }
                            if (total > 0 && total != read) throw MusicError("Скачивание прервалось")
                        }
                    }
                } finally {
                    connection.disconnect()
                }
            } catch (error: IOException) {
                last = error
            }
        }
        throw last ?: MusicError("Не удалось получить файл трека")
    }

    private fun install(directory: File, id: String, write: (java.io.OutputStream) -> Unit): File {
        val temp = File(directory, ".${UUID.randomUUID()}.part")
        try {
            temp.outputStream().buffered().use(write)
            val ext = temp.inputStream().use { audioExtension(it.readNBytesCompat(12)) }
                ?: throw MusicError("Формат аудиофайла не поддерживается")
            val target = File(directory, "$id.$ext")
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            EXTENSIONS.filterNot { it == ext }.forEach { File(directory, "$id.$it").delete() }
            return target
        } finally {
            temp.delete()
        }
    }

    private fun trim(directory: File, current: File) {
        val files = directory.listFiles { file -> file.extension in EXTENSIONS }.orEmpty()
        var total = files.sumOf(File::length)
        for (file in files.sortedBy(File::lastModified)) {
            if (total <= 400L * 1024 * 1024) break
            if (file == current) continue
            val bytes = file.length()
            if (file.delete()) total -= bytes
        }
    }

    private companion object {
        val EXTENSIONS = listOf("mp3", "m4a", "flac", "aac", "ogg", "wav")
        const val MAX_FILE_SIZE = 300L * 1024 * 1024
    }
}

private fun java.io.InputStream.readNBytesCompat(size: Int): ByteArray {
    val bytes = ByteArray(size)
    var offset = 0
    while (offset < size) {
        val read = read(bytes, offset, size - offset)
        if (read < 0) break
        offset += read
    }
    return bytes.copyOf(offset)
}

internal fun audioExtension(head: ByteArray): String? {
    fun tag(position: Int, text: String) = head.size >= position + text.length &&
        text.indices.all { head[position + it].toInt() == text[it].code }
    if (head.size < 4) return null
    val b0 = head[0].toInt() and 0xff
    val b1 = head[1].toInt() and 0xff
    return when {
        tag(0, "fLaC") -> "flac"
        tag(4, "ftyp") -> "m4a"
        tag(0, "ID3") -> "mp3"
        tag(0, "OggS") -> "ogg"
        tag(0, "RIFF") && tag(8, "WAVE") -> "wav"
        b0 == 0xff && b1 and 0xf6 == 0xf0 -> "aac"
        b0 == 0xff && b1 and 0xe0 == 0xe0 -> "mp3"
        else -> null
    }
}

internal fun decrypt(key: String): Cipher {
    if (key.length !in setOf(32, 48, 64) || !key.all { it.digitToIntOrNull(16) != null }) {
        throw MusicError("Яндекс прислал неподдерживаемый ключ аудиофайла")
    }
    val bytes = key.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    return Cipher.getInstance("AES/CTR/NoPadding").apply {
        init(Cipher.DECRYPT_MODE, SecretKeySpec(bytes, "AES"), IvParameterSpec(ByteArray(16)))
    }
}
