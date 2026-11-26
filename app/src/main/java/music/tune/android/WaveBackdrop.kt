package music.tune.android

import android.annotation.SuppressLint
import android.database.ContentObserver
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import androidx.core.graphics.ColorUtils
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

private class WaveRgb(var r: Float, var g: Float, var b: Float) {
    fun lerpTo(target: WaveRgb, factor: Float, out: WaveRgb) {
        out.r = r + (target.r - r) * factor
        out.g = g + (target.g - g) * factor
        out.b = b + (target.b - b) * factor
    }

    fun copyFrom(other: WaveRgb) {
        r = other.r
        g = other.g
        b = other.b
    }

    fun toColor(alpha: Int = 255): Int = Color.argb(
        alpha.coerceIn(0, 255),
        (r.coerceIn(0f, 1f) * 255f + .5f).toInt(),
        (g.coerceIn(0f, 1f) * 255f + .5f).toInt(),
        (b.coerceIn(0f, 1f) * 255f + .5f).toInt(),
    )
}

private class WavePalette(
    val primary: WaveRgb,
    val secondary: WaveRgb,
    val deep: WaveRgb,
    val highlight: WaveRgb,
) {
    fun lerpTo(target: WavePalette, factor: Float) {
        primary.lerpTo(target.primary, factor, primary)
        secondary.lerpTo(target.secondary, factor, secondary)
        deep.lerpTo(target.deep, factor, deep)
        highlight.lerpTo(target.highlight, factor, highlight)
    }

    fun copyFrom(other: WavePalette) {
        primary.copyFrom(other.primary)
        secondary.copyFrom(other.secondary)
        deep.copyFrom(other.deep)
        highlight.copyFrom(other.highlight)
    }

    companion object {
        fun defaultDark() = WavePalette(
            primary = WaveRgb(1.0f, 0.78f, 0.18f),
            secondary = WaveRgb(1.0f, 0.50f, 0.10f),
            deep = WaveRgb(0.90f, 0.24f, 0.06f),
            highlight = WaveRgb(1.0f, 0.91f, 0.58f),
        )

        fun defaultLight() = WavePalette(
            primary = WaveRgb(0.96f, 0.62f, 0.04f),
            secondary = WaveRgb(0.95f, 0.36f, 0.04f),
            deep = WaveRgb(0.84f, 0.18f, 0.04f),
            highlight = WaveRgb(1.0f, 0.74f, 0.10f),
        )

        fun fromHex(hex: String?, light: Boolean): WavePalette {
            val base = parseHexColor(hex) ?: return if (light) defaultLight() else defaultDark()
            val hsv = FloatArray(3)
            Color.colorToHSV(base, hsv)
            val hue = hsv[0]
            val s = hsv[1]
            val v = hsv[2]
            val sat = if (s < 0.08f) s else max(s, if (light) 0.80f else 0.72f)
            val secSat = if (s < 0.08f) s else max(s, if (light) 0.84f else 0.78f)
            return WavePalette(
                primary = hsbToRgb(hue, sat, if (light) v.coerceIn(0.72f, 0.86f) else max(v, 0.88f)),
                secondary = hsbToRgb((hue + 21.6f) % 360f, secSat, if (light) v.coerceIn(0.68f, 0.80f) else max(v, 0.82f)),
                deep = hsbToRgb((hue + 342f) % 360f, min(1.0f, s * 1.05f + 0.15f), if (light) max(0.48f, v * 0.65f) else max(0.55f, v * 0.72f)),
                highlight = hsbToRgb(hue, if (light) max(0.55f, s * 0.75f) else max(0.18f, s * 0.38f), if (light) 0.92f else 1.0f),
            )
        }

        private fun hsbToRgb(hueDeg: Float, s: Float, b: Float): WaveRgb {
            val h = ((hueDeg % 360f) + 360f) % 360f / 60f
            val c = b * s
            val x = c * (1f - abs(h % 2f - 1f))
            val m = b - c
            val r1: Float
            val g1: Float
            val b1: Float
            when {
                h < 1f -> { r1 = c; g1 = x; b1 = 0f }
                h < 2f -> { r1 = x; g1 = c; b1 = 0f }
                h < 3f -> { r1 = 0f; g1 = c; b1 = x }
                h < 4f -> { r1 = 0f; g1 = x; b1 = c }
                h < 5f -> { r1 = x; g1 = 0f; b1 = c }
                else -> { r1 = c; g1 = 0f; b1 = x }
            }
            return WaveRgb(r1 + m, g1 + m, b1 + m)
        }
    }
}

