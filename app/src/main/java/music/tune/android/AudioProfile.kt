package music.tune.android

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sqrt

internal enum class AudioMood(val wire: String) {
    ACTIVE("active"),
    FUN("fun"),
    CALM("calm"),
    SAD("sad");

    companion object {
        fun fromWire(value: String?): AudioMood? = when (value?.lowercase(Locale.ROOT)) {
            "active" -> ACTIVE
            "fun" -> FUN
            "calm" -> CALM
            "sad" -> SAD
            else -> null
        }
    }
}

internal data class AudioProfile(
    val energy: Float,
    val bass: Float,
    val brightness: Float,
    val tempo: Float,
    val pulse: Float,
    val mood: AudioMood,
) {
    fun toJson(): JSONObject {
        val round3 = { x: Float -> (x.coerceIn(0f, 1f) * 1000f).roundToInt() / 1000.0 }
        val t = (tempo.coerceIn(40f, 220f) * 10f).roundToInt() / 10.0
        return JSONObject()
            .put("energy", round3(energy))
            .put("bass", round3(bass))
            .put("brightness", round3(brightness))
            .put("tempo", t)
            .put("pulse", round3(pulse))
            .put("mood", mood.wire)
    }

    fun moodAffinity(target: AudioMood): Float {
        val tempoNorm = ((tempo - 95f) / 60f).coerceIn(0f, 1f)
        val raw = when (target) {
            AudioMood.SAD ->
                (1f - energy) * .45f + (1f - bass) * .30f + (1f - pulse) * .15f + (1f - brightness) * .10f
            AudioMood.CALM ->
                (1f - energy) * .50f + (1f - bass) * .25f + (1f - pulse) * .25f
            AudioMood.ACTIVE ->
                energy * .45f + pulse * .25f + bass * .20f + tempoNorm * .10f
            AudioMood.FUN ->
                energy * .35f + brightness * .30f + pulse * .25f + tempoNorm * .10f
        }
        return raw.coerceIn(0f, 1f)
    }

    fun bestMood(): AudioMood =
        AudioMood.entries.maxByOrNull { moodAffinity(it) } ?: AudioMood.CALM

    fun distance(other: AudioProfile): Float {
        val de = abs(energy - other.energy) * .36f
        val db = abs(bass - other.bass) * .34f
        val dp = abs(pulse - other.pulse) * .16f
        val dbr = abs(brightness - other.brightness) * .14f
        return (de + db + dp + dbr).coerceIn(0f, 1f)
    }

    companion object {
        fun fromJson(obj: JSONObject?): AudioProfile? {
            obj ?: return null
            if (!obj.has("energy") || !obj.has("bass")) return null
            val energy = obj.optDouble("energy", Double.NaN).toFloat()
            val bass = obj.optDouble("bass", Double.NaN).toFloat()
            if (!energy.isFinite() || !bass.isFinite()) return null
            val brightness = obj.optDouble("brightness", 0.5).toFloat().let { if (it.isFinite()) it else .5f }.coerceIn(0f, 1f)
            val tempo = obj.optDouble("tempo", 110.0).toFloat().let { if (it.isFinite()) it else 110f }.coerceIn(40f, 220f)
            val pulse = obj.optDouble("pulse", 0.35).toFloat().let { if (it.isFinite()) it else .35f }.coerceIn(0f, 1f)
            val raw = AudioProfile(energy.coerceIn(0f, 1f), bass.coerceIn(0f, 1f), brightness, tempo, pulse, AudioMood.CALM)
            val mood = AudioMood.fromWire(obj.optString("mood", "")) ?: raw.bestMood()
            return raw.copy(mood = mood)
        }

        fun average(list: List<AudioProfile>): AudioProfile? {
            if (list.isEmpty()) return null
            val n = list.size.toFloat()
            val tmp = AudioProfile(
                energy = list.sumOf { it.energy.toDouble() }.toFloat() / n,
                bass = list.sumOf { it.bass.toDouble() }.toFloat() / n,
                brightness = list.sumOf { it.brightness.toDouble() }.toFloat() / n,
                tempo = list.sumOf { it.tempo.toDouble() }.toFloat() / n,
                pulse = list.sumOf { it.pulse.toDouble() }.toFloat() / n,
                mood = AudioMood.CALM,
            )
            return tmp.copy(mood = tmp.bestMood())
        }
    }
}

