package music.tune.android

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout

@SuppressLint("ViewConstructor")
internal class TrackTitle(
    private val ui: TuneUi,
    size: Int,
    badgeSize: Int = 14,
    private val onDark: Boolean = false
) : FrameLayout(ui.context) {
    val label = ui.line("", size, bold = true)
    private val surface = if (onDark) 0xbfffffff.toInt() else ui.palette.muted
    private val ink = if (onDark) Color.BLACK else ui.palette.background
    private val explicit = ui.text("E", (badgeSize * .68f).toInt(), ink, bold = true).apply {
        typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
        gravity = Gravity.CENTER
        background = ui.shape(surface, 3)
        contentDescription = "Ненормативная лексика"
    }
    private val uncensored = IconView(context, Symbol.UNLOCK, ink).apply {
        iconSize = (badgeSize * .66f).toInt()
        background = ui.shape(surface, 3)
        contentDescription = "Без цензуры"
    }
    private val catalogTrack = ui.text("T", (badgeSize * .68f).toInt(), ink, bold = true).apply {
        typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
        gravity = Gravity.CENTER
        background = ui.shape(surface, 3)
        contentDescription = "Каталог Tune"
    }
    private val privateTrack = IconView(context, Symbol.EYE_OFF, if (onDark) 0x99ffffff.toInt() else ui.palette.muted).apply {
        iconSize = (badgeSize * .86f).toInt()
        contentDescription = "Этот трек можете слушать только вы"
    }
    private val badges = ui.row().apply {
        listOf(explicit, uncensored, catalogTrack, privateTrack).forEach { badge ->
            addView(badge, LinearLayout.LayoutParams(ui.dp(badgeSize), ui.dp(badgeSize)).apply { marginStart = ui.dp(4) })
        }
    }

    init {
        addView(label, LayoutParams(-2, -2))
        addView(badges, LayoutParams(-2, -2))
        bind(null)
    }

    fun bind(track: Track?) {
        val name = track?.title.orEmpty()
        if (label.text != name) label.text = name
        val store = (context.applicationContext as? TuneApp)?.store
        val uid = store?.profile?.value?.uid
        val usePlugin = track != null && (uid == null || store?.overrides?.read(uid, "track", track.id)?.usePlugin != false)
        val isCatalog = track != null && usePlugin && when {
            store != null && store.versions.hasCatalogLoaded() -> store.versions.isCatalogTrack(track.id)
            store != null -> store.catalogEnabled && track.catalog
            else -> track.catalog
        }
        val isUncensored = track != null && usePlugin && !isCatalog && when {
            store != null && store.versions.hasFckLoaded() -> store.versions.hasFckAudio(track.id)
            store != null -> store.alternateEnabled && track.alternate
            else -> track.alternate
        }
        explicit.visibility = visible(track?.explicit == true)
        uncensored.visibility = visible(isUncensored)
        catalogTrack.visibility = visible(isCatalog)
        privateTrack.visibility = visible(track?.uploaded == true)
        badges.visibility = visible(track?.let { it.explicit || isUncensored || isCatalog || it.uploaded } == true)
    }

    private fun visible(value: Boolean) = if (value) View.VISIBLE else View.GONE

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val mode = MeasureSpec.getMode(widthMeasureSpec)
        val unbounded = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        if (badges.visibility == View.VISIBLE) badges.measure(unbounded, unbounded)
        val badgeWidth = if (badges.visibility == View.VISIBLE) badges.measuredWidth else 0
        val titleSpec = if (mode == MeasureSpec.UNSPECIFIED) unbounded else
            MeasureSpec.makeMeasureSpec((width - badgeWidth).coerceAtLeast(0), MeasureSpec.AT_MOST)
        label.measure(titleSpec, heightMeasureSpec)
        val badgeHeight = if (badges.visibility == View.VISIBLE) badges.measuredHeight else 0
        setMeasuredDimension(resolveSize(label.measuredWidth + badgeWidth, widthMeasureSpec),
            resolveSize(maxOf(label.measuredHeight, badgeHeight), heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val rtl = layoutDirection == View.LAYOUT_DIRECTION_RTL
        val titleLeft = if (rtl) width - label.measuredWidth else 0
        val titleTop = (height - label.measuredHeight) / 2
        label.layout(titleLeft, titleTop, titleLeft + label.measuredWidth, titleTop + label.measuredHeight)
        if (badges.visibility == View.VISIBLE) {
            val badgeLeft = if (rtl) titleLeft - badges.measuredWidth else label.right
            val badgeTop = (height - badges.measuredHeight) / 2
            badges.layout(badgeLeft, badgeTop, badgeLeft + badges.measuredWidth, badgeTop + badges.measuredHeight)
        }
    }
}
