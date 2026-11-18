package music.tune.android

import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

internal data class FadeWindow(val start: Long, val end: Long, val incomingStart: Long, val incomingEnd: Long)

internal fun fadeWindow(current: Track, next: Track, duration: Long, setting: Int, mix: Boolean): FadeWindow? {
    if (setting == 0 || listOf(current.mediaType, next.mediaType, current.album?.releaseType, next.album?.releaseType)
            .any { it in setOf("podcast", "audiobook", "podcast_episode", "audiobook_chapter") }) return null
    val length = duration.takeIf { it > 0 } ?: current.duration
    if (length < 30_000) return null
    if (next.duration in 1..29_999) return null
    if (setting > 0) {
        val span = setting.coerceIn(1, 12) * 1_000L
        val end = length
        val start = (end - span).coerceAtLeast(0L)
        return if (end - start < 300 || start < length / 2) null else FadeWindow(start, end, 0L, span)
    }
    val marked = if (mix) current.mixFade ?: current.fade else current.fade
    val incoming = if (mix) next.mixFade ?: next.fade else next.fade
    val end = (marked?.outStop?.times(1000)?.toLong() ?: length).coerceIn(0L, length)
    if (mix && marked != null) {
        val start = (marked.outStart * 1000).toLong().coerceIn(0L, end)
        if (end - start < 300 || start < length / 2) return null
        val fadeIn = (incoming?.inStart?.times(1000)?.toLong() ?: 0L).coerceAtLeast(0L)
        val rawInEnd = incoming?.inStop?.times(1000)?.toLong() ?: (fadeIn + (end - start))
        val fadeInEnd = if (rawInEnd - fadeIn >= 1_000L) rawInEnd else fadeIn + (end - start)
        if (next.duration > 0 && fadeIn >= next.duration) return null
        return FadeWindow(start, end, fadeIn, fadeInEnd)
    }
    val rawSpan = marked?.outStart?.times(1000)?.toLong()?.let { end - it } ?: 6_000L
    val span = rawSpan.coerceIn(4_000L, 6_000L)
    val start = (end - span).coerceAtLeast(0L)
    if (end - start < 300 || start < length / 2) return null
    val fadeIn = (incoming?.inStart?.times(1000)?.toLong() ?: 0L).coerceIn(0L, 3_000L)
    val rawInEnd = incoming?.inStop?.times(1000)?.toLong() ?: (fadeIn + (end - start))
    val fadeInEnd = if (rawInEnd - fadeIn >= 1_000L) rawInEnd else fadeIn + (end - start)
    if (next.duration > 0 && fadeIn >= next.duration) return null
    return FadeWindow(start, end, fadeIn, fadeInEnd)
}