private data class FrameStat(
    val rms: Float,
    val bassRms: Float,
    val highRms: Float,
    val zcrHz: Float,
)

internal class StreamingAudioAnalyzer(channels: Int, sampleRate: Int) {
    private val chCount = channels.coerceAtLeast(1)
    private val rate = sampleRate.coerceAtLeast(8_000)
    private val frameSamples = (rate / FRAMES_PER_SEC).coerceAtLeast(1)
    private val alphaLp = (1.0 - exp(-2.0 * PI * 180.0 / rate)).toFloat()
    private val alphaHp = (1.0 - exp(-2.0 * PI * 2000.0 / rate)).toFloat()

    private var chAccum = 0f
    private var chIdx = 0
    private var lp1 = 0f
    private var lp2 = 0f
    private var lpHi = 0f
    private var prevX = 0f
    private var curCount = 0
    private var sumSq = 0f
    private var bassSq = 0f
    private var highSq = 0f
    private var zcrCount = 0
    private val frames = ArrayList<FrameStat>(1800)

    fun pushSample(sample: Float) {
        if (frames.size >= MAX_FRAMES) return
        chAccum += sample
        chIdx++
        if (chIdx < chCount) return
        val x = (chAccum / chCount).coerceIn(-2f, 2f)
        chAccum = 0f
        chIdx = 0

        lp1 += alphaLp * (x - lp1)
        lp2 += alphaLp * (lp1 - lp2)
        val bass = lp2

        lpHi += alphaHp * (x - lpHi)
        val high = x - lpHi

        if ((prevX < 0f) != (x < 0f)) zcrCount++
        prevX = x

        sumSq += x * x
        bassSq += bass * bass
        highSq += high * high
        curCount++

        if (curCount >= frameSamples) {
            val n = curCount.toFloat()
            val secs = n / rate.toFloat()
            frames.add(
                FrameStat(
                    rms = sqrt(sumSq / n),
                    bassRms = sqrt(bassSq / n),
                    highRms = sqrt(highSq / n),
                    zcrHz = (zcrCount / secs) * .5f,
                )
            )
            curCount = 0
            sumSq = 0f
            bassSq = 0f
            highSq = 0f
            zcrCount = 0
        }
    }

    val ready: Boolean get() = frames.size >= TARGET_READY_FRAMES
    val hasMinimum: Boolean get() = frames.size >= MIN_FINALIZE_FRAMES

    fun finalizeProfile(): AudioProfile? {
        val active = frames.filter { it.rms > .003f }
        if (active.size < 8 * FRAMES_PER_SEC) return null

        val rmsP75 = percentile(active.map { it.rms }, .75f)
        val bassP85 = percentile(active.map { it.bassRms }, .85f)
        val bassRatioP75 = percentile(active.map { it.bassRms / it.rms.coerceAtLeast(1e-6f) }, .75f)
        val highRatioP50 = percentile(active.map { it.highRms / it.rms.coerceAtLeast(1e-6f) }, .50f)
        val zcrP50 = percentile(active.map { it.zcrHz }, .50f)

        val rmsDb = 20f * log10(rmsP75.coerceAtLeast(1e-6f))
        val rmsScore = ((rmsDb + 26f) / 19f).coerceIn(0f, 1f)

        val bassDb = 20f * log10(bassP85.coerceAtLeast(1e-6f))
        val bassLevel = ((bassDb + 26f) / 18f).coerceIn(0f, 1f)
        val bassRatio = ((bassRatioP75 - .16f) / .54f).coerceIn(0f, 1f)
        val bass = (.55f * bassRatio + .45f * bassLevel).coerceIn(0f, 1f)

        val highScore = ((highRatioP50 - .08f) / .42f).coerceIn(0f, 1f)
        val zcrScore = ((zcrP50 - 180f) / 1800f).coerceIn(0f, 1f)
        val brightness = (.65f * highScore + .35f * zcrScore).coerceIn(0f, 1f)

        val (tempo, pulse) = estimateTempo(frames)
        val energy = (.60f * rmsScore + .25f * pulse + .15f * bass).coerceIn(0f, 1f)

        val base = AudioProfile(energy, bass, brightness, tempo, pulse, AudioMood.CALM)
        return base.copy(mood = base.bestMood())
    }

