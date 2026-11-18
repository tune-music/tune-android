package music.tune.android

import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

internal class UserUploads(private val store: MusicStore) {
    private val resolver get() = store.context.contentResolver
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).callTimeout(10, TimeUnit.MINUTES).build()
    private val pendingLock = Mutex()
    private var worker: Job? = null
    private var workerUid: String? = null
    private val uploadLock = Mutex()

    suspend fun upload(uris: List<Uri>, destination: Card?) = uploadLock.withLock {
        if (uris.isEmpty()) return@withLock
        val user = store.requireUser()
        val api = store.api()
        val playlist = destination?.takeIf { it.owner == user.uid }
            ?: store.ownPlaylists.value.firstOrNull { it.title == "Загруженное" }
            ?: withContext(Dispatchers.IO) { api.playlists(user.uid).firstOrNull { it.title == "Загруженное" } }
            ?: withContext(Dispatchers.IO) { api.createPlaylist(user.uid, "Загруженное") }.also { store.changeLocalPlaylist(it) }
        var uploaded = 0
        var reason: String? = null
        for ((index, uri) in uris.withIndex()) {
            if (store.profile.value?.uid != user.uid) break
            store.message("Загружаем ${index + 1} из ${uris.size}")
            try {
                val id = uploadFile(api, playlist, uri)
                if (destination == null) pendingLock.withLock {
                    write(user.uid, pending(user.uid) + id)
                }
                uploaded++
            } catch (error: java.io.IOException) {
                reason = error.message
                if ((error as? MusicError)?.status == 401 || reason == "Превышен лимит загруженных треков") break
            }
        }
        if (destination == null) resume()
        if (uploaded != uris.size) store.message("Загружено $uploaded из ${uris.size}. ${reason.orEmpty()}")
        else store.message("Яндекс обрабатывает загруженные треки")
    }

    private suspend fun uploadFile(api: MusicApi, destination: Card, uri: Uri): String = withContext(Dispatchers.IO) {
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }?.replace(Regex("[\r\n]"), "")?.take(200) ?: "track"
        val type = resolver.getType(uri)?.takeIf { it.startsWith("audio/") } ?: "application/octet-stream"
        val file = File.createTempFile("tune-upload-", ".audio", store.context.cacheDir)
        try {
            val context = currentCoroutineContext()
            val source = resolver.openInputStream(uri) ?: throw MusicError("Не удалось открыть аудиофайл")
            source.use { input ->
                file.outputStream().buffered().use { output ->
                    val buffer = ByteArray(128 * 1024)
                    var total = 0L
                    while (true) {
                        context.ensureActive()
                        val count = input.read(buffer)
                        if (count == -1) break
                        total += count
                        if (total > 300L * 1024 * 1024) throw MusicError("Аудиофайл слишком большой")
                        output.write(buffer, 0, count)
                    }
                }
            }
            if (file.length() == 0L) throw MusicError("Аудиофайл пуст")
            val (target, id) = api.uploadTarget(destination, name)
            val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("file", name, file.asRequestBody(type.toMediaTypeOrNull())).build()
            client.newCall(Request.Builder().url(target).post(body).build()).execute().use { result ->
                if (result.code == 413) throw MusicError("Аудиофайл слишком большой")
                if (!result.isSuccessful) throw MusicError("Яндекс не принял аудиофайл (${result.code})")
            }
            id
        } finally {
            file.delete()
        }
    }

    private fun pending(uid: String) = store.preferences.getStringSet("pendingUploads:$uid", emptySet()).orEmpty().toSet()
    private fun write(uid: String, ids: Set<String>) { store.preferences.edit().putStringSet("pendingUploads:$uid", ids).apply() }

    fun resume() {
        val uid = store.profile.value?.uid?.takeUnless { store.offline }
        if (uid == workerUid && worker?.isActive == true) return
        worker?.cancel()
        workerUid = uid
        if (uid == null || pending(uid).isEmpty()) return
        worker = store.scope.launch {
            repeat(25) {
                if (store.profile.value?.uid != uid || store.offline) return@launch
                val ids = pending(uid).take(100)
                if (ids.isEmpty()) return@launch
                val ready = optional { store.online { readyUploads(ids) } }.orEmpty()
                for (id in ready) {
                    if (store.profile.value?.uid != uid) return@launch
                    val success = optional {
                        store.online { setTrackRating(uid, id, disliked = false, add = true) }
                        true
                    } == true
                    if (success) pendingLock.withLock { write(uid, pending(uid) - id) }
                }
                if (ready.isNotEmpty() && pending(uid).isEmpty()) {
                    optional { store.refreshAccount() }
                    store.message("Загруженные треки появились в «Мне нравится»")
                    return@launch
                }
                delay(3_000)
            }
        }
    }
}
