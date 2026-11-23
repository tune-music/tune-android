package music.tune.android

import android.os.SystemClock
import androidx.media3.common.Player
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.random.Random

internal data class YnisonDevice(val id: String, val name: String, val kind: String)
internal data class YnisonSong(val id: String, val album: String?, val title: String, val cover: String?)
internal data class YnisonPlayback(
    val songs: List<YnisonSong> = emptyList(),
    val index: Int = -1,
    val entity: String = "VARIOUS",
    val item: String = "",
    val duration: Long = 0L,
    val position: Long = 0L,
    val paused: Boolean = true,
    val speed: Float = 1f,
) {
    val current get() = songs.getOrNull(index)

    fun predicted(elapsed: Long): Long {
        val advance = if (paused) 0L else (elapsed.coerceAtLeast(0) * speed).toLong()
        return (position + advance).let { if (duration > 0) it.coerceAtMost(duration) else it }
    }

    fun sameQueue(other: YnisonPlayback) = index == other.index && entity == other.entity && item == other.item &&
        songs.size == other.songs.size && songs.indices.all { index ->
            val a = songs[index]
            val b = other.songs[index]
            a.id == b.id && a.album == b.album
        }
}

internal data class YnisonState(
    val devices: List<YnisonDevice> = emptyList(),
    val active: String? = null,
    val playback: YnisonPlayback = YnisonPlayback(),
    val queueBy: String? = null,
    val statusBy: String? = null,
    val queueVersion: String? = null,
    val statusVersion: String? = null,
)

internal object YnisonProtocol {
    const val ORIGIN = "https://music.yandex.ru"
    const val ROUTER = "ynison.music.yandex.ru"
    const val REDIRECT = "/redirector.YnisonRedirectService/GetRedirectToYnison"
    const val HUB = "/ynison_state.YnisonStateService/PutYnisonState"
    private const val HEX_DIGITS = "0123456789ABCDEF"

    private fun encodeURIComponent(text: String): String = buildString {
        for (byte in text.toByteArray(Charsets.UTF_8)) {
            val code = byte.toInt() and 0xFF
            val ch = code.toChar()
            if (ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch in "-_.!~*'()") {
                append(ch)
            } else {
                append('%')
                append(HEX_DIGITS[code ushr 4])
                append(HEX_DIGITS[code and 0x0F])
            }
        }
    }

    fun url(host: String, path: String): String {
        val normalized = host.lowercase(java.util.Locale.ROOT)
        if (!normalized.matches(Regex("[a-z0-9.-]+")) ||
            !(normalized == "yandex.ru" || normalized.endsWith(".yandex.ru") || normalized.endsWith(".yandex.net"))) {
            throw MusicError("Ynison прислал неизвестный адрес")
        }
        return "wss://$normalized$path"
    }

    fun protocols(token: String, id: String, extra: JSONObject): String {
        val info = JSONObject().put("app_name", "Desktop").put("app_version", "5.120.0").put("type", 1)
        val params = extra.put("Ynison-Device-Id", id)
            .put("Ynison-Device-Info", info.toString().replace("\\/", "/"))
            .put("authorization", "OAuth $token")
        val value = encodeURIComponent(params.toString().replace("\\/", "/"))
        return "Bearer, v2, $value"
    }

    private fun version(id: String) = JSONObject().put("device_id", id)
        .put("version", Random.nextLong(1, Long.MAX_VALUE / 4)).put("timestamp_ms", System.currentTimeMillis())

    fun device(id: String) = JSONObject().put("volume", 1.0)
        .put("volume_info", JSONObject().put("volume", 1.0).put("version", JSONObject.NULL))
        .put("capabilities", JSONObject().put("can_be_player", true).put("can_be_remote_controller", false).put("volume_granularity", 20))
        .put("info", JSONObject().put("app_name", "Desktop").put("app_version", "5.120.0")
            .put("title", "Tune на Android").put("device_id", id).put("type", "WEB_DESKTOP"))
        .put("is_shadow", false)

    fun status(playback: YnisonPlayback, id: String) = JSONObject().put("duration_ms", playback.duration)
        .put("paused", playback.paused).put("playback_speed", playback.speed.toDouble())
        .put("progress_ms", playback.position).put("version", version(id))