    private fun percentile(values: List<Float>, q: Float): Float {
        if (values.isEmpty()) return 0f
        val sorted = values.sorted()
        val idx = ((sorted.size - 1) * q.coerceIn(0f, 1f)).roundToInt().coerceIn(0, sorted.lastIndex)
        return sorted[idx]
    }

    private fun estimateTempo(all: List<FrameStat>): Pair<Float, Float> {
        if (all.size < 4 * FRAMES_PER_SEC) return 100f to 0f
        val onsets = FloatArray(all.size - 1)
        var sum = 0f
        for (i in onsets.indices) {
            val d = (all[i + 1].rms - all[i].rms).coerceAtLeast(0f) +
                .8f * (all[i + 1].bassRms - all[i].bassRms).coerceAtLeast(0f)
            onsets[i] = d
            sum += d
        }
        val mean = sum / onsets.size
        var denom = 0f
        for (i in onsets.indices) {
            val centered = (onsets[i] - mean).coerceAtLeast(0f)
            onsets[i] = centered
            denom += centered * centered
        }
        if (denom < 1e-6f) return 90f to 0f
        var bestLag = 20
        var bestCorr = 0f
        val maxLag = minOf(40, onsets.size / 2)
        for (lag in 12..maxLag) {
            var num = 0f
            for (i in 0 until onsets.size - lag) num += onsets[i] * onsets[i + lag]
            val corr = num / denom
            if (corr > bestCorr) {
                bestCorr = corr
                bestLag = lag
            }
        }
        val tempo = (FRAMES_PER_SEC * 60f / bestLag).coerceIn(60f, 200f)
        val pulse = (bestCorr * 1.45f).coerceIn(0f, 1f)
        return tempo to pulse
    }

    companion object {
        private const val FRAMES_PER_SEC = 40
        private const val MIN_FINALIZE_FRAMES = 10 * FRAMES_PER_SEC
        private const val TARGET_READY_FRAMES = 45 * FRAMES_PER_SEC
        private const val MAX_FRAMES = 90 * FRAMES_PER_SEC
    }
}

