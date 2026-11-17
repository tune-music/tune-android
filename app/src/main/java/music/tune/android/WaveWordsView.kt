package music.tune.android

import android.animation.ValueAnimator
import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import kotlinx.coroutines.Job

internal class WaveWordsView(private val activity: MainActivity) : FrameLayout(activity) {
    private val ui = activity.ui
    private val section = VerticalDisclosure(activity)
    private val content = ui.column()
    private val storySlot = VerticalDisclosure(activity)
    private val storyBody = ui.column()
    private val feedbackButtons = ui.row()
    private val label = ui.text("", 19, bold = true)
    private val more = ui.chip("Подробнее", click = ::toggleStory)
    private var track: Track? = null
    private var card: WordCard? = null
    private var story: WaveStory? = null
    private var fetching: Job? = null
    private var expand = false
    private var showing = false
    private val removal = Runnable {
        if (!showing) section.setOpen(false)
    }

    init {
        content.setPadding(ui.dp(20), ui.dp(18), ui.dp(20), ui.dp(18))
        content.background = ui.shape(ui.palette.panel, 22)
        content.addView(ui.row().apply {
            val badge = ui.text("Слова в волне", 12, ui.palette.accentText, bold = true).apply {
                setPadding(ui.dp(10), ui.dp(5), ui.dp(10), ui.dp(5))
                background = ui.shape(0x24facc15, 30)
            }
            addView(badge)
            addView(View(activity), LinearLayout.LayoutParams(0, 1, 1f))
            addView(ui.text("ИИ", 12, ui.palette.muted))
        }, ui.full())
        content.addView(ui.space(14))
        label.setLineSpacing(ui.dp(3).toFloat(), 1f)
        content.addView(label, ui.full())
        storySlot.addView(storyBody, LayoutParams(-1, -2))
        content.addView(storySlot, ui.full())
        content.addView(ui.space(14))
        content.addView(feedbackButtons, ui.full())
        val padded = ui.column().apply {
            setPadding(ui.dp(16), ui.dp(5), ui.dp(16), ui.dp(16))
            addView(content, ui.full())
        }
        section.addView(padded, LayoutParams(-1, -2))
        addView(section, LayoutParams(-1, -2))
    }

    fun bind(nextTrack: Track?, nextCard: WordCard?) {
        if (nextTrack == null || nextCard == null) {
            if (!showing) return
            showing = false
            section.hideContent()
            if (ValueAnimator.areAnimatorsEnabled() && isAttachedToWindow) postDelayed(removal, 280) else removal.run()
            return
        }
        val changed = card?.id != nextCard.id || track?.id != nextTrack.id
        val feedbackChanged = card?.feedback != nextCard.feedback
        if (showing && !changed && !feedbackChanged) return
        removeCallbacks(removal)
        if (changed) {
            section.animateContentChange()
            fetching?.cancel()
            fetching = null
            track = nextTrack
            story = null
            expand = false
            storySlot.setOpen(false, animated = false)
            storyBody.removeAllViews()
            label.text = nextCard.text
        }
        if (changed || feedbackChanged) {
            card = nextCard
            bindButtons()
        }
        if (!showing) section.setOpen(true)
        showing = true
        if (isShown) activity.store.waveExtras.shown(nextCard)
    }

    private fun bindButtons() {
        val card = card ?: return
        val track = track ?: return
        feedbackButtons.removeAllViews()
        fun addRating(title: String, state: String) {
            feedbackButtons.addView(ui.chip(title, card.feedback == state) {
                activity.action(onError = { activity.toast("Не удалось отправить оценку") }) {
                    activity.store.waveExtras.rate(track, card, state)
                }
            }.apply {
                textSize = 13f
                setPadding(ui.dp(8), 0, ui.dp(8), 0)
            }, LinearLayout.LayoutParams(-2, ui.dp(42)).apply { marginEnd = ui.dp(6) })
        }
        addRating("Больше такого", "like")
        addRating("Мимо", "dislike")
        feedbackButtons.addView(View(activity), LinearLayout.LayoutParams(0, 1, 1f))
        if (card.story != null) {
            more.textSize = 13f
            more.setPadding(ui.dp(4), 0, ui.dp(4), 0)
            more.background = ui.touch()
            more.gravity = Gravity.END or Gravity.CENTER_VERTICAL
            feedbackButtons.addView(more, LinearLayout.LayoutParams(-2, ui.dp(42)))
            bindMore()
        }
    }

    private fun bindMore() {
        more.text = when {
            fetching != null -> "Собираю…"
            expand -> "Свернуть"
            else -> "Подробнее ›"
        }
        more.isEnabled = fetching == null
        more.setTextColor(if (expand || fetching != null) ui.palette.muted else ui.palette.accentText)
    }

