package music.tune.android

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.CornerPathEffect
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import coil.dispose
import coil.load
import coil.request.CachePolicy
import androidx.core.graphics.ColorUtils

internal class TuneColors(val light: Boolean) {
    val background = if (light) Color.WHITE else Color.BLACK
    val panel = if (light) 0xfff2f2f2.toInt() else 0xff1c1c1c.toInt()
    val lifted = if (light) 0xffe6e6e6.toInt() else 0xff2b2b2b.toInt()
    val line = if (light) 0xffd9d9d9.toInt() else 0xff303030.toInt()
    val text = if (light) 0xff121212.toInt() else Color.WHITE
    val muted = if (light) 0xff6b6b6b.toInt() else 0xff999999.toInt()
    val accent = 0xfffacc15.toInt()
    val accentText = if (light) 0xffb88000.toInt() else accent
}

internal val Palette = TuneColors(false)

internal class TuneUi(val context: Context, val palette: TuneColors = Palette) {
    fun dp(value: Int): Int = (value * context.resources.displayMetrics.density + .5f).toInt()
    fun column() = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    fun row() = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    fun full(height: Int = -2) = LinearLayout.LayoutParams(-1, if (height < 0) height else dp(height))
    fun weight() = LinearLayout.LayoutParams(0, -2, 1f)
    fun shape(color: Int, radius: Int = 8) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }
    fun touch(color: Int = Color.TRANSPARENT, radius: Int = 8, showSelection: Boolean = false,
              focusHighlight: Boolean = true) = shape(color, radius).apply {
        val opaque = color != Color.TRANSPARENT
        val overlay = if (palette.light || color == palette.accent) 0x15000000 else 0x17ffffff
        val highlight = if (opaque) ColorUtils.compositeColors(overlay, color) else Color.TRANSPARENT
        val selected = if (showSelection) ColorUtils.compositeColors(0x1cfacc15, color) else color
        setColor(ColorStateList(arrayOf(
            intArrayOf(android.R.attr.state_pressed, android.R.attr.state_enabled),
            intArrayOf(android.R.attr.state_focused, android.R.attr.state_enabled),
            intArrayOf(android.R.attr.state_selected),
            intArrayOf(),
        ), intArrayOf(highlight, if (opaque && focusHighlight) highlight else color, selected, color)))
    }

    fun text(label: String, size: Int, color: Int = palette.text, bold: Boolean = false): TextView = TextView(context).apply {
        text = label
        textSize = size.toFloat()
        setTextColor(color)
        includeFontPadding = false
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    fun line(label: String, size: Int, color: Int = palette.text, bold: Boolean = false): TextView =
        text(label, size, color, bold).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }

    fun icon(symbol: Symbol, label: String, color: Int = palette.text, click: () -> Unit): IconView =
        IconView(context, symbol, color).apply {
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
            contentDescription = label
            tooltipText = label
            background = touch()
            pressFeedback()
            setOnClickListener { click() }
            isFocusable = true
        }

    fun button(label: String, primary: Boolean = false, click: () -> Unit) = text(
        label, 14, if (primary) Color.BLACK else palette.text, bold = true
    ).apply {
        gravity = Gravity.CENTER
        minHeight = dp(48)
        setPadding(dp(16), dp(10), dp(16), dp(10))
        background = touch(if (primary) palette.accent else palette.lifted)
        pressFeedback(.94f)
        setOnClickListener { click() }
        isFocusable = true
        accessibilityDelegate = ButtonAccessibility
    }

    fun art(uri: String?, size: Int, circle: Boolean = false, corner: Int = 10, backdrop: Boolean = true, tone: ((Int) -> Unit)? = null): CoverView =
        CoverView(context, tone, showPlaceholder = backdrop).apply {
            background = shape(if (backdrop) palette.lifted else Color.TRANSPARENT, if (circle) 500 else corner)
            clipToOutline = circle || corner > 0
            bind(uri, size)
        }

    fun chip(label: String, active: Boolean = false, click: () -> Unit) = button(label, active, click).apply {
        minHeight = dp(40)
        setPadding(dp(15), dp(8), dp(15), dp(8))
        background = touch(if (active) palette.accent else palette.lifted, 30)
        isSelected = active
    }

    fun symbolCover(symbol: Symbol, size: Int, corner: Int = 18) =
        IconView(context, symbol, if (symbol == Symbol.HEART_FILLED) Color.BLACK else palette.text).apply {
            val colors = when {
                symbol == Symbol.HEART_FILLED -> intArrayOf(0xffffd440.toInt(), 0xfff7a81a.toInt())
                palette.light -> intArrayOf(0xffd6d6d6.toInt(), 0xffe6e6e6.toInt())
                else -> intArrayOf(0xff404040.toInt(), 0xff262626.toInt())
            }
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, colors).apply { cornerRadius = dp(corner).toFloat() }
            iconSize = (size * .42f).toInt()
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }

    fun space(height: Int) = View(context).apply { layoutParams = full(height) }
    fun divider() = View(context).apply {
        setBackgroundColor(palette.line)
        layoutParams = LinearLayout.LayoutParams(-1, dp(1))
    }

    fun heading(title: String) = text(title, 20, bold = true).apply {
        setPadding(dp(16), dp(20), dp(16), dp(8))
    }

    fun note(message: String) = text(message, 14, palette.muted).apply {
        setLineSpacing(dp(3).toFloat(), 1f)
        setPadding(dp(16), dp(15), dp(16), dp(18))
    }
}