internal class AudioProfileStore(
    context: Context,
    private val catalogProfile: (String) -> AudioProfile?,
) {
    private val file = File(context.filesDir, "audio-profiles.json")
    private val lock = Any()
    private var loaded = false
    private val profiles = mutableMapOf<String, AudioProfile>()
    private val failed = mutableSetOf<String>()
    private val busy = AtomicBoolean(false)

    private fun ensureLoadedLocked() {
        if (loaded) return
        loaded = true
        if (file.isFile) {
            optional {
                val obj = JSONObject(file.readText(Charsets.UTF_8))
                obj.keys().forEach { id ->
                    AudioProfile.fromJson(obj.optJSONObject(id))?.let { profiles[id] = it }
                }
            }
        }
    }

    fun get(trackId: String): AudioProfile? {
        val cached = synchronized(lock) {
            ensureLoadedLocked()
            profiles[trackId]
        }
        return cached ?: catalogProfile(trackId)
    }

    fun put(trackId: String, profile: AudioProfile) {
        val snapshot = synchronized(lock) {
            ensureLoadedLocked()
            failed.remove(trackId)
            profiles[trackId] = profile
            JSONObject().also { obj -> profiles.forEach { (id, p) -> obj.put(id, p.toJson()) } }
        }
        optional { writeFileAtomically(file, snapshot.toString()) }
    }

    fun ensureAnalyzed(ids: List<String>, scope: CoroutineScope, resolveUri: (String) -> android.net.Uri?) {
        if (ids.isEmpty() || busy.get()) return
        val need = synchronized(lock) {
            ensureLoadedLocked()
            ids.filter { it !in profiles && it !in failed && catalogProfile(it) == null }.take(4)
        }
        if (need.isEmpty() || !busy.compareAndSet(false, true)) return
        scope.launch(Dispatchers.IO) {
            try {
                for (id in need) {
                    val uri = optional { resolveUri(id) }
                    val profile = uri?.let { decodePcmAndAnalyze(it) }
                    if (profile != null) {
                        put(id, profile)
                    } else {
                        synchronized(lock) { failed.add(id) }
                    }
                    kotlinx.coroutines.delay(400)
                }
            } finally {
                busy.set(false)
            }
        }
    }

    private fun decodePcmAndAnalyze(uri: android.net.Uri): AudioProfile? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            if (uri.scheme == "file" && uri.path != null) {
                extractor.setDataSource(uri.path!!)
            } else {
                extractor.setDataSource(uri.toString(), mapOf("User-Agent" to "Yandex-Music-API"))
            }
            val trackIndex = (0 until extractor.trackCount).firstOrNull { i ->
                extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null
            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            var rate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) format.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 44_100
            var channels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 2
            var analyzer = StreamingAudioAnalyzer(channels, rate)

            codec = MediaCodec.createDecoderByType(mime).apply {
                configure(format, null, null, 0)
                start()
            }
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var pushedSamples = 0L
            val maxSamples = rate.toLong() * channels.coerceAtLeast(1) * 60L

            while (!outputDone && pushedSamples < maxSamples) {
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buf = codec.getInputBuffer(inIdx)
                        val size = if (buf != null) extractor.readSampleData(buf, 0) else -1
                        if (size < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outFmt = codec.outputFormat
                        rate = outFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = outFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        analyzer = StreamingAudioAnalyzer(channels, rate)
                    }
                    outIdx >= 0 -> {
                        val outBuf = codec.getOutputBuffer(outIdx)
                        if (outBuf != null && info.size > 0) {
                            outBuf.position(info.offset)
                            outBuf.limit(info.offset + info.size)
                            outBuf.order(ByteOrder.nativeOrder())
                            while (outBuf.remaining() >= 2 && pushedSamples < maxSamples) {
                                analyzer.pushSample(outBuf.short / 32768f)
                                pushedSamples++
                            }
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true
                    }
                }
            }
            return analyzer.finalizeProfile()
        } catch (_: Throwable) {
            return null
        } finally {
            optional { codec?.stop() }
            optional { codec?.release() }
            optional { extractor.release() }
        }
    }
}

