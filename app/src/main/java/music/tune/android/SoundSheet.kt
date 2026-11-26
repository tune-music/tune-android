package music.tune.android

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.LayerDrawable
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs
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
            val speedSteps = (prefs.getFloat("speed", 1f) * 20).roundToInt().coerceIn(10, 40)
            var speedBarRef: SeekBar? = null
            var speedValueRef: TextView? = null
            val speedPresets = listOf(17 to "0,85×", 20 to "1×", 25 to "1,25×", 30 to "1,5×")
            val speedChips = mutableListOf<Pair<Int, TextView>>()
            fun syncSpeedChips(step: Int) {
                speedChips.forEach { (presetStep, chip) ->
                    val active = presetStep == step
                    chip.isSelected = active
                    chip.setTextColor(if (active) Color.BLACK else ui.palette.text)
                    chip.background = ui.touch(if (active) ui.palette.accent else ui.palette.lifted, 10)
                }
            }
            add(slider("Скорость", speedSteps, 10, 40, { "${speedText(it)}×" }, onCreated = { bar, label ->
                speedBarRef = bar
                speedValueRef = label
            }) { step ->
                prefs.edit().putFloat("speed", step / 20f).apply()
                syncSpeedChips(step)
            })
            add(ui.row().apply {
                setPadding(ui.dp(16), ui.dp(6), ui.dp(16), ui.dp(10))
                speedPresets.forEachIndexed { idx, (presetStep, caption) ->
                    val chip = ui.line(caption, 13, bold = true).apply {
                        gravity = Gravity.CENTER
                        minHeight = ui.dp(32)
                        fontFeatureSettings = "tnum"
                        pressFeedback(.94f)
                        isFocusable = true
                        setOnClickListener {
                            prefs.edit().putFloat("speed", presetStep / 20f).apply()
                            speedBarRef?.progress = presetStep - 10
                            speedValueRef?.text = "${speedText(presetStep)}×"
                            syncSpeedChips(presetStep)
                            updateReset()
                        }
                    }
                    speedChips += presetStep to chip
                    addView(chip, LinearLayout.LayoutParams(0, ui.dp(32), 1f).apply {
                        if (idx > 0) marginStart = ui.dp(8)
                    })
                }
                syncSpeedChips(speedSteps)
            })
            toggle("Тон вместе со скоростью", prefs.getBoolean("pitchWithSpeed", false)) {
                prefs.edit().putBoolean("pitchWithSpeed", it).apply()
                updateReset()
            }
            add(slider("Реверб", prefs.getInt("reverb", 0), 0, 100, { "$it%" }) {
                prefs.edit().putInt("reverb", it).apply()
            })
        }.view, ui.full())
        val eightD = prefs.getBoolean("eightD", false)
        val eightDMode = EightDMode.fromId(prefs.getInt("eightDMode", 0))
        rows.addView(SettingsGroup(ui, "", if (eightD) eightDMode.description else "").apply {
            toggle("8D звук", eightD) {
                prefs.edit().putBoolean("eightD", it).apply()
                render()
            }
            if (eightD) {
                line("Режим", eightDMode.title, chevron = true) {
                    val modes = EightDMode.entries
                    activity.tuneChoices("Режим 8D", modes.map { it.title }, modes.indexOf(eightDMode), dark = true) { choice ->
                        prefs.edit().putInt("eightDMode", modes[choice].id).apply()
                        render()
                    }
                }
                val periodTitle = if (eightDMode == EightDMode.FIXED) "Период" else "База"
                val periodSteps = (prefs.getFloat("eightDPeriod", 8f).coerceIn(2f, 20f) * 2).roundToInt()
                add(slider(periodTitle, periodSteps, 4, 40, { "${periodText(it)} с" }, live = true) {
                    prefs.edit().putFloat("eightDPeriod", it / 2f).apply()
                })
                add(slider("Плавность", prefs.getInt("eightDSmoothness", 85), 0, 100, { "$it%" }, live = true) {
                    prefs.edit().putInt("eightDSmoothness", it).apply()
                })
                add(slider("Ширина", prefs.getInt("eightDDepth", 85), 0, 100, { "$it%" }, live = true) {
                    prefs.edit().putInt("eightDDepth", it).apply()
                })
                if (eightDMode != EightDMode.FIXED) {
                    add(slider("Отклик", prefs.getInt("eightDSensitivity", 65), 10, 100, { "$it%" }, live = true) {
                        prefs.edit().putInt("eightDSensitivity", it).apply()
                    })
                }
            }
        }.view, ui.full())
        rows.addView(SettingsGroup(ui, "", "").apply {
            val enabled = SoundSettings.enabled(prefs)
            toggle("Эквалайзер", enabled) {
                prefs.edit().putBoolean("eqEnabled", it).apply()
                render()
            }
            if (enabled) {
                val gains = SoundSettings.gains(prefs).toMutableList()
                val options = listOf("Своя настройка") + SoundSettings.presets.keys
                val saved = prefs.getString("eqPreset", null)
                var currentPreset = if (saved in options) saved.orEmpty() else
                    SoundSettings.presets.entries.firstOrNull { it.value.toList() == gains }?.key ?: options.first()
                var preampBarRef: SeekBar? = null
                var preampValueRef: TextView? = null
                lateinit var eqView: GraphicEqualizerView
                val presetChips = mutableListOf<Pair<String, TextView>>()
                fun syncPresetChips(selected: String) {
                    currentPreset = selected
                    presetChips.forEach { (name, chip) ->
                        val active = name == selected
                        chip.visibility = if (name == options.first() && !active) View.GONE else View.VISIBLE
                        chip.isSelected = active
                        chip.setTextColor(if (active) Color.BLACK else ui.palette.text)
                        chip.background = ui.touch(if (active) ui.palette.accent else ui.palette.lifted, 20)
                    }
                }
                val chipRow = ui.row().apply {
                    setPadding(ui.dp(16), ui.dp(10), ui.dp(16), ui.dp(10))
                }
                options.forEach { option ->
                    val active = option == currentPreset
                    val chip = ui.button(option, active) {
                        val edit = prefs.edit().putString("eqPreset", option)
                        SoundSettings.presets[option]?.let { preset ->
                            edit.putInt("preamp", 100)
                            preset.forEachIndexed { index, gain ->
                                gains[index] = gain
                                edit.putInt("eqStep:$index", gain)
                            }
                            preampBarRef?.progress = 10
                            preampValueRef?.text = "100%"
                            eqView.setLevels(gains)
                        }
                        edit.apply()
                        syncPresetChips(option)
                        updateReset()
                    }.apply {
                        minHeight = ui.dp(32)
                        setPadding(ui.dp(12), ui.dp(5), ui.dp(12), ui.dp(5))
                        visibility = if (option == options.first() && !active) View.GONE else View.VISIBLE
                        background = ui.touch(if (active) ui.palette.accent else ui.palette.lifted, 20)
                    }
                    presetChips += option to chip
                    chipRow.addView(chip, LinearLayout.LayoutParams(-2, ui.dp(32)).apply {
                        marginEnd = ui.dp(8)
                    })
                }
                val presetStrip = HorizontalScrollView(activity).apply {
                    isHorizontalScrollBarEnabled = false
                    overScrollMode = View.OVER_SCROLL_NEVER
                    clipToPadding = false
                    addView(chipRow, ViewGroup.LayoutParams(-2, -2))
                    addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                        val activeChip = presetChips.firstOrNull { it.first == currentPreset }?.second ?: return@addOnLayoutChangeListener
                        if (activeChip.visibility == View.VISIBLE && (activeChip.right > scrollX + width || activeChip.left < scrollX)) {
                            scrollTo((activeChip.left - ui.dp(16)).coerceAtLeast(0), 0)
                        }
                    }
                }
                add(presetStrip)
                fun markCustom(edit: SharedPreferences.Editor) {
                    edit.putString("eqPreset", options.first()).apply()
                    if (currentPreset != options.first()) syncPresetChips(options.first())
                }
                eqView = GraphicEqualizerView(ui, gains, ::gainText) { index, halfDb ->
                    gains[index] = halfDb
                    markCustom(prefs.edit().putInt("eqStep:$index", halfDb))
                    updateReset()
                }
                add(eqView)
                add(slider("Уровень", (prefs.getInt("preamp", 100) / 5f).roundToInt(), 10, 30, { "${it * 5}%" },
                    live = true, onCreated = { bar, label ->
                        preampBarRef = bar
                        preampValueRef = label
                    }) {
                    markCustom(prefs.edit().putInt("preamp", it * 5))
                })
            }
        }.view, ui.full())
        reset = SettingsGroup(ui, "", "").apply {
            line("Сбросить настройки звука", color = ui.palette.accentText) {
                val edit = prefs.edit().remove("speed").remove("pitchWithSpeed").remove("reverb")
                    .remove("eightD").remove("eightDMode").remove("eightDPeriod")
                    .remove("eightDSmoothness").remove("eightDDepth").remove("eightDSensitivity")
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
                       live: Boolean = false, onCreated: ((SeekBar, TextView) -> Unit)? = null,
                       save: (Int) -> Unit): View =
        ui.row().apply {
            minimumHeight = ui.dp(48)
            setPadding(ui.dp(16), 0, ui.dp(16), 0)
            val value = ui.line(format(level.coerceIn(min, max)), 14, ui.palette.muted).apply {
                gravity = Gravity.END
                fontFeatureSettings = "tnum"
            }
            addView(ui.text(title, 15), LinearLayout.LayoutParams(ui.dp(78), -2))
            val bar = SeekBar(activity).apply {
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
                setPadding(ui.dp(11), 0, ui.dp(11), 0)
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
            }
            addView(bar, LinearLayout.LayoutParams(0, ui.dp(46), 1f))
            addView(value, LinearLayout.LayoutParams(ui.dp(58), -2).apply { marginStart = ui.dp(6) })
            onCreated?.invoke(bar, value)
        }

    private fun speedText(value: Int) = NumberFormat.getNumberInstance(Locale.forLanguageTag("ru")).apply {
        maximumFractionDigits = 2
    }.format(value / 20f)

    private fun periodText(value: Int) = NumberFormat.getNumberInstance(Locale.forLanguageTag("ru")).apply {
        maximumFractionDigits = 1
    }.format(value / 2f)

    private fun gainText(value: Int): String {
        val number = NumberFormat.getNumberInstance(Locale.forLanguageTag("ru")).apply {
            maximumFractionDigits = 1
        }.format(value / 2f)
        return if (value > 0) "+$number дБ" else "$number дБ"
    }
}

