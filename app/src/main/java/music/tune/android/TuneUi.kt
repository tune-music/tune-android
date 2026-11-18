package music.tune.android

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.StateListAnimator
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
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import coil.dispose
import coil.load
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
        val overlay = if (palette.light || color == palette.accent) 0x15000000 else 0x17ffffff
        val highlight = ColorUtils.compositeColors(overlay, color)
        val selected = if (showSelection) ColorUtils.compositeColors(0x1cfacc15, color) else color
        setColor(ColorStateList(arrayOf(
            intArrayOf(android.R.attr.state_pressed, android.R.attr.state_enabled),
            intArrayOf(android.R.attr.state_focused, android.R.attr.state_enabled),
            intArrayOf(android.R.attr.state_selected),
            intArrayOf(),
        ), intArrayOf(highlight, if (focusHighlight) highlight else color, selected, color)))
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

    fun art(uri: String?, size: Int, circle: Boolean = false, corner: Int = 10, tone: ((Int) -> Unit)? = null): CoverView = CoverView(context, tone).apply {
        background = shape(palette.lifted, if (circle) 500 else corner)
        clipToOutline = true
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
    fun motion(target: Float, time: Long, release: Boolean) = ObjectAnimator.ofPropertyValuesHolder(this,
        PropertyValuesHolder.ofFloat(View.SCALE_X, target), PropertyValuesHolder.ofFloat(View.SCALE_Y, target)).apply {
        duration = time
        interpolator = if (release) OvershootInterpolator(.85f) else DecelerateInterpolator()
    }
    stateListAnimator = StateListAnimator().apply {
        addState(intArrayOf(android.R.attr.state_pressed, android.R.attr.state_enabled), motion(scale, 95, false))
        addState(intArrayOf(), motion(1f, 225, true))
    }
}

private object ButtonAccessibility : View.AccessibilityDelegate() {
    override fun onInitializeAccessibilityNodeInfo(host: View, info: android.view.accessibility.AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(host, info)
        info.className = "android.widget.Button"
    }
}

internal class CoverView(context: Context, private val tone: ((Int) -> Unit)? = null) : FrameLayout(context) {
    private val image = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
    private val placeholder = IconView(context, Symbol.NOTE, Palette.muted)