@OptIn(UnstableApi::class)
internal class AudioProfileProcessor(
    private val store: AudioProfileStore,
    private val currentTrackId: () -> String?,
) : BaseAudioProcessor() {
    private var activeId: String? = null
    private var analyzer: StreamingAudioAnalyzer? = null
    private var saved = false

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat =
        if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT) inputAudioFormat else AudioFormat.NOT_SET

    private fun flushAnalyzer(force: Boolean) {
        val id = activeId ?: return
        val current = analyzer ?: return
        if (saved) return
        if (current.ready || (force && current.hasMinimum)) {
            current.finalizeProfile()?.let {
                store.put(id, it)
                saved = true
            }
        }
    }

    override fun onFlush() {
        flushAnalyzer(force = true)
        val id = currentTrackId()
        activeId = id
        saved = id != null && store.get(id) != null
        analyzer = if (id != null && !saved && inputAudioFormat.channelCount > 0 && inputAudioFormat.sampleRate > 0) {
            StreamingAudioAnalyzer(inputAudioFormat.channelCount, inputAudioFormat.sampleRate)
        } else null
    }

    override fun onReset() {
        flushAnalyzer(force = true)
        activeId = null
        analyzer = null
        saved = false
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return
        val cur = currentTrackId()
        if (cur != activeId) {
            flushAnalyzer(force = true)
            activeId = cur
            saved = cur != null && store.get(cur) != null
            analyzer = if (cur != null && !saved && inputAudioFormat.channelCount > 0 && inputAudioFormat.sampleRate > 0) {
                StreamingAudioAnalyzer(inputAudioFormat.channelCount, inputAudioFormat.sampleRate)
            } else null
        }
        val a = analyzer
        if (a != null && !saved) {
            val dup = inputBuffer.duplicate().order(ByteOrder.nativeOrder())
            while (dup.remaining() >= 2) {
                a.pushSample(dup.short / 32768f)
                if (a.ready) {
                    flushAnalyzer(force = false)
                    break
                }
            }
        }
        val output = replaceOutputBuffer(inputBuffer.remaining())
        output.put(inputBuffer)
        output.flip()
    }
}

internal enum class WaveDiversity { ANY, FAVORITE, DISCOVER, POPULAR }

internal data class WaveContext(
    val targetMood: AudioMood? = null,
    val diversity: WaveDiversity = WaveDiversity.ANY,
    val focusArtist: String? = null,
    val blockedWave: Boolean = false,
    val centroid: AudioProfile? = null,
) {
    companion object {
        fun fromSeeds(seeds: List<String>, recentProfiles: List<AudioProfile>): WaveContext {
            var mood: AudioMood? = null
            var diversity = WaveDiversity.ANY
            var focusArtist: String? = null
            var blocked = false
            for (seed in seeds) {
                when (seed) {
                    "settingMoodEnergy:sad", "mood:sad" -> mood = AudioMood.SAD
                    "settingMoodEnergy:calm", "activity:fall-asleep", "activity:work-background", "mood:calm", "mood:chill" -> mood = AudioMood.CALM
                    "settingMoodEnergy:active", "activity:workout", "activity:wake-up", "mood:energetic", "mood:workout" -> mood = AudioMood.ACTIVE
                    "settingMoodEnergy:fun", "activity:road-trip", "mood:happy", "mood:party" -> mood = AudioMood.FUN
                    "settingDiversity:favorite" -> diversity = WaveDiversity.FAVORITE
                    "settingDiversity:discover" -> diversity = WaveDiversity.DISCOVER
                    "settingDiversity:popular" -> diversity = WaveDiversity.POPULAR
                    "settingLanguage:without-words" -> blocked = true
                    else -> when {
                        seed.startsWith("artist:") -> focusArtist = seed.removePrefix("artist:").takeIf(String::isNotBlank)
                        seed.contains("children") || seed.contains("podcast") || seed.contains("audiobook") -> blocked = true
                        seed.startsWith("mix-by-mood:") -> {
                            val part = seed.removePrefix("mix-by-mood:")
                            when {
                                "sad" in part -> mood = AudioMood.SAD
                                "calm" in part || "chill" in part -> mood = AudioMood.CALM
                                "fun" in part || "happy" in part -> mood = AudioMood.FUN
                                "active" in part || "energetic" in part -> mood = AudioMood.ACTIVE
                            }
                        }
                    }
                }
            }
            return WaveContext(mood, diversity, focusArtist, blocked, AudioProfile.average(recentProfiles))
        }
    }
}

internal fun writeFileAtomically(target: File, content: String) {
    val parent = target.parentFile
    if (parent != null && !parent.exists()) parent.mkdirs()
    val tmp = File(parent, "${target.name}.tmp")
    tmp.writeText(content, Charsets.UTF_8)
    if (!tmp.renameTo(target)) {
        target.writeText(content, Charsets.UTF_8)
        tmp.delete()
    }
}

