package music.tune.android

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

internal enum class EightDMode(val id: Int, val title: String, val description: String) {
    FIXED(0, "Фиксированный", "Равномерное перемещение между каналами с заданным периодом."),
    ENERGY(1, "По динамике трека", "На громких участках и припевах звук движется быстрее, на тихих — плавнее."),
    BEATS(2, "Под биты", "Удары баса и бочки ускоряют и перебрасывают звук между каналами.");

    companion object {
        fun fromId(id: Int) = entries.firstOrNull { it.id == id } ?: FIXED
    }
}

internal data class PcmTuning(
    val gains: List<Float> = emptyList(),
    val preamp: Float = 1f,
    val reverb: Float = 0f,
    val eightD: Boolean = false,
    val eightDMode: EightDMode = EightDMode.FIXED,
    val eightDPeriod: Float = 8f,
    val eightDSmoothness: Float = .85f,
    val eightDDepth: Float = .85f,
    val eightDSensitivity: Float = .65f,
) {
    val active = preamp != 1f || reverb > 0f || eightD || gains.any { it != 0f }
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
    private var spatial: Spatial8D? = null
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
        val effectiveReverb = if (tuning.eightD) maxOf(tuning.reverb, .16f * tuning.eightDDepth) else tuning.reverb
        if (effectiveReverb <= 0f) hall = null
        else if (hall == null) hall = Array(channels) { HallReverb(rate, it) }
        if (!tuning.eightD || channels < 2) spatial = null
        else if (spatial == null) spatial = Spatial8D(rate.toFloat())
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
        spatial?.process(frame, current)
        val reverbs = hall
        val mix = if (current.eightD) maxOf(current.reverb, .16f * current.eightDDepth) else current.reverb
        for (channel in frame.indices) {
            val sample = if (reverbs == null) frame[channel] else
                frame[channel] * (1f - mix * .4f) + reverbs[channel].process(mono * inputScale) * mix * .8f
            frame[channel] = sample.coerceIn(-1f, 1f)
        }
    }
}

internal class Spatial8D(sampleRate: Float) {
    private val rate = sampleRate.coerceAtLeast(8000f)
    private val delayMask: Int
    private val delayL: FloatArray
    private val delayR: FloatArray
    private var writeIndex = 0

    private var phase = 0f
    private var lowpassState = 0f
    private var fastBassEnv = 0f
    private var slowBassEnv = .01f
    private var fastEnergyEnv = 0f
    private var slowEnergyEnv = .02f
    private var beatBoost = 0f
    private var beatCooldown = 0
    private var direction = 1f

    private var shadeL = 0f
    private var shadeR = 0f

    private val bassAlpha: Float
    private val fastBassAttack: Float
    private val fastBassRelease: Float
    private val slowBassAlpha: Float
    private val fastEnergyAlpha: Float
    private val slowEnergyAlpha: Float
    private val beatDecay: Float
    private val minBeatGap: Int
    private val maxItdSamples: Float
    private val baseShadeAlpha: Float

    init {
        var size = 64
        val needed = (rate * .002f).toInt() + 5
        while (size < needed) size = size shl 1
        delayMask = size - 1
        delayL = FloatArray(size)
        delayR = FloatArray(size)

        val twoPi = (2.0 * PI).toFloat()
        bassAlpha = minOf(.5f, twoPi * 130f / rate)
        fastBassAttack = 1f - exp(-1f / (rate * .004f))
        fastBassRelease = 1f - exp(-1f / (rate * .045f))
        slowBassAlpha = 1f - exp(-1f / (rate * .38f))
        fastEnergyAlpha = 1f - exp(-1f / (rate * .12f))
        slowEnergyAlpha = 1f - exp(-1f / (rate * 2.2f))
        beatDecay = exp(-1f / (rate * .14f))
        minBeatGap = (rate * .2f).toInt()
        maxItdSamples = rate * .00048f
        baseShadeAlpha = minOf(.85f, twoPi * 3400f / rate)
    }

