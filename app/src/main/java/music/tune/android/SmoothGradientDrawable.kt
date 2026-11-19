package music.tune.android

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable

internal fun buildBayerMatrix(order: Int): IntArray {
    require(order in 0..4) { "Unsupported Bayer matrix order: $order" }
    var size = 1
    var matrix = intArrayOf(0)
    repeat(order) {
        val nextSize = size * 2
        val next = IntArray(nextSize * nextSize)
        for (y in 0 until size) {
            val rowOffset = y * size
            val topOffset = y * nextSize
            val bottomOffset = (y + size) * nextSize
            for (x in 0 until size) {
                val base = matrix[rowOffset + x] shl 2
                next[topOffset + x] = base
                next[topOffset + x + size] = base + 2
                next[bottomOffset + x] = base + 3
                next[bottomOffset + x + size] = base + 1
            }
        }
        size = nextSize
        matrix = next
    }
    return matrix
}

private val BAYER_16 = buildBayerMatrix(4)

internal fun fillDitheredVerticalGradient(
    pixels: IntArray,
    width: Int,
    height: Int,
    topColor: Int,
    bottomColor: Int,
) {
    if (width <= 0 || height <= 0) return
    val a0 = topColor ushr 24 and 0xff
    val r0 = topColor ushr 16 and 0xff
    val g0 = topColor ushr 8 and 0xff
    val b0 = topColor and 0xff
    val a1 = bottomColor ushr 24 and 0xff
    val r1 = bottomColor ushr 16 and 0xff
    val g1 = bottomColor ushr 8 and 0xff
    val b1 = bottomColor and 0xff
    val da = (a1 - a0).toLong()
    val dr = (r1 - r0).toLong()
    val dg = (g1 - g0).toLong()
    val db = (b1 - b0).toLong()
    val denom = (height - 1).coerceAtLeast(1)

    for (y in 0 until height) {
        val scaledY = y.toLong() shl 8
        val aFixed = (a0 shl 8) + ((da * scaledY) / denom).toInt()
        val rFixed = (r0 shl 8) + ((dr * scaledY) / denom).toInt()
        val gFixed = (g0 shl 8) + ((dg * scaledY) / denom).toInt()
        val bFixed = (b0 shl 8) + ((db * scaledY) / denom).toInt()
        val bayerRow = (y and 15) shl 4
        val pixelRow = y * width
        for (x in 0 until width) {
            val d = BAYER_16[bayerRow + (x and 15)]
            val a = (aFixed + d) shr 8
            val r = (rFixed + d) shr 8
            val g = (gFixed + d) shr 8
            val b = (bFixed + d) shr 8
            pixels[pixelRow + x] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }
    }
}

internal class SmoothGradientDrawable(
    private var topColor: Int,
    private var bottomColor: Int,
) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isFilterBitmap = false
        isDither = false
    }
    private val rectF = RectF()
    private val shaderMatrix = Matrix()
    private var bitmap: Bitmap? = null
    private var pixels = IntArray(0)
    private var renderedHeight = 0
    private var dirty = true

    var cornerRadius: Float = 0f
        set(value) {
            if (field != value) {
                field = value
                invalidateSelf()
            }
        }

    fun setColors(top: Int, bottom: Int) {
        if (topColor == top && bottomColor == bottom) return
        topColor = top
        bottomColor = bottom
        dirty = true
        invalidateSelf()
    }

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        rectF.set(bounds)
        if (bounds.height().coerceIn(0, MAX_STRIP_HEIGHT) != renderedHeight) {
            dirty = true
        }
    }

    private fun ensureShader(bounds: Rect): Boolean {
        val targetHeight = bounds.height().coerceAtMost(MAX_STRIP_HEIGHT)
        if (targetHeight <= 0) return false
        var strip = bitmap
        if (dirty || strip == null || renderedHeight != targetHeight) {
            if (strip == null || renderedHeight != targetHeight) {
                // Allocate without recycling the prior bitmap synchronously while HWUI may still read it.
                strip = Bitmap.createBitmap(TILE_WIDTH, targetHeight, Bitmap.Config.ARGB_8888)
                bitmap = strip
                pixels = IntArray(TILE_WIDTH * targetHeight)
                renderedHeight = targetHeight
            }
            fillDitheredVerticalGradient(pixels, TILE_WIDTH, targetHeight, topColor, bottomColor)
            strip.setPixels(pixels, 0, TILE_WIDTH, 0, 0, TILE_WIDTH, targetHeight)
            paint.shader = BitmapShader(strip, Shader.TileMode.REPEAT, Shader.TileMode.CLAMP)
            dirty = false
        }
        val shader = paint.shader ?: return false
        shaderMatrix.reset()
        if (bounds.height() > targetHeight) {
            shaderMatrix.setScale(1f, bounds.height().toFloat() / targetHeight)
        }
        if (bounds.left != 0 || bounds.top != 0) {
            shaderMatrix.postTranslate(bounds.left.toFloat(), bounds.top.toFloat())
        }
        shader.setLocalMatrix(shaderMatrix)
        return true
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty || !ensureShader(b)) return
        if (cornerRadius > 0f) {
            canvas.drawRoundRect(rectF, cornerRadius, cornerRadius, paint)
        } else {
            canvas.drawRect(b, paint)
        }
    }

    override fun getOutline(outline: Outline) {
        if (bounds.isEmpty) return
        outline.setRoundRect(bounds, cornerRadius)
        outline.alpha = if (paint.alpha == 255 && Color.alpha(topColor) == 255 && Color.alpha(bottomColor) == 255) {
            1f
        } else {
            0f
        }
    }

    override fun setAlpha(alpha: Int) {
        if (paint.alpha != alpha) {
            paint.alpha = alpha
            invalidateSelf()
        }
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        if (paint.colorFilter !== colorFilter) {
            paint.colorFilter = colorFilter
            invalidateSelf()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    private companion object {
        const val TILE_WIDTH = 16
        const val MAX_STRIP_HEIGHT = 4096
    }
}
