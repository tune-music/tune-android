package music.tune.android

import android.content.SharedPreferences
import kotlin.math.pow

internal object SoundSettings {
    val bands = listOf(60, 170, 310, 600, 1000, 3000, 6000, 12000, 14000, 16000)
    val presets = linkedMapOf(
        "По умолчанию" to IntArray(10),
        "Классическая музыка" to intArrayOf(-1, -1, -1, -1, -1, -1, -7, -7, -7, -9),
        "Клубная музыка" to intArrayOf(-1, -1, 8, 5, 5, 5, 3, -1, -1, -1),
        "Танцевальная музыка" to intArrayOf(9, 7, 2, -1, -1, -5, -7, -7, -1, -1),
        "Усиление НЧ" to intArrayOf(8, 9, 9, 5, 1, -4, -8, -10, -11, -11),
        "Усиление НЧ и ВЧ" to intArrayOf(7, 5, -1, -7, -4, 1, 8, 11, 12, 12),
        "Усиление ВЧ" to intArrayOf(-9, -9, -9, -4, 2, 11, 16, 16, 16, 16),
        "Колонки ноутбука" to intArrayOf(4, 11, 5, -3, -2, 1, 4, 9, 12, 14),
        "Большой зал" to intArrayOf(10, 10, 5, 5, -1, -4, -4, -4, -1, -1),
        "Концерт" to intArrayOf(-4, -1, 4, 5, 5, 5, 4, 2, 2, 2),
        "Вечеринка" to intArrayOf(7, 7, -1, -1, -1, -1, -1, -1, 7, 7),
        "Поп" to intArrayOf(-1, 4, 7, 8, 5, -1, -2, -2, -1, -1),
        "Регги" to intArrayOf(-1, -1, -1, -5, -1, 6, 6, -1, -1, -1),
        "Рок" to intArrayOf(8, 4, -5, -8, -3, 4, 8, 11, 11, 11),
        "Ска" to intArrayOf(-2, -4, -4, -1, 4, 5, 8, 9, 11, 9),
        "Мягкое звучание" to intArrayOf(4, 1, -1, -2, -1, 4, 8, 9, 11, 12),
        "Софт-рок" to intArrayOf(4, 4, 2, -1, -4, -5, -3, -1, 2, 8),
        "Техно" to intArrayOf(8, 5, -1, -5, -4, -1, 8, 9, 9, 8),
    )

    fun enabled(prefs: SharedPreferences) = prefs.getBoolean("eqEnabled", prefs.getString("equalizer", "off") != "off")

    // Store integer half-decibels. Older Android builds stored whole decibels in eqBand.
    fun gains(prefs: SharedPreferences): List<Int> {
        val legacy = when (prefs.getString("equalizer", "off")) {
            "bass" -> intArrayOf(6, 5, 4, 2, 0, 0, -1, -1, 0, 0)
            "voice" -> intArrayOf(-4, -4, -2, 0, 2, 4, 5, 3, 0, -1)
            "bright" -> intArrayOf(-1, -1, 0, 0, 0, 0, 2, 3, 4, 5)
            else -> IntArray(bands.size)
        }
        return bands.indices.map { index ->
            prefs.getInt("eqStep:$index", prefs.getInt("eqBand:$index", legacy[index]) * 2).coerceIn(-24, 24)
        }
    }

    fun tuning(prefs: SharedPreferences): PcmTuning {
        val active = enabled(prefs)
        return PcmTuning(
            gains = if (active) gains(prefs).map { it / 2f } else emptyList(),
            preamp = if (active) prefs.getInt("preamp", 100).coerceIn(50, 150) / 100f else 1f,
            reverb = prefs.getInt("reverb", 0).coerceIn(0, 100) / 100f
        )
    }

    fun gain(track: Track): Float {
        val loudness = track.loudness ?: return 1f
        val limit = track.peak?.let { -1 - it } ?: Double.POSITIVE_INFINITY
        val adjustment = minOf(-14 - loudness, limit).coerceIn(-12.0, 0.0)
        return 10.0.pow(adjustment / 20).toFloat()
    }
}