internal fun View.pressFeedback(scale: Float = .86f) {
    if (!ValueAnimator.areAnimatorsEnabled()) return
    foreground = PressFeedbackDrawable(this, scale)
}

private class PressFeedbackDrawable(
    private val host: View,
    private val pressedScale: Float,
) : Drawable() {
    private var wasPressed = false
    private var releasing = false
    private var restAlpha = 1f

    override fun isStateful() = true

    override fun onStateChange(state: IntArray): Boolean {
        var pressed = false
        var enabled = false
        for (flag in state) {
            when (flag) {
                android.R.attr.state_pressed -> pressed = true
                android.R.attr.state_enabled -> enabled = true
            }
        }
        val active = pressed && enabled
        if (active == wasPressed) return false
        wasPressed = active
        if (active) {
            if (!releasing) restAlpha = host.alpha
            releasing = false
            host.animate().cancel()
            host.animate()
                .scaleX(pressedScale)
                .scaleY(pressedScale)
                .alpha(restAlpha * .75f)
                .setDuration(95)
                .setInterpolator(DecelerateInterpolator())
                .withEndAction(null)
                .start()
        } else {
            releasing = true
            host.animate().cancel()
            val anim = host.animate()
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(225)
                .setInterpolator(OvershootInterpolator(.85f))
                .withEndAction { releasing = false }
            if (enabled) anim.alpha(restAlpha)
            anim.start()
        }
        return false
    }

    override fun jumpToCurrentState() {
        super.jumpToCurrentState()
        if (wasPressed || releasing) {
            host.animate().cancel()
            host.scaleX = 1f
            host.scaleY = 1f
            if (host.isEnabled) host.alpha = restAlpha
            wasPressed = false
            releasing = false
        }
    }

    override fun draw(canvas: Canvas) = Unit
    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) = Unit

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

private object ButtonAccessibility : View.AccessibilityDelegate() {
    override fun onInitializeAccessibilityNodeInfo(host: View, info: android.view.accessibility.AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(host, info)
        info.className = "android.widget.Button"
    }
}

internal class CoverView(context: Context, private val tone: ((Int) -> Unit)? = null, private val showPlaceholder: Boolean = true) : FrameLayout(context) {
    private val image = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
    private val placeholder = IconView(context, Symbol.NOTE, Palette.muted)
    private var lastOffline = false

    init {
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        placeholder.visibility = if (showPlaceholder) View.VISIBLE else View.GONE
        addView(placeholder, LayoutParams(dp(32), dp(32), Gravity.CENTER))
        addView(image, LayoutParams(-1, -1))
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    fun bind(cover: String?, size: Int) {
        val store = (context.applicationContext as? TuneApp)?.store
        val offline = store?.offline == true
        val downloaded = cover?.let { store?.files?.savedCover(it) }
        val source = MetadataOverrides.image(context, cover) ?: downloaded ?: coverUrl(cover, size)
        if (source == image.tag && lastOffline == offline) return
        image.tag = source
        lastOffline = offline
        placeholder.visibility = if (showPlaceholder) View.VISIBLE else View.GONE
        if (source == null) {
            image.dispose()
            image.setImageDrawable(null)
            tone?.invoke(Palette.lifted)
        } else image.load(source) {
            crossfade(130)
            size(size)
            if (offline) networkCachePolicy(CachePolicy.DISABLED)
            if (tone != null) allowHardware(false)
            listener(onSuccess = { _, result ->
                if (image.tag == source) {
                    placeholder.visibility = if (showPlaceholder) View.INVISIBLE else View.GONE
                    tone?.invoke(coverTone(result.drawable as? BitmapDrawable))
                }
            }, onError = { _, _ ->
                if (image.tag == source) tone?.invoke(Palette.lifted)
            })
        }
    }

    private fun coverTone(drawable: BitmapDrawable?): Int {
        val bitmap = drawable?.bitmap ?: return Palette.lifted
        val sample = android.graphics.Bitmap.createScaledBitmap(bitmap, 5, 5, true)
        var red = 0
        var green = 0
        var blue = 0
        for (y in 0 until 5) for (x in 0 until 5) {
            val color = sample.getPixel(x, y)
            red += Color.red(color)
            green += Color.green(color)
            blue += Color.blue(color)
        }
        if (sample !== bitmap) sample.recycle()
        val hsv = FloatArray(3)
        Color.RGBToHSV(red / 25, green / 25, blue / 25, hsv)
        hsv[1] = minOf(hsv[1] * 1.15f, .7f)
        hsv[2] = hsv[2].coerceIn(.3f, .46f)
        return Color.HSVToColor(hsv)
    }
}

internal enum class Symbol {
    HOME, SEARCH, LIBRARY, WAVE, SETTINGS, BACK, DOWN, MORE, PLAY, PAUSE, NEXT, PREVIOUS,
    NOTE, HEART, HEART_FILLED, HEART_SLASH, BAN, DOWNLOAD, CHECK, QUEUE, QUEUE_NEXT, QUEUE_LAST, PLAYLIST_ADD,
    LYRICS, REPEAT, REPEAT_ONE, SHUFFLE, CLOCK, HISTORY, ADD, CLOSE, MINUS_CIRCLE, SHARE,
    ARROW_UP, ARROW_DOWN, ARROW_UP_LEFT, VIDEO, CONCERT, LINK, OUTPUT, MOON, THUMB_DOWN, THUMB_DOWN_FILLED,
    ACCOUNT, PERSON_MINUS, GEAR, STATS, TRASH, EDIT, FILTER, SORT, REORDER, PHOTO, SPEAKER, CIRCLE, CHECK_CIRCLE,
    FILE, DOWNLOAD_CIRCLE, UNLOCK, EYE_OFF, WIFI_OFF, MIC_MUSIC, MIC, BOOK, BALLOON, GRID
}

internal class IconView(context: Context, var symbol: Symbol, var tint: Int) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 1.8f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()
    private val rounded = CornerPathEffect(1.8f)
    private var glyphScale = 1f
    private var glyphMotion: ValueAnimator? = null
    var iconSize = 24