internal class WaveTaste(filesDir: File) {
    constructor(context: Context) : this(context.filesDir)

    private val file = File(filesDir, "wave-taste.json")
    private val lock = Any()
    private var loaded = false
    private val artistIds = mutableMapOf<String, Pair<Double, Long>>()
    private val artistNames = mutableMapOf<String, Pair<Double, Long>>()
    private val trackScores = mutableMapOf<String, Pair<Double, Long>>()
    private val similar = mutableMapOf<String, Pair<Long, Set<String>>>()
    private val simBusy = AtomicBoolean(false)

    private fun ensureLoadedLocked() {
        if (loaded) return
        loaded = true
        if (!file.isFile) return
        optional {
            val root = JSONObject(file.readText(Charsets.UTF_8))
            readMap(root.optJSONObject("artistIds"), artistIds)
            readMap(root.optJSONObject("artistNames"), artistNames)
            readMap(root.optJSONObject("tracks"), trackScores)
            val sim = root.optJSONObject("similar")
            sim?.keys()?.forEach { id ->
                val e = sim.optJSONObject(id) ?: return@forEach
                val at = e.optLong("at", 0L)
                val ids = e.arr("ids").items().mapNotNull { it.asId() }.toSet()
                similar[id] = at to ids
            }
        }
    }

    private fun readMap(obj: JSONObject?, dst: MutableMap<String, Pair<Double, Long>>) {
        obj ?: return
        obj.keys().forEach { k ->
            val arr = obj.optJSONArray(k) ?: return@forEach
            if (arr.length() >= 2) {
                val s = arr.optDouble(0, 0.0)
                val ts = arr.optLong(1, 0L)
                if (s.isFinite() && ts > 0L) dst[k] = s to ts
            }
        }
    }

    private fun saveLocked() {
        val writeMap = { m: Map<String, Pair<Double, Long>> ->
            JSONObject().apply {
                m.forEach { (k, v) -> put(k, org.json.JSONArray(listOf(v.first, v.second))) }
            }
        }
        val simObj = JSONObject().apply {
            similar.forEach { (id, v) ->
                put(id, JSONObject().put("at", v.first).put("ids", org.json.JSONArray(v.second.toList())))
            }
        }
        val root = JSONObject()
            .put("artistIds", writeMap(artistIds))
            .put("artistNames", writeMap(artistNames))
            .put("tracks", writeMap(trackScores))
            .put("similar", simObj)
        optional { writeFileAtomically(file, root.toString()) }
    }

    private fun nowSec() = System.currentTimeMillis() / 1000L

    private fun decay(score: Double, since: Long, at: Long): Double =
        score * Math.pow(0.5, (at - since).coerceAtLeast(0L) / (HALF_LIFE * DAY))

    private fun bump(map: MutableMap<String, Pair<Double, Long>>, key: String, change: Double, at: Long) {
        val prev = map[key]?.let { (s, since) -> decay(s, since, at) } ?: 0.0
        map[key] = (prev + change).coerceIn(TASTE_MIN, TASTE_MAX) to at
    }

    fun react(kind: String, track: Track, playedSec: Double? = null) {
        val len = track.duration / 1000.0
        val aDelta = when (kind) {
            "trackFinished" -> if (len <= 0.0 || (playedSec ?: len) / len >= 0.7) 1.0 else 0.0
            "skip" -> if (playedSec != null && playedSec < 30.0) -1.0 else 0.0
            "like" -> 3.0
            "unlike" -> -3.0
            "dislike" -> -5.0
            "undislike" -> 5.0
            else -> 0.0
        }
        val tDelta = when (kind) {
            "trackFinished" -> if (len <= 0.0 || (playedSec ?: len) / len >= 0.7) 1.5 else 0.0
            "skip" -> if (playedSec != null && playedSec < 30.0) -2.0 else 0.0
            "like" -> 4.0
            "unlike" -> -3.0
            "dislike" -> -8.0
            "undislike" -> 5.0
            else -> 0.0
        }
        if (aDelta == 0.0 && tDelta == 0.0) return
        val ids = track.artists.map { it.id }.filter { it.isNotBlank() && !AlternateVersions.isSyntheticArtistId(it) }
        val names = track.artists.map { normName(it.title) }.filter(String::isNotEmpty)
        synchronized(lock) {
            ensureLoadedLocked()
            val at = nowSec()
            if (aDelta != 0.0) {
                ids.forEach { bump(artistIds, it, aDelta, at) }
                names.forEach { bump(artistNames, it, aDelta, at) }
            }
            if (tDelta != 0.0 && track.id.isNotBlank()) bump(trackScores, track.id, tDelta, at)
            saveLocked()
        }
    }