    init {
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(placeholder, LayoutParams(dp(32), dp(32), Gravity.CENTER))
        addView(image, LayoutParams(-1, -1))
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    fun bind(cover: String?, size: Int) {
        val downloaded = cover?.let { (context.applicationContext as? TuneApp)?.store?.files?.savedCover(it) }
        val source = MetadataOverrides.image(context, cover) ?: downloaded ?: coverUrl(cover, size)
        if (source == image.tag) return
        image.tag = source
        placeholder.visibility = View.VISIBLE
        if (source == null) {
            image.dispose()
            image.setImageDrawable(null)
            tone?.invoke(Palette.lifted)
        } else image.load(source) {
            crossfade(130)
            size(size)
            if (tone != null) allowHardware(false)
            listener(onSuccess = { _, result ->
                if (image.tag == source) {
                    placeholder.visibility = View.INVISIBLE
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
    NOTE, HEART, HEART_FILLED, BAN, DOWNLOAD, CHECK, QUEUE, LYRICS, REPEAT, REPEAT_ONE, SHUFFLE,
    CLOCK, ADD, CLOSE, SHARE, ARROW_UP, ARROW_DOWN, VIDEO, CONCERT, LINK, OUTPUT, MOON, THUMB_DOWN, ACCOUNT, GEAR, STATS,
    TRASH, EDIT, FILTER, REORDER, PHOTO, SPEAKER, CIRCLE, CHECK_CIRCLE, FILE, DOWNLOAD_CIRCLE, UNLOCK, EYE_OFF, WIFI_OFF
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
        paint.color = if (isPressed && isClickable) ColorUtils.setAlphaComponent(tint, (Color.alpha(tint) * .75f).toInt()) else tint
        paint.style = Paint.Style.STROKE
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
            Symbol.LIBRARY -> { line(4, 4, 4, 20); line(9, 4, 9, 20); line(14, 4, 14, 20); line(17.5f, 5, 21, 19) }
            Symbol.WAVE -> shape(2, 12, 5, 12, 8, 5, 11, 19, 14, 7, 17, 15, 19, 12, 22, 12)
            Symbol.SETTINGS -> { line(3, 6, 21, 6); line(3, 12, 21, 12); line(3, 18, 21, 18)
                paint.style = Paint.Style.FILL; circle(8, 6, 2.2f); circle(16, 12, 2.2f); circle(10, 18, 2.2f) }
            Symbol.GEAR -> {
                shape(10, 2, 14, 2, 15, 5, 17, 6, 20, 5.5f, 22, 9, 20, 11, 20, 13, 22, 15, 20, 18.5f,
                    17, 18, 15, 19, 14, 22, 10, 22, 9, 19, 7, 18, 4, 18.5f, 2, 15, 4, 13, 4, 11,
                    2, 9, 4, 5.5f, 7, 6, 9, 5, close = true)
                circle(12, 12, 3.2f)
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
            Symbol.HEART, Symbol.HEART_FILLED -> {
                if (symbol == Symbol.HEART_FILLED) paint.style = Paint.Style.FILL
                path.rewind()
                path.moveTo(12f, 20.5f)
                path.cubicTo(10f, 18.8f, 3f, 14.6f, 3f, 9.5f)
                path.cubicTo(3f, 4.1f, 9f, 3.1f, 12f, 7.4f)
                path.cubicTo(15f, 3.1f, 21f, 4.1f, 21f, 9.5f)
                path.cubicTo(21f, 14.6f, 14f, 18.8f, 12f, 20.5f)
                path.close()
                canvas.drawPath(path, paint)
            }
            Symbol.BAN -> { circle(12, 12, 8); line(6.5f, 6.5f, 17.5f, 17.5f) }
            Symbol.DOWNLOAD -> { line(12, 3, 12, 15); shape(7, 11, 12, 16, 17, 11); line(4, 21, 20, 21) }
            Symbol.CHECK -> shape(4, 12, 9, 17, 20, 6)
            Symbol.QUEUE -> { line(3, 6, 21, 6); line(3, 12, 17, 12); line(3, 18, 13, 18); shape(17, 16, 22, 19, 17, 22, close = true, fill = true) }
            Symbol.LYRICS -> {
                shape(7, 5, 4, 7, 4, 11, 7, 11); shape(13, 5, 10, 7, 10, 11, 13, 11)
                line(17, 7, 21, 7); line(4, 16, 21, 16); line(4, 21, 16, 21)
            }
            Symbol.REPEAT, Symbol.REPEAT_ONE -> {
                shape(3, 11, 3, 7, 20, 7); shape(16, 3, 20, 7, 16, 11)
                shape(21, 13, 21, 17, 4, 17); shape(8, 13, 4, 17, 8, 21)
                if (symbol == Symbol.REPEAT_ONE) shape(11, 11, 12.5f, 10, 12.5f, 14)
            }
            Symbol.SHUFFLE -> { shape(3, 6, 7, 6, 17, 18, 21, 18); shape(17, 14, 21, 18, 17, 22)
                shape(3, 18, 7, 18, 10, 14); shape(14, 10, 17, 6, 21, 6); shape(17, 2, 21, 6, 17, 10) }
            Symbol.CLOCK -> { circle(12, 12, 8.5f); shape(12, 7, 12, 12, 16, 14) }
            Symbol.ADD -> { line(12, 4, 12, 20); line(4, 12, 20, 12) }
            Symbol.CLOSE -> { line(5, 5, 19, 19); line(19, 5, 5, 19) }
            Symbol.SHARE -> { shape(8, 9, 5, 9, 5, 22, 19, 22, 19, 9, 16, 9)
                line(12, 14, 12, 2); shape(8, 6, 12, 2, 16, 6) }
            Symbol.ARROW_UP -> { line(12, 20, 12, 4); shape(6, 10, 12, 4, 18, 10) }
            Symbol.ARROW_DOWN -> { line(12, 4, 12, 20); shape(6, 14, 12, 20, 18, 14) }
            Symbol.VIDEO -> { canvas.drawRoundRect(3f, 5f, 21f, 19f, 2f, 2f, paint); shape(10, 9, 16, 12, 10, 15, close = true, fill = true) }
            Symbol.CONCERT -> { shape(3, 5, 21, 5, 21, 19, 3, 19, close = true); line(14, 8, 14, 10); line(14, 14, 14, 16) }
            Symbol.LINK -> { canvas.drawRoundRect(RectF(3f, 8f, 12f, 16f), 4f, 4f, paint)
                canvas.drawRoundRect(RectF(12f, 8f, 21f, 16f), 4f, 4f, paint); line(8, 12, 16, 12) }
            Symbol.OUTPUT -> {
                shape(7, 17, 3, 17, 3, 5, 21, 5, 21, 17, 17, 17)
                shape(12, 13, 18, 21, 6, 21, close = true)
            }
            Symbol.MOON -> {
                path.rewind()
                path.moveTo(13f, 3f)
                path.cubicTo(2f, 2f, 0f, 18f, 11f, 21f)
                path.cubicTo(16f, 22.3f, 21f, 19f, 21f, 14f)
                path.cubicTo(14f, 17f, 8f, 10f, 13f, 3f)
                canvas.drawPath(path, paint)
            }
            Symbol.THUMB_DOWN -> {
                shape(19, 4, 22, 4, 22, 14, 19, 14, close = true)
                shape(15, 4, 7, 4, 5, 6, 3, 12, 4, 14, 10, 14, 9, 19, 11, 21, 16, 14, 17, 7, 15, 4)
            }
            Symbol.ACCOUNT -> {
                circle(12, 12, 9)
                circle(12, 9, 2.5f)
                path.rewind()
                path.moveTo(6.5f, 18f)
                path.quadTo(12f, 11f, 17.5f, 18f)
                canvas.drawPath(path, paint)
            }
            Symbol.TRASH -> { shape(6, 7, 7, 21, 17, 21, 18, 7); line(4, 6, 20, 6); shape(9, 6, 9, 3, 15, 3, 15, 6)
                line(10, 10, 10, 17); line(14, 10, 14, 17) }
            Symbol.EDIT -> { shape(4, 20, 5, 15, 17, 3, 21, 7, 9, 19, close = true); line(14, 6, 18, 10) }
            Symbol.FILTER -> { line(4, 6, 20, 6); line(7, 12, 17, 12); line(10, 18, 14, 18) }
            Symbol.REORDER -> { line(5, 7, 19, 7); line(5, 12, 19, 12); line(5, 17, 19, 17) }
            Symbol.PHOTO -> { canvas.drawRoundRect(3f, 4f, 21f, 20f, 2f, 2f, paint); circle(15, 9, 1.5f); shape(4, 17, 9, 12, 13, 16, 16, 13, 21, 17) }
            Symbol.SPEAKER -> { canvas.drawRoundRect(4f, 2f, 17f, 22f, 3f, 3f, paint); circle(10.5f, 8, 1.4f); circle(10.5f, 15.5f, 3.1f); line(20, 7, 20, 17) }
            Symbol.CIRCLE -> circle(12, 12, 8.5f)
            Symbol.CHECK_CIRCLE -> { circle(12, 12, 8.5f); shape(8, 12, 11, 15, 16, 9) }
            Symbol.FILE -> { shape(5, 3, 15, 3, 20, 8, 20, 21, 5, 21, close = true); shape(15, 3, 15, 8, 20, 8); line(8, 12, 16, 12); line(8, 16, 16, 16) }
            Symbol.DOWNLOAD_CIRCLE -> { circle(12, 12, 9); line(12, 6, 12, 16); shape(8, 12, 12, 16, 16, 12) }
            Symbol.UNLOCK -> { canvas.drawRoundRect(5f, 10f, 19f, 22f, 2f, 2f, paint)
                path.rewind(); path.moveTo(8f, 10f); path.lineTo(8f, 6f); path.cubicTo(8f, 1f, 17f, 1f, 17f, 6f); canvas.drawPath(path, paint)
                line(12, 15, 12, 18) }
            Symbol.EYE_OFF -> { path.rewind(); path.moveTo(2f, 12f); path.quadTo(12f, 0f, 22f, 12f)
                path.quadTo(12f, 24f, 2f, 12f); canvas.drawPath(path, paint); circle(12, 12, 3); line(3, 3, 21, 21) }
        }
        canvas.restore()
    }
}

internal class WaveBackdrop(context: MainActivity, private val backgroundColor: Int) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val motion = context.playbackMotion
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

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        lastFrame = null
        if (isVisible) invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val animated = playing && motion.enabled && isShown && windowVisibility == VISIBLE && getLocalVisibleRect(visibleBounds)
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
