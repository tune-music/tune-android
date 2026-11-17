package music.tune.android

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.LinearLayout
import android.widget.ProgressBar

internal fun TuneUi.loadingRows(count: Int = 7): View = column().apply {
    setPadding(dp(16), 0, dp(16), 0)
    repeat(count) { index ->
        addView(row().apply {
            addView(loadingShape(6), LinearLayout.LayoutParams(dp(46), dp(46)))
            addView(column().apply {
                addView(loadingShape(), LinearLayout.LayoutParams(dp(if (index % 2 == 0) 170 else 120), dp(12)))
                addView(space(8))
                addView(loadingShape(), LinearLayout.LayoutParams(dp(if (index % 3 == 0) 90 else 130), dp(10)))
            }, weight().apply { marginStart = dp(12) })
        }, full(60))
    }
    pulseWhileAttached()
}

internal fun TuneUi.loadingGrid(circle: Boolean): View = column().apply {
    val size = ((context.resources.configuration.screenWidthDp - 44) / 2).coerceIn(96, 320)
    setPadding(dp(16), 0, dp(16), 0)
    repeat(2) {
        addView(row().apply {
            repeat(2) { index ->
                addView(column().apply {
                    addView(loadingShape(if (circle) size / 2 else 14), full(size))
                    addView(space(8))
                    addView(loadingShape(), LinearLayout.LayoutParams(dp(size * 2 / 3), dp(12)))
                }, LinearLayout.LayoutParams(dp(size), -2).apply { if (index != 0) marginStart = dp(12) })
            }
        }, full().apply { topMargin = dp(8); bottomMargin = dp(14) })
    }
    pulseWhileAttached()
}

internal fun TuneUi.loadingShape(radius: Int = 12) = View(context).apply {
    background = shape(palette.panel, radius)
    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
}

internal fun TuneUi.loadingTrackControls(): View = row().apply {
    gravity = Gravity.CENTER
    addView(loadingShape(25), LinearLayout.LayoutParams(dp(50), dp(50)))
    addView(loadingShape(25), LinearLayout.LayoutParams(0, dp(50), 1f).apply { marginStart = dp(14); marginEnd = dp(14) })
    addView(loadingShape(25), LinearLayout.LayoutParams(dp(50), dp(50)))
    pulseWhileAttached()
}

internal fun TuneUi.pagePlaceholder(symbol: Symbol, title: String, message: String = "", retry: (() -> Unit)? = null): View = column().apply {
    gravity = Gravity.CENTER
    setPadding(dp(24), dp(34), dp(24), dp(36))
    addView(IconView(context, symbol, palette.muted).apply { iconSize = 36 },
        LinearLayout.LayoutParams(dp(46), dp(46)))
    addView(space(10))
    addView(text(title, 18, bold = true).apply { gravity = Gravity.CENTER })
    if (message.isNotBlank()) {
        addView(space(10))
        addView(text(message, 15, palette.muted).apply { gravity = Gravity.CENTER })
    }
    if (retry != null) {
        addView(space(14))
        addView(button("Повторить", click = retry).apply { background = touch(palette.panel, 24); textSize = 15f })
    }
}

internal fun TuneUi.searchPageProgress(): View = row().apply {
    gravity = Gravity.CENTER
    setPadding(0, dp(12), 0, dp(12))
    addView(ProgressBar(context).apply {
        contentDescription = "Загрузка результатов"
        indeterminateTintList = ColorStateList.valueOf(palette.muted)
    }, LinearLayout.LayoutParams(dp(22), dp(22)))
}

internal fun View.pulseWhileAttached() {
    if (!ValueAnimator.areAnimatorsEnabled()) return
    val pulse = ObjectAnimator.ofFloat(this, View.ALPHA, 1f, .45f).apply {
        duration = 900
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.REVERSE
        interpolator = AccelerateDecelerateInterpolator()
    }
    addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) { pulse.start() }
        override fun onViewDetachedFromWindow(view: View) { pulse.cancel(); view.alpha = 1f }
    })
}
