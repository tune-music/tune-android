package music.tune.android

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Point
import android.view.DragEvent
import android.view.View
import android.widget.ListView
import kotlin.math.roundToInt

@SuppressLint("ViewConstructor")
internal class PlaylistTrackList(context: Context, private val ui: TuneUi) : ListView(context) {
    private class TrackDrag(val from: Int, val last: Int, val drop: (Int, Int) -> Unit)

    private var drag: TrackDrag? = null
    private var target = -1
    private var pointerY = 0f
    private val marker = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ui.palette.accentText
        strokeWidth = ui.dp(2).toFloat()
        strokeCap = Paint.Cap.ROUND
    }
    private val edgeScroll = object : Runnable {
        override fun run() {
            val active = drag ?: return
            val edge = ui.dp(46).toFloat()
            val speed = when {
                pointerY < edge -> -((edge - pointerY) / edge)
                pointerY > height - edge -> (pointerY - height + edge) / edge
                else -> 0f
            }
            if (speed == 0f || !canScrollList(if (speed < 0) -1 else 1)) return
            scrollListBy((speed.coerceIn(-1f, 1f) * ui.dp(14)).roundToInt())
            locate(active, pointerY)
            postOnAnimation(this)
        }
    }

    fun lift(row: View, rawX: Float, from: Int, last: Int, drop: (Int, Int) -> Unit): Boolean {
        val location = IntArray(2)
        row.getLocationOnScreen(location)
        val touchX = (rawX - location[0]).roundToInt().coerceIn(0, row.width)
        val shadow = TrackShadow(row, touchX)
        return row.startDragAndDrop(null, shadow, TrackDrag(from, last, drop), 0)
    }

    override fun onDragEvent(event: DragEvent): Boolean {
        val active = event.localState as? TrackDrag ?: return super.onDragEvent(event)
        when (event.action) {
            DragEvent.ACTION_DRAG_STARTED -> {
                drag = active
                target = active.from
                invalidate()
            }
            DragEvent.ACTION_DRAG_LOCATION -> {
                pointerY = event.y
                locate(active, pointerY)
                removeCallbacks(edgeScroll)
                postOnAnimation(edgeScroll)
            }
            DragEvent.ACTION_DRAG_EXITED -> {
                removeCallbacks(edgeScroll)
                target = -1
                invalidate()
            }
            DragEvent.ACTION_DROP -> {
                if (drag !== active) return false
                locate(active, event.y)
                val destination = target
                clearDrag()
                if (destination in 0..active.last && destination != active.from) active.drop(active.from, destination)
            }
            DragEvent.ACTION_DRAG_ENDED -> clearDrag()
        }
        return true
    }

    private fun locate(active: TrackDrag, y: Float) {
        val position = pointToPosition(width / 2, y.toInt())
        val nearest = when {
            position != INVALID_POSITION -> position
            y <= (getChildAt(0)?.top ?: 0) -> firstVisiblePosition
            else -> lastVisiblePosition
        }
        target = (nearest - 1).coerceIn(0, active.last) // Position zero contains the playlist header.
        invalidate()
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        val active = drag ?: return
        if (target < 0 || target == active.from) return
        val row = getChildAt(target + 1 - firstVisiblePosition) ?: return
        val y = (if (target < active.from) row.top else row.bottom).toFloat()
        canvas.drawLine(ui.dp(16).toFloat(), y, (width - ui.dp(16)).toFloat(), y, marker)
    }

    override fun drawChild(canvas: Canvas, child: View, drawingTime: Long): Boolean {
        val active = drag
        if (active == null || child !== getChildAt(active.from + 1 - firstVisiblePosition)) {
            return super.drawChild(canvas, child, drawingTime)
        }
        val layer = canvas.saveLayerAlpha(child.left.toFloat(), child.top.toFloat(),
            child.right.toFloat(), child.bottom.toFloat(), 45)
        val drawn = super.drawChild(canvas, child, drawingTime)
        canvas.restoreToCount(layer)
        return drawn
    }

    fun clearDrag() {
        drag = null
        target = -1
        removeCallbacks(edgeScroll)
        invalidate()
    }

    override fun onDetachedFromWindow() {
        clearDrag()
        super.onDetachedFromWindow()
    }

    private inner class TrackShadow(private val row: View, private val touchX: Int) : DragShadowBuilder(row) {
        private val surface = ui.shape(ui.palette.lifted, 12)

        override fun onProvideShadowMetrics(size: Point, touch: Point) {
            size.set(row.width, row.height)
            touch.set(touchX, row.height / 2)
        }

        override fun onDrawShadow(canvas: Canvas) {
            val inset = ui.dp(6)
            surface.setBounds(inset, 0, row.width - inset, row.height)
            surface.draw(canvas)
            row.draw(canvas)
        }
    }
}
