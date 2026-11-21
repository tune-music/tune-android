package music.tune.android

import android.app.PendingIntent
import android.content.Intent
import android.content.SharedPreferences
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.LibraryResult
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.SettableFuture
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal object QueueItems {
    const val SLEEP = "tune.sleep"
    const val EXTEND_WAVE = "tune.wave.next"
    const val RELOAD_VERSION = "tune.track.reload"
    const val LIKE = "tune.track.like"
    const val DISLIKE = "tune.track.dislike"
    private const val TRACK = "tune.track"
    private const val CONTEXT = "tune.context"
    private val trackCache = object : LinkedHashMap<String, Track>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Track>?) = size > 16
    }
    private val contextCache = object : LinkedHashMap<String, PlayContext>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, PlayContext>?) = size > 16
    }

    fun make(track: Track, source: PlayContext, uid: String? = null): MediaItem {
        val data = Bundle().apply {
            putString(TRACK, track.stored().toString())
            putString(CONTEXT, JSONObject().put("kind", source.kind).put("name", source.name)
                .put("item", source.item).put("wave", source.wave).toString())
        }
        val builder = MediaItem.Builder().setMediaId(track.id).setMediaMetadata(MediaMetadata.Builder()
            .setTitle(track.title).setArtist(track.artistText).setAlbumTitle(track.album?.title)
            .setArtworkUri(coverUrl(track.cover, 300)?.let(Uri::parse)).setIsPlayable(true).setExtras(data).build())
        if (uid != null) builder.setUri(Uri.Builder().scheme("tune-audio").authority("track").appendPath(uid).appendPath(track.id).build())
        return builder.build()
    }

    fun track(item: MediaItem?): Track? {
        val raw = item?.mediaMetadata?.extras?.getString(TRACK) ?: return null
        synchronized(trackCache) { trackCache[raw] }?.let { return it }
        return optional { Track.parse(JSONObject(raw)) }?.also { parsed ->
            synchronized(trackCache) { trackCache[raw] = parsed }
        }
    }

    fun context(item: MediaItem?): PlayContext {
        val raw = item?.mediaMetadata?.extras?.getString(CONTEXT) ?: return PlayContext()
        synchronized(contextCache) { contextCache[raw] }?.let { return it }
        return (optional {
            val data = JSONObject(raw)
            PlayContext(data.text("kind") ?: "various", data.text("item"), data.text("name").orEmpty(), data.text("wave"))
        } ?: PlayContext()).also { parsed ->
            synchronized(contextCache) { contextCache[raw] = parsed }
        }
    }
}