    fun likedArtists(): List<String> = synchronized(lock) {
        ensureLoadedLocked()
        val at = nowSec()
        artistIds.entries
            .map { (id, v) -> id to decay(v.first, v.second, at) }
            .filter { it.second >= 1.0 }
            .sortedByDescending { it.second }
            .take(20)
            .map { it.first }
    }

    fun loadSimilar(seeds: List<String>, scope: CoroutineScope, fetchSimilar: suspend (String) -> List<String>) {
        val need = synchronized(lock) {
            ensureLoadedLocked()
            val at = nowSec()
            seeds.filter { id ->
                id.isNotBlank() && !AlternateVersions.isSyntheticArtistId(id) &&
                    similar[id]?.let { (since, _) -> at - since > 7 * DAY.toLong() } != false
            }.take(3)
        }
        if (need.isEmpty() || !simBusy.compareAndSet(false, true)) return
        scope.launch(Dispatchers.IO) {
            try {
                for (id in need) {
                    val fetched = optional { fetchSimilar(id) } ?: continue
                    synchronized(lock) {
                        ensureLoadedLocked()
                        similar[id] = nowSec() to fetched.toSet()
                        saveLocked()
                    }
                }
            } finally {
                simBusy.set(false)
            }
        }
    }

    fun scoreWaveCandidate(
        track: Track,
        likedTrack: Boolean,
        inHistory: Boolean,
        isUgc: Boolean,
        profile: AudioProfile?,
        ctx: WaveContext,
        recentArtistIds: Set<String>,
        recentArtistNames: Set<String>,
        likedArtistIds: List<String>,
    ): Double? = synchronized(lock) {
        ensureLoadedLocked()
        scoreWaveCandidateLocked(
            at = nowSec(),
            trackId = track.id,
            artistIdList = track.artists.map { it.id }.filter(String::isNotBlank),
            artistNameList = track.artists.map { it.title }.filter(String::isNotBlank),
            likedTrack = likedTrack,
            inHistory = inHistory,
            isUgc = isUgc,
            profile = profile,
            ctx = ctx,
            recentArtistIds = recentArtistIds,
            recentArtistNames = recentArtistNames,
            likedArtistIds = likedArtistIds,
        )
    }

    internal fun setScoreForTest(trackId: String? = null, artistId: String? = null, artistName: String? = null, score: Double) {
        synchronized(lock) {
            ensureLoadedLocked()
            val at = nowSec()
            trackId?.let { trackScores[it] = score to at }
            artistId?.let { artistIds[it] = score to at }
            artistName?.let { artistNames[normName(it)] = score to at }
        }
    }