internal fun soundIsAdjusted(prefs: SharedPreferences): Boolean =
    prefs.getFloat("speed", 1f) != 1f || SoundSettings.tuning(prefs).active

@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
private class GraphicEqualizerView(
    ui: TuneUi,
    initialLevels: List<Int>,
    private val formatGain: (Int) -> String,
    private val onChange: (index: Int, halfDb: Int) -> Unit,
) : View(ui.context) {
    private val density = resources.displayMetrics.density
    private val slop = ViewConfiguration.get(ui.context).scaledTouchSlop.toFloat()
    private val levels = IntArray(SoundSettings.bands.size) { initialLevels.getOrElse(it) { 0 }.coerceIn(-24, 24) }
    private val labels = SoundSettings.bands.map { hz -> if (hz < 1000) "$hz" else "${hz / 1000}k" }
    private val fullTitles = SoundSettings.bands.map { hz -> if (hz < 1000) "$hz Гц" else "${hz / 1000} кГц" }
    private val pointX = FloatArray(levels.size)
    private val pointY = FloatArray(levels.size)
    private var activeBand = -1
    private var lastUpBand = -1
    private var lastUpMs = 0L
    private var downX = 0f
    private var downY = 0f
    private var maxMove = 0f

    private val zeroPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ColorUtils.setAlphaComponent(ui.palette.text, 41)
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
        pathEffect = DashPathEffect(floatArrayOf(3f * density, 3f * density), 0f)
    }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ColorUtils.setAlphaComponent(ui.palette.text, 31)
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 2f * density
    }
    private val activeTrackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ui.palette.accent
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 3f * density
    }
    private val curvePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ui.palette.accent
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 2f * density
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ColorUtils.setAlphaComponent(ui.palette.accent, 36)
        style = Paint.Style.FILL
    }
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ui.palette.accent
        style = Paint.Style.FILL
    }
    private val thumbStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xff1c1c1c.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ui.palette.muted
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 10f, resources.displayMetrics)
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val activeLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ui.palette.accentText
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 10f, resources.displayMetrics)
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
    }
    private val headerLeftPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ui.palette.muted
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 12f, resources.displayMetrics)
        textAlign = Paint.Align.LEFT
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val headerRightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ui.palette.muted
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 11f, resources.displayMetrics)
        textAlign = Paint.Align.RIGHT
        fontFeatureSettings = "tnum"
    }
    private val curvePath = Path()
    private val areaPath = Path()

    init {
        minimumHeight = ui.dp(220)
        isFocusable = true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = resolveSize((320f * density).roundToInt(), widthMeasureSpec)
        val h = resolveSize(minimumHeight, heightMeasureSpec)
        setMeasuredDimension(w, h)
    }

    fun setLevels(updated: List<Int>) {
        for (i in levels.indices) {
            levels[i] = updated.getOrElse(i) { 0 }.coerceIn(-24, 24)
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val sidePad = 20f * density
        val headerY = 18f * density
        val top = 34f * density
        val bottom = h - 26f * density
        val spanH = (bottom - top).coerceAtLeast(1f)
        val midY = (top + bottom) * 0.5f
        val colStep = (w - sidePad * 2f) / (levels.size - 1).coerceAtLeast(1)

        if (activeBand in levels.indices) {
            headerLeftPaint.color = Color.WHITE
            headerRightPaint.color = 0xfffacc15.toInt()
            headerRightPaint.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 12f, resources.displayMetrics)
            canvas.drawText(fullTitles[activeBand], sidePad * 0.8f, headerY, headerLeftPaint)
            canvas.drawText(formatGain(levels[activeBand]), w - sidePad * 0.8f, headerY, headerRightPaint)
        } else {
            headerLeftPaint.color = 0xff999999.toInt()
            headerRightPaint.color = 0xff999999.toInt()
            headerRightPaint.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 11f, resources.displayMetrics)
            canvas.drawText("АЧХ", sidePad * 0.8f, headerY, headerLeftPaint)
            canvas.drawText("+12 / 0 / −12 дБ", w - sidePad * 0.8f, headerY, headerRightPaint)
        }

        canvas.drawLine(sidePad * 0.5f, midY, w - sidePad * 0.5f, midY, zeroPaint)

        for (i in levels.indices) {
            val x = sidePad + colStep * i
            val norm = (24 - levels[i]) / 48f
            pointX[i] = x
            pointY[i] = top + norm * spanH
            canvas.drawLine(x, top, x, bottom, trackPaint)
            if (levels[i] != 0) {
                canvas.drawLine(x, midY, x, pointY[i], activeTrackPaint)
            }
        }

        curvePath.rewind()
        areaPath.rewind()
        curvePath.moveTo(pointX[0], pointY[0])
        areaPath.moveTo(pointX[0], midY)
        areaPath.lineTo(pointX[0], pointY[0])
        for (i in 0 until levels.lastIndex) {
            val midX = (pointX[i] + pointX[i + 1]) * 0.5f
            curvePath.cubicTo(midX, pointY[i], midX, pointY[i + 1], pointX[i + 1], pointY[i + 1])
            areaPath.cubicTo(midX, pointY[i], midX, pointY[i + 1], pointX[i + 1], pointY[i + 1])
        }
        areaPath.lineTo(pointX.last(), midY)
        areaPath.close()

        canvas.drawPath(areaPath, fillPaint)
        canvas.drawPath(curvePath, curvePaint)

        val labelY = h - 7f * density
        for (i in levels.indices) {
            val isActive = i == activeBand
            val r = (if (isActive) 7.5f else 6f) * density
            canvas.drawCircle(pointX[i], pointY[i], r, thumbPaint)
            canvas.drawCircle(pointX[i], pointY[i], r, thumbStrokePaint)
            canvas.drawText(labels[i], pointX[i], labelY, if (isActive) activeLabelPaint else labelPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return false
        val sidePad = 20f * density
        val top = 34f * density
        val bottom = h - 26f * density
        val spanH = (bottom - top).coerceAtLeast(1f)
        val colStep = ((w - sidePad * 2f) / (levels.size - 1).coerceAtLeast(1)).coerceAtLeast(1f)

        fun updateBand(index: Int, y: Float) {
            val norm = ((y - top) / spanH).coerceIn(0f, 1f)
            val snapped = (24 - (norm * 48f).roundToInt()).coerceIn(-24, 24)
            if (levels[index] != snapped) {
                levels[index] = snapped
                if (snapped == 0) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                onChange(index, snapped)
                invalidate()
            }
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                downX = event.x
                downY = event.y
                maxMove = 0f
                val band = ((event.x - sidePad) / colStep).roundToInt().coerceIn(0, levels.lastIndex)
                activeBand = band
                val now = SystemClock.uptimeMillis()
                if (band == lastUpBand && now - lastUpMs < 300L) {
                    lastUpBand = -1
                    if (levels[band] != 0) {
                        levels[band] = 0
                        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        onChange(band, 0)
                    }
                    invalidate()
                    return true
                }
                updateBand(band, event.y)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - downX
                val dy = event.y - downY
                maxMove = maxOf(maxMove, kotlin.math.hypot(dx, dy))
                if (activeBand in levels.indices) updateBand(activeBand, event.y)
                return true
            }
            MotionEvent.ACTION_UP -> {
                val endedBand = activeBand
                activeBand = -1
                parent?.requestDisallowInterceptTouchEvent(false)
                if (maxMove < slop && endedBand in levels.indices) {
                    lastUpBand = endedBand
                    lastUpMs = SystemClock.uptimeMillis()
                }
                invalidate()
                performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                activeBand = -1
                parent?.requestDisallowInterceptTouchEvent(false)
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