@OptIn(UnstableApi::class)
internal class Crossfade(
    private val main: ExoPlayer,
    private val scope: CoroutineScope,
    private val create: () -> ExoPlayer,
    private val setting: () -> Int,
    private val gain: (Track) -> Float,
    private val restoreGain: () -> Unit,
    private val warm: (MediaItem) -> Unit = {},
    private val sleepAtEnd: () -> Boolean = { false },
) : Player.Listener {
    private var running: Job? = null
    private var auxiliary: ExoPlayer? = null
    private var handingOff = false
    private var attempted: Pair<MediaItem, Int>? = null
    private var activeTarget: MediaItem? = null
    var mixing: Boolean = false
        private set
    var transitioning: Boolean = false
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
        if (main.currentPosition > window.end - 500) return
        running = scope.launch {
            val job = currentCoroutineContext()[Job]
            var reschedule = false
            var warmFrom: Job? = null
            var warmTo: Job? = null
            try {
                while (isActive && stillOn(from, to, nextIndex) && main.currentPosition < window.start - 12_000) {
                    val waitMs = ((window.start - 12_000 - main.currentPosition) / main.playbackParameters.speed)
                        .toLong().coerceIn(60L, 1_000L)
                    delay(waitMs)
                }
                if (!stillOn(from, to, nextIndex) || sleepAtEnd()) return@launch
                attempted = from to nextIndex
                warmFrom = scope.launch(Dispatchers.IO) { optional { warm(from) } }
                warmTo = scope.launch(Dispatchers.IO) { optional { warm(to) } }
                while (isActive && stillOn(from, to, nextIndex) && main.currentPosition < window.start - 8_000) {
                    val waitMs = ((window.start - 8_000 - main.currentPosition) / main.playbackParameters.speed)
                        .toLong().coerceIn(50L, 1_000L)
                    delay(waitMs)
                }
                if (!stillOn(from, to, nextIndex) || sleepAtEnd()) return@launch
                warmFrom.join()
                if (!stillOn(from, to, nextIndex) || sleepAtEnd()) return@launch
                val outgoing = create().also { auxiliary = it }
                outgoing.volume = 0f
                outgoing.playbackParameters = main.playbackParameters
                val startAt = maxOf(window.start, main.currentPosition + 120L).coerceAtMost(window.end - 400L)
                outgoing.setMediaItem(from, startAt)
                outgoing.prepare()
                while (isActive && stillOn(from, to, nextIndex) && !sleepAtEnd() && main.currentPosition < window.end - 300) {
                    if (outgoing.playerError != null) return@launch
                    if (main.currentPosition >= startAt && outgoing.playbackState == Player.STATE_READY) break
                    delay(if (startAt - main.currentPosition <= 250L) 15L else 40L)
                }
                if (!stillOn(from, to, nextIndex) || sleepAtEnd() ||
                    outgoing.playbackState != Player.STATE_READY || main.currentPosition >= window.end - 300) return@launch
                val outGain = main.volume
                val inGain = gain(next)
                outgoing.volume = 0f
                outgoing.play()
                val readyDeadline = SystemClock.elapsedRealtime() + 400L
                while (isActive && stillOn(from, to, nextIndex) && !sleepAtEnd() &&
                    !outgoing.isPlaying && SystemClock.elapsedRealtime() < readyDeadline) {
                    if (outgoing.playerError != null || outgoing.playbackState == Player.STATE_ENDED) return@launch
                    delay(10)
                }
                if (!stillOn(from, to, nextIndex) || sleepAtEnd() || !outgoing.isPlaying ||
                    main.currentPosition >= window.end - 300) return@launch
                val outBase = outgoing.currentPosition.coerceIn(startAt, window.end - 250L)
                val fadeStart = SystemClock.elapsedRealtime()
                val fadeSpan = (window.end - outBase).coerceAtLeast(300L)
                val inSpan = (window.incomingEnd - window.incomingStart).coerceAtLeast(300L)
                mixing = true
                activeTarget = to
                handingOff = true
                transitioning = true
                outgoing.volume = outGain
                main.volume = 0f
                if (window.incomingStart > 0) main.seekTo(nextIndex, window.incomingStart) else main.seekToNextMediaItem()
                transitioning = false
                handingOff = false
                var outDone = false
                var inDone = false
                while (isActive && main.currentMediaItem == to && main.playWhenReady) {
                    if (main.playerError != null) break
                    val elapsed = ((SystemClock.elapsedRealtime() - fadeStart) * main.playbackParameters.speed).toLong().coerceAtLeast(0L)
                    val outPos = if (outgoing.currentPosition >= outBase) outgoing.currentPosition - outBase else elapsed
                    val outPart = if (outDone || outgoing.playbackState == Player.STATE_ENDED || outgoing.playerError != null) 1f
                    else (maxOf(outPos, elapsed).toFloat() / fadeSpan).coerceIn(0f, 1f)
                    if (!outDone) {
                        if (outPart >= 1f) {
                            outDone = true
                            outgoing.volume = 0f
                            outgoing.pause()
                        } else {
                            outgoing.volume = outGain * easeOut(outPart)
                        }
                    }
                    val inPos = if (main.isPlaying || main.currentPosition > window.incomingStart + 40L) {
                        (main.currentPosition - window.incomingStart).coerceAtLeast(0L)
                    } else 0L
                    val inPart = if (inDone) 1f else (inPos.toFloat() / inSpan).coerceIn(0f, 1f)
                    main.volume = inGain * easeIn(inPart)
                    if (inPart >= 1f) inDone = true
                    if (outDone && inDone) break
                    if (elapsed > maxOf(fadeSpan, inSpan) + 3_000L) break
                    delay(25)
                }
                reschedule = isActive && main.currentMediaItem == to && main.playWhenReady
            } finally {
                warmFrom?.cancel()
                warmTo?.cancel()
                auxiliary?.release()
                auxiliary = null
                val restore = mixing
                mixing = false
                handingOff = false
                transitioning = false
                activeTarget = null
                if (running === job) running = null
                if (restore) restoreGain()
                if (reschedule) scope.launch { schedule() }
            }
        }
    }

    private fun easeOut(progress: Float): Float {
        val c = cos(PI * progress.coerceIn(0f, 1f) / 2.0)
        return (c * c).toFloat()
    }

    private fun easeIn(progress: Float): Float {
        val s = sin(PI * progress.coerceIn(0f, 1f) / 2.0)
        return (s * s).toFloat()
    }

    private fun stillOn(from: MediaItem, to: MediaItem, nextIndex: Int) =
        main.isPlaying && main.currentMediaItem == from && nextIndex == main.nextMediaItemIndex &&
            nextIndex in 0 until main.mediaItemCount && main.getMediaItemAt(nextIndex) == to

    private fun cancel(reset: Boolean = true) {
        if (reset) attempted = null
        val previous = running
        previous?.cancel()
        scope.launch {
            previous?.join()
            if (running === previous || running == null) {
                running = null
                schedule()
            }
        }
    }

    override fun onPositionDiscontinuity(old: Player.PositionInfo, new: Player.PositionInfo, reason: Int) {
        if (handingOff) return
        if (mixing && main.currentMediaItem == activeTarget && reason != Player.DISCONTINUITY_REASON_SEEK) return
        cancel()
    }

    override fun onTimelineChanged(timeline: Timeline, reason: Int) {
        if (handingOff) return
        if (reason != Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) return
        cancel()
    }

    override fun onEvents(player: Player, events: Player.Events) {
        if (events.contains(Player.EVENT_PLAY_WHEN_READY_CHANGED) && !main.playWhenReady) auxiliary?.pause()
        if (handingOff) return
        if (mixing) {
            val targetChanged = main.currentMediaItem != activeTarget
            val stopped = !main.playWhenReady
            val modeChanged = events.containsAny(
                Player.EVENT_REPEAT_MODE_CHANGED,
                Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED,
                Player.EVENT_PLAYBACK_PARAMETERS_CHANGED,
            )
            if (targetChanged || stopped || modeChanged) cancel()
            return
        }
        if (events.containsAny(
                Player.EVENT_MEDIA_ITEM_TRANSITION,
                Player.EVENT_REPEAT_MODE_CHANGED,
                Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED,
                Player.EVENT_PLAYBACK_PARAMETERS_CHANGED,
            )) {
            cancel()
        } else if (events.contains(Player.EVENT_IS_PLAYING_CHANGED)) {
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
        running = null
        auxiliary?.release()
        auxiliary = null
        mixing = false
        handingOff = false
        transitioning = false
        activeTarget = null
    }
}
