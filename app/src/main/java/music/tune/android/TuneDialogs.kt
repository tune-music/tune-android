package music.tune.android

import android.app.Dialog
import android.graphics.Rect
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.text.InputType
import androidx.core.widget.doAfterTextChanged

internal fun MainActivity.tuneInput(
    title: String,
    value: String,
    hint: String,
    multiline: Boolean = false,
    positive: String = "Готово",
    dark: Boolean = false,
    confirm: (String) -> Unit
) {
    val style = if (dark) TuneUi(this) else ui
    val dialog = tuneDialog(style)
    val field = style.dialogField(value, hint, multiline)
    fun submit() {
        val text = field.text.toString().trim().takeIf(String::isNotEmpty) ?: return
        dialog.dismiss()
        confirm(text)
    }
    val body = style.dialogBody(title)
    body.addView(field, style.full().apply { setMargins(style.dp(18), style.dp(2), style.dp(18), style.dp(16)) })
    val done = style.dialogButton(positive, accent = true, click = ::submit)
    field.bindSubmit(done, ::submit)
    body.addView(style.dialogButtons(style.dialogButton("Отмена") { dialog.dismiss() }, done), style.full())
    dialog.showEditor(style, body, field)
}

internal fun MainActivity.tuneTrackEditor(track: Track, confirm: (String, String) -> Unit) {
    val style = ui
    val dialog = tuneDialog(style)
    val body = style.dialogBody("Редактировать трек")
    val title = style.dialogField(track.title, "Название").apply {
        id = View.generateViewId()
        imeOptions = EditorInfo.IME_ACTION_NEXT
    }
    val artist = style.dialogField(track.artistText, "Исполнитель").apply { id = View.generateViewId() }
    title.nextFocusForwardId = artist.id
    title.setOnEditorActionListener { _, id, _ ->
        if (id != EditorInfo.IME_ACTION_NEXT) false else { artist.requestFocus(); true }
    }
    listOf(title, artist).forEach {
        body.addView(it, style.full().apply { setMargins(style.dp(18), style.dp(2), style.dp(18), style.dp(12)) })
    }
    fun submit() {
        val name = title.text.toString().trim().takeIf(String::isNotEmpty) ?: return
        dialog.dismiss()
        confirm(name, artist.text.toString().trim())
    }
    val save = style.dialogButton("Сохранить", accent = true, click = ::submit)
    title.bindSubmit(save, ::submit, useImeDone = false)
    artist.setOnEditorActionListener { _, id, _ ->
        if (id != EditorInfo.IME_ACTION_DONE) false else { submit(); true }
    }
    body.addView(style.dialogButtons(style.dialogButton("Отмена") { dialog.dismiss() }, save), style.full())
    dialog.showEditor(style, body, title)
}

private fun TuneUi.dialogField(value: String, hint: String, multiline: Boolean = false) = EditText(context).apply {
        inputType = if (multiline) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        if (multiline) {
            minLines = 3
            maxLines = 6
            gravity = Gravity.TOP
        } else {
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_DONE
        }
        background = shape(palette.background, 9)
        setTextColor(palette.text)
        setHintTextColor(palette.muted)
        highlightColor = 0x66facc15
        if (Build.VERSION.SDK_INT >= 29) {
            textCursorDrawable = shape(palette.accentText, 1).apply { setSize(dp(2), dp(18)) }
        }
        setPadding(dp(12), dp(10), dp(12), dp(10))
        textSize = 16f
        minimumHeight = dp(44)
        this.hint = hint
        setText(value)
        setSelection(value.length)
}

private fun EditText.bindSubmit(done: TextView, submit: () -> Unit, useImeDone: Boolean = true) {
    fun validate() {
        done.isEnabled = text.isNotBlank()
        done.alpha = if (done.isEnabled) 1f else .45f
    }
    doAfterTextChanged { validate() }
    validate()
    if (useImeDone) setOnEditorActionListener { _, action, _ ->
        if (action != EditorInfo.IME_ACTION_DONE) false else { submit(); true }
    }
}

private fun Dialog.showEditor(style: TuneUi, body: View, field: EditText) {
    @Suppress("DEPRECATION")
    window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
    setContentView(body)
    field.requestFocus()
    showSized(style)
}