    fun process(frame: FloatArray, tuning: PcmTuning) {
        if (frame.size < 2) return
        val left = frame[0]
        val right = frame[1]
        val mid = (left + right) * .5f
        val side = (left - right) * .5f

        val absMid = abs(mid)
        fastEnergyEnv += (absMid - fastEnergyEnv) * fastEnergyAlpha
        slowEnergyEnv += (absMid - slowEnergyEnv) * slowEnergyAlpha

        lowpassState += (mid - lowpassState) * bassAlpha
        val bass = abs(lowpassState)
        val bassCoeff = if (bass > fastBassEnv) fastBassAttack else fastBassRelease
        fastBassEnv += (bass - fastBassEnv) * bassCoeff
        slowBassEnv += (bass - slowBassEnv) * slowBassAlpha

        if (beatCooldown > 0) beatCooldown -= 1
        beatBoost *= beatDecay

        val sensitivity = tuning.eightDSensitivity.coerceIn(.1f, 1f)
        var speedMultiplier = 1f

        when (tuning.eightDMode) {
            EightDMode.FIXED -> direction = 1f
            EightDMode.ENERGY -> {
                direction = 1f
                val ratio = fastEnergyEnv / maxOf(.008f, slowEnergyEnv)
                val delta = (ratio - 1f) * (1.2f + sensitivity * 2.2f)
                speedMultiplier = (1f + delta).coerceIn(.35f, 3.4f)
            }
            EightDMode.BEATS -> {
                val threshold = 1.95f - sensitivity * .72f
                if (beatCooldown == 0 && fastBassEnv > .018f && fastBassEnv > slowBassEnv * threshold) {
                    val strength = minOf(1f, (fastBassEnv / maxOf(.008f, slowBassEnv) - threshold) * 1.4f)
                    beatBoost = minOf(4.5f, beatBoost + 1.6f + strength * (1.5f + sensitivity * 2f))
                    if (strength > .55f && sensitivity > .45f) {
                        phase += direction * (.28f + .35f * sensitivity)
                    }
                    beatCooldown = minBeatGap
                }
                speedMultiplier = .65f + beatBoost
            }
        }

        val twoPi = (2.0 * PI).toFloat()
        val period = tuning.eightDPeriod.coerceIn(2f, 20f)
        val angularStep = (twoPi / (period * rate)) * speedMultiplier * direction
        phase += angularStep
        if (phase >= twoPi) phase -= twoPi
        else if (phase < 0f) phase += twoPi

        val depth = tuning.eightDDepth.coerceIn(0f, 1f)
        val pan = shapedPan(phase, tuning.eightDSmoothness, depth)
        val frontBack = cos(phase)

        val stereoKeep = .35f * (1f - depth * .65f)
        val sourceL = mid + side * stereoKeep
        val sourceR = mid - side * stereoKeep

        delayL[writeIndex] = sourceL
        delayR[writeIndex] = sourceR
        val delayedL = readDelay(delayL, maxOf(0f, pan) * maxItdSamples)
        val delayedR = readDelay(delayR, maxOf(0f, -pan) * maxItdSamples)
        writeIndex = (writeIndex + 1) and delayMask

        val rearShadow = maxOf(0f, -frontBack) * .25f * depth
        val shadowL = minOf(.75f, maxOf(0f, pan) * .55f + rearShadow)
        val shadowR = minOf(.75f, maxOf(0f, -pan) * .55f + rearShadow)

        shadeL += (delayedL - shadeL) * baseShadeAlpha
        shadeR += (delayedR - shadeR) * baseShadeAlpha

        val filteredL = delayedL * (1f - shadowL) + shadeL * shadowL
        val filteredR = delayedR * (1f - shadowR) + shadeR * shadowR

        val angle = (pan + 1f) * (PI.toFloat() * .25f)
        val gainL = cos(angle)
        val gainR = sin(angle)

        val cross = .12f * (1f - depth * .4f)
        frame[0] = filteredL * (gainL + cross * gainR)
        frame[1] = filteredR * (gainR + cross * gainL)
    }

    private fun readDelay(buffer: FloatArray, offset: Float): Float {
        val whole = offset.toInt()
        val frac = offset - whole
        val i0 = (writeIndex - whole) and delayMask
        val i1 = (i0 - 1) and delayMask
        return buffer[i0] * (1f - frac) + buffer[i1] * frac
    }

    companion object {
        fun shapedPan(phase: Float, smoothness: Float, depth: Float): Float {
            val raw = sin(phase)
            val sharpness = 1f + (1f - smoothness.coerceIn(0f, 1f)) * 8f
            val shaped = if (sharpness <= 1.001f) raw else tanh(raw * sharpness) / tanh(sharpness)
            return shaped * depth.coerceIn(0f, 1f)
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
