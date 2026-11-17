package music.tune.android

import android.animation.ValueAnimator
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView

internal data class MenuAction(val title: String, val icon: Symbol, val destructive: Boolean = false, val run: () -> Unit)

private fun TuneUi.actionRow(action: MenuAction, sheet: Boolean, click: () -> Unit) = row().apply {
    minimumHeight = dp(if (sheet) 49 else 45)
    setPadding(dp(if (sheet) 20 else 15), dp(8), dp(if (sheet) 20 else 14), dp(8))
    background = touch(radius = 5)
    val color = if (action.destructive) {
        if (palette.light) 0xffba2828.toInt() else 0xffff6a65.toInt()
    } else palette.text
    val glyph = IconView(context, action.icon, color).apply { iconSize = if (sheet) 21 else 19 }
    if (sheet) addView(glyph, LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(13) })
    addView(text(action.title, if (sheet) 17 else 15, color), weight())
    if (!sheet) addView(glyph, LinearLayout.LayoutParams(dp(23), dp(23)).apply { marginStart = dp(12) })
    setOnClickListener { click() }
    isFocusable = true
}

internal fun MainActivity.actionMenu(anchor: View, actions: List<MenuAction>) {
    if (actions.isEmpty() || !anchor.isAttachedToWindow) return
    val frame = Rect().also(anchor::getWindowVisibleDisplayFrame)
    val location = IntArray(2).also(anchor::getLocationOnScreen)
    val padding = ui.dp(12)
    val width = minOf(ui.dp(290), frame.width() - padding * 2).coerceAtLeast(1)
    val list = ui.column().apply { setPadding(0, ui.dp(5), 0, ui.dp(5)) }
    val scroll = ScrollView(this).apply {
        isVerticalScrollBarEnabled = false
        background = ui.shape(if (ui.palette.light) Color.WHITE else ui.palette.panel, 16)
        clipToOutline = true
        addView(list, FrameLayout.LayoutParams(-1, -2))
    }
    val popup = PopupWindow(scroll, width, 1, true).apply {
        setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        isOutsideTouchable = true
        elevation = ui.dp(14).toFloat()
        animationStyle = 0
    }
    actions.forEachIndexed { index, action ->
        if (action.destructive && index > 0 && !actions[index - 1].destructive) {
            list.addView(ui.divider(), ui.full(1))
        }
        list.addView(ui.actionRow(action, sheet = false) { popup.dismiss(); action.run() }, ui.full())
    }
    scroll.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
    val anchorBottom = location[1] + anchor.height + ui.dp(4)
    val below = frame.bottom - anchorBottom - padding
    val above = location[1] - frame.top - padding
    val opensBelow = below >= scroll.measuredHeight || below >= above
    val available = (if (opensBelow) below else above).coerceAtLeast(ui.dp(90))
    val height = minOf(scroll.measuredHeight, available, (frame.height() * .64f).toInt()).coerceAtLeast(1)
    popup.height = height
    val left = (location[0] + anchor.width - width).coerceIn(frame.left + padding, (frame.right - width - padding).coerceAtLeast(frame.left + padding))
    val top = (if (opensBelow) anchorBottom else location[1] - height - ui.dp(4))
        .coerceIn(frame.top + padding, (frame.bottom - height - padding).coerceAtLeast(frame.top + padding))
    popup.showAtLocation(anchor, Gravity.TOP or Gravity.LEFT, left, top)
    if (ValueAnimator.areAnimatorsEnabled()) {
        scroll.pivotX = (location[0] + anchor.width / 2f - left).coerceIn(0f, width.toFloat())
        scroll.pivotY = if (opensBelow) 0f else height.toFloat()
        scroll.alpha = 0f
        scroll.scaleX = .96f
        scroll.scaleY = .96f
        scroll.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(150).start()
    }
}

internal fun MainActivity.trackActionsSheet(track: Track, actions: List<MenuAction>) {
    val dark = TuneUi(this)
    val body = dark.column()
    val sheet = bottomSheet(body, dark = true)
    val frame = Rect().also { window.decorView.getWindowVisibleDisplayFrame(it) }
    val medium = (frame.height() * .56f).toInt()
    val expanded = (frame.height() * .86f).toInt()
    var full = false
    fun resize() {
        sheet.window?.setLayout(-1, if (full) expanded else medium)
    }
    val handle = FrameLayout(this).apply {
        contentDescription = "Развернуть или свернуть меню"
        isFocusable = true
        addView(View(context).apply { background = dark.shape(0x65ffffff, 3) },
            FrameLayout.LayoutParams(dark.dp(36), dark.dp(4), Gravity.CENTER))
        setOnClickListener { full = !full; resize() }
        var start = 0f
        setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { start = event.rawY; view.parent.requestDisallowInterceptTouchEvent(true); true }
                MotionEvent.ACTION_UP -> {
                    val travel = event.rawY - start
                    when {
                        travel < -dark.dp(34) -> { full = true; resize() }
                        travel > dark.dp(34) && full -> { full = false; resize() }
                        travel > dark.dp(34) -> sheet.dismiss()
                        else -> view.performClick()
                    }
                    true
                }
                MotionEvent.ACTION_MOVE, MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }
    }
    body.addView(handle, dark.full(28))
    body.addView(dark.row().apply {
        setPadding(dark.dp(20), dark.dp(5), dark.dp(20), dark.dp(9))
        addView(dark.art(track.cover, 200, corner = 10), LinearLayout.LayoutParams(dark.dp(56), dark.dp(56)))
        addView(dark.column().apply {
            setPadding(dark.dp(12), 0, 0, 0)
            addView(dark.line(track.title, 17, bold = true))
            addView(dark.space(3))
            addView(dark.line(track.artistText, 14, Palette.muted))
        }, dark.weight())
    }, dark.full())
    actions.forEach { action ->
        body.addView(dark.actionRow(action, sheet = true) { sheet.dismiss(); action.run() }, dark.full())
    }
    body.addView(dark.space(14))
    sheet.window?.setWindowAnimations(R.style.TuneSheetAnimation)
    sheet.show()
    resize()
}
