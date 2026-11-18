package music.tune.android

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.view.Gravity
import android.view.View
import android.view.animation.OvershootInterpolator
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import kotlin.math.abs

@SuppressLint("ViewConstructor")
internal class CoverTrackPager(private val ui: TuneUi, private val onTone: (Int) -> Unit) : FrameLayout(ui.context) {
    private inner class Page {
        var track: Track? = null
        var tone: Int? = null
        val cover = ui.art(null, 640, corner = 22) { color ->
            tone = color
            if (pages.getOrNull(1) === this) onTone(color)
        }.apply {
            elevation = ui.dp(14).toFloat()
        }

        fun bind(value: Track?) {
            if (value == null) tone = Palette.lifted
            else if (track?.cover != value.cover) tone = null
            track = value
            cover.visibility = if (value == null) View.INVISIBLE else View.VISIBLE
            cover.bind(value?.cover, 640)
        }
    }

    private var pages = List(3) { Page() }
    private var coverSize = 0
    private var offset = 0f
    private var dragBaseOffset = 0f
    private var pendingDirection = 0
    private var motion: ValueAnimator? = null
    private var playing: Boolean? = null
    private val pendingSettle = Runnable {
        pendingDirection = 0
        settle()
    }

    init {
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        clipChildren = false
        clipToPadding = false
    }

    fun setCoverSize(size: Int) {
        coverSize = size
        pages.forEach { addView(it.cover, LayoutParams(size, size, Gravity.CENTER)) }
    }

    private fun step(): Float = maxOf(width, coverSize + ui.dp(40)).toFloat().coerceAtLeast(1f)

    fun bind(previous: Track?, current: Track, next: Track?, playing: Boolean) {
        val before = pages[1].track?.id
        val change = before != current.id
        val direction = if (!change || before == null) 0 else pendingDirection.takeIf { it != 0 } ?: when (current.id) {
            pages[0].track?.id -> -1
            pages[2].track?.id -> 1
            else -> 0
        }
        if (change) {
            pendingDirection = 0
            removeCallbacks(pendingSettle)
            motion?.cancel()
        }
        if (change && direction != 0) {
            pages = if (direction > 0) listOf(pages[1], pages[2], pages[0]) else listOf(pages[2], pages[0], pages[1])
        }
        pages[0].bind(previous)
        pages[1].bind(current)
        pages[2].bind(next)
        if (change) {
            pages[1].tone?.let(onTone)
            if (direction != 0 && isShown && width > 0 && ValueAnimator.areAnimatorsEnabled()) {
                place(offset + direction * step())
                settle()
            } else {
                place(0f)
            }
        }
        if (this.playing != playing) {
            this.playing = playing
            val scale = if (playing) 1f else .9f
            pages.forEach {
                if (ValueAnimator.areAnimatorsEnabled()) {
                    it.cover.animate().scaleX(scale).scaleY(scale).setDuration(320)
                        .setInterpolator(OvershootInterpolator(.75f)).start()
                } else {
                    it.cover.scaleX = scale
                    it.cover.scaleY = scale
                }
            }
        }
    }

    fun startDrag() {
        pendingDirection = 0
        removeCallbacks(pendingSettle)
        motion?.cancel()
        dragBaseOffset = offset
    }

    fun drag(displacement: Float) {
        if (!ValueAnimator.areAnimatorsEnabled()) return
        val limit = step()
        val raw = (dragBaseOffset + displacement).coerceIn(-limit, limit)
        val neighbor = if (raw == 0f) null else pages[if (raw < 0f) 2 else 0].track
        place(if (neighbor == null) raw * .24f else raw)
    }

    fun finishDrag(direction: Int, action: () -> Unit) {
        val neighbor = pages[if (direction > 0) 2 else 0].track
        if (neighbor == null) {
            pendingDirection = 0
            settle()
            action()
            return
        }
        pendingDirection = direction
        val before = pages[1].track?.id
        action()
        if (pages[1].track?.id == before) {
            // Continue gliding in the swipe direction while waiting for MediaController IPC, avoiding a sticky frame stall.
            animateOffset(-direction * step(), duration = 340)
            postDelayed(pendingSettle, 300)
        }
    }

    fun settle() {
        animateOffset(0f)
    }

    private fun animateOffset(target: Float, duration: Long? = null) {
        motion?.cancel()
        if (offset == target || !ValueAnimator.areAnimatorsEnabled()) {
            place(target)
            return
        }
        val distance = abs(offset - target)
        val glideMs = duration ?: (distance / step() * 340f).toLong().coerceIn(220L, 340L)
        motion = ValueAnimator.ofFloat(offset, target).apply {
            this.duration = glideMs
            interpolator = PathInterpolator(0.22f, 1f, 0.36f, 1f)
            addUpdateListener { place(it.animatedValue as Float) }
            start()
        }
    }

    private fun place(value: Float) {
        offset = value
        val distance = step()
        pages.forEachIndexed { index, page ->
            page.cover.translationX = value + (index - 1) * distance
            page.cover.visibility = when {
                page.track == null -> View.INVISIBLE
                index == 1 -> View.VISIBLE
                index == 0 -> if (value > 0f) View.VISIBLE else View.INVISIBLE
                else -> if (value < 0f) View.VISIBLE else View.INVISIBLE
            }
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        place(offset)
    }

    override fun onDetachedFromWindow() {
        pendingDirection = 0
        removeCallbacks(pendingSettle)
        motion?.cancel()
        place(0f)
        super.onDetachedFromWindow()
    }
}