    fun animateGlyph() {
        if (!ValueAnimator.areAnimatorsEnabled() || !isAttachedToWindow || !isShown) return
        glyphMotion?.cancel()
        glyphMotion = ValueAnimator.ofFloat(.74f, 1f).apply {
            duration = 215
            interpolator = OvershootInterpolator(.65f)
            addUpdateListener {
                glyphScale = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val side = minOf(width.toFloat(), height.toFloat(), iconSize * resources.displayMetrics.density)
        if (side <= 0) return
        canvas.save()
        canvas.translate((width - side) / 2, (height - side) / 2)
        canvas.scale(side / 24f, side / 24f)
        canvas.scale(glyphScale, glyphScale, 12f, 12f)
        paint.color = if (!ValueAnimator.areAnimatorsEnabled() && isPressed && isClickable) {
            ColorUtils.setAlphaComponent(tint, (Color.alpha(tint) * .75f).toInt())
        } else tint
        paint.style = Paint.Style.STROKE
        paint.pathEffect = null
        fun line(x: Number, y: Number, x2: Number, y2: Number) = canvas.drawLine(x.toFloat(), y.toFloat(), x2.toFloat(), y2.toFloat(), paint)
        fun circle(x: Number, y: Number, radius: Number) = canvas.drawCircle(x.toFloat(), y.toFloat(), radius.toFloat(), paint)
        fun shape(vararg points: Number, close: Boolean = false, fill: Boolean = false) {
            path.rewind()
            path.moveTo(points[0].toFloat(), points[1].toFloat())
            for (i in 2 until points.size step 2) path.lineTo(points[i].toFloat(), points[i + 1].toFloat())
            if (close) path.close()
            if (fill) paint.style = Paint.Style.FILL
            if (fill) paint.pathEffect = rounded
            canvas.drawPath(path, paint)
            paint.pathEffect = null
            paint.style = Paint.Style.STROKE
        }
        when (symbol) {
            Symbol.WIFI_OFF -> {
                canvas.drawArc(-2f, 5f, 26f, 29f, 225f, 35f, false, paint)
                canvas.drawArc(-2f, 5f, 26f, 29f, 275f, 40f, false, paint)
                canvas.drawArc(2f, 10f, 22f, 26f, 220f, 32f, false, paint)
                canvas.drawArc(6f, 14.5f, 18f, 24.5f, 222f, 26f, false, paint)
                line(3, 3, 21, 21)
                paint.style = Paint.Style.FILL
                circle(12, 20, 1)
            }
            Symbol.HOME -> shape(2, 11, 12, 3, 22, 11, 20, 11, 20, 21, 14, 21, 14, 15, 10, 15, 10, 21, 4, 21, 4, 11, close = true, fill = true)
            Symbol.SEARCH -> { circle(10.5f, 10.5f, 6.5f); line(16, 16, 21, 21) }
            Symbol.LIBRARY -> {
                line(7.5f, 3.8f, 16.5f, 3.8f)
                line(5.5f, 7f, 18.5f, 7f)
                paint.style = Paint.Style.FILL
                canvas.drawRoundRect(3.5f, 10.2f, 20.5f, 21f, 2.8f, 2.8f, paint)
                paint.style = Paint.Style.STROKE
            }
            Symbol.WAVE -> shape(2, 12, 5, 12, 8, 5, 11, 19, 14, 7, 17, 15, 19, 12, 22, 12)
            Symbol.SETTINGS -> { line(3, 6, 21, 6); line(3, 12, 21, 12); line(3, 18, 21, 18)
                paint.style = Paint.Style.FILL; circle(8, 6, 2.2f); circle(16, 12, 2.2f); circle(10, 18, 2.2f) }
            Symbol.GEAR -> {
                path.rewind()
                val teeth = 8
                val outer = 9.8f
                val inner = 7.4f
                for (i in 0 until teeth) {
                    val base = i * 2.0 * Math.PI / teeth
                    val step = 2.0 * Math.PI / (teeth * 4)
                    val angles = doubleArrayOf(base - step * 1.1, base - step * .55, base + step * .55, base + step * 1.1)
                    val radii = floatArrayOf(inner, outer, outer, inner)
                    for (j in angles.indices) {
                        val x = 12f + radii[j] * kotlin.math.cos(angles[j]).toFloat()
                        val y = 12f + radii[j] * kotlin.math.sin(angles[j]).toFloat()
                        if (i == 0 && j == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                }
                path.close()
                paint.pathEffect = CornerPathEffect(.9f)
                canvas.drawPath(path, paint)
                paint.pathEffect = null
                circle(12, 12, 3.1f)
            }
            Symbol.STATS -> {
                paint.style = Paint.Style.FILL
                canvas.drawRoundRect(3f, 12f, 7f, 21f, 1f, 1f, paint)
                canvas.drawRoundRect(10f, 7f, 14f, 21f, 1f, 1f, paint)
                canvas.drawRoundRect(17f, 3f, 21f, 21f, 1f, 1f, paint)
            }
            Symbol.BACK -> shape(15, 4, 7, 12, 15, 20)
            Symbol.DOWN -> shape(5, 9, 12, 16, 19, 9)
            Symbol.MORE -> { paint.style = Paint.Style.FILL; circle(5, 12, 1.4f); circle(12, 12, 1.4f); circle(19, 12, 1.4f) }
            Symbol.PLAY -> shape(7, 3.5f, 21, 12, 7, 20.5f, close = true, fill = true)
            Symbol.PAUSE -> {
                paint.style = Paint.Style.FILL
                canvas.drawRoundRect(6f, 4f, 10f, 20f, 1f, 1f, paint)
                canvas.drawRoundRect(14f, 4f, 18f, 20f, 1f, 1f, paint)
            }
            Symbol.NEXT, Symbol.PREVIOUS -> {
                if (symbol == Symbol.PREVIOUS) canvas.scale(-1f, 1f, 12f, 12f)
                shape(2, 5, 13, 12, 2, 19, close = true, fill = true)
                shape(12, 5, 23, 12, 12, 19, close = true, fill = true)
            }
            Symbol.NOTE -> {
                shape(9, 17, 9, 6, 20, 3, 20, 14)
                paint.style = Paint.Style.FILL; circle(6.5f, 17.5f, 2.8f); circle(17.5f, 14.5f, 2.8f)
            }
            Symbol.HEART, Symbol.HEART_FILLED, Symbol.HEART_SLASH -> {
                if (symbol == Symbol.HEART_FILLED) paint.style = Paint.Style.FILL
                path.rewind()
                path.moveTo(12f, 20.5f)
                path.cubicTo(10f, 18.8f, 3f, 14.6f, 3f, 9.5f)
                path.cubicTo(3f, 4.1f, 9f, 3.1f, 12f, 7.4f)
                path.cubicTo(15f, 3.1f, 21f, 4.1f, 21f, 9.5f)
                path.cubicTo(21f, 14.6f, 14f, 18.8f, 12f, 20.5f)
                path.close()
                canvas.drawPath(path, paint)
                if (symbol == Symbol.HEART_SLASH) line(3.5f, 3.5f, 20.5f, 20.5f)
            }
            Symbol.BAN -> { circle(12, 12, 8); line(6.5f, 6.5f, 17.5f, 17.5f) }
            Symbol.DOWNLOAD -> { line(12, 3, 12, 15); shape(7, 11, 12, 16, 17, 11); line(4, 21, 20, 21) }
            Symbol.CHECK -> shape(4, 12, 9, 17, 20, 6)
            Symbol.QUEUE -> { line(3, 6, 21, 6); line(3, 12, 17, 12); line(3, 18, 13, 18); shape(17, 16, 22, 19, 17, 22, close = true, fill = true) }
            Symbol.QUEUE_NEXT -> {
                shape(4, 4.5f, 7, 7, 4, 9.5f)
                line(10, 7, 20.5f, 7)
                line(4, 13, 20.5f, 13)
                line(4, 18.5f, 17, 18.5f)
            }
            Symbol.QUEUE_LAST -> {
                line(4, 6, 20.5f, 6)
                line(4, 11.5f, 20.5f, 11.5f)
                shape(4, 14.5f, 7, 17, 4, 19.5f)
                line(10, 17, 20.5f, 17)
            }
            Symbol.PLAYLIST_ADD -> {
                line(3.5f, 6, 19.5f, 6)
                line(3.5f, 11.5f, 16.5f, 11.5f)
                line(3.5f, 17, 12, 17)
                line(17.5f, 13.5f, 17.5f, 20.5f)
                line(14, 17, 21, 17)
            }
            Symbol.LYRICS -> {
                shape(7.2f, 4.8f, 4.5f, 7, 4.5f, 10.5f, 7.2f, 10.5f)
                shape(12.2f, 4.8f, 9.5f, 7, 9.5f, 10.5f, 12.2f, 10.5f)
                line(16, 6.2f, 20.5f, 6.2f)
                line(16, 10.2f, 20.5f, 10.2f)
                line(4.5f, 15, 20.5f, 15)
                line(4.5f, 19.5f, 15.5f, 19.5f)
            }
            Symbol.REPEAT, Symbol.REPEAT_ONE -> {
                shape(3, 11, 3, 7, 20, 7); shape(16, 3, 20, 7, 16, 11)
                shape(21, 13, 21, 17, 4, 17); shape(8, 13, 4, 17, 8, 21)
                if (symbol == Symbol.REPEAT_ONE) shape(11, 11, 12.5f, 10, 12.5f, 14)
            }
            Symbol.SHUFFLE -> { shape(3, 6, 7, 6, 17, 18, 21, 18); shape(17, 14, 21, 18, 17, 22)
                shape(3, 18, 7, 18, 10, 14); shape(14, 10, 17, 6, 21, 6); shape(17, 2, 21, 6, 17, 10) }
            Symbol.CLOCK -> { circle(12, 12, 8.5f); shape(12, 7, 12, 12, 16, 14) }
            Symbol.HISTORY -> {
                canvas.drawArc(3.8f, 3.8f, 20.2f, 20.2f, -155f, 305f, false, paint)
                shape(3.4f, 5.2f, 4.5f, 8.8f, 8.1f, 7.7f)
                shape(12, 7.5f, 12, 12.2f, 15.2f, 14)
            }
            Symbol.ADD -> { line(12, 4, 12, 20); line(4, 12, 20, 12) }
            Symbol.CLOSE -> { line(5, 5, 19, 19); line(19, 5, 5, 19) }
            Symbol.MINUS_CIRCLE -> { circle(12, 12, 8.5f); line(8, 12, 16, 12) }
            Symbol.SHARE -> { shape(8, 9, 5, 9, 5, 22, 19, 22, 19, 9, 16, 9)
                line(12, 14, 12, 2); shape(8, 6, 12, 2, 16, 6) }
            Symbol.ARROW_UP -> { line(12, 20, 12, 4); shape(6, 10, 12, 4, 18, 10) }
            Symbol.ARROW_DOWN -> { line(12, 4, 12, 20); shape(6, 14, 12, 20, 18, 14) }
            Symbol.ARROW_UP_LEFT -> { line(18, 18, 6, 6); shape(6, 14, 6, 6, 14, 6) }
            Symbol.VIDEO -> { canvas.drawRoundRect(3f, 5f, 21f, 19f, 2f, 2f, paint); shape(10, 9, 16, 12, 10, 15, close = true, fill = true) }
            Symbol.CONCERT -> {
                canvas.drawRoundRect(3.5f, 5f, 20.5f, 20.5f, 3f, 3f, paint)
                line(8, 2.8f, 8, 6.2f)
                line(16, 2.8f, 16, 6.2f)
                line(3.5f, 9.5f, 20.5f, 9.5f)
                paint.style = Paint.Style.FILL
                circle(7.5f, 13, 1.1f); circle(12, 13, 1.1f); circle(16.5f, 13, 1.1f)
                circle(7.5f, 16.8f, 1.1f); circle(12, 16.8f, 1.1f)
                paint.style = Paint.Style.STROKE
            }
            Symbol.LINK -> { canvas.drawRoundRect(RectF(3f, 8f, 12f, 16f), 4f, 4f, paint)
                canvas.drawRoundRect(RectF(12f, 8f, 21f, 16f), 4f, 4f, paint); line(8, 12, 16, 12) }
            Symbol.OUTPUT -> {
                canvas.drawArc(3f, 3f, 21f, 21f, 140f, 260f, false, paint)
                canvas.drawArc(6.5f, 6.5f, 17.5f, 17.5f, 145f, 250f, false, paint)
                shape(12, 14, 16.5f, 20.5f, 7.5f, 20.5f, close = true, fill = true)
            }
            Symbol.MOON -> {
                path.rewind()
                path.moveTo(13.2f, 3.5f)
                path.cubicTo(4f, 3.5f, 2f, 16.5f, 10.5f, 20.2f)
                path.cubicTo(15f, 22f, 19.5f, 19.5f, 20.5f, 15.2f)
                path.cubicTo(14f, 17f, 8.8f, 11f, 13.2f, 3.5f)
                path.close()
                canvas.drawPath(path, paint)
                shape(15.2f, 4.8f, 18.6f, 4.8f, 15.2f, 8.2f, 18.6f, 8.2f)
                shape(18f, 9.8f, 21f, 9.8f, 18f, 12.8f, 21f, 12.8f)
            }
            Symbol.THUMB_DOWN, Symbol.THUMB_DOWN_FILLED -> {
                val fill = symbol == Symbol.THUMB_DOWN_FILLED
                if (fill) paint.style = Paint.Style.FILL
                canvas.drawRoundRect(17.5f, 4.5f, 21f, 13.5f, 1.5f, 1.5f, paint)
                path.rewind()
                path.moveTo(17.5f, 5f)
                path.lineTo(9.2f, 5f)
                path.cubicTo(7.4f, 5f, 6.2f, 6.1f, 5.7f, 7.6f)
                path.lineTo(4.2f, 12.2f)
                path.cubicTo(3.8f, 13.6f, 4.7f, 14.8f, 6.2f, 14.8f)
                path.lineTo(10.6f, 14.8f)
                path.lineTo(9.8f, 18.8f)
                path.cubicTo(9.5f, 20.2f, 10.7f, 21f, 11.8f, 20.3f)
                path.cubicTo(12.3f, 20f, 12.7f, 19.3f, 13.2f, 18.3f)
                path.lineTo(16.2f, 13.5f)
                path.lineTo(17.5f, 12.8f)
                if (fill) path.close()
                canvas.drawPath(path, paint)
                paint.style = Paint.Style.STROKE
            }
            Symbol.ACCOUNT -> {
                circle(12, 12, 9)
                circle(12, 9, 2.5f)
                path.rewind()
                path.moveTo(6.5f, 18f)
                path.quadTo(12f, 11f, 17.5f, 18f)
                canvas.drawPath(path, paint)
            }
            Symbol.PERSON_MINUS -> {
                canvas.drawArc(3f, 3f, 20f, 20f, 80f, 285f, false, paint)
                circle(11.5f, 9, 2.5f)
                path.rewind()
                path.moveTo(6.8f, 16.8f)
                path.quadTo(11.5f, 12.5f, 15.2f, 15.5f)
                canvas.drawPath(path, paint)
                line(15.5f, 18, 21, 18)
            }
            Symbol.TRASH -> { shape(6, 7, 7, 21, 17, 21, 18, 7); line(4, 6, 20, 6); shape(9, 6, 9, 3, 15, 3, 15, 6)
                line(10, 10, 10, 17); line(14, 10, 14, 17) }
            Symbol.EDIT -> { shape(4, 20, 5, 15, 17, 3, 21, 7, 9, 19, close = true); line(14, 6, 18, 10) }
            Symbol.FILTER -> { line(4, 6, 20, 6); line(7, 12, 17, 12); line(10, 18, 14, 18) }
            Symbol.SORT -> {
                line(8, 19, 8, 5); shape(4.5f, 8.5f, 8, 5, 11.5f, 8.5f)
                line(16, 5, 16, 19); shape(12.5f, 15.5f, 16, 19, 19.5f, 15.5f)
            }
            Symbol.REORDER -> { line(5, 7, 19, 7); line(5, 12, 19, 12); line(5, 17, 19, 17) }
            Symbol.PHOTO -> { canvas.drawRoundRect(3f, 4f, 21f, 20f, 2f, 2f, paint); circle(15, 9, 1.5f); shape(4, 17, 9, 12, 13, 16, 16, 13, 21, 17) }
            Symbol.SPEAKER -> { canvas.drawRoundRect(4f, 2f, 17f, 22f, 3f, 3f, paint); circle(10.5f, 8, 1.4f); circle(10.5f, 15.5f, 3.1f); line(20, 7, 20, 17) }
            Symbol.CIRCLE -> circle(12, 12, 8.5f)
            Symbol.CHECK_CIRCLE -> { circle(12, 12, 8.5f); shape(8, 12, 11, 15, 16, 9) }
            Symbol.FILE -> { shape(5, 3, 15, 3, 20, 8, 20, 21, 5, 21, close = true); shape(15, 3, 15, 8, 20, 8); line(8, 12, 16, 12); line(8, 16, 16, 16) }
            Symbol.DOWNLOAD_CIRCLE -> { circle(12, 12, 9); line(12, 7.5f, 12, 15.5f); shape(8.5f, 12.2f, 12, 15.8f, 15.5f, 12.2f) }
            Symbol.UNLOCK -> { canvas.drawRoundRect(5f, 10f, 19f, 22f, 2f, 2f, paint)
                path.rewind(); path.moveTo(8f, 10f); path.lineTo(8f, 6f); path.cubicTo(8f, 1f, 17f, 1f, 17f, 6f); canvas.drawPath(path, paint)
                line(12, 15, 12, 18) }
            Symbol.EYE_OFF -> { path.rewind(); path.moveTo(2f, 12f); path.quadTo(12f, 0f, 22f, 12f)
                path.quadTo(12f, 24f, 2f, 12f); canvas.drawPath(path, paint); circle(12, 12, 3); line(3, 3, 21, 21) }
            Symbol.MIC_MUSIC -> {
                circle(15.2f, 8.2f, 4.2f)
                line(12.2f, 5.2f, 18.2f, 11.2f)
                shape(11.6f, 10.2f, 4.2f, 17.8f, 6.2f, 19.8f, 13.8f, 12.4f)
                line(3.4f, 20.6f, 5.2f, 18.8f)
            }
            Symbol.MIC -> {
                canvas.drawRoundRect(9f, 2.8f, 15f, 13.8f, 3f, 3f, paint)
                canvas.drawArc(6f, 6.2f, 18f, 16.8f, 0f, 180f, false, paint)
                line(12, 16.8f, 12, 20.5f)
                line(8.2f, 20.5f, 15.8f, 20.5f)
            }
            Symbol.BOOK -> {
                line(12, 6.2f, 12, 19.8f)
                path.rewind()
                path.moveTo(12f, 6.2f); path.quadTo(7.5f, 4.2f, 3f, 5.8f); path.lineTo(3f, 18.8f); path.quadTo(7.5f, 17.2f, 12f, 19.8f)
                canvas.drawPath(path, paint)
                path.rewind()
                path.moveTo(12f, 6.2f); path.quadTo(16.5f, 4.2f, 21f, 5.8f); path.lineTo(21f, 18.8f); path.quadTo(16.5f, 17.2f, 12f, 19.8f)
                canvas.drawPath(path, paint)
            }
            Symbol.BALLOON -> {
                path.rewind()
                path.moveTo(12f, 2.5f)
                path.cubicTo(17.2f, 2.5f, 18.2f, 7.6f, 16.4f, 11.6f)
                path.cubicTo(15f, 14.6f, 13.3f, 15.8f, 12f, 16f)
                path.cubicTo(10.7f, 15.8f, 9f, 14.6f, 7.6f, 11.6f)
                path.cubicTo(5.8f, 7.6f, 6.8f, 2.5f, 12f, 2.5f)
                path.close()
                canvas.drawPath(path, paint)
                canvas.drawArc(8.5f, 4.8f, 14f, 10.5f, 190f, 60f, false, paint)
                shape(10.7f, 17.4f, 12, 16, 13.3f, 17.4f, close = true)
                path.rewind()
                path.moveTo(12f, 17.5f)
                path.cubicTo(10.5f, 19f, 13.5f, 20.5f, 11.5f, 22f)
                canvas.drawPath(path, paint)
            }
            Symbol.GRID -> {
                paint.style = Paint.Style.FILL
                canvas.drawRoundRect(3.5f, 3.5f, 10.7f, 10.7f, 2f, 2f, paint)
                canvas.drawRoundRect(13.3f, 3.5f, 20.5f, 10.7f, 2f, 2f, paint)
                canvas.drawRoundRect(3.5f, 13.3f, 10.7f, 20.5f, 2f, 2f, paint)
                canvas.drawRoundRect(13.3f, 13.3f, 20.5f, 20.5f, 2f, 2f, paint)
                paint.style = Paint.Style.STROKE
            }
        }
        canvas.restore()
    }
}

internal class WaveBackdrop(private val activity: MainActivity, private val backgroundColor: Int) : View(activity) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
    private val motion = activity.playbackMotion
    private val visibleBounds = android.graphics.Rect()
    private val density = resources.displayMetrics.density
    private val speeds = doubleArrayOf(0.69, 0.42, 0.53)
    private val matrix = Matrix()
    private val glows = intArrayOf(0xffffb81f.toInt(), 0xffff6e14.toInt(), 0xffe8400d.toInt()).map { color ->
        RadialGradient(0f, 0f, 1f, intArrayOf(ColorUtils.setAlphaComponent(color, 148),
            ColorUtils.setAlphaComponent(color, 56), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
    }
    private var fade: LinearGradient? = null
    private var lastFrame: Long? = null
    private var phase = 0.0
    init { motion.observe(this) }

    var playing = false
        set(value) {
            if (field != value) {
                field = value
                lastFrame = null
                invalidate()
            }
        }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        fade = if (height > 0) LinearGradient(0f, 0f, 0f, height.toFloat(),
            intArrayOf(backgroundColor, Color.TRANSPARENT, Color.TRANSPARENT, backgroundColor),
            floatArrayOf(0f, .22f, .72f, 1f), Shader.TileMode.CLAMP) else null
    }

    fun resumeIfPlaying() {
        if (playing) invalidate()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        lastFrame = null
        if (isVisible) invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val animated = playing && motion.enabled && !activity.hasOverlay && isShown && windowVisibility == VISIBLE && getLocalVisibleRect(visibleBounds)
        if (animated) {
            val now = android.os.SystemClock.uptimeMillis()
            lastFrame?.let { phase += (now - it) / 1000.0 }
            lastFrame = now
        } else lastFrame = null
        val radius = minOf(width * .53f, 208f * density)
        for (index in glows.indices) {
            val angle = phase * speeds[index] + index * 2.1
            val x = width / 2f + kotlin.math.cos(angle).toFloat() * radius * .4f
            val y = height * .44f + kotlin.math.sin(angle * 1.2).toFloat() * radius * .24f
            val spread = radius * (.82f + .1f * kotlin.math.sin(angle + 1).toFloat())
            matrix.setScale(spread, spread)
            matrix.postTranslate(x, y)
            val glow = glows[index]
            glow.setLocalMatrix(matrix)
            paint.shader = glow
            canvas.drawCircle(x, y, spread, paint)
        }
        fade?.let {
            paint.shader = it
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        }
        paint.shader = null
        if (animated) postInvalidateOnAnimation()
    }
}

internal class PlayingBars(context: MainActivity, color: Int) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
    private val motion = context.playbackMotion
    private val visibleBounds = android.graphics.Rect()
    init { motion.observe(this) }

    var running = false
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    override fun onDraw(canvas: Canvas) {
        val animate = running && motion.enabled && isShown && windowVisibility == VISIBLE && getLocalVisibleRect(visibleBounds)
        val time = android.os.SystemClock.uptimeMillis() / 1000.0
        for (index in REST.indices) {
            val relative = if (animate) .3 + .7 * kotlin.math.abs(kotlin.math.sin(time * SPEEDS[index] + PHASES[index])) else REST[index]
            val x = width * (.02f + index * .25f)
            val barWidth = width * .16f
            canvas.drawRoundRect(x, height * (1f - relative.toFloat()), x + barWidth, height.toFloat(), barWidth / 2f, barWidth / 2f, paint)
        }
        if (animate) postInvalidateDelayed(33)
    }

    private companion object {
        val REST = doubleArrayOf(.45, .85, .6, .35)
        val SPEEDS = doubleArrayOf(2.4, 3.3, 2.8, 3.8)
        val PHASES = doubleArrayOf(0.0, 1.3, 2.2, .6)
    }
}

internal class BufferRingView(private val activity: MainActivity) : View(activity) {
    private val stroke = 3f * resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xe6ffffff.toInt()
        style = Paint.Style.STROKE
        strokeWidth = stroke
        strokeCap = Paint.Cap.ROUND
    }
    private val oval = RectF()
    private var shown = false
    private val showRing = Runnable {
        if (!buffering || !isAttachedToWindow) return@Runnable
        shown = true
        visibility = VISIBLE
        animate().cancel()
        if (ValueAnimator.areAnimatorsEnabled()) {
            alpha = 0f
            animate().alpha(1f).setDuration(200).start()
        } else alpha = 1f
        invalidate()
    }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        visibility = INVISIBLE
        alpha = 0f
        activity.playbackMotion.observe(this)
    }

    var buffering = false
        set(value) {
            if (field == value) return
            field = value
            removeCallbacks(showRing)
            animate().cancel()
            if (value) {
                postDelayed(showRing, 600)
            } else {
                shown = false
                if (ValueAnimator.areAnimatorsEnabled() && isAttachedToWindow) {
                    animate().alpha(0f).setDuration(150).withEndAction {
                        if (!buffering) visibility = INVISIBLE
                    }.start()
                } else {
                    alpha = 0f
                    visibility = INVISIBLE
                }
            }
        }

    override fun onSizeChanged(w: Int, h: Int, oldW: Int, oldH: Int) {
        super.onSizeChanged(w, h, oldW, oldH)
        val inset = stroke / 2f
        oval.set(inset, inset, w - inset, h - inset)
    }

    override fun onDraw(canvas: Canvas) {
        if (!shown || oval.isEmpty) return
        val angle = if (buffering && activity.playbackMotion.enabled) {
            ((android.os.SystemClock.uptimeMillis() % 900L) / 900f) * 360f - 90f
        } else -90f
        canvas.drawArc(oval, angle, 108f, false, paint)
        if (buffering && activity.playbackMotion.enabled && isShown && windowVisibility == VISIBLE) {
            postInvalidateOnAnimation()
        }
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(showRing)
        animate().cancel()
        super.onDetachedFromWindow()
    }
}

internal class ProgressRingView(context: Context, trackColor: Int, progressColor: Int) : View(context) {
    private val stroke = 2.5f * resources.displayMetrics.density
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = trackColor
        style = Paint.Style.STROKE
        strokeWidth = stroke
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = progressColor
        style = Paint.Style.STROKE
        strokeWidth = stroke
        strokeCap = Paint.Cap.ROUND
    }
    private val oval = RectF()

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    var progress = 0f
        set(value) {
            val clamped = value.coerceIn(0f, 1f)
            if (field != clamped) {
                field = clamped
                invalidate()
            }
        }

