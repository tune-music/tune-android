package music.tune.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class SmoothGradientTest {
    @Test
    fun bayerMatrixHasExpectedOrderingAndAllUniqueValues() {
        val matrix = buildBayerMatrix(4)
        assertEquals(256, matrix.size)
        assertEquals((0..255).toSet(), matrix.toSet())

        val bayer2 = buildBayerMatrix(1)
        assertEquals(listOf(0, 2, 3, 1), bayer2.toList())

        val bayer4 = buildBayerMatrix(2)
        assertEquals(
            listOf(
                0, 8, 2, 10,
                12, 4, 14, 6,
                3, 11, 1, 9,
                15, 7, 13, 5,
            ),
            bayer4.toList(),
        )
        // Top-left 2x2 quadrant scaled by 16 matches Bayer order-4 top-left strides (at multiples of 4)
        assertEquals(0, matrix[0])
        assertEquals(2, matrix[8])
        assertEquals(3, matrix[8 * 16])
        assertEquals(1, matrix[8 * 16 + 8])
    }

    @Test
    fun darkPlayerGradientHasExactEndpointsAndContinuousSmoothDither() {
        val top = 0xff4d382c.toInt()
        val bottom = 0xff0d0d0d.toInt()
        val width = 16
        val height = 2400
        val pixels = IntArray(width * height)

        fillDitheredVerticalGradient(pixels, width, height, top, bottom)

        for (x in 0 until width) {
            assertEquals(top, pixels[x])
            assertEquals(bottom, pixels[(height - 1) * width + x])
        }

        val r0 = (top ushr 16) and 0xff
        val g0 = (top ushr 8) and 0xff
        val b0 = top and 0xff
        val r1 = (bottom ushr 16) and 0xff
        val g1 = (bottom ushr 8) and 0xff
        val b1 = bottom and 0xff

        for (y in 0 until height) {
            val t = y.toDouble() / (height - 1)
            val expectedR = r0 + (r1 - r0) * t
            val expectedG = g0 + (g1 - g0) * t
            val expectedB = b0 + (b1 - b0) * t
            var sumA = 0.0
            var sumR = 0.0
            var sumG = 0.0
            var sumB = 0.0
            val offset = y * width
            for (x in 0 until width) {
                val pixel = pixels[offset + x]
                sumA += (pixel ushr 24) and 0xff
                sumR += (pixel ushr 16) and 0xff
                sumG += (pixel ushr 8) and 0xff
                sumB += pixel and 0xff
            }
            assertEquals(255.0, sumA / width, 1e-6)
            assertTrue("Row $y R error too large", abs(sumR / width - expectedR) < 1.0)
            assertTrue("Row $y G error too large", abs(sumG / width - expectedG) < 1.0)
            assertTrue("Row $y B error too large", abs(sumB / width - expectedB) < 1.0)
        }

        var prevBlockR = Double.POSITIVE_INFINITY
        for (blockStart in 0 until height step 16) {
            var blockR = 0.0
            for (y in blockStart until blockStart + 16) {
                val rowOffset = y * width
                for (x in 0 until width) {
                    blockR += (pixels[rowOffset + x] ushr 16) and 0xff
                }
            }
            val meanR = blockR / (16 * width)
            assertTrue("Non-monotonic block at y=$blockStart: $meanR > $prevBlockR", meanR <= prevBlockR + 1e-9)
            if (prevBlockR.isFinite()) {
                assertTrue("Harsh jump at y=$blockStart: ${prevBlockR - meanR}", prevBlockR - meanR <= 1.0)
            }
            prevBlockR = meanR
        }
    }

    @Test
    fun edgeCasesSingleRowTwoRowsUniformAndAlphaInterpolationWorkCleanly() {
        val top = 0x80305070.toInt()
        val bottom = 0x20102030

        val single = IntArray(16)
        fillDitheredVerticalGradient(single, 16, 1, top, bottom)
        for (pixel in single) assertEquals(top, pixel)

        val pair = IntArray(16 * 2)
        fillDitheredVerticalGradient(pair, 16, 2, top, bottom)
        for (x in 0 until 16) {
            assertEquals(top, pair[x])
            assertEquals(bottom, pair[16 + x])
        }

        val uniformColor = 0xff223344.toInt()
        val uniform = IntArray(16 * 32)
        fillDitheredVerticalGradient(uniform, 16, 32, uniformColor, uniformColor)
        for (pixel in uniform) assertEquals(uniformColor, pixel)

        val alphaRamp = IntArray(16 * 65)
        fillDitheredVerticalGradient(alphaRamp, 16, 65, top, bottom)
        var midAlphaSum = 0.0
        for (y in 24 until 40) {
            val rowOffset = y * 16
            for (x in 0 until 16) {
                midAlphaSum += (alphaRamp[rowOffset + x] ushr 24) and 0xff
            }
        }
        val expectedMidAlpha = (0x80 + 0x20) / 2.0
        assertTrue(abs(midAlphaSum / (16 * 16) - expectedMidAlpha) < 3.0)
    }
}