    fun player(playback: YnisonPlayback, id: String): JSONObject {
        val songs = playback.songs.map { song ->
            JSONObject().put("album_id_optional", song.album ?: JSONObject.NULL).put("from", MusicApi.FROM)
                .put("playable_id", song.id).put("playable_type", "TRACK").put("title", song.title)
                .put("cover_url_optional", song.cover ?: JSONObject.NULL).put("navigation_id_optional", JSONObject.NULL)
                .put("playback_action_id_optional", JSONObject.NULL)
        }
        val queue = JSONObject().put("current_playable_index", playback.index)
            .put("entity_id", playback.item).put("entity_type", playback.entity).put("playable_list", JSONArray(songs))
            .put("options", JSONObject().put("repeat_mode", "NONE")).put("shuffle_optional", JSONObject.NULL)
            .put("entity_context", "BASED_ON_ENTITY_BY_DEFAULT").put("version", version(id))
            .put("from_optional", MusicApi.FROM).put("initial_entity_optional", JSONObject.NULL)
            .put("adding_options_optional", JSONObject.NULL).put("queue", JSONObject.NULL)
        return JSONObject().put("player_queue", queue).put("status", status(playback, id))
            .put("player_queue_inject_optional", JSONObject.NULL)
    }

    fun message(type: String, fields: JSONObject, intercept: Boolean) = JSONObject().put(type, fields)
        .put("rid", UUID.randomUUID().toString()).put("player_action_timestamp_ms", System.currentTimeMillis())
        .put("activity_interception_type", if (intercept) "INTERCEPT_EAGER" else "DO_NOT_INTERCEPT_BY_DEFAULT")

