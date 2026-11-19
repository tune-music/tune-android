package music.tune.android

import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.Switch

internal class SettingsGroup(private val ui: TuneUi, title: String, footer: String) {
    private val card = ui.column().apply {
        background = ui.shape(if (ui.palette.light) android.graphics.Color.WHITE else ui.palette.panel, 12)
        clipToOutline = true
    }
    val view = ui.column().apply {
        setPadding(ui.dp(16), ui.dp(20), ui.dp(16), 0)
        if (title.isNotBlank()) addView(ui.text(title, 13, ui.palette.muted).apply {
            setPadding(ui.dp(16), 0, ui.dp(16), ui.dp(7))
        }, ui.full())
        addView(card, ui.full())
        if (footer.isNotBlank()) addView(ui.text(footer, 13, ui.palette.muted).apply {
            setPadding(ui.dp(16), ui.dp(7), ui.dp(16), 0)
            setLineSpacing(ui.dp(2).toFloat(), 1f)
        }, ui.full())
    }

    fun add(view: View) {
        if (card.childCount > 0) card.addView(ui.divider(), ui.full(1).apply { marginStart = ui.dp(16) })
        card.addView(view, ui.full())
    }

    fun line(title: String, value: String = "", icon: Symbol? = null, color: Int = ui.palette.text,
             chevron: Boolean = false, checked: Boolean = false, enabled: Boolean = true, click: (() -> Unit)? = null) = add(lineView().apply {
        icon?.let { addView(IconView(ui.context, it, color), LinearLayout.LayoutParams(ui.dp(24), ui.dp(24)).apply {
            marginEnd = ui.dp(10)
        }) }
        addView(ui.text(title, 16, if (enabled) color else ui.palette.muted), ui.weight())
        if (value.isNotBlank()) addView(ui.text(value, 15, ui.palette.muted).apply {
            fontFeatureSettings = "tnum"
            setPadding(ui.dp(8), 0, 0, 0)
        })
        if (checked) addView(IconView(ui.context, Symbol.CHECK, ui.palette.accentText).apply {
            iconSize = 16
        }, LinearLayout.LayoutParams(ui.dp(20), ui.dp(20)).apply { marginStart = ui.dp(6) })
        if (chevron) addView(IconView(ui.context, Symbol.BACK, ui.palette.muted).apply {
            rotation = 180f
            iconSize = 13
        }, LinearLayout.LayoutParams(ui.dp(19), ui.dp(20)).apply { marginStart = ui.dp(6) })
        if (click != null && enabled) {
            background = ui.touch()
            setOnClickListener { click() }
            isFocusable = true
        }
    })

    fun toggle(title: String, checked: Boolean, subtitle: String = "", enabled: Boolean = true, change: (Boolean) -> Unit) =
        add(lineView().apply {
            val toggle = Switch(ui.context).apply {
                isChecked = checked
                isEnabled = enabled
                contentDescription = title
                val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
                thumbTintList = ColorStateList.valueOf(android.graphics.Color.WHITE)
                trackTintList = ColorStateList(states, intArrayOf(ui.palette.accent, ui.palette.lifted))
                setOnCheckedChangeListener { _, value -> change(value) }
            }
            addView(ui.column().apply {
                setPadding(0, 0, ui.dp(10), 0)
                addView(ui.text(title, 16, if (enabled) ui.palette.text else ui.palette.muted))
                if (subtitle.isNotBlank()) {
                    addView(ui.space(3))
                    addView(ui.text(subtitle, 13, ui.palette.muted))
                }
            }, ui.weight())
            addView(toggle)
            if (enabled) setOnClickListener { toggle.toggle() }
        })

    fun lineView() = ui.row().apply {
        minimumHeight = ui.dp(46)
        gravity = Gravity.CENTER_VERTICAL
        setPadding(ui.dp(16), ui.dp(10), ui.dp(16), ui.dp(10))
    }
}