@SuppressLint("ViewConstructor")
internal class WaveBackdrop(
    private val activity: MainActivity,
    private val light: Boolean,
) : View(activity) {
    private val motion = activity.playbackMotion
    private val audio = activity.getSystemService(AudioManager::class.java)
    private val visibleBounds = Rect()
    private val density = resources.displayMetrics.density
    private val addMode = if (light) null else PorterDuffXfermode(PorterDuff.Mode.ADD)

    private val blobPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG).apply {
        xfermode = addMode
    }
    private val contourFillPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG).apply {
        style = Paint.Style.FILL
        xfermode = addMode
        maskFilter = BlurMaskFilter((if (light) 12f else 18f) * density, BlurMaskFilter.Blur.NORMAL)
    }
    private val contourStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        xfermode = addMode
        maskFilter = BlurMaskFilter((if (light) 12f else 18f) * density, BlurMaskFilter.Blur.NORMAL)
    }
    private val ribbonGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        xfermode = addMode
        maskFilter = BlurMaskFilter(3f * density, BlurMaskFilter.Blur.NORMAL)
    }
    private val ribbonCorePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        xfermode = addMode
    }
    private val sparkPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG).apply {
        xfermode = addMode
    }

    private val path = Path()
    private val matrix = Matrix()
    private val ptsX = FloatArray(CONTOUR_STEPS)
    private val ptsY = FloatArray(CONTOUR_STEPS)

    private val blobShaders = arrayOfNulls<RadialGradient>(3)
    private val sparkShaders = arrayOfNulls<RadialGradient>(2)
    private val ribbonShaders = arrayOfNulls<LinearGradient>(3)
    private val shaderColors = IntArray(4)
    private var shadersValid = false

    private var phase = 0.0
    private var lastFrameMs: Long? = null
    private var systemVolume = 0.5f
    private var energy = 0.5f
    private var activityLevel = 0f
    private var audioLevel = 0f
    private var audioBass = 0f
    private var surge = 0f
    private var lastWaveKey = ""
    private var waveHex: String? = null
    private var targetPalette = if (light) WavePalette.defaultLight() else WavePalette.defaultDark()
    private val palette = if (light) WavePalette.defaultLight() else WavePalette.defaultDark()

    private var volumeObserverRegistered = false
    private val volumeObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) = refreshVolume()
    }

    init {
        motion.observe(this)
        refreshVolume()
        energy = volumeTarget()
    }

    var playing = false
        set(value) {
            if (field != value) {
                field = value
                lastFrameMs = null
                invalidate()
            }
        }

    fun setWave(hex: String?, key: String) {
        if (waveHex != hex || lastWaveKey != key) {
            if (lastWaveKey != key && lastWaveKey.isNotEmpty()) {
                surge = min(1f, surge + 0.5f)
            }
            val initial = lastWaveKey.isEmpty()
            lastWaveKey = key
            waveHex = hex
            targetPalette = WavePalette.fromHex(hex, light)
            if (initial) {
                palette.copyFrom(targetPalette)
                shadersValid = false
            }
            invalidate()
        }
    }

    fun refreshVolume() {
        val next = readSystemVolume()
        if (abs(next - systemVolume) > 0.005f) {
            systemVolume = next
            invalidate()
        }
    }

    fun resumeIfPlaying() {
        lastFrameMs = null
        refreshVolume()
        invalidate()
    }

    private fun readSystemVolume(): Float {
        val manager = audio ?: return 0.5f
        val maxVol = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (maxVol <= 0) return 0.5f
        return (manager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / maxVol).coerceIn(0f, 1f)
    }

    private fun volumeTarget(): Float = systemVolume.coerceIn(0f, 1f).pow(0.85f)

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        refreshVolume()
        if (!volumeObserverRegistered) {
            optional {
                context.contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, volumeObserver)
                volumeObserverRegistered = true
            }
        }
    }

    override fun onDetachedFromWindow() {
        if (volumeObserverRegistered) {
            optional { context.contentResolver.unregisterContentObserver(volumeObserver) }
            volumeObserverRegistered = false
        }
        lastFrameMs = null
        super.onDetachedFromWindow()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        lastFrameMs = null
        if (isVisible) {
            refreshVolume()
            invalidate()
        }
    }

    private fun ensureShaders() {
        val primary = palette.primary.toColor()
        val secondary = palette.secondary.toColor()
        val deep = palette.deep.toColor()
        val highlight = palette.highlight.toColor()
        if (shadersValid &&
            shaderColors[0] == primary &&
            shaderColors[1] == secondary &&
            shaderColors[2] == deep &&
            shaderColors[3] == highlight
        ) return

        shaderColors[0] = primary
        shaderColors[1] = secondary
        shaderColors[2] = deep
        shaderColors[3] = highlight
        shadersValid = true

        val blobStops = floatArrayOf(0f, 0.28f, 0.58f, 0.82f, 1f)
        val blobColors = intArrayOf(primary, secondary, deep)
        for (i in blobColors.indices) {
            val base = blobColors[i]
            blobShaders[i] = RadialGradient(
                0f, 0f, 1f,
                intArrayOf(
                    base,
                    ColorUtils.setAlphaComponent(base, if (light) 184 else 158),
                    ColorUtils.setAlphaComponent(base, if (light) 87 else 61),
                    ColorUtils.setAlphaComponent(base, if (light) 23 else 13),
                    Color.TRANSPARENT,
                ),
                blobStops,
                Shader.TileMode.CLAMP,
            )
        }

        val sparkBase0 = if (light) primary else highlight
        val sparkBase1 = if (light) secondary else primary
        sparkShaders[0] = RadialGradient(0f, 0f, 1f, intArrayOf(sparkBase0, Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
        sparkShaders[1] = RadialGradient(0f, 0f, 1f, intArrayOf(sparkBase1, Color.TRANSPARENT), null, Shader.TileMode.CLAMP)

        val ribbonBases = intArrayOf(highlight, primary, secondary)
        val ribbonStops = floatArrayOf(0f, 0.22f, 0.50f, 0.78f, 1f)
        for (i in ribbonBases.indices) {
            val side = ribbonBases[i]
            val mid = if (light) side else highlight
            ribbonShaders[i] = LinearGradient(
                0f, 0f, 1f, 0f,
                intArrayOf(
                    Color.TRANSPARENT,
                    ColorUtils.setAlphaComponent(side, 204),
                    mid,
                    ColorUtils.setAlphaComponent(side, 204),
                    Color.TRANSPARENT,
                ),
                ribbonStops,
                Shader.TileMode.CLAMP,
            )
        }
    }

    private fun stepAnimation(animated: Boolean) {
        val targetAct = if (playing) 1f else 0f
        val targetEn = volumeTarget()
        if (!animated) {
            lastFrameMs = null
            energy = targetEn
            activityLevel = targetAct
            palette.copyFrom(targetPalette)
            audioLevel = 0f
            audioBass = 0f
            surge = 0f
            return
        }

        val now = SystemClock.uptimeMillis()
        val dt = lastFrameMs?.let { ((now - it) / 1000f).coerceIn(0f, 0.05f) } ?: 0f
        lastFrameMs = now
        if (dt > 0f) {
            energy += (targetEn - energy) * min(1f, dt * 6.5f)
            activityLevel += (targetAct - activityLevel) * min(1f, dt * 3.2f)
            palette.lerpTo(targetPalette, min(1f, dt * 3.0f))
            surge *= exp(-dt * 2.6f)

            val targetAudio: Float
            val targetBass: Float
            if (playing) {
                val (liveLevel, liveBass, live) = AudioMeter.snapshot()
                if (live) {
                    targetAudio = liveLevel
                    targetBass = liveBass
                } else {
                    val synth = 0.24f + 0.18f * sin(phase * 2.4).toFloat() + 0.12f * sin(phase * 4.1 + 1.1).toFloat()
                    val beat = max(0f, sin(phase * 1.9).toFloat()).pow(3f)
                    targetAudio = synth.coerceIn(0f, 1f)
                    targetBass = (0.12f + 0.24f * beat).coerceAtMost(1f)
                }
            } else {
                targetAudio = 0f
                targetBass = 0f
            }

            val levelRate = if (targetAudio > audioLevel) 18f else 6.5f
            val bassRate = if (targetBass > audioBass) 22f else 7.5f
            audioLevel += (targetAudio - audioLevel) * min(1f, dt * levelRate)
            audioBass += (targetBass - audioBass) * min(1f, dt * bassRate)

            val idleSpeed = 0.22 + 0.10 * energy
            val activeSpeed = (0.35 + 0.85 * energy + 0.75 * audioLevel * (0.25f + 0.75f * energy)) * activityLevel
            phase += dt * (idleSpeed + activeSpeed)
        } else {
            energy = targetEn
            activityLevel = targetAct
            palette.copyFrom(targetPalette)
        }
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val animated = motion.enabled && !activity.hasOverlay && isShown &&
            windowVisibility == VISIBLE && getLocalVisibleRect(visibleBounds)
        stepAnimation(animated)
        ensureShaders()

        val time = phase
        val en = energy
        val act = activityLevel
        val volScale = 0.22f + 0.78f * en
        val sonic = audioLevel * volScale * act
        val kick = audioBass * volScale * act
        val power = (0.24f + 0.34f * en) + (0.22f + 0.32f * en) * act + 0.28f * sonic + 0.16f * surge
        val cx = w * 0.5f
        val cy = h * 0.48f
        val maxSpread = min(w * 0.52f, h * 0.42f)

        // 1. Дышащее атмосферное свечение без резких границ
        for (i in blobShaders.indices) {
            val angle = time * BLOB_SPEEDS[i] + i * 2.1
            val orbitX = maxSpread * (0.20f + 0.14f * en + 0.06f * sonic)
            val orbitY = maxSpread * (0.10f + 0.08f * en + 0.04f * kick)
            val bx = cx + cos(angle).toFloat() * orbitX
            val by = cy + sin(angle * 1.25).toFloat() * orbitY
            val radius = maxSpread * (0.72f + 0.18f * en + 0.08f * kick + 0.06f * surge + 0.07f * sin(angle + 1.0).toFloat())
            val shader = blobShaders[i] ?: continue
            matrix.setScale(radius, radius)
            matrix.postTranslate(bx, by)
            shader.setLocalMatrix(matrix)
            blobPaint.shader = shader
            blobPaint.alpha = (((if (light) 0.48f else 0.40f) * power).coerceIn(0f, 1f) * 255f).toInt()
            canvas.drawCircle(bx, by, radius, blobPaint)
        }
        blobPaint.shader = null

        // 2. Размытые органические контуры волны (полярные гармоники)
        val primaryColor = shaderColors[0]
        val secondaryColor = shaderColors[1]
        val contourAmp = 0.04f + 0.05f * act + 0.11f * kick + 0.06f * surge
        drawContour(canvas, cx, cy, w, h, maxSpread, en, kick, time, 0.41f, 0.20f, 1.0, 0.0, primaryColor,
            (if (light) 0.46f else 0.40f) * power, contourAmp)
        drawContour(canvas, cx, cy, w, h, maxSpread, en, kick, time, 0.34f, 0.16f, -0.82, 1.9, secondaryColor,
            (if (light) 0.38f else 0.34f) * power, contourAmp)

        // 3. Шёлковые акустические нити с плавным затуханием к краям
        val ribbonAmp = (4.5f + 4.5f * en + 12f * sonic + 14f * kick + 8f * surge) * density
        val leftX = w * 0.06f
        val spanX = w * 0.88f
        for (i in ribbonShaders.indices) {
            val shader = ribbonShaders[i] ?: continue
            buildSilkPath(leftX, spanX, cy + RIBBON_Y_BIAS[i] * density, ribbonAmp, RIBBON_FREQ[i], time * RIBBON_SPEED[i] + RIBBON_PHASE[i])
            matrix.setScale(spanX, 1f)
            matrix.postTranslate(leftX, 0f)
            shader.setLocalMatrix(matrix)

            ribbonGlowPaint.shader = shader
            ribbonGlowPaint.alpha = (((if (light) 0.44f else 0.48f) * power).coerceIn(0f, 1f) * 255f).toInt()
            ribbonGlowPaint.strokeWidth = RIBBON_WIDTH[i] * 3.2f * density
            canvas.drawPath(path, ribbonGlowPaint)

            ribbonCorePaint.shader = shader
            ribbonCorePaint.alpha = (((if (light) 0.82f else 0.68f) * power).coerceIn(0f, 1f) * 255f).toInt()
            ribbonCorePaint.strokeWidth = RIBBON_WIDTH[i] * (if (light) 1.25f else 1f) * density
            canvas.drawPath(path, ribbonCorePaint)
        }
        ribbonGlowPaint.shader = null
        ribbonCorePaint.shader = null

        // 4. Мягкие тёплые искры вокруг центра
        for (i in 0 until 10) {
            val seed = i * 1.6180339
            val angle = time * (0.28 + (i % 3) * 0.14) + seed * 3.1
            val orbit = maxSpread * (0.26f + (i % 5) * 0.11f) * (0.75f + 0.28f * en + 0.12f * sonic)
            val px = cx + cos(angle).toFloat() * orbit * 1.08f
            val py = cy + sin(angle * 1.3 + seed).toFloat() * orbit * 0.52f
            val pulse = 0.45f + 0.55f * sin(time * 1.9 + seed * 4.0).toFloat()
            val radius = (1.4f + (i % 3) * 0.7f) * density * (0.85f + 0.25f * en + 0.25f * kick) * 2.6f
            val shader = sparkShaders[i % 2] ?: continue
            matrix.setScale(radius, radius)
            matrix.postTranslate(px, py)
            shader.setLocalMatrix(matrix)
            sparkPaint.shader = shader
            sparkPaint.alpha = (((if (light) 0.58f else 0.52f) * power * pulse).coerceIn(0f, 1f) * 255f).toInt()
            canvas.drawCircle(px, py, radius, sparkPaint)
        }
        sparkPaint.shader = null

        if (animated) postInvalidateOnAnimation()
    }

    private fun drawContour(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        w: Float,
        h: Float,
        maxSpread: Float,
        en: Float,
        kick: Float,
        time: Double,
        rxFactor: Float,
        ryFactor: Float,
        speed: Double,
        offset: Double,
        color: Int,
        alpha: Float,
        amp: Float,
    ) {
        val scale = 0.84f + 0.14f * en + 0.10f * kick + 0.08f * surge
        val rx = min(w * rxFactor, maxSpread * 1.12f) * scale
        val ry = min(h * ryFactor, maxSpread * 0.60f) * scale
        val loopTime = time * speed
        for (i in 0 until CONTOUR_STEPS) {
            val theta = (i.toDouble() / CONTOUR_STEPS) * Math.PI * 2.0
            val harmonic = 0.45 * sin(2.0 * theta + loopTime) +
                0.35 * sin(3.0 * theta - 1.35 * loopTime + offset) +
                0.20 * sin(5.0 * theta + 2.1 * loopTime)
            val s = 1f + amp * harmonic.toFloat()
            ptsX[i] = cx + cos(theta).toFloat() * rx * s
            ptsY[i] = cy + sin(theta).toFloat() * ry * s
        }
        path.rewind()
        val last = CONTOUR_STEPS - 1
        path.moveTo((ptsX[last] + ptsX[0]) * 0.5f, (ptsY[last] + ptsY[0]) * 0.5f)
        for (i in 0 until CONTOUR_STEPS) {
            val next = (i + 1) % CONTOUR_STEPS
            path.quadTo(ptsX[i], ptsY[i], (ptsX[i] + ptsX[next]) * 0.5f, (ptsY[i] + ptsY[next]) * 0.5f)
        }
        path.close()

        contourFillPaint.color = color
        contourFillPaint.alpha = ((alpha * (if (light) 0.50f else 0.42f)).coerceIn(0f, 1f) * 255f).toInt()
        canvas.drawPath(path, contourFillPaint)

        contourStrokePaint.color = color
        contourStrokePaint.alpha = (alpha.coerceIn(0f, 1f) * 255f).toInt()
        contourStrokePaint.strokeWidth = (9f + 7f * en + 5f * kick) * density
        canvas.drawPath(path, contourStrokePaint)
    }

    private fun buildSilkPath(leftX: Float, spanX: Float, baseY: Float, amp: Float, freq: Double, phaseOffset: Double) {
        val steps = 64
        path.rewind()
        for (i in 0..steps) {
            val u = i.toDouble() / steps
            val env = sin(Math.PI * u)
            val window = env * env
            val yWave = sin(u * Math.PI * 2.0 * freq + phaseOffset) * 0.65 +
                sin(u * Math.PI * 2.0 * (freq * 1.7) - phaseOffset * 1.3) * 0.35
            val x = leftX + spanX * u.toFloat()
            val y = baseY + amp * (window * yWave).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
    }

    private companion object {
        const val CONTOUR_STEPS = 48
        val BLOB_SPEEDS = doubleArrayOf(0.58, 0.37, 0.46)
        val RIBBON_FREQ = doubleArrayOf(2.1, 3.0, 1.5)
        val RIBBON_SPEED = doubleArrayOf(1.55, -1.15, 0.90)
        val RIBBON_PHASE = doubleArrayOf(0.0, 1.7, 3.3)
        val RIBBON_Y_BIAS = floatArrayOf(-8f, 4f, 12f)
        val RIBBON_WIDTH = floatArrayOf(1.4f, 1.2f, 1.0f)
    }
}