    internal fun scoreWaveCandidateLocked(
        at: Long,
        trackId: String,
        artistIdList: List<String>,
        artistNameList: List<String>,
        likedTrack: Boolean,
        inHistory: Boolean,
        isUgc: Boolean,
        profile: AudioProfile?,
        ctx: WaveContext,
        recentArtistIds: Set<String>,
        recentArtistNames: Set<String>,
        likedArtistIds: List<String>,
    ): Double? {
        if (ctx.blockedWave) return null
        if (ctx.diversity == WaveDiversity.FAVORITE && !likedTrack && !inHistory) return null
        if (ctx.diversity == WaveDiversity.DISCOVER && (likedTrack || inHistory)) return null
        ctx.focusArtist?.let { focus ->
            val direct = focus in artistIdList
            val sim = similar[focus]?.second?.any { it in artistIdList } == true
            if (!direct && !sim) return null
        }

        val trTaste = trackScores[trackId]?.let { (s, since) -> decay(s, since, at) } ?: 0.0
        if (trTaste <= TASTE_BLOCK) return null

        val idTastes = artistIdList.map { id -> artistIds[id]?.let { (s, since) -> decay(s, since, at) } ?: 0.0 }
        if (idTastes.any { it <= TASTE_BLOCK }) return null

        val normNames = artistNameList.map(::normName).filter(String::isNotEmpty)
        val nameTastes = normNames.map { n -> artistNames[n]?.let { (s, since) -> decay(s, since, at) } ?: 0.0 }
        if (nameTastes.any { it <= TASTE_BLOCK }) return null

        var moodBonus = 0.0
        ctx.targetMood?.let { target ->
            val p = profile ?: return null
            when (target) {
                AudioMood.SAD, AudioMood.CALM -> if (p.bass > .52f || p.energy > .58f || p.pulse > .68f) return null
                AudioMood.ACTIVE -> if (p.energy < .35f) return null
                AudioMood.FUN -> if (p.energy < .25f) return null
            }
            val aff = p.moodAffinity(target)
            if (aff < .45f) return null
            moodBonus += (aff - .45f) * 4.0
        }
        ctx.centroid?.let { centroid ->
            if (profile != null) {
                val dist = profile.distance(centroid)
                if (dist > .46f || (centroid.bass < .38f && profile.bass > .62f) || (centroid.energy < .40f && profile.energy > .70f)) {
                    return null
                }
                moodBonus += (1f - (dist / .46f)).coerceIn(0f, 1f) * 1.5
            } else if (centroid.energy < .42f && centroid.bass < .42f) {
                return null
            }
        }

        var total = 0.0
        if (likedTrack) total += 2.0
        if (inHistory) total += 1.0
        if (isUgc && likedTrack && ctx.targetMood == null && ctx.centroid == null) total += 0.5
        total += trTaste.coerceIn(-3.0, 3.0)

        if (artistIdList.any { it in recentArtistIds } || normNames.any { it in recentArtistNames }) {
            total += 2.0
        }
        val bestIdTaste = idTastes.maxOrNull() ?: Double.NEGATIVE_INFINITY
        val bestNameTaste = nameTastes.maxOrNull() ?: Double.NEGATIVE_INFINITY
        val bestArtistTaste = maxOf(bestIdTaste, bestNameTaste)
        if (bestArtistTaste.isFinite()) total += bestArtistTaste.coerceIn(-2.0, 3.0)

        fun similarTo(seed: String) = similar[seed]?.second?.any { it in artistIdList } == true
        val simScore = likedArtistIds.count(::similarTo) * 2.0 + recentArtistIds.count(::similarTo)
        total += minOf(simScore, 2.0)
        total += moodBonus
        return total
    }

    companion object {
        const val WAVE_PASS = 2.0
        private const val TASTE_MIN = -10.0
        private const val TASTE_MAX = 10.0
        private const val TASTE_BLOCK = -3.0
        private const val DAY = 86_400.0
        private const val HALF_LIFE = 60.0

        fun normName(name: String): String =
            name.trim().lowercase(Locale.ROOT).replace('ё', 'е')

        fun pickWeighted(scores: List<Double>, random: Double): Int? {
            val total = scores.sum()
            if (scores.isEmpty() || total <= 0.0) return null
            var at = random.coerceIn(0.0, 0.999999) * total
            scores.forEachIndexed { index, score ->
                at -= score
                if (at < 0.0) return index
            }
            return scores.lastIndex
        }
    }
}
