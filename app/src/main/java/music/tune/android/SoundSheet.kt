package music.tune.android

import android.app.Dialog
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.LayerDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.roundToInt

internal class SoundSheet(private val activity: MainActivity) {
    private val ui = TuneUi(activity)
    private val prefs = activity.store.preferences
    private val rows = ui.column()
    private val scroll = ScrollView(activity).apply {
        isFillViewport = true
        overScrollMode = View.OVER_SCROLL_NEVER
        isVerticalScrollBarEnabled = false
        addView(rows, FrameLayout.LayoutParams(-1, -2))
    }
    private lateinit var reset: View

    fun show(onDismiss: () -> Unit): Dialog {
        val body = ui.column().apply { setBackgroundColor(0xff0d0d0d.toInt()) }
        val dialog = activity.bottomSheet(body, dark = true, scrollContent = false)
        body.addView(FrameLayout(activity).apply {
            addView(ui.line("Звук", 17, bold = true).apply { gravity = Gravity.CENTER },
                FrameLayout.LayoutParams(-1, -1).apply { marginStart = ui.dp(84); marginEnd = ui.dp(84) })
            addView(ui.line("Готово", 16, ui.palette.accentText, bold = true).apply {
                gravity = Gravity.CENTER
                setPadding(ui.dp(12), 0, ui.dp(12), 0)
                background = ui.touch()
                isFocusable = true
                pressFeedback(.94f)
                setOnClickListener { dialog.dismiss() }
            }, FrameLayout.LayoutParams(-2, -1, Gravity.END).apply { marginEnd = ui.dp(8) })
        }, ui.full(48))
        body.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        render()
        dialog.setOnDismissListener { onDismiss() }
        dialog.window?.setWindowAnimations(R.style.TuneSheetAnimation)
        dialog.show()
        val usable = Rect().also(activity.window.decorView::getWindowVisibleDisplayFrame)
        dialog.window?.setLayout(-1, (usable.height() * .92f).roundToInt())
        return dialog
    }

    private fun render() {
        val offset = scroll.scrollY
        rows.removeAllViews()
        rows.addView(SettingsGroup(ui, "Воспроизведение", "").apply {
            val speed = (prefs.getFloat("speed", 1f) * 20).roundToInt()
            add(slider("Скорость", speed, 10, 40, { "${speedText(it)}×" }) {
                prefs.edit().putFloat("speed", it / 20f).apply()
            })
            toggle("Тон вместе со скоростью", prefs.getBoolean("pitchWithSpeed", false)) {
                prefs.edit().putBoolean("pitchWithSpeed", it).apply()
                updateReset()
            }
            add(slider("Реверб", prefs.getInt("reverb", 0), 0, 100, { "$it%" }) {
                prefs.edit().putInt("reverb", it).apply()
            })
        }.view, ui.full())
        rows.addView(SettingsGroup(ui, "", "").apply {
            val enabled = SoundSettings.enabled(prefs)
            toggle("Эквалайзер", enabled) {
                prefs.edit().putBoolean("eqEnabled", it).apply()
                render()
            }
            if (enabled) {
                val gains = SoundSettings.gains(prefs)
                val options = listOf("Своя настройка") + SoundSettings.presets.keys
                val saved = prefs.getString("eqPreset", null)
                val current = if (saved in options) saved.orEmpty() else
                    SoundSettings.presets.entries.firstOrNull { it.value.toList() == gains }?.key ?: options.first()
                val presetLabel = ui.text(current, 15, ui.palette.muted).apply { gravity = Gravity.END }
                add(lineView().apply {
                    addView(ui.text("Пресет", 16), ui.weight())
                    addView(presetLabel, ui.weight())
                    addView(IconView(activity, Symbol.BACK, ui.palette.muted).apply { rotation = 180f; iconSize = 13 },
                        LinearLayout.LayoutParams(ui.dp(19), ui.dp(20)).apply { marginStart = ui.dp(6) })
                    background = ui.touch()
                    isFocusable = true
                    setOnClickListener {
                        activity.tuneChoices("Пресет", options, options.indexOf(presetLabel.text.toString()), dark = true) { choice ->
                            val option = options[choice]
                            val edit = prefs.edit().putString("eqPreset", option)
                            SoundSettings.presets[option]?.let { preset ->
                                edit.putInt("preamp", 100)
                                preset.forEachIndexed { index, gain -> edit.putInt("eqStep:$index", gain) }
                            }
                            edit.apply()
                            render()
                        }
                    }
                })
                fun markCustom(edit: SharedPreferences.Editor) {
                    edit.putString("eqPreset", options.first()).apply()
                    presetLabel.text = options.first()
                }
                add(slider("Уровень", (prefs.getInt("preamp", 100) / 5f).roundToInt(), 10, 30, { "${it * 5}%" }, live = true) {
                    markCustom(prefs.edit().putInt("preamp", it * 5))
                })
                SoundSettings.bands.forEachIndexed { index, hz ->
                    val label = if (hz < 1000) "$hz Гц" else "${hz / 1000} кГц"
                    add(slider(label, gains[index], -24, 24, ::gainText, live = true) {
                        markCustom(prefs.edit().putInt("eqStep:$index", it))
                    })
                }
            }
        }.view, ui.full())
        reset = SettingsGroup(ui, "", "").apply {
            line("Сбросить настройки звука", color = ui.palette.accentText) {
                val edit = prefs.edit().remove("speed").remove("pitchWithSpeed").remove("reverb")
                    .remove("eqEnabled").remove("equalizer").remove("preamp").remove("eqPreset")
                SoundSettings.bands.indices.forEach { edit.remove("eqBand:$it").remove("eqStep:$it") }
                edit.apply()
                render()
            }
        }.view
        rows.addView(reset, ui.full())
        rows.addView(ui.space(24))
        updateReset()
        scroll.post { scroll.scrollTo(0, offset) }
    }

