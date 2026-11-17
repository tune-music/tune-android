package music.tune.android

import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos

internal data class FadeWindow(val start: Long, val end: Long, val incomingStart: Long, val incomingEnd: Long)

internal fun fadeWindow(current: Track, next: Track, duration: Long, setting: Int, mix: Boolean): FadeWindow? {
    if (setting == 0 || listOf(current.mediaType, next.mediaType, current.album?.releaseType, next.album?.releaseType)
            .any { it in setOf("podcast", "audiobook", "podcast_episode", "audiobook_chapter") }) return null
    val length = duration.takeIf { it > 0 } ?: current.duration
    if (length < 30_000) return null
    if (next.duration in 1..29_999) return null
    val marked = if (setting > 0) null else if (mix) current.mixFade ?: current.fade else current.fade
    val incoming = if (setting > 0) null else if (mix) next.mixFade ?: next.fade else next.fade
    val seconds = (if (setting > 0) setting.coerceIn(1, 8) else 6) * 1_000L
    val end = (marked?.outStop?.times(1000)?.toLong() ?: length).coerceIn(0L, length)
    val proposed = marked?.outStart?.times(1000)?.toLong() ?: (end - seconds)
    val start = maxOf(0L, proposed, if (mix && marked != null) 0L else end - seconds)
    if (end - start < 300 || start < length / 2) return null
    val fadeIn = incoming?.inStart?.times(1000)?.toLong() ?: 0L
    val fadeInEnd = incoming?.inStop?.times(1000)?.toLong() ?: (fadeIn + (end - start))
    if (next.duration > 0 && fadeIn >= next.duration) return null
    return FadeWindow(start, end, fadeIn, fadeInEnd.coerceAtLeast(fadeIn + 100))
}

@OptIn(UnstableApi::class)
internal class Crossfade(
    private val main: ExoPlayer,
    private val scope: CoroutineScope,
    private val create: () -> ExoPlayer,
    private val setting: () -> Int,
    private val gain: (Track) -> Float,
    private val restoreGain: () -> Unit,
) : Player.Listener {
    private var running: Job? = null
    private var auxiliary: ExoPlayer? = null
    private var handingOff = false
    private var attempted: Pair<MediaItem, Int>? = null
    var mixing: Boolean = false
        private set

    init { main.addListener(this) }

    fun schedule() {
        if (!main.isPlaying || setting() == 0 || running?.isActive == true || main.repeatMode == Player.REPEAT_MODE_ONE) return
        val from = main.currentMediaItem ?: return
        val nextIndex = main.nextMediaItemIndex.takeIf { it != C.INDEX_UNSET && it != main.currentMediaItemIndex } ?: return
        val to = main.getMediaItemAt(nextIndex)
        if (attempted == (from to nextIndex)) return
        val current = QueueItems.track(from) ?: return
        val next = QueueItems.track(to) ?: return
        val context = QueueItems.context(from)
        val mix = context.kind == "radio" && context.item?.split(',')?.any { it.startsWith("mix-") } == true
        val window = fadeWindow(current, next, main.duration, setting(), mix) ?: return
        if (main.currentPosition > window.end - 400) return
        running = scope.launch {
            try {
                while (isActive && stillOn(from, to, nextIndex) && main.currentPosition < window.start - 8_000) {
                    delay(((window.start - 8_000 - main.currentPosition) / main.playbackParameters.speed).toLong().coerceIn(60L, 1_000L))
                }
                if (!stillOn(from, to, nextIndex)) return@launch
                attempted = from to nextIndex
                val incoming = create().also { auxiliary = it }
                incoming.volume = 0f
                incoming.playbackParameters = main.playbackParameters
                incoming.setMediaItem(to, window.incomingStart)
                incoming.prepare()
                while (isActive && stillOn(from, to, nextIndex) && main.currentPosition < window.end - 350 &&
                    (main.currentPosition < window.start || incoming.playbackState != Player.STATE_READY)) delay(45)
                if (!stillOn(from, to, nextIndex) || incoming.playbackState != Player.STATE_READY || main.currentPosition >= window.end - 350) return@launch
                val outGain = main.volume
                val inGain = gain(next)
                mixing = true
                incoming.play()
                while (isActive && stillOn(from, to, nextIndex) && main.currentPosition < window.end - 90) {
                    val out = curve(main.currentPosition - window.start, window.end - window.start)
                    val into = curve(incoming.currentPosition - window.incomingStart, window.incomingEnd - window.incomingStart)
                    main.volume = outGain * (1f - out)
                    incoming.volume = inGain * into
                    if (incoming.playerError != null || incoming.playbackState == Player.STATE_ENDED) return@launch
                    delay(35)
                }
                if (!stillOn(from, to, nextIndex) || !incoming.isPlaying) return@launch
                handingOff = true
                main.volume = 0f
                incoming.volume = inGain
                main.seekTo(nextIndex, incoming.currentPosition)
                val deadline = SystemClock.elapsedRealtime() + 1_200
                while (isActive && main.playWhenReady && !main.isPlaying && SystemClock.elapsedRealtime() < deadline) delay(25)
                if (!main.isPlaying || main.currentMediaItem != to) return@launch
                // The second player may have continued while the session player buffered.
                if (kotlin.math.abs(main.currentPosition - incoming.currentPosition) > 140) {
                    main.seekTo(incoming.currentPosition)
                }
                repeat(5) { step ->
                    if (!main.playWhenReady || main.currentMediaItem != to) return@launch
                    val fraction = (step + 1) / 5f
                    main.volume = inGain * fraction
                    incoming.volume = inGain * (1 - fraction)
                    delay(18)
                }
            } finally {
                auxiliary?.release()
                auxiliary = null
                val restore = mixing
                mixing = false
                handingOff = false
                if (restore) restoreGain()
            }
        }
    }

    private fun curve(value: Long, span: Long): Float = ((1.0 - cos(PI * (value.toDouble() / span.coerceAtLeast(1)).coerceIn(0.0, 1.0))) / 2).toFloat()
    private fun stillOn(from: MediaItem, to: MediaItem, nextIndex: Int) =
        main.isPlaying && main.currentMediaItem == from && nextIndex == main.nextMediaItemIndex &&
            nextIndex in 0 until main.mediaItemCount && main.getMediaItemAt(nextIndex) == to

    private fun cancel(reset: Boolean = true) {
        if (reset) attempted = null
        running?.cancel()
        val previous = running
        scope.launch {
            previous?.join()
            if (running === previous) {
                running = null
                schedule()
            }
        }
    }

    override fun onPositionDiscontinuity(old: Player.PositionInfo, new: Player.PositionInfo, reason: Int) {
        if (!handingOff) cancel()
    }

    override fun onEvents(player: Player, events: Player.Events) {
        if (events.contains(Player.EVENT_PLAY_WHEN_READY_CHANGED) && !main.playWhenReady) auxiliary?.pause()
        if (handingOff) return
        if (events.containsAny(Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_TIMELINE_CHANGED,
                Player.EVENT_REPEAT_MODE_CHANGED, Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED, Player.EVENT_PLAYBACK_PARAMETERS_CHANGED)) cancel()
        else if (events.contains(Player.EVENT_IS_PLAYING_CHANGED)) {
            if (main.isPlaying) schedule() else {
                auxiliary?.pause()
                cancel(reset = false)
            }
        }
    }

    fun settingsChanged() = cancel()

    fun release() {
        main.removeListener(this)
        running?.cancel()
        auxiliary?.release()
        auxiliary = null
        mixing = false
    }
}