@OptIn(UnstableApi::class)
class PlaybackService : MediaLibraryService(), Player.Listener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val store get() = (application as TuneApp).store
    private val waveMix by lazy { WaveMix(store) }
    private lateinit var player: ExoPlayer
    private var session: MediaLibrarySession? = null
    private var activeUser: String? = null
    private var extensionJob: Job? = null
    private var snapshot: Job? = null
    private var sleep: Job? = null
    private var sleepAtEnd = false
    private var pendingWave: String? = null
    private var listen: Listen? = null
    private var startedAt: Long? = null
    @Volatile private var effects = PcmTuning()
    private var levels: Job? = null
    private var crossfade: Crossfade? = null
    private var resumeWave: Job? = null
    private val connectivity get() = getSystemService(ConnectivityManager::class.java)
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) scope.launch { restoreOnlineWave() }
        }
    }
    @Volatile private var forceFile: String? = null

    private val settingsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when {
            key == "equalizer" || key == "eqEnabled" || key == "preamp" || key == "reverb" ||
                key == "eightD" || key == "eightDMode" || key == "eightDPeriod" ||
                key == "eightDSmoothness" || key == "eightDDepth" || key == "eightDSensitivity" ||
                key?.startsWith("eqStep:") == true || key?.startsWith("eqBand:") == true ->
                effects = SoundSettings.tuning(store.preferences)
            key == "normalize" -> volume(QueueItems.track(player.currentMediaItem))
            key == "pitchWithSpeed" || key == "speed" -> speed()
            key == "crossfade" -> crossfade?.settingsChanged()
            key == "offline" && !store.offline -> restoreOnlineWave()
        }
    }

    override fun onCreate() {
        super.onCreate()
        effects = SoundSettings.tuning(store.preferences)
        val http = DefaultHttpDataSource.Factory().setConnectTimeoutMs(12_000).setReadTimeoutMs(20_000)
        val upstream = DefaultDataSource.Factory(this, http)
        val resolving = ResolvingDataSource.Factory(upstream) { spec ->
            val (uid, track) = spec.uri.pathSegments.takeIf { it.size == 2 }
                ?: throw MusicError("Не удалось открыть трек")
            spec.withUri(store.files.resolve(uid, track, track == forceFile))
        }
        player = ExoPlayer.Builder(this, soundRenderers()).setMediaSourceFactory(DefaultMediaSourceFactory(resolving))
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build().also {
                it.repeatMode = store.preferences.getInt("repeat", Player.REPEAT_MODE_OFF)
                it.shuffleModeEnabled = store.preferences.getBoolean("shuffle", false)
                it.addListener(this)
            }
        crossfade = Crossfade(player, scope, create = {
            ExoPlayer.Builder(this, soundRenderers()).setMediaSourceFactory(DefaultMediaSourceFactory(resolving))
                .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), false)
                .build()
        }, setting = { store.preferences.getInt("crossfade", -1) }, gain = ::baseVolume,
            restoreGain = { volume(QueueItems.track(player.currentMediaItem)) },
            warm = { item ->
                val uid = activeUser
                val track = QueueItems.track(item)
                if (uid != null && track != null) store.files.resolve(uid, track.id, false)
            },
            sleepAtEnd = { sleepAtEnd })
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java)
            .setAction(MainActivity.OPEN_PLAYER).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        session = MediaLibrarySession.Builder(this, player, Callback()).setSessionActivity(open).build()
        store.preferences.registerOnSharedPreferenceChangeListener(settingsListener)
        optional { connectivity.registerDefaultNetworkCallback(networkCallback) }
        speed()
        scope.launch {
            store.profile.collectLatest { user ->
                if (activeUser == user?.uid) return@collectLatest
                finishListen(false)
                extensionJob?.cancel()
                resumeWave?.cancel()
                sleep?.cancel()
                sleepAtEnd = false
                pendingWave = null
                snapshot?.cancel()
                activeUser = null
                player.stop()
                player.clearMediaItems()
                activeUser = user?.uid
                user?.uid?.let { restore(it) }
                store.ynison.bind(player, user?.uid)
            }
        }
        scope.launch {
            while (isActive) {
                delay(5_000)
                if (player.isPlaying) savePosition()
            }
        }
        scope.launch { store.likes.collect { updateMediaButtons() } }
        scope.launch { store.dislikes.collect { updateMediaButtons() } }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = session

    private inner class Callback : MediaLibrarySession.Callback {
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            if (controller.packageName != packageName && !controller.isTrusted) return MediaSession.ConnectionResult.reject()
            val base = super.onConnect(session, controller)
            val commands = base.availablePlayerCommands.buildUpon()
            val sessionCommands = base.availableSessionCommands.buildUpon()
            if (controller.packageName == packageName) {
                sessionCommands.add(SessionCommand(QueueItems.SLEEP, Bundle.EMPTY))
                    .add(SessionCommand(QueueItems.EXTEND_WAVE, Bundle.EMPTY))
                    .add(SessionCommand(QueueItems.RELOAD_VERSION, Bundle.EMPTY))
            } else {
                commands.remove(Player.COMMAND_CHANGE_MEDIA_ITEMS)
            }
            if (controller.isTrusted || controller.packageName == packageName) {
                sessionCommands.add(SessionCommand(QueueItems.LIKE, Bundle.EMPTY)).add(SessionCommand(QueueItems.DISLIKE, Bundle.EMPTY))
            }
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailablePlayerCommands(commands.build()).setAvailableSessionCommands(sessionCommands.build()).build()
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, params: LibraryParams?
        ): ListenableFuture<LibraryResult<MediaItem>> = Futures.immediateFuture(
            LibraryResult.ofItem(libraryItem("tune:root", "Tune", folder = true), params)
        )

        override fun onGetChildren(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, parentId: String,
            page: Int, pageSize: Int, params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = mediaResult(
            { LibraryResult.ofError(SessionError.ERROR_IO) }
        ) {
            if (activeUser == null && parentId != "tune:root") return@mediaResult LibraryResult.ofError(SessionError.ERROR_SESSION_AUTHENTICATION_EXPIRED)
            val items = when (parentId) {
                "tune:root" -> listOf(
                    libraryItem("tune:wave", "Моя волна"),
                    libraryItem("tune:likes", "Мне нравится", folder = true),
                    libraryItem("tune:downloads", "Скачанные треки", folder = true),
                    libraryItem("tune:playlists", "Плейлисты", folder = true),
                )
                "tune:playlists" -> (store.ownPlaylists.value + store.savedPlaylists.value).map { card ->
                    libraryItem("tune:playlist:${card.owner}:${card.id}", card.title, folder = true, cover = card.cover)
                }
                else -> {
                    val (tracks, _) = libraryTracks(parentId)
                    tracks.map { track -> libraryItem("$parentId/${track.id}", track.title, subtitle = track.artistText, cover = track.cover) }
                }
            }
            val offset = (page.toLong() * pageSize).coerceIn(0, items.size.toLong()).toInt()
            LibraryResult.ofItemList(items.drop(offset).take(pageSize.coerceAtLeast(0)), params)
        }

        override fun onSetMediaItems(
            session: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            if (controller.packageName == packageName) return super.onSetMediaItems(session, controller, mediaItems, startIndex, startPositionMs)
            val request = mediaItems.singleOrNull()
            val id = request?.mediaId.orEmpty()
            return mediaResult({ MediaSession.MediaItemsWithStartPosition(emptyList(), 0, 0) }) {
                val uid = activeUser ?: throw MusicError("Войдите через Яндекс")
                val query = request?.requestMetadata?.searchQuery
                val (tracks, context) = when {
                    id == "tune:wave" || query != null && query.isBlank() -> startLibraryWave()
                    query != null -> store.online { search(query, "track", 0) }.tracks to PlayContext("search", query, "Поиск")
                    else -> libraryTracks(id.substringBeforeLast('/'))
                }
                val target = id.substringAfterLast('/', "")
                val playable = tracks.filter { !it.unavailable || store.files.has(it) || store.files.hasReplacement(it) }
                if (activeUser != uid || playable.isEmpty()) throw MusicError("Нет доступных треков")
                val position = playable.indexOfFirst { it.id == target }.coerceAtLeast(0)
                MediaSession.MediaItemsWithStartPosition(playable.map { QueueItems.make(it, context, uid) }, position, 0)
            }
        }

        override fun onSearch(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, query: String, params: LibraryParams?
        ): ListenableFuture<LibraryResult<Void>> = mediaResult({ LibraryResult.ofError(SessionError.ERROR_IO) }) {
            val result = searchLibrary(query)
            session.notifySearchResultChanged(browser, query, result.size, params)
            LibraryResult.ofVoid(params)
        }

        override fun onGetSearchResult(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, query: String, page: Int, pageSize: Int, params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = mediaResult({ LibraryResult.ofError(SessionError.ERROR_IO) }) {
            val items = searchLibrary(query)
            val offset = (page.toLong() * pageSize).coerceIn(0, items.size.toLong()).toInt()
            LibraryResult.ofItemList(items.drop(offset).take(pageSize.coerceAtLeast(0)), params)
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: List<MediaItem>
        ): ListenableFuture<List<MediaItem>> {
            val uid = activeUser
            val items = if (uid == null || controller.packageName != packageName) emptyList() else mediaItems.mapNotNull { item ->
                QueueItems.track(item)?.let { QueueItems.make(it, QueueItems.context(item), uid) }
            }
            return Futures.immediateFuture(items)
        }

        override fun onCustomCommand(
            session: MediaSession, controller: MediaSession.ControllerInfo, command: SessionCommand, args: Bundle
        ): ListenableFuture<SessionResult> {
            val rating = command.customAction in setOf(QueueItems.LIKE, QueueItems.DISLIKE)
            if (controller.packageName != packageName && !(rating && controller.isTrusted)) {
                return Futures.immediateFuture(SessionResult(SessionError.ERROR_PERMISSION_DENIED))
            }
            when (command.customAction) {
                QueueItems.LIKE, QueueItems.DISLIKE -> {
                    val track = QueueItems.track(player.currentMediaItem)
                        ?: return Futures.immediateFuture(SessionResult(SessionError.ERROR_INVALID_STATE))
                    return mediaResult({ SessionResult(SessionError.ERROR_IO) }) {
                        val dislike = command.customAction == QueueItems.DISLIKE
                        val add = store.rate(track, dislike)
                        if (dislike && add && player.currentMediaItem?.mediaId == track.id &&
                            QueueItems.context(player.currentMediaItem).wave != null) {
                            if (player.hasNextMediaItem()) player.seekToNextMediaItem() else extendWave(play = true)
                        }
                        SessionResult(SessionResult.RESULT_SUCCESS)
                    }
                }
                QueueItems.SLEEP -> {
                    sleep?.cancel()
                    sleep = null
                    val minutes = args.getInt("minutes").coerceIn(-1, 180)
                    when {
                        minutes == -1 -> {
                            sleepAtEnd = true
                            store.setSleepState(SleepState(afterTrack = true))
                        }
                        minutes > 0 -> {
                            sleepAtEnd = false
                            val delayMs = minutes * 60_000L
                            store.setSleepState(SleepState(stopAtMs = System.currentTimeMillis() + delayMs))
                            sleep = scope.launch {
                                delay(delayMs)
                                store.setSleepState(SleepState())
                                player.pause()
                                store.message("Таймер сна остановил музыку")
                            }
                        }
                        else -> {
                            sleepAtEnd = false
                            store.setSleepState(SleepState())
                        }
                    }
                }
                QueueItems.EXTEND_WAVE -> extendWave(play = true)
                QueueItems.RELOAD_VERSION -> {
                    if (player.currentMediaItem?.mediaId == args.getString("track")) {
                        val position = player.currentPosition
                        val mayPlay = player.playWhenReady
                        crossfade?.settingsChanged()
                        forceFile = null
                        player.stop()
                        player.seekTo(position)
                        player.prepare()
                        player.playWhenReady = mayPlay
                    }
                }
                else -> return super.onCustomCommand(session, controller, command, args)
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
    }

    private fun libraryItem(id: String, title: String, folder: Boolean = false, subtitle: String? = null, cover: String? = null) =
        MediaItem.Builder().setMediaId(id).setMediaMetadata(MediaMetadata.Builder().setTitle(title).setArtist(subtitle)
            .setIsBrowsable(folder).setIsPlayable(!folder).setArtworkUri(coverUrl(cover, 200)?.let(Uri::parse)).build()).build()

    private var lastLibrarySearch: Triple<String, String, List<Track>>? = null

    private suspend fun searchLibrary(query: String): List<MediaItem> {
        val uid = activeUser ?: return emptyList()
        val tracks = lastLibrarySearch?.takeIf { it.first == uid && it.second == query }?.third
            ?: store.online { search(query, "track", 0) }.tracks.filterNot { it.unavailable }.also {
                lastLibrarySearch = Triple(uid, query, it)
            }
        return tracks.map { track -> libraryItem("tune:search/${track.id}", track.title, subtitle = track.artistText, cover = track.cover) }
    }

    private fun <T> mediaResult(fallback: () -> T, action: suspend () -> T): ListenableFuture<T> {
        val result = SettableFuture.create<T>()
        val job = scope.launch {
            try { result.set(action()) }
            catch (error: kotlinx.coroutines.CancellationException) { result.cancel(false); throw error }
            catch (_: Exception) { result.set(fallback()) }
        }
        result.addListener({ if (result.isCancelled) job.cancel() }, ContextCompat.getMainExecutor(this))
        return result
    }

    private suspend fun libraryTracks(id: String): Pair<List<Track>, PlayContext> {
        val uid = activeUser ?: throw MusicError("Войдите через Яндекс")
        return when {
            id == "tune:likes" -> store.likedTracks() to
                PlayContext("playlist", "$uid:3", "Мне нравится")
            id == "tune:downloads" -> store.files.downloaded() to PlayContext(name = "Скачанные треки")
            id == "tune:search" -> lastLibrarySearch?.takeIf { it.first == uid }?.third.orEmpty() to PlayContext("search", null, "Поиск")
            id.startsWith("tune:playlist:") -> {
                val card = (store.ownPlaylists.value + store.savedPlaylists.value).firstOrNull { "tune:playlist:${it.owner}:${it.id}" == id }
                    ?: throw MusicError("Плейлист недоступен")
                val page = store.details(card)
                page.tracks to PlayContext("playlist", "${card.owner}:${card.id}", page.card.title)
            }
            else -> emptyList<Track>() to PlayContext()
        }
    }

    private suspend fun startLibraryWave(): Pair<List<Track>, PlayContext> {
        val seeds = listOf("user:onyourwave")
        val wave = if (store.offline) null else try {
            val chosen = store.preferences.getStringSet("waveSettings", emptySet()).orEmpty()
            store.online { startWave(seeds + chosen) }
        } catch (error: MusicError) {
            if (error.cause !is java.io.IOException) throw error
            null
        }
        if (wave == null) return store.files.downloaded().shuffled() to PlayContext("radio", seeds.single(), "Моя волна")
        scope.launch { optional { store.online { waveFeedback(wave.session, "radioStarted") } } }
        return wave.tracks to PlayContext("radio", seeds.single(), "Моя волна", wave.session)
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        val automatic = reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO ||
            reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT ||
            crossfade?.transitioning == true
        finishListen(automatic)
        if (automatic && sleepAtEnd) {
            sleepAtEnd = false
            store.setSleepState(SleepState())
            player.pause()
            store.message("Таймер сна остановил музыку")
        }
        if (forceFile != mediaItem?.mediaId) forceFile = null
        volume(QueueItems.track(mediaItem))
        updateMediaButtons()
        NowPlayingWidget.update(this, QueueItems.track(mediaItem), player.isPlaying)
        persist()
        if (mediaItem != null && player.mediaItemCount - player.currentMediaItemIndex <= 3) extendWave()
        if (player.isPlaying) beginListen()
    }

    private fun updateMediaButtons() {
        val track = QueueItems.track(player.currentMediaItem)
        if (track == null) {
            session?.setMediaButtonPreferences(emptyList())
            return
        }
        val liked = track.id in store.likes.value
        val disliked = track.id in store.dislikes.value
        val like = CommandButton.Builder(if (liked) CommandButton.ICON_HEART_FILLED else CommandButton.ICON_HEART_UNFILLED)
            .setDisplayName(if (liked) "Убрать из «Мне нравится»" else "Мне нравится")
            .setSessionCommand(SessionCommand(QueueItems.LIKE, Bundle.EMPTY)).setSlots(CommandButton.SLOT_BACK_SECONDARY).build()
        val dislike = CommandButton.Builder(if (disliked) CommandButton.ICON_THUMB_DOWN_FILLED else CommandButton.ICON_THUMB_DOWN_UNFILLED)
            .setDisplayName(if (disliked) "Снять дизлайк" else "Не нравится")
            .setSessionCommand(SessionCommand(QueueItems.DISLIKE, Bundle.EMPTY)).setSlots(CommandButton.SLOT_FORWARD_SECONDARY).build()
        session?.setMediaButtonPreferences(listOf(like, dislike))
    }
    override fun onPositionDiscontinuity(old: Player.PositionInfo, new: Player.PositionInfo, reason: Int) {
        listen?.position = old.positionMs
        if (reason == Player.DISCONTINUITY_REASON_SEEK) {
            listen?.seek = true
            listen?.position = new.positionMs
        }
        savePosition()
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        NowPlayingWidget.update(this, QueueItems.track(player.currentMediaItem), isPlaying)
        if (isPlaying) beginListen()
        else {
            countTime()
            if (!player.playWhenReady) listen?.pause = true
            savePosition()
        }
    }

    override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
        if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) persist()
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState != Player.STATE_ENDED) return
        finishListen(true)
        if (sleepAtEnd) {
            sleepAtEnd = false
            player.pause()
        } else if (player.playWhenReady && QueueItems.context(player.currentMediaItem).wave != null) {
            if (player.hasNextMediaItem()) {
                player.seekToNextMediaItem()
                player.play()
            } else extendWave(play = true)
        }
    }

    override fun onPlayerError(error: PlaybackException) {
        val track = QueueItems.track(player.currentMediaItem) ?: return
        val detail = generateSequence(error as Throwable) { it.cause }.filterIsInstance<MusicError>().firstOrNull()
        val from = QueueItems.context(player.currentMediaItem)
        val network = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
        if (from.wave != null && (store.offline || network?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) != true ||
                detail?.cause is java.io.IOException && detail.status == 0)) {
            val available = store.files.downloaded().shuffled()
            val uid = activeUser
            if (uid != null && available.isNotEmpty()) {
                player.setMediaItems(available.map { QueueItems.make(it, from.copy(wave = null), uid) }, 0, 0)
                player.prepare()
                player.play()
                store.message("Моя волна играет из скачанных треков")
                return
            }
        }
        if (detail == null && !store.offline && forceFile != track.id) {
            forceFile = track.id
            player.prepare()
            return
        }
        store.message(detail?.message ?: "Не удалось воспроизвести «${track.title}»")
    }

    private fun restoreOnlineWave() {
        if (store.offline || resumeWave?.isActive == true) return
        val pending = player.currentMediaItem ?: return
        val source = QueueItems.context(pending)
        if (source.kind != "radio" || source.wave != null || !player.playWhenReady) return
        val tokens = source.item.orEmpty().split(',').filter(String::isNotBlank)
        val uid = activeUser ?: return
        val settings = if (tokens == listOf("user:onyourwave")) store.preferences.getStringSet("waveSettings", emptySet()).orEmpty() else emptySet()
        resumeWave = scope.launch {
            val live = optional { store.online { startWave(tokens + settings) } } ?: return@launch
            if (activeUser != uid || store.offline || QueueItems.context(player.currentMediaItem) != source || !player.playWhenReady) return@launch
            val tracks = live.tracks.filterNot { it.id == player.currentMediaItem?.mediaId }
            if (tracks.isEmpty()) return@launch
            val current = player.currentMediaItemIndex
            player.replaceMediaItems(current + 1, player.mediaItemCount,
                tracks.map { QueueItems.make(it, source.copy(wave = live.session), uid) })
            scope.launch { optional { store.online { waveFeedback(live.session, "radioStarted") } } }
        }
    }

    override fun onRepeatModeChanged(repeatMode: Int) { store.preferences.edit().putInt("repeat", repeatMode).apply() }
    override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) { store.preferences.edit().putBoolean("shuffle", shuffleModeEnabled).apply() }
    override fun onPlaybackParametersChanged(parameters: androidx.media3.common.PlaybackParameters) {
        store.preferences.edit().putFloat("speed", parameters.speed).apply()
    }
    private fun soundRenderers() = object : DefaultRenderersFactory(this) {
        override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink =
            DefaultAudioSink.Builder(context)
                .setEnableFloatOutput(false)
                .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                .setAudioProcessors(arrayOf(SoundProcessor { effects }))
                .build()
    }

    private fun speed() {
        val speed = store.preferences.getFloat("speed", 1f).coerceIn(.5f, 2f)
        val pitch = if (store.preferences.getBoolean("pitchWithSpeed", false)) speed else 1f
        val parameters = androidx.media3.common.PlaybackParameters(speed, pitch)
        if (player.playbackParameters != parameters) player.playbackParameters = parameters
    }

    private fun volume(track: Track?) {
        levels?.cancel()
        if (crossfade?.mixing == true) return
        player.volume = baseVolume(track)
        if (!store.preferences.getBoolean("normalize", true) || track == null || track.loudness != null || store.offline) return
        levels = scope.launch {
            val updated = optional { store.online { tracks(listOf(track.id)) } }?.firstOrNull() ?: return@launch
            if (player.currentMediaItem?.mediaId == track.id && crossfade?.mixing != true) player.volume = baseVolume(updated)
        }
    }

    private fun baseVolume(track: Track?): Float {
        return if (track != null && store.preferences.getBoolean("normalize", true)) SoundSettings.gain(track) else 1f
    }

    private fun extendWave(play: Boolean = false) {
        val item = player.currentMediaItem ?: return
        val context = QueueItems.context(item)
        val wave = context.wave ?: return
        if (play) pendingWave = wave
        if (extensionJob?.isActive == true) return
        val uid = activeUser ?: return
        val queue = (0 until player.mediaItemCount).mapNotNull { QueueItems.track(player.getMediaItemAt(it)) }
        val index = player.currentMediaItemIndex
        extensionJob = scope.launch {
            try {
                val incoming = store.online { waveMore(wave, queue.takeLast(30).map { it.fullId }) }
                if (activeUser != uid || QueueItems.context(player.currentMediaItem).wave != wave) return@launch
                val seen = queue.takeLast(50).map { it.id }.toSet()
                val next = incoming.distinctBy { it.id }.filterNot { it.id in seen }
                val more = waveMix.incorporate(uid, context, next, queue.takeLast(80))
                if (activeUser != uid || QueueItems.context(player.currentMediaItem).wave != wave) return@launch
                if (more.isNotEmpty()) {
                    player.addMediaItems(more.map { QueueItems.make(it, context, uid) })
                    if (index > 50 && player.currentMediaItemIndex > 50) player.removeMediaItems(0, player.currentMediaItemIndex - 30)
                    if (pendingWave == wave || player.playbackState == Player.STATE_ENDED && player.playWhenReady) {
                        player.seekToNextMediaItem()
                        player.play()
                    }
                } else if (pendingWave == wave) store.message("Волна пока не предложила новых треков")
            } catch (error: java.io.IOException) {
                if (pendingWave == wave) store.message(error.message ?: "Не удалось продолжить волну")
            } finally {
                if (pendingWave == wave) pendingWave = null
            }
        }
    }

    private fun beginListen() {
        val item = player.currentMediaItem ?: return
        val track = QueueItems.track(item) ?: return
        if (startedAt == null) startedAt = SystemClock.elapsedRealtime()
        if (listen != null) return
        val current = Listen(track, QueueItems.context(item), optional { store.api() }, activeUser, position = player.currentPosition)
        listen = current
        report(current, first = true, finished = false)
    }

    private fun countTime() {
        startedAt?.let { start -> listen?.let { it.played += SystemClock.elapsedRealtime() - start } }
        startedAt = null
    }

    private fun finishListen(finished: Boolean) {
        countTime()
        val last = listen ?: return
        listen = null
        if (finished) last.position = last.track.duration
        last.uid?.let { uid ->
            store.scope.launch { optional { store.listening.add(uid, last.track, last.played, last.source.kind == "radio") } }
        }
        report(last, first = false, finished = finished)
    }

    private fun report(listen: Listen, first: Boolean, finished: Boolean) {
        val api = listen.api ?: return
        val play = listen.report(first)
        val feedback = if (first) "trackStarted" else if (finished) "trackFinished" else "skip"
        val played = if (first) 0.0 else listen.played / 1000.0
        store.scope.launch(Dispatchers.IO) {
            optional { api.report(play) }
            listen.source.wave?.let { optional { api.waveFeedback(it, feedback, listen.track, played) } }
        }
    }

    private fun savePosition() {
        val uid = activeUser ?: return
        if (player.mediaItemCount == 0) return
        val index = player.currentMediaItemIndex
        val position = player.currentPosition
        store.preferences.edit().putInt("queueIndex:$uid", index)
            .putLong("queuePosition:$uid", position).apply()
    }

    private fun persist() {
        val uid = activeUser ?: return
        snapshot?.cancel()
        val entries = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaMetadata.extras }
        savePosition()
        snapshot = scope.launch {
            val serialized = withContext(Dispatchers.Default) {
                JSONArray(entries.mapNotNull { data ->
                    val track = data?.getString("tune.track") ?: return@mapNotNull null
                    JSONObject().put("track", track).put("context", data.getString("tune.context"))
                }).toString()
            }
            store.preferences.edit().putString("queue:$uid", serialized).apply()
        }
    }

    private fun restore(uid: String) {
        val raw = store.preferences.getString("queue:$uid", null) ?: return
        val items = optional {
            JSONArray(raw).objects().mapNotNull { entry ->
                val track = Track.parse(JSONObject(entry.getString("track"))) ?: return@mapNotNull null
                val source = JSONObject(entry.getString("context"))
                QueueItems.make(track, PlayContext(source.text("kind") ?: "various", source.text("item"),
                    source.text("name").orEmpty(), source.text("wave")), uid)
            }
        }.orEmpty()
        if (items.isEmpty()) return
        val index = store.preferences.getInt("queueIndex:$uid", 0).coerceIn(items.indices)
        val position = store.preferences.getLong("queuePosition:$uid", 0)
        player.setMediaItems(items, index, position)
        player.prepare()
    }

    override fun onDestroy() {
        store.ynison.bind(null, null)
        savePosition()
        listen?.position = player.currentPosition
        finishListen(false)
        store.preferences.unregisterOnSharedPreferenceChangeListener(settingsListener)
        optional { connectivity.unregisterNetworkCallback(networkCallback) }
        store.setSleepState(SleepState())
        crossfade?.release()
        scope.cancel()
        player.removeListener(this)
        session?.release()
        player.release()
        super.onDestroy()
    }
}

