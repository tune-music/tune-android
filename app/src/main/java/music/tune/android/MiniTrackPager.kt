package music.tune.android

import android.animation.ValueAnimator
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import kotlin.math.abs

internal class MiniTrackPager(
    private val ui: TuneUi,
    onTone: (Int) -> Unit,
    private val openPlayer: () -> Unit,
    private val previous: () -> Unit,
    private val next: () -> Unit,
    private val canPageBack: () -> Boolean,
) : FrameLayout(ui.context) {
    private class Tile(ui: TuneUi, onTone: ((Int) -> Unit)? = null) {
        private val art = ui.art(null, 200, corner = 8, tone = onTone)
        private val title = TrackTitle(ui, 15, badgeSize = 12, onDark = true)
        private val artist = ui.line("", 13, ui.palette.muted)
        val view = ui.row().apply {
            setPadding(ui.dp(8), 0, ui.dp(8), 0)
            addView(art, LinearLayout.LayoutParams(ui.dp(42), ui.dp(42)))
            addView(ui.column().apply {
                setPadding(ui.dp(10), 0, 0, 0)
                addView(title, ui.full())
                addView(ui.space(3))
                addView(artist)
            }, ui.weight())
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }

        fun bind(track: Track?) {
            view.visibility = if (track == null) View.INVISIBLE else View.VISIBLE
            title.bind(track)
            artist.text = track?.artistText.orEmpty()
            art.bind(track?.cover, 200)
        }
    }

    private val tiles = listOf(Tile(ui), Tile(ui, onTone), Tile(ui))
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var tracks = List<Track?>(3) { null }
    private var x = 0f
    private var y = 0f
    private var dragOriginX = 0f
    private var offset = 0f
    private var axis = 0
    private var velocity: VelocityTracker? = null
    private var animator: ValueAnimator? = null
    private var awaitingTrack = false
    private val settleTimeout = Runnable { awaitingTrack = false; animateTo(0f) }

    init {
        clipChildren = true
        background = ui.touch(focusHighlight = false)
        defaultFocusHighlightEnabled = false
        isClickable = true
        isFocusable = true
        tiles.forEach { addView(it.view, LayoutParams(-1, -1, Gravity.CENTER)) }
    }

    override fun performClick(): Boolean {
        super.performClick()
        openPlayer()
        return true
    }

    fun bind(before: Track?, current: Track?, after: Track?) {
        val values = listOf(before, current, after)
        if (tracks == values) return
        val previousId = tracks[1]?.id
        val currentId = current?.id
        val changed = previousId != currentId
        val direction = when (currentId) {
            null -> 0
            tracks[0]?.id -> -1
            tracks[2]?.id -> 1
            else -> 0
        }
        val animate = changed && !awaitingTrack && previousId != null && direction != 0
        if (changed) {
            animator?.cancel()
            removeCallbacks(settleTimeout)
            awaitingTrack = false
        }
        tracks = values
        tiles.forEachIndexed { index, tile -> tile.bind(values[index]) }
        contentDescription = current?.let {
            val store = (context.applicationContext as? TuneApp)?.store
            val uid = store?.profile?.value?.uid
            val usePlugin = uid == null || store?.overrides?.read(uid, "track", it.id)?.usePlugin != false
            val isCatalog = usePlugin && when {
                store != null && store.versions.hasCatalogLoaded() -> store.versions.isCatalogTrack(it.id)
                store != null -> store.catalogEnabled && it.catalog
                else -> it.catalog
            }
            val isUncensored = usePlugin && !isCatalog && when {
                store != null && store.versions.hasFckLoaded() -> store.versions.hasFckAudio(it.id)
                store != null -> store.alternateEnabled && it.alternate
                else -> it.alternate
            }
            listOfNotNull(it.title, it.artistText.takeIf(String::isNotBlank),
                "Ненормативная лексика".takeIf { _ -> it.explicit },
                "Без цензуры".takeIf { _ -> isUncensored },
                "Каталог Tune".takeIf { _ -> isCatalog },
                "Этот трек можете слушать только вы".takeIf { _ -> it.uploaded }).joinToString(", ")
        }
        if (changed) {
            place(if (animate && ValueAnimator.areAnimatorsEnabled()) width.toFloat() * direction else 0f)
            if (offset != 0f) animateTo(0f)
        }
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        place(offset)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                x = event.x
                y = event.y
                dragOriginX = 0f
                axis = 0
                velocity?.recycle()
                velocity = VelocityTracker.obtain().also { it.addMovement(event) }
                return super.onTouchEvent(event)
            }
            MotionEvent.ACTION_MOVE -> {
                velocity?.addMovement(event)
                val dx = event.x - x
                val dy = event.y - y
                if (axis == 0 && maxOf(abs(dx), abs(dy)) > slop) {
                    axis = if (abs(dx) > abs(dy)) 1 else 2
                    if (axis == 1) dragOriginX = dx
                    animator?.cancel()
                    removeCallbacks(settleTimeout)
                    awaitingTrack = false
                    parent.requestDisallowInterceptTouchEvent(true)
                    cancelPendingInputEvents()
                    isPressed = false
                }
                if (axis == 1 && ValueAnimator.areAnimatorsEnabled()) {
                    val moveX = dx - dragOriginX
                    val neighbor = if (moveX < 0) tracks[2] else tracks[0].takeIf { canPageBack() }
                    place((moveX * if (neighbor == null) .24f else 1f).coerceIn(-width.toFloat(), width.toFloat()))
                } else if (axis == 2 && ValueAnimator.areAnimatorsEnabled()) {
                    tiles[1].view.translationY = (dy * .17f).coerceIn(-ui.dp(14).toFloat(), 0f)
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val cancelled = event.actionMasked == MotionEvent.ACTION_CANCEL
                val dx = event.x - x
                val dy = event.y - y
                val speed = velocity?.let { it.addMovement(event); it.computeCurrentVelocity(1000); it.xVelocity } ?: 0f
                velocity?.recycle()
                velocity = null
                if (axis == 0) return super.onTouchEvent(event)
                isPressed = false
                parent.requestDisallowInterceptTouchEvent(false)
                tiles[1].view.animate().translationY(0f).setDuration(160).start()
                val moveX = dx - dragOriginX
                if (!cancelled && axis == 1 && (abs(moveX) > minOf(width * .3f, ui.dp(70).toFloat()) ||
                            abs(moveX) > slop && abs(speed) > ui.dp(550) && moveX * speed > 0)) {
                    val goingForward = moveX < 0
                    val target = if (goingForward) -width.toFloat() else width.toFloat()
                    val action = if (goingForward) next else previous
                    if ((if (goingForward) tracks[2] else tracks[0].takeIf { canPageBack() }) != null) {
                        awaitingTrack = true
                        animateTo(target) {
                            postDelayed(settleTimeout, 420)
                            action()
                        }
                    } else {
                        animateTo(0f)
                        action()
                    }
                } else {
                    animateTo(0f)
                    if (!cancelled && axis == 2 && dy < -ui.dp(30)) openPlayer()
                }
                axis = 0
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun place(value: Float) {
        offset = value
        tiles.forEachIndexed { index, tile -> tile.view.translationX = (index - 1) * width + value }
    }

    private fun animateTo(target: Float, finished: (() -> Unit)? = null) {
        animator?.cancel()
        if (!ValueAnimator.areAnimatorsEnabled() || offset == target) {
            place(target)
            finished?.invoke()
            return
        }
        animator = ValueAnimator.ofFloat(offset, target).apply {
            duration = 210
            interpolator = DecelerateInterpolator(1.55f)
            addUpdateListener {
                place(it.animatedValue as Float)
                if (it.animatedFraction == 1f) finished?.invoke()
            }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(settleTimeout)
        velocity?.recycle()
        velocity = null
        animator?.cancel()
        awaitingTrack = false
        isPressed = false
        place(0f)
        super.onDetachedFromWindow()
    }
}
