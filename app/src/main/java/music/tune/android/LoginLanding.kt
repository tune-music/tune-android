package music.tune.android

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView

internal fun loginLanding(
    context: Context,
    connecting: Boolean = false,
    failed: Boolean = false,
    cancel: (() -> Unit)? = null,
    openYandex: () -> Unit,
): View {
    val ui = TuneUi(context)
    return ScrollView(context).apply {
        isFillViewport = true
        isVerticalScrollBarEnabled = false
        setBackgroundColor(Color.BLACK)
        val body = ui.column().apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(ui.dp(24), ui.dp(20), ui.dp(24), ui.dp(if (cancel == null) 32 else 24))
            addView(View(context), LinearLayout.LayoutParams(1, 0, 1f))
            addView(IconView(context, Symbol.SETTINGS, ui.palette.accent).apply {
                rotation = 90f
                iconSize = 64
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(ui.dp(64), ui.dp(64)))
            addView(ui.space(20))
            addView(ui.text("Tune", 40).apply {
                gravity = Gravity.CENTER
                typeface = Typeface.create("sans-serif", Typeface.BOLD)
            }, ui.full())
            addView(ui.space(20))
            addView(ui.text("Яндекс Музыка без цензуры\nи с офлайн-библиотекой", 16, ui.palette.muted).apply {
                gravity = Gravity.CENTER
            }, ui.full())
            addView(View(context), LinearLayout.LayoutParams(1, 0, 1f))
            addView(ui.space(20))
            if (cancel != null) {
                addView(ui.text("Вход ещё одним аккаунтом", 13, ui.palette.muted).apply { gravity = Gravity.CENTER }, ui.full())
                addView(ui.space(20))
            }
            if (failed) {
                addView(ui.text("Войти не получилось. Попробуйте ещё раз.", 13, 0xffff6861.toInt()).apply {
                    gravity = Gravity.CENTER
                }, ui.full())
                addView(ui.space(20))
            }
            addView(ui.button(if (connecting) "Проверяем вход…" else "Войти через Яндекс", primary = true, click = openYandex).apply {
                isEnabled = !connecting
                textSize = 17f
                setPadding(ui.dp(16), ui.dp(14), ui.dp(16), ui.dp(14))
                background = ui.touch(ui.palette.accent, 12)
            }, ui.full())
            if (cancel != null) {
                addView(ui.space(4))
                addView(ui.text("Отмена", 16).apply {
                    gravity = Gravity.CENTER
                    isFocusable = true
                    minHeight = ui.dp(48)
                    background = ui.touch()
                    setOnClickListener { cancel() }
                })
            }
        }
        addView(body, ViewGroup.LayoutParams(-1, -2))
    }
}