private class Listen(val track: Track, val source: PlayContext, val api: MusicApi?, val uid: String?, var position: Long) {
    private val id = UUID.randomUUID().toString()
    private val start = MusicApi.now()
    private val added = "${SystemClock.elapsedRealtime()}-${System.currentTimeMillis() / 1000}"
    var played = 0L
    var seek = false
    var pause = false

    fun report(first: Boolean): JSONObject = JSONObject().put("playId", id).put("from", MusicApi.FROM)
        .put("trackId", track.id).put("albumId", track.album?.id).put("timestamp", MusicApi.now())
        .put("startTimestamp", start).put("totalPlayedSeconds", if (first) 0.0 else played / 1000.0)
        .put("endPositionSeconds", if (first) 0.0 else position.coerceAtLeast(0) / 1000.0)
        .put("trackLengthSeconds", track.duration / 1000.0).put("context", source.kind)
        .put("contextItem", source.item ?: JSONObject.NULL).put("addTracksToPlayerTime", added)
        .put("seek", seek).put("pause", pause).put("fromCache", false).put("isRestored", false)
        .put("audioAuto", "none").put("audioOutputType", "Speaker").put("isRepeated", false)
        .put("isLivePlayableIndex", source.wave != null).put("radioSessionId", source.wave).put("batchId", track.batch)
        .also { if (!first) it.put("maxPlayerStage", "play") }
}
