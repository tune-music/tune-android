package music.tune.android

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

internal data class PcmTuning(val gains: List<Float> = emptyList(), val preamp: Float = 1f, val reverb: Float = 0f) {
    val active = preamp != 1f || reverb > 0f || gains.any { it != 0f }
}

@OptIn(UnstableApi::class)
internal class SoundProcessor(private val tuning: () -> PcmTuning) : BaseAudioProcessor() {
    private var renderer: SoundDsp? = null
    private var frame = FloatArray(0)

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat =
        if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT) inputAudioFormat else AudioFormat.NOT_SET

    override fun onFlush() {
        val size = inputAudioFormat.channelCount.coerceAtLeast(0)
        frame = FloatArray(size)
        renderer = if (size > 0 && inputAudioFormat.sampleRate > 0) SoundDsp(inputAudioFormat.sampleRate, size) else null
    }

    override fun onReset() {
        renderer = null
        frame = FloatArray(0)
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return
        val output = replaceOutputBuffer(inputBuffer.remaining())
        val options = tuning()
        val dsp = renderer
        dsp?.update(options)
        if (!options.active || dsp == null) {
            output.put(inputBuffer)
        } else {
            inputBuffer.order(ByteOrder.nativeOrder())
            while (inputBuffer.remaining() >= frame.size * 2) {
                for (index in frame.indices) frame[index] = inputBuffer.short / 32768f
                dsp.process(frame)
                for (sample in frame) output.putShort((sample * 32768f).roundToInt().coerceIn(-32768, 32767).toShort())
            }
            if (inputBuffer.hasRemaining()) output.put(inputBuffer)
        }
        output.flip()
    }
}

internal class SoundDsp(private val rate: Int, private val channels: Int) {
    private var current = PcmTuning()
    private var filters = emptyArray<EqualizerBand>()
    private var hall: Array<HallReverb>? = null
    private var gain = 1f
    private val inputScale = .015f * minOf(1f, 2f / channels)

    fun update(tuning: PcmTuning) {
        if (current == tuning) return
        if (current.gains != tuning.gains) {
            filters = SoundSettings.bands.mapIndexedNotNull { index, frequency ->
                val level = tuning.gains.getOrElse(index) { 0f }
                if (level == 0f || frequency >= rate * .49) return@mapIndexedNotNull null
                val shelf = when (index) { 0 -> 1; SoundSettings.bands.lastIndex -> -1; else -> 0 }
                EqualizerBand(frequency, level, shelf, rate, channels)
            }.toTypedArray()
        }
        val boost = filters.maxOfOrNull { it.level }?.coerceAtLeast(0f) ?: 0f
        gain = tuning.preamp * 10f.pow(-boost / 20f)
        if (tuning.reverb <= 0f) hall = null
        else if (hall == null) hall = Array(channels) { HallReverb(rate, it) }
        current = tuning
    }

    fun process(frame: FloatArray) {
        var mono = 0f
        for (channel in frame.indices) {
            var sample = frame[channel]
            for (band in filters) sample = band.process(sample, channel)
            frame[channel] = sample * gain
            mono += frame[channel]
        }
        val reverbs = hall
        val mix = current.reverb
        for (channel in frame.indices) {
            val sample = if (reverbs == null) frame[channel] else
                frame[channel] * (1f - mix * .4f) + reverbs[channel].process(mono * inputScale) * mix * .8f
            frame[channel] = sample.coerceIn(-1f, 1f)
        }
    }
}

private class EqualizerBand(frequency: Int, val level: Float, shelf: Int, rate: Int, channels: Int) {
    private val z1 = FloatArray(channels)
    private val z2 = FloatArray(channels)
    private val b0: Float
    private val b1: Float
    private val b2: Float
    private val a1: Float
    private val a2: Float

    init {
        val amplitude = 10f.pow(level / 40f)
        val angle = (2.0 * Math.PI * frequency / rate).toFloat()
        val cosine = cos(angle)
        val sine = sin(angle)
        if (shelf != 0) {
            val sum = amplitude + 1f
            val difference = amplitude - 1f
            val slope = sqrt(2f * amplitude) * sine
            val normalization = sum + shelf * difference * cosine + slope
            b0 = amplitude * (sum - shelf * difference * cosine + slope) / normalization
            b1 = shelf * 2f * amplitude * (difference - shelf * sum * cosine) / normalization
            b2 = amplitude * (sum - shelf * difference * cosine - slope) / normalization
            a1 = -shelf * 2f * (difference + shelf * sum * cosine) / normalization
            a2 = (sum + shelf * difference * cosine - slope) / normalization
        } else {
            val alpha = sine / 2f
            val normalization = 1f + alpha / amplitude
            b0 = (1f + alpha * amplitude) / normalization
            b1 = -2f * cosine / normalization
            b2 = (1f - alpha * amplitude) / normalization
            a1 = b1
            a2 = (1f - alpha / amplitude) / normalization
        }
    }

    fun process(input: Float, channel: Int): Float {
        val output = b0 * input + z1[channel]
        z1[channel] = b1 * input - a1 * output + z2[channel]
        z2[channel] = b2 * input - a2 * output
        return output
    }
}

private class HallReverb(rate: Int, channel: Int) {
    private val spread = if (channel % 2 == 0) 0 else 23
    private val combs = COMB_LENGTHS.map { DelayLine(length(it, rate)) }
    private val allpasses = ALLPASS_LENGTHS.map { DelayLine(length(it, rate)) }

    private fun length(length: Int, rate: Int) = ((length + spread) * (rate / 44100f)).roundToInt().coerceAtLeast(1)

    fun process(input: Float): Float {
        var output = 0f
        for (line in combs) output += line.comb(input)
        for (line in allpasses) output = line.allpass(output)
        return output
    }

    private class DelayLine(length: Int) {
        private val samples = FloatArray(length)
        private var cursor = 0
        private var damped = 0f

        fun comb(input: Float): Float {
            val delayed = samples[cursor]
            damped = delayed * .75f + damped * .25f
            if (abs(damped) < 1e-20f) damped = 0f
            put(input + damped * .86f)
            return delayed
        }

        fun allpass(input: Float): Float {
            val delayed = samples[cursor]
            put(input + delayed * .5f)
            return delayed - input
        }

        private fun put(sample: Float) {
            samples[cursor++] = sample
            if (cursor == samples.size) cursor = 0
        }
    }

    private companion object {
        val COMB_LENGTHS = intArrayOf(1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617)
        val ALLPASS_LENGTHS = intArrayOf(556, 441, 341, 225)
    }
}