    override fun onSizeChanged(w: Int, h: Int, oldW: Int, oldH: Int) {
        super.onSizeChanged(w, h, oldW, oldH)
        val inset = stroke / 2f
        oval.set(inset, inset, w - inset, h - inset)
    }

    override fun onDraw(canvas: Canvas) {
        if (oval.isEmpty) return
        canvas.drawArc(oval, 0f, 360f, false, trackPaint)
        canvas.drawArc(oval, -90f, progress.coerceIn(.04f, 1f) * 360f, false, fillPaint)
    }
}

internal class TuneRefreshLayout(
    ui: TuneUi,
    private val list: ListView,
    private val canRefresh: () -> Boolean = { true },
    private val onRefresh: () -> Unit,
) : FrameLayout(ui.context) {
    private val slop = ViewConfiguration.get(ui.context).scaledTouchSlop.toFloat()
    private val threshold = ui.dp(64).toFloat()
    private val resting = ui.dp(56).toFloat()
    private val maxPull = ui.dp(108).toFloat()
    private val pillOffset = ui.dp(44).toFloat()
    private val pill = FrameLayout(ui.context).apply {
        background = ui.shape(ui.palette.panel, 19)
        elevation = ui.dp(4).toFloat()
        visibility = View.GONE
        alpha = 0f
        addView(ProgressBar(ui.context).apply {
            indeterminateTintList = ColorStateList.valueOf(ui.palette.accentText)
        }, LayoutParams(ui.dp(20), ui.dp(20), Gravity.CENTER))
    }
    private var startX = 0f
    private var startY = 0f
    private var pulling = false
    private var primed = false
    private var refreshing = false

    init {
        addView(list, LayoutParams(-1, -1))
        addView(pill, LayoutParams(ui.dp(38), ui.dp(38), Gravity.CENTER_HORIZONTAL or Gravity.TOP))
    }

    fun setRefreshing(active: Boolean) {
        if (refreshing == active) return
        refreshing = active
        if (!active) reset()
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        if (refreshing || !canRefresh() || list.canScrollVertically(-1)) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.x
                startY = event.y
                pulling = false
                primed = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - startX
                val dy = event.y - startY
                if (dy > slop && dy > kotlin.math.abs(dx) * 1.4f && !list.canScrollVertically(-1)) {
                    pulling = true
                    startY = event.y
                    return true
                }
            }
        }
        return false
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!pulling) return super.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                val pull = ((event.y - startY) * .45f).coerceIn(0f, maxPull)
                val progress = (pull / threshold).coerceIn(0f, 1f)
                list.animate().cancel()
                pill.animate().cancel()
                list.translationY = pull
                pill.visibility = if (pull > 2f) View.VISIBLE else View.GONE
                pill.translationY = (pull - pillOffset).coerceAtLeast(0f)
                pill.alpha = progress
                val scale = .75f + .25f * progress
                pill.scaleX = scale
                pill.scaleY = scale
                val ready = pull >= threshold
                if (ready && !primed) {
                    primed = true
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                } else if (!ready) {
                    primed = false
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                pulling = false
                if (event.actionMasked == MotionEvent.ACTION_UP && primed) {
                    refreshing = true
                    list.animate().translationY(resting).setDuration(180).start()
                    pill.animate().translationY(resting - pillOffset).alpha(1f).scaleX(1f).scaleY(1f).setDuration(180).start()
                    onRefresh()
                } else {
                    reset()
                }
            }
        }
        return true
    }

    private fun reset() {
        primed = false
        list.animate().translationY(0f).setDuration(200).start()
        pill.animate().translationY(-pillOffset).alpha(0f).scaleX(.75f).scaleY(.75f).setDuration(180)
            .withEndAction { if (!pulling && !refreshing) pill.visibility = View.GONE }
            .start()
    }
}
