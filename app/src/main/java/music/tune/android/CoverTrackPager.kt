package music.tune.android

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout

@SuppressLint("ViewConstructor")
internal class CoverTrackPager(private val ui: TuneUi, private val onTone: (Int) -> Unit) : FrameLayout(ui.context) {
    private inner class Page {
        var track: Track? = null
        var tone = Palette.lifted
        val cover = ui.art(null, 640, corner = 22) { color ->
            tone = color
            if (pages.getOrNull(1) === this) onTone(color)
        }

        fun bind(value: Track?) {
            if (track?.cover != value?.cover) tone = Palette.lifted
            track = value
            cover.visibility = if (value == null) View.INVISIBLE else View.VISIBLE
            cover.bind(value?.cover, 640)
        }
    }

    private var pages = List(3) { Page() }
    private var offset = 0f
    private var motion: ValueAnimator? = null
    private var playing: Boolean? = null
    private val pendingSettle = Runnable { settle() }

    init {
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        clipChildren = true
    }

    fun setCoverSize(size: Int) {
        pages.forEach { addView(it.cover, LayoutParams(size, size, Gravity.CENTER)) }
    }

    fun bind(previous: Track?, current: Track, next: Track?, playing: Boolean) {
        val before = pages[1].track?.id
        val change = before != current.id
        val direction = when (current.id) {
            pages[0].track?.id -> -1
            pages[2].track?.id -> 1
            else -> 0
        }
        if (change) {
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
            onTone(pages[1].tone)
            if (direction != 0 && isShown && width > 0 && ValueAnimator.areAnimatorsEnabled()) {
                place(offset + direction * width)
                settle()
            } else place(0f)
        }
        if (this.playing != playing) {
            this.playing = playing
            val scale = if (playing) 1f else .9f
            pages.forEach {
                if (ValueAnimator.areAnimatorsEnabled()) {
                    it.cover.animate().scaleX(scale).scaleY(scale).setDuration(240).start()
                } else {
                    it.cover.scaleX = scale
                    it.cover.scaleY = scale
                }
            }
        }
    }

    fun startDrag() {
        removeCallbacks(pendingSettle)
        motion?.cancel()
    }

    fun drag(displacement: Float) {
        if (!ValueAnimator.areAnimatorsEnabled()) return
        val neighbor = pages[if (displacement < 0) 2 else 0].track
        place((displacement * if (neighbor == null) .24f else 1f).coerceIn(-width.toFloat(), width.toFloat()))
    }

    fun finishDrag(direction: Int, action: () -> Unit) {
        if (pages[if (direction > 0) 2 else 0].track != null) {
            postDelayed(pendingSettle, 380)
        } else settle()
        action()
    }

    fun settle() {
        motion?.cancel()
        if (offset == 0f || !ValueAnimator.areAnimatorsEnabled()) {
            place(0f)
            return
        }
        motion = ValueAnimator.ofFloat(offset, 0f).apply {
            duration = 255
            interpolator = DecelerateInterpolator(1.7f)
            addUpdateListener { place(it.animatedValue as Float) }
            start()
        }
    }

    private fun place(value: Float) {
        offset = value
        pages.forEachIndexed { index, page -> page.cover.translationX = value + (index - 1) * width }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        place(offset)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(pendingSettle)
        motion?.cancel()
        place(0f)
        super.onDetachedFromWindow()
    }
}