    fun state(value: JSONObject): YnisonState? {
        if (!value.has("devices") && !value.has("player_state")) return null
        val devices = value.arr("devices").objects().mapNotNull {
            if (it.optBoolean("is_shadow") || !it.obj("capabilities").optBoolean("can_be_player", true)) return@mapNotNull null
            val info = it.obj("info")
            val id = info.text("device_id") ?: return@mapNotNull null
            YnisonDevice(id, info.text("title") ?: "Устройство", info.text("type").orEmpty())
        }.distinctBy(YnisonDevice::id)
        val player = value.obj("player_state")
        val queue = player.obj("player_queue")
        val status = player.obj("status")
        val list = queue.arr("playable_list")
        val original = queue.optInt("current_playable_index", -1)
        val start = (original - 100).coerceIn(0, list.length())
        val songs = (start until minOf(start + 500, list.length())).map { index ->
            val song = list.optJSONObject(index) ?: JSONObject()
            YnisonSong(song.text("playable_id").orEmpty(), song.text("album_id_optional"),
                song.text("title").orEmpty(), song.text("cover_url_optional"))
        }
        val playback = YnisonPlayback(songs, (original - start).takeIf { it in songs.indices } ?: -1,
            queue.text("entity_type") ?: "VARIOUS", queue.text("entity_id").orEmpty(),
            duration = status.optLong("duration_ms").coerceIn(0L, 1_000_000_000_000L),
            position = status.optLong("progress_ms").coerceIn(0L, 1_000_000_000_000L),
            paused = status.optBoolean("paused", true),
            speed = status.optDouble("playback_speed", 1.0).takeIf(Double::isFinite)?.toFloat()?.coerceIn(.1f, 10f) ?: 1f)
        val queueVersion = queue.obj("version")
        val statusVersion = status.obj("version")
        return YnisonState(devices, value.text("active_device_id_optional"), playback, queueVersion.text("device_id"),
            statusVersion.text("device_id"), queueVersion.text("version"), statusVersion.text("version"))
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class Ynison(private val store: MusicStore) {
    private val _state = MutableStateFlow(YnisonState())
    val state = _state.asStateFlow()
    private val _connection = MutableStateFlow("Выключена")
    val connection = _connection.asStateFlow()
    private val _detail = MutableStateFlow<String?>(null)
    val detail = _detail.asStateFlow()
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .pingInterval(25, TimeUnit.SECONDS).build()
    private var runner: Job? = null
    private var applying: Job? = null
    private var socket: WebSocket? = null
    private var bound: Player? = null
    private var uid: String? = null
    private var sent: Pair<YnisonPlayback, Long>? = null
    private var received = 0L
    private var busy = false
    private var generation = 0
    private var snapshotTimeline: androidx.media3.common.Timeline? = null
    private var snapshotStart = -1
    private var snapshotTracks = emptyList<YnisonSong>()
    val enabled get() = store.preferences.getBoolean("ynisonEnabled", false)
    val deviceId: String
        get() = store.preferences.getString("ynisonDeviceId", null) ?: UUID.randomUUID().toString().also {
            store.preferences.edit().putString("ynisonDeviceId", it).apply()
        }

    fun bind(player: Player?, account: String?) {
        if (bound === player && uid == account) return
        bound = player
        uid = account
        snapshotTimeline = null
        snapshotTracks = emptyList()
        reconnect()
    }

    fun enable(value: Boolean) {
        store.preferences.edit().putBoolean("ynisonEnabled", value).apply()
        reconnect()
    }

    fun ensureConnected() {
        if (enabled && runner?.isActive != true) reconnect()
    }

    fun reconnect() {
        generation++
        runner?.cancel()
        applying?.cancel()
        applying = null
        socket?.cancel()
        socket = null
        busy = false
        _state.value = YnisonState()
        sent = null
        val token = store.ynisonToken()
        if (!enabled || uid == null || token == null || bound == null) {
            _connection.value = if (enabled) "Нет подключения" else "Выключена"
            _detail.value = when {
                !enabled -> null
                store.offline -> "Включён офлайн-режим"
                bound == null -> "Плеер ещё не подключён"
                uid == null -> "Аккаунт не загружен"
                token == null -> "Нет токена авторизации"
                else -> null
            }
            return
        }
        val turn = generation
        _connection.value = "Подключение…"
        _detail.value = null
        runner = store.scope.launch {
            var retry = 2L
            while (isActive && turn == generation) {
                try {
                    connect(token, turn)
                } catch (error: Exception) {
                    if (error is CancellationException && !isActive) throw error
                    if (turn != generation) break
                    val wasConnected = _connection.value == CONNECTED
                    retry = (error as? Retry)?.seconds ?: if (wasConnected) 2L else (retry * 2).coerceAtMost(60)
                    _connection.value = "Повторное подключение…"
                    _detail.value = (error as? Retry)?.reason ?: error.message ?: error::class.java.simpleName
                    delay(retry * 1_000)
                }
            }
        }
    }

    private class Retry(val seconds: Long?, val reason: String? = null) : Exception(reason)

    private class Socket(client: OkHttpClient, request: Request) : AutoCloseable {
        private val channel = Channel<String>(capacity = 16)
        val webSocket: WebSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) = enqueue(text, webSocket)
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (bytes.size <= 4 * 1024 * 1024) enqueue(bytes.utf8(), webSocket) else webSocket.cancel()
            }
            private fun enqueue(text: String, webSocket: WebSocket) {
                if (text.length > 4 * 1024 * 1024 || channel.trySend(text).isFailure) webSocket.cancel()
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val code = response?.code
                response?.close()
                val detail = listOfNotNull(code?.let { "HTTP $it" }, t.message?.takeIf(String::isNotBlank))
                    .joinToString(": ").ifBlank { t::class.java.simpleName }
                channel.close(Retry(null, detail))
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, null)
                val suffix = reason.takeIf(String::isNotBlank)?.let { ": $it" }.orEmpty()
                channel.close(Retry(null, "Закрыто ($code$suffix)"))
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                val suffix = reason.takeIf(String::isNotBlank)?.let { ": $it" }.orEmpty()
                channel.close(Retry(null, "Закрыто ($code$suffix)"))
            }
        })

        suspend fun receive(): JSONObject {
            val value = JSONObject(channel.receive())
            val error = value.optJSONObject("error")
            if (error != null) {
                val delay = error.obj("details").optLong("ynison-go-away-for-seconds").takeIf { it > 0 }?.coerceAtMost(3600)
                val code = error.text("http_code") ?: error.text("grpc_code")
                val msg = error.text("message") ?: "Ошибка Ynison"
                val reason = if (code != null) "$msg ($code)" else msg
                throw Retry(delay, reason)
            }
            return value
        }

        override fun close() {
            webSocket.cancel()
            channel.cancel()
        }
    }

    private fun open(host: String, path: String, token: String, extras: JSONObject = JSONObject()): Socket {
        val request = Request.Builder().url(YnisonProtocol.url(host, path))
            .header("Origin", YnisonProtocol.ORIGIN)
            .header("Sec-WebSocket-Protocol", YnisonProtocol.protocols(token, deviceId, extras)).build()
        return Socket(client, request)
    }

    private suspend fun connect(token: String, turn: Int) = coroutineScope {
        val address = open(YnisonProtocol.ROUTER, YnisonProtocol.REDIRECT, token).use { router ->
            withTimeout(20_000) { router.receive() }
        }
        val host = address.text("host") ?: throw Retry(null)
        val ticket = address.opt("redirect_ticket")?.takeUnless { it == JSONObject.NULL } ?: throw Retry(null)
        val session = address.text("session_id") ?: throw Retry(null)
        open(host, YnisonProtocol.HUB, token, JSONObject().put("Ynison-Redirect-Ticket", ticket).put("Ynison-Session-Id", session)).use { hub ->
            socket = hub.webSocket
            try {
                val local = snapshot()
                send("update_full_state", JSONObject().put("player_state", YnisonProtocol.player(local, deviceId))
                    .put("device", YnisonProtocol.device(deviceId)).put("is_currently_active", false)
                    .put("sync_state_from_eov_optional", JSONObject.NULL))
                sent = local to SystemClock.elapsedRealtime()
                read(withTimeout(20_000) { hub.receive() }, turn)
                val monitor = launch {
                    while (isActive) {
                        delay(1_000)
                        push()
                    }
                }
                try {
                    while (isActive) read(hub.receive(), turn)
                } finally {
                    monitor.cancel()
                }
            } finally {
                if (socket === hub.webSocket) socket = null
            }
        }
    }

    private fun send(command: String, data: JSONObject, intercept: Boolean = false) {
        if (socket?.send(YnisonProtocol.message(command, data, intercept).toString()) != true) throw Retry(null)
    }

    private fun snapshot(): YnisonPlayback {
        val player = bound ?: return YnisonPlayback()
        if (player.mediaItemCount == 0) return YnisonPlayback()
        val index = player.currentMediaItemIndex
        val start = (index - 100).coerceAtLeast(0)
        val timeline = player.currentTimeline
        if (snapshotTimeline !== timeline || snapshotStart != start) {
            snapshotTracks = (start until minOf(start + 500, player.mediaItemCount)).map { i ->
                val item = player.getMediaItemAt(i)
                val track = QueueItems.track(item)
                YnisonSong(item.mediaId, track?.album?.id, track?.title.orEmpty(),
                    track?.cover?.takeUnless { it.startsWith("file:") || it.startsWith("tune-cover:") })
            }
            snapshotTimeline = timeline
            snapshotStart = start
        }
        val playing = QueueItems.context(player.currentMediaItem)
        val entity = when (playing.kind) { "album" -> "ALBUM"; "artist" -> "ARTIST"; "playlist" -> "PLAYLIST"; "radio" -> "RADIO"; else -> "VARIOUS" }
        return YnisonPlayback(snapshotTracks, index - start, entity, playing.item.orEmpty(), player.duration.coerceAtLeast(0),
            player.currentPosition.coerceAtLeast(0), !player.playWhenReady, player.playbackParameters.speed)
    }

    private fun push() {
        val (previous, time) = sent ?: return
        if (busy || applying?.isActive == true || _connection.value != CONNECTED) return
        val local = snapshot()
        val same = local.sameQueue(previous)
        val moved = abs(local.position - previous.predicted(SystemClock.elapsedRealtime() - time)) > 3_000
        if (same && !moved && local.paused == previous.paused && local.speed == previous.speed) return
        sent = local to SystemClock.elapsedRealtime()
        val here = state.value.active == deviceId
        val startedHere = !local.paused && (previous.paused || !same)
        if (!here && !startedHere) return
        if (!same || !here) send("update_player_state", JSONObject().put("player_state", YnisonProtocol.player(local, deviceId)), !here)
        else send("update_playing_status", JSONObject().put("playing_status", YnisonProtocol.status(local, deviceId)))
    }

    private fun read(value: JSONObject, turn: Int) {
        if (turn != generation) return
        val incoming = YnisonProtocol.state(value) ?: return
        val player = bound ?: return
        _connection.value = CONNECTED
        _detail.value = null
        val previous = state.value
        if (incoming == previous) return
        received = SystemClock.elapsedRealtime()
        _state.value = incoming
        if (incoming.active != deviceId) {
            applying?.cancel()
            if (incoming.active != null && incoming.active != previous.active && player.playWhenReady && !busy) {
                player.pause()
                sent = snapshot() to SystemClock.elapsedRealtime()
            }
            return
        }
        if (incoming.playback.current == null || busy) return
        val activated = previous.active != deviceId
        val queueChanged = incoming.queueBy != deviceId && (activated || incoming.queueVersion != previous.queueVersion ||
            !incoming.playback.sameQueue(previous.playback))
        val statusChanged = incoming.statusBy != deviceId && (activated || incoming.statusVersion != previous.statusVersion ||
            incoming.statusVersion == null && (incoming.playback.position != previous.playback.position || incoming.playback.paused != previous.playback.paused))
        if (!queueChanged && !statusChanged) return
        applying?.cancel()
        val timestamp = received
        applying = store.scope.launch {
            optional {
                apply(incoming.playback, timestamp) { turn == generation && _state.value.active == deviceId }
                sent = snapshot() to SystemClock.elapsedRealtime()
            }
        }
    }

    suspend fun transfer(id: String) {
        if (busy || _connection.value != CONNECTED || state.value.active == id) return
        if (id != deviceId && state.value.devices.none { it.id == id }) return
        val turn = generation
        val incoming = state.value
        val player = bound ?: return
        busy = true
        try {
            if (id == deviceId) {
                if (incoming.active != null && incoming.playback.current != null) {
                    apply(incoming.playback.copy(paused = false), received) {
                        turn == generation && state.value.active == incoming.active && state.value.queueVersion == incoming.queueVersion
                    }
                } else if (player.currentMediaItem != null) player.play()
                else throw MusicError("На устройствах пока ничего не играет")
                val local = snapshot()
                send("update_player_state", JSONObject().put("player_state", YnisonProtocol.player(local, deviceId)), intercept = true)
                sent = local to SystemClock.elapsedRealtime()
            } else {
                if (incoming.active == null || incoming.active == deviceId) {
                    val local = snapshot()
                    if (local.current == null) throw MusicError("Сначала включите музыку")
                    send("update_player_state", JSONObject().put("player_state", YnisonProtocol.player(local, deviceId)))
                }
                send("update_active_device", JSONObject().put("device_id_optional", id))
                player.pause()
                sent = snapshot() to SystemClock.elapsedRealtime()
            }
        } catch (error: Retry) {
            throw MusicError("Соединение с устройством прервалось", cause = error)
        } finally {
            if (turn == generation) busy = false
        }
    }

    fun control(paused: Boolean? = null, offset: Int? = null) {
        val incoming = state.value
        if (busy || incoming.active == null || incoming.active == deviceId || _connection.value != CONNECTED) return
        val playback = incoming.playback
        if (playback.current == null) return
        try {
            if (offset != null && playback.index + offset in playback.songs.indices) {
                val next = playback.copy(index = playback.index + offset, position = 0L, duration = 0L)
                send("update_player_state", JSONObject().put("player_state", YnisonProtocol.player(next, deviceId)))
            } else if (paused != null) {
                val next = playback.copy(paused = paused, position = playback.predicted(SystemClock.elapsedRealtime() - received))
                send("update_playing_status", JSONObject().put("playing_status", YnisonProtocol.status(next, deviceId)))
            }
        } catch (_: Retry) { store.message("Соединение с устройством прервалось") }
    }

    private suspend fun apply(playback: YnisonPlayback, at: Long, current: () -> Boolean) {
        val player = bound ?: return
        val account = uid ?: return
        val original = playback.current ?: return
        val local = snapshot()
        val same = local.songs.size == playback.songs.size && local.songs.zip(playback.songs).all { (a, b) -> a.id == b.id }
        val position = { playback.predicted(SystemClock.elapsedRealtime() - at) }
        if (same) {
            if (!current()) throw CancellationException()
            val start = (player.currentMediaItemIndex - local.index).coerceAtLeast(0)
            if (player.currentMediaItemIndex != start + playback.index || abs(player.currentPosition - position()) > 2_000L) {
                player.seekTo(start + playback.index, position())
            }
        } else {
            val ids = playback.songs.map { it.id }.filter(String::isNotBlank).distinct()
            val found = store.online { tracks(ids) }.filter { !it.unavailable || store.files.has(it) }.associateBy { it.id }
            val available = playback.songs.mapIndexedNotNull { index, song -> found[song.id]?.let { index to it } }
            val target = available.indexOfFirst { it.first == playback.index }
            if (target < 0) throw MusicError("Трек «${original.title}» недоступен")
            if (!current()) throw CancellationException()
            val kind = playback.entity.lowercase(java.util.Locale.ROOT)
            val source = PlayContext(kind, playback.item.takeIf(String::isNotBlank), "С другого устройства")
            player.setMediaItems(available.map { (_, track) -> QueueItems.make(track, source, account) }, target, position())
        }
        if (player.playbackState == Player.STATE_IDLE) player.prepare()
        player.setPlaybackSpeed(playback.speed)
        player.playWhenReady = !playback.paused
    }

    companion object { const val CONNECTED = "Подключено" }
}