    private fun updateReset() {
        if (::reset.isInitialized) {
            val changed = soundIsAdjusted(prefs) || SoundSettings.enabled(prefs) || prefs.getBoolean("pitchWithSpeed", false) ||
                prefs.getInt("preamp", 100) != 100 || SoundSettings.gains(prefs).any { it != 0 }
            reset.visibility = if (changed) View.VISIBLE else View.GONE
        }
    }

    private fun slider(title: String, level: Int, min: Int, max: Int, format: (Int) -> String,
                       live: Boolean = false, save: (Int) -> Unit): View =
        ui.row().apply {
            minimumHeight = ui.dp(48)
            setPadding(ui.dp(16), 0, ui.dp(16), 0)
            val value = ui.line(format(level.coerceIn(min, max)), 14, ui.palette.muted).apply {
                gravity = Gravity.END
                fontFeatureSettings = "tnum"
            }
            addView(ui.text(title, 15), LinearLayout.LayoutParams(ui.dp(78), -2))
            addView(SeekBar(activity).apply {
                this.max = max - min
                progress = (level - min).coerceIn(0, this.max)
                contentDescription = title
                background = null
                val track = LayerDrawable(arrayOf(ui.shape(ui.palette.line, 2),
                    ClipDrawable(ui.shape(ui.palette.accent, 2), Gravity.START, ClipDrawable.HORIZONTAL)))
                track.setId(0, android.R.id.background)
                track.setId(1, android.R.id.progress)
                for (index in 0..1) {
                    track.setLayerHeight(index, ui.dp(4))
                    track.setLayerGravity(index, Gravity.CENTER_VERTICAL)
                }
                progressDrawable = track
                thumb = ui.shape(Color.WHITE, 10).apply { setSize(ui.dp(20), ui.dp(20)) }
                thumbOffset = ui.dp(10)
                splitTrack = false
                progress = (level - min).coerceIn(0, this.max)
                setPadding(ui.dp(7), 0, ui.dp(7), 0)
                var dragging = false
                fun commit(position: Int) { save(min + position); updateReset() }
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onStartTrackingTouch(bar: SeekBar) { dragging = true }
                    override fun onStopTrackingTouch(bar: SeekBar) { dragging = false; if (!live) commit(bar.progress) }
                    override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                        value.text = format(min + progress)
                        if (fromUser && (live || !dragging)) commit(progress)
                    }
                })
            }, LinearLayout.LayoutParams(0, ui.dp(46), 1f))
            addView(value, LinearLayout.LayoutParams(ui.dp(58), -2).apply { marginStart = ui.dp(6) })
        }

    private fun speedText(value: Int) = NumberFormat.getNumberInstance(Locale.forLanguageTag("ru")).apply {
        maximumFractionDigits = 2
    }.format(value / 20f)

    private fun gainText(value: Int): String {
        val number = NumberFormat.getNumberInstance(Locale.forLanguageTag("ru")).apply {
            maximumFractionDigits = 1
        }.format(value / 2f)
        return if (value > 0) "+$number дБ" else "$number дБ"
    }
}

internal fun soundIsAdjusted(prefs: SharedPreferences): Boolean =
    prefs.getFloat("speed", 1f) != 1f || SoundSettings.tuning(prefs).active