    private fun toggleStory() {
        val track = track ?: return
        val card = card ?: return
        if (!showing || fetching != null) return
        if (story != null) {
            expand = !expand
            storySlot.setOpen(expand)
            bindMore()
            return
        }
        fetching = activity.action(onError = { activity.toast("Яндекс не рассказал подробнее") }) {
            try {
                val loaded = activity.store.waveExtras.story(track, card) ?: return@action
                if (this.track?.id != track.id || this.card?.id != card.id) return@action
                story = loaded
                storyBody.removeAllViews()
                storyBody.addView(ui.space(14))
                storyBody.addView(ui.text(loaded.text, 15).apply { setLineSpacing(ui.dp(4).toFloat(), 1f) }, ui.full())
                loaded.action?.let { (title, url) ->
                    storyBody.addView(ui.text("$title ›", 13, ui.palette.accentText, bold = true).apply {
                        minHeight = ui.dp(44)
                        gravity = Gravity.CENTER_VERTICAL
                        setOnClickListener { activity.browser(url) }
                        isFocusable = true
                    })
                }
                expand = true
                storySlot.setOpen(true)
            } finally {
                if (this.track?.id == track.id && this.card?.id == card.id) {
                    fetching = null
                    bindMore()
                }
            }
        }
        bindMore()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        card?.takeIf { showing }?.let(activity.store.waveExtras::shown)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(removal)
        if (!showing) section.setOpen(false, animated = false)
        super.onDetachedFromWindow()
    }
}

/** Measures content at its full height and reveals it from the top without moving the text. */
private class VerticalDisclosure(context: Context) : FrameLayout(context) {
    private var fraction = 0f
    private var target = 0f
    private var motion: ValueAnimator? = null
    private var resize: ValueAnimator? = null
    private var previousHeight: Int? = null
    private var resizeFraction = 1f
    private var fullHeight = 0

    init {
        clipChildren = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }

    fun hideContent() {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        getChildAt(0)?.let { child ->
            child.animate().cancel()
            child.animate().alpha(0f).setDuration(150).start()
        }
    }

    fun animateContentChange() {
        if (!isAttachedToWindow || !ValueAnimator.areAnimatorsEnabled() || height == 0 || target != 1f) return
        resize?.cancel()
        previousHeight = height
        resizeFraction = 0f
        resize = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 340
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                resizeFraction = it.animatedValue as Float
                if (resizeFraction == 1f) previousHeight = null
                requestLayout()
            }
            start()
        }
    }

    fun setOpen(open: Boolean, animated: Boolean = true) {
        val next = if (open) 1f else 0f
        importantForAccessibility = if (open) IMPORTANT_FOR_ACCESSIBILITY_AUTO else IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        getChildAt(0)?.let { child ->
            child.animate().cancel()
            if (animated && isAttachedToWindow && ValueAnimator.areAnimatorsEnabled()) {
                child.animate().alpha(next).setDuration(if (open) 220 else 150).start()
            } else child.alpha = next
        }
        if (target == next && motion?.isRunning == true && animated) return
        target = next
        motion?.cancel()
        if (fraction == next) return
        if (!animated || !isAttachedToWindow || !ValueAnimator.areAnimatorsEnabled()) {
            fraction = next
            requestLayout()
            return
        }
        motion = ValueAnimator.ofFloat(fraction, next).apply {
            duration = 340
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { animation ->
                fraction = animation.animatedValue as Float
                requestLayout()
            }
            start()
        }
    }

    override fun onInterceptTouchEvent(event: android.view.MotionEvent): Boolean =
        importantForAccessibility == IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS || super.onInterceptTouchEvent(event)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val child = getChildAt(0)
        val width = MeasureSpec.getSize(widthMeasureSpec)
        child?.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        fullHeight = child?.measuredHeight ?: 0
        val start = previousHeight
        val height = (fullHeight * fraction).toInt()
        setMeasuredDimension(width, if (start == null) height else (start + (height - start) * resizeFraction).toInt())
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        getChildAt(0)?.layout(0, 0, right - left, fullHeight)
    }

    override fun dispatchDraw(canvas: android.graphics.Canvas) {
        val saved = canvas.save()
        canvas.clipRect(0, 0, width, height)
        super.dispatchDraw(canvas)
        canvas.restoreToCount(saved)
    }

    override fun onDetachedFromWindow() {
        motion?.cancel()
        resize?.cancel()
        previousHeight = null
        fraction = target
        getChildAt(0)?.let { it.animate().cancel(); it.alpha = target }
        super.onDetachedFromWindow()
    }
}