internal fun MainActivity.tuneChoices(
    title: String,
    labels: List<String>,
    selected: Int,
    dark: Boolean,
    action: (Int) -> Unit
) {
    if (labels.isEmpty()) return
    val style = if (dark) TuneUi(this) else ui
    val dialog = tuneDialog(style)
    val body = style.dialogBody(title)
    val choices = style.column()
    val usable = Rect().also(window.decorView::getWindowVisibleDisplayFrame)
    val limit = minOf(style.dp(424), usable.height() * 54 / 100)
    labels.forEachIndexed { index, label ->
        if (index > 0) choices.addView(style.divider(), style.full(1).apply { marginStart = style.dp(16) })
        choices.addView(style.row().apply {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = style.dp(46)
            setPadding(style.dp(18), style.dp(8), style.dp(18), style.dp(8))
            val current = selected == index
            background = style.touch()
            isSelected = current
            isFocusable = true
            addView(style.text(label, 16, if (current) style.palette.accentText else style.palette.text), style.weight())
            if (current) addView(IconView(context, Symbol.CHECK, style.palette.accentText).apply { iconSize = 17 },
                LinearLayout.LayoutParams(style.dp(22), style.dp(22)))
            setOnClickListener { dialog.dismiss(); action(index) }
        }, style.full())
    }
    val height = if (labels.size > limit / style.dp(47)) limit else -2
    val scroller = ScrollView(this).apply {
        isVerticalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        addView(choices)
    }
    body.addView(scroller, LinearLayout.LayoutParams(-1, height))
    body.addView(style.dialogButtons(style.dialogButton(if (selected >= 0) "Отмена" else "Закрыть") { dialog.dismiss() }), style.full())
    dialog.setContentView(body)
    dialog.showSized(style)
    if (selected > 0) scroller.post {
        choices.getChildAt(selected * 2)?.let { current -> scroller.scrollTo(0, current.top - (scroller.height - current.height) / 2) }
    }
}

internal fun MainActivity.tuneConfirm(
    title: String, positive: String, message: String = "", negative: String = "Отмена",
    destructive: Boolean = false, action: () -> Unit
) {
    val dialog = tuneDialog(ui)
    val body = ui.dialogBody(title)
    if (message.isNotBlank()) body.addView(ui.text(message, 14, ui.palette.muted).apply {
        gravity = Gravity.CENTER
        setPadding(ui.dp(20), 0, ui.dp(20), ui.dp(18))
    }, ui.full())
    body.addView(ui.dialogButtons(ui.dialogButton(negative) { dialog.dismiss() },
        ui.dialogButton(positive, accent = true, destructive = destructive) { dialog.dismiss(); action() }), ui.full())
    dialog.setContentView(body)
    dialog.showSized(ui)
}

private fun MainActivity.tuneDialog(ui: TuneUi) = Dialog(this,
    if (ui.palette.light) android.R.style.Theme_Material_Light_Dialog else android.R.style.Theme_Material_Dialog
).apply {
    setOwnerActivity(this@tuneDialog)
    requestWindowFeature(Window.FEATURE_NO_TITLE)
    window?.apply {
        setBackgroundDrawableResource(android.R.color.transparent)
        addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        setDimAmount(.44f)
        setGravity(Gravity.CENTER)
    }
}

private fun Dialog.showSized(ui: TuneUi) {
    show()
    val width = minOf(ui.dp(345), ui.context.resources.displayMetrics.widthPixels - ui.dp(36))
    window?.setLayout(width, -2)
}

private fun TuneUi.dialogBody(title: String) = column().apply {
    background = shape(palette.panel, 22)
    clipToOutline = true
    addView(text(title, 17, bold = true).apply {
        gravity = Gravity.CENTER
        setPadding(dp(18), dp(20), dp(18), dp(16))
    }, full())
}

private fun TuneUi.dialogButton(label: String, accent: Boolean = false, destructive: Boolean = false, click: () -> Unit) =
    button(label, click = click).apply {
        textSize = 16f
        setTextColor(when {
            destructive -> if (palette.light) 0xffba2828.toInt() else 0xffff6a65.toInt()
            accent -> palette.accentText
            else -> palette.text
        })
        background = touch(radius = 0)
    }

private fun TuneUi.dialogButtons(vararg buttons: View) = column().apply {
    addView(divider(), full(1))
    addView(row().apply {
        buttons.forEachIndexed { index, button ->
            if (index > 0) addView(View(context).apply { setBackgroundColor(palette.line) },
                LinearLayout.LayoutParams(dp(1), -1))
            addView(button, LinearLayout.LayoutParams(0, -2, 1f))
        }
    }, full())
}
