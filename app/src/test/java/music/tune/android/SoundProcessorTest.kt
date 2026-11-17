package music.tune.android

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

@OptIn(UnstableApi::class)
class SoundProcessorTest {
    @Test
    fun reverbCanBeEnabledDuringPlaybackAndAddsAStereoTail() {
        var tuning = PcmTuning()
        val processor = SoundProcessor { tuning }
        processor.configure(AudioFormat(44100, 2, C.ENCODING_PCM_16BIT))
        processor.flush()
        processor.queueInput(AudioProcessor.EMPTY_BUFFER)
        assertEquals(0, processor.output.remaining())
        processor.queueInput(pcm(2).apply { putShort(1234); putShort(-1234); flip() })
        val dry = processor.output
        assertEquals(1234, dry.short.toInt())
        assertEquals(-1234, dry.short.toInt())

        tuning = PcmTuning(reverb = 1f)
        processor.queueInput(AudioProcessor.EMPTY_BUFFER)
        assertEquals(0, processor.output.remaining())
        val impulse = pcm(8192)
        impulse.putShort(24000)
        while (impulse.hasRemaining()) impulse.putShort(0)
        impulse.flip()
        processor.queueInput(impulse)
        val output = processor.output
        assertEquals(14400, output.short.toInt())
        assertEquals(0, output.short.toInt())
        var wet = false
        var stereoSpread = false
        while (output.remaining() >= 4) {
            val left = output.short
            val right = output.short
            wet = wet || left != 0.toShort() || right != 0.toShort()
            stereoSpread = stereoSpread || left != right
        }
        assertTrue(wet && stereoSpread)
    }

    @Test
    fun equalizerTargetsItsFrequencyAndLevelUsesHeadroom() {
        val gains = MutableList(SoundSettings.bands.size) { 0f }.apply { this[4] = 12f }
        fun measure(freq: Int): Float {
            val dsp = SoundDsp(48000, 1).apply { update(PcmTuning(gains)) }
            val frame = FloatArray(1)
            var max = 0f
            repeat(4800) { index ->
                frame[0] = (sin(index * 2 * PI * freq / 48000) * .3).toFloat()
                dsp.process(frame)
                if (index > 3000) max = maxOf(max, abs(frame[0]))
            }
            return max
        }
        assertTrue(measure(1000) > measure(8000) * 3)
        val frame = floatArrayOf(.9f, -.9f)
        SoundDsp(44100, 2).apply { update(PcmTuning(preamp = 1.5f)) }.process(frame)
        assertEquals(1f, frame[0], .001f)
        assertEquals(-1f, frame[1], .001f)
    }

    private fun pcm(samples: Int) = ByteBuffer.allocateDirect(samples * 2).order(ByteOrder.nativeOrder())
}
