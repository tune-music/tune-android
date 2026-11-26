package music.tune.android

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Dialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.ShapeDrawable
import android.media.MediaRouter2
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.PathInterpolator
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.media3.common.Player
import androidx.media3.session.SessionCommand
import androidx.core.animation.doOnEnd
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.Insets
import kotlinx.coroutines.Job
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil

internal class PlayerPanel(private val activity: MainActivity) {
    private val ui = TuneUi(activity)
    private val store = activity.store
    private val player get() = activity.controller
    private val source = ui.line("", 14, bold = true).apply { gravity = Gravity.CENTER }
    private val sourceKind = ui.line("", 11, Palette.muted, bold = true).apply {
        gravity = Gravity.CENTER
        letterSpacing = .09f
    }
    private val title = TrackTitle(ui, 22, onDark = true)
    private val artist = ui.line("", 17, Palette.muted)
    private var topTone = Palette.lifted
    private var toneAnimator: ValueAnimator? = null
    private val backdrop = SmoothGradientDrawable(Palette.lifted, 0xff0d0d0d.toInt())
    private val art = CoverTrackPager(ui) { tone -> animateTone(tone) }
    private val lyricBody = ui.column()
    private val lyricScroll = LyricsScroll().apply {
        isVerticalScrollBarEnabled = false
        isVerticalFadingEdgeEnabled = true
        isNestedScrollingEnabled = true
        overScrollMode = View.OVER_SCROLL_NEVER
        addView(lyricBody, ViewGroup.LayoutParams(-1, -2))
    }
    private val lyricStatus = FrameLayout(activity)
    private val lyricPane = FrameLayout(activity).apply {
        clipChildren = true
        clipToPadding = true
        visibility = View.GONE
        alpha = 0f
        addView(lyricScroll, FrameLayout.LayoutParams(-1, -1))
        addView(lyricStatus, FrameLayout.LayoutParams(-1, -1))
    }
    private val elapsed = ui.text("0:00", 12, Palette.muted).apply { fontFeatureSettings = "tnum" }
    private var showRemaining = store.preferences.getBoolean("playerRemainingTime", false)
    private val duration = ui.text("0:00", 12, Palette.muted).apply {
        fontFeatureSettings = "tnum"
        setPadding(ui.dp(6), ui.dp(2), 0, ui.dp(2))
        setOnClickListener {
            showRemaining = !showRemaining
            store.preferences.edit().putBoolean("playerRemainingTime", showRemaining).apply()
            progress()
        }
    }
    private val seekLayers = LayerDrawable(arrayOf(
        ui.shape(0x38ffffff, 10),
        ClipDrawable(ui.shape(0x26ffffff, 10), Gravity.START, ClipDrawable.HORIZONTAL),
        ClipDrawable(ui.shape(Palette.text, 10), Gravity.START, ClipDrawable.HORIZONTAL),
    )).apply {
        setId(0, android.R.id.background)
        setId(1, android.R.id.secondaryProgress)
        setId(2, android.R.id.progress)
        val thin = ui.dp(5)
        for (index in 0..2) {
            setLayerHeight(index, thin)
            setLayerGravity(index, Gravity.CENTER_VERTICAL)
        }
    }
    private var seekThicknessAnimator: ValueAnimator? = null
    private val seek = SeekBar(activity).apply {
        max = 1000
        progressDrawable = seekLayers
        thumb = ShapeDrawable().apply {
            intrinsicWidth = 0
            intrinsicHeight = 0
            paint.color = Color.TRANSPARENT
        }
        thumbOffset = 0
        contentDescription = "Позиция трека"
        setPadding(0, 0, 0, 0)
        splitTrack = false
    }
    private val bufferRing = BufferRingView(activity)
    private val toggle = ui.icon(Symbol.PLAY, "Воспроизвести", Palette.background, activity::toggle).apply {
        background = ui.touch(Palette.text, 40)
        iconSize = 30
    }
    private val shuffle = ui.icon(Symbol.SHUFFLE, "Перемешать") { player?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled } }
    private val repeat = ui.icon(Symbol.REPEAT, "Повтор") {
        player?.let { it.repeatMode = (it.repeatMode + 1) % 3 }
    }
    private val like = ui.icon(Symbol.HEART, "Мне нравится") { activity.currentTrack()?.let { activity.rate(it) } }
    private val dislike = ui.icon(Symbol.THUMB_DOWN, "Не нравится") { activity.currentTrack()?.let { activity.rate(it, true) } }
    private val lyricButton = ui.icon(Symbol.LYRICS, "Текст песни", click = ::toggleLyrics)
    private val download = ui.icon(Symbol.DOWNLOAD_CIRCLE, "Скачать трек", click = ::toggleDownload).apply { iconSize = 21 }
    private val downloadRing = ProgressRingView(activity, 0x38ffffff, Palette.accent).apply { visibility = View.GONE }
    private val downloadSlot = FrameLayout(activity).apply {
        addView(download, FrameLayout.LayoutParams(-1, -1))
        addView(downloadRing, FrameLayout.LayoutParams(ui.dp(22), ui.dp(22), Gravity.CENTER))
    }
    private val statusRow = ui.row().apply {
        gravity = Gravity.CENTER
    }
    private var lastStatusSignature: String? = null
    private val soundButton = ui.icon(Symbol.SETTINGS, "Настройки звука", click = ::showSound)
    private val sleepButton = ui.icon(Symbol.MOON, "Таймер сна", click = ::sleepTimer)
    private var dragging = false
    private var dialog: Dialog? = null
    private var lyricJob: Job? = null
    private var showingLyrics = false
    private var lyricMotion: AnimatorSet? = null
    private var scrollAnimator: ValueAnimator? = null
    private var scrollTargetY = -1
    private var lyrics: List<LyricsLine> = emptyList()
    private var lyricRows: List<TextView> = emptyList()
    private var timedLyrics = false
    private var activeLine = -1
    private var lastAnchor = -1
    private var recenterLyrics = false
    private var queueAdapter: BaseAdapter? = null
    private var currentId: String? = null

    private val toolbar = ui.row().apply {
        addView(ui.icon(Symbol.DOWN, "Свернуть плеер") { activity.closePlayer(animate = true) }.apply {
            background = ui.touch(0x32ffffff, 30)
            iconSize = 18
        }, LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)))
        addView(ui.column().apply {
            gravity = Gravity.CENTER
            addView(sourceKind)
            addView(ui.space(3))
            addView(source)
        }, ui.weight())
        addView(ui.icon(Symbol.MORE, "Ещё") { showTrackActions() }.apply {
            background = ui.touch(0x32ffffff, 30)
            iconSize = 18
        }, LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)))
    }

    private val layout = ScrollView(activity).apply {
        isVerticalScrollBarEnabled = false
        isFillViewport = true
        val body = ui.column().apply {
            gravity = Gravity.CENTER_HORIZONTAL
            clipChildren = false
            clipToPadding = false
            setPadding(0, 0, 0, ui.dp(12))
        }
        val config = activity.resources.configuration
        val size = minOf(config.screenWidthDp - 48, (config.screenHeightDp * .44f).toInt(), 540).coerceAtLeast(140)
        lyricPane.setPadding(ui.dp(24), ui.dp(12), ui.dp(24), ui.dp(10))
        body.addView(FrameLayout(activity).apply {
            clipChildren = false
            clipToPadding = false
            minimumHeight = ui.dp(size + 32)
            art.setCoverSize(ui.dp(size))
            addView(art, FrameLayout.LayoutParams(-1, -1))
            addView(lyricPane, FrameLayout.LayoutParams(-1, -1))
        }, LinearLayout.LayoutParams(-1, ui.dp(size + 32), 1f))
        val controls = ui.column().apply {
            clipChildren = false
            setPadding(ui.dp(24), 0, ui.dp(24), 0)
        }
        controls.addView(ui.row().apply {
            addView(ui.column().apply {
                addView(title.apply { label.setOnClickListener { activity.currentTrack()?.let(activity::openTrackAlbum) } }, ui.full())
                addView(ui.space(6))
                addView(artist.apply { setOnClickListener { activity.currentTrack()?.let(activity::openTrackArtist) } })
            }, ui.weight())
            addView(dislike)
            addView(like)
        }, ui.full())
        controls.addView(ui.space(14))
        controls.addView(seek, ui.full(28))
        controls.addView(FrameLayout(activity).apply {
            addView(elapsed, FrameLayout.LayoutParams(-2, -2, Gravity.START or Gravity.CENTER_VERTICAL))
            addView(statusRow, FrameLayout.LayoutParams(-2, ui.dp(20), Gravity.CENTER))
            addView(duration, FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.CENTER_VERTICAL))
        }, ui.full(22))
        controls.addView(ui.space(7))
        controls.addView(ui.row().apply {
            clipChildren = false
            addView(shuffle)
            addView(View(activity), LinearLayout.LayoutParams(0, 1, 1f))
            addView(skip(Symbol.PREVIOUS, "Предыдущий", -5_000, activity::previous))
            addView(View(activity), LinearLayout.LayoutParams(0, 1, 1f))
            addView(FrameLayout(activity).apply {
                clipChildren = false
                addView(bufferRing, FrameLayout.LayoutParams(ui.dp(90), ui.dp(90), Gravity.CENTER))
                addView(toggle, FrameLayout.LayoutParams(ui.dp(76), ui.dp(76), Gravity.CENTER))
            }, LinearLayout.LayoutParams(ui.dp(90), ui.dp(90)))
            addView(View(activity), LinearLayout.LayoutParams(0, 1, 1f))
            addView(skip(Symbol.NEXT, "Следующий", 5_000, activity::next))
            addView(View(activity), LinearLayout.LayoutParams(0, 1, 1f))
            addView(repeat)
        }, ui.full(90))
        controls.addView(ui.space(11))
        controls.addView(ui.row().apply {
            listOf<View>(lyricButton, ui.icon(Symbol.OUTPUT, "Устройство вывода", click = ::output),
                soundButton, sleepButton, downloadSlot, ui.icon(Symbol.QUEUE, "Очередь", click = ::showQueue)).forEach { item ->
                (item as? IconView)?.iconSize = 21
                addView(item, LinearLayout.LayoutParams(0, ui.dp(44), 1f))
            }
        }, ui.full(44))
        body.addView(controls, ui.full())
        addView(body, ViewGroup.LayoutParams(-1, -2))
    }

    private val container = ui.column().apply {
        clipChildren = false
        clipToPadding = false
        toolbar.setPadding(ui.dp(24), 0, ui.dp(24), 0)
        setPadding(0, ui.dp(8), 0, 0)
        addView(toolbar, ui.full(44))
        addView(layout, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    val view: FrameLayout = PlayerSurface().apply {
        background = backdrop
        isClickable = true
        addView(container, FrameLayout.LayoutParams(-1, -1))
    }

    fun applyInsets(bars: Insets) {
        container.setPadding(bars.left, ui.dp(8) + bars.top, bars.right, bars.bottom)
    }

    private fun animateTone(target: Int) {
        if (topTone == target) return
        toneAnimator?.cancel()
        if (!ValueAnimator.areAnimatorsEnabled() || !view.isAttachedToWindow) {
            topTone = target
            backdrop.setColors(target, 0xff0d0d0d.toInt())
            return
        }
        val start = topTone
        toneAnimator = ValueAnimator.ofArgb(start, target).apply {
            duration = 400
            addUpdateListener {
                topTone = it.animatedValue as Int
                backdrop.setColors(topTone, 0xff0d0d0d.toInt())
            }
            start()
        }
    }

    private fun animateSeekThickness(thick: Boolean) {
        val target = ui.dp(if (thick) 10 else 5)
        val current = seekLayers.getLayerHeight(0)
        if (current == target) return
        seekThicknessAnimator?.cancel()
        if (!ValueAnimator.areAnimatorsEnabled()) {
            for (index in 0..2) seekLayers.setLayerHeight(index, target)
            seek.invalidate()
            return
        }
        seekThicknessAnimator = ValueAnimator.ofInt(current, target).apply {
            duration = 150
            addUpdateListener { anim ->
                val h = anim.animatedValue as Int
                for (index in 0..2) seekLayers.setLayerHeight(index, h)
                seekLayers.invalidateSelf()
                seek.invalidate()
            }
            start()
        }
    }

    init {
        lyricScroll.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop) {
                lyricScroll.setFadingEdgeLength((lyricScroll.height * .16f).toInt())
                if (lyricRows.isNotEmpty()) {
                    padLyrics()
                    lastAnchor = -1
                    lyricScroll.post(::progress)
                }
            }
        }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(bar: SeekBar) {
                dragging = true
                animateSeekThickness(true)
            }
            override fun onStopTrackingTouch(bar: SeekBar) {
                val length = player?.duration ?: 0
                if (length > 0) player?.seekTo(length * bar.progress / 1000)
                dragging = false
                animateSeekThickness(false)
            }
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val length = player?.duration ?: 0
                if (length > 0) {
                    val position = length * progress / 1000
                    elapsed.text = timeLabel(position)
                    if (showRemaining) {
                        duration.text = "-${timeLabel((length - position).coerceAtLeast(0))}"
                    }
                    if (!dragging) player?.seekTo(position)
                }
            }
        })
    }

    fun update() {
        val track = activity.currentTrack() ?: return
        val player = player ?: return
        if (currentId != track.id) {
            currentId = track.id
            if (showingLyrics) loadLyrics(track)
        }
        val context = QueueItems.context(player.currentMediaItem)
        sourceKind.text = when (context.kind) {
            "radio" -> "ИГРАЕТ ВОЛНА"
            "album" -> if (track.isPodcastEpisode) "ИГРАЕТ ПОДКАСТ" else "ИГРАЕТ АЛЬБОМ"
            "playlist" -> "ИГРАЕТ ПЛЕЙЛИСТ"
            "artist" -> "ИГРАЕТ ИСПОЛНИТЕЛЬ"
            else -> "ИГРАЕТ"
        }
        source.text = context.name
        source.visibility = if (context.name.isBlank()) View.GONE else View.VISIBLE
        title.bind(track)
        artist.text = track.artistText
        fun neighbor(index: Int) = if (index in 0 until player.mediaItemCount && index != player.currentMediaItemIndex)
            QueueItems.track(player.getMediaItemAt(index)) else null
        val playing = player.isPlaying || player.playWhenReady && player.playbackState == Player.STATE_BUFFERING
        bufferRing.buffering = player.playWhenReady && player.playbackState == Player.STATE_BUFFERING
        art.bind(neighbor(player.previousMediaItemIndex), track, neighbor(player.nextMediaItemIndex), playing)
        activity.setIcon(toggle, if (playing) Symbol.PAUSE else Symbol.PLAY, if (playing) "Пауза" else "Воспроизвести", Palette.background)
        val liked = track.id in store.likes.value
        activity.setIcon(like, if (liked) Symbol.HEART_FILLED else Symbol.HEART,
            if (liked) "Убрать из «Мне нравится»" else "Мне нравится", if (liked) Palette.accent else Palette.text)
        val disliked = track.id in store.dislikes.value
        activity.setIcon(dislike, if (disliked) Symbol.THUMB_DOWN_FILLED else Symbol.THUMB_DOWN,
            if (disliked) "Снять дизлайк" else "Не нравится", if (disliked) Palette.accent else Palette.text)
        shuffle.isEnabled = context.wave == null
        shuffle.alpha = if (shuffle.isEnabled) 1f else .35f
        activity.setIcon(shuffle, Symbol.SHUFFLE, if (player.shuffleModeEnabled) "Выключить перемешивание" else "Перемешать",
            if (player.shuffleModeEnabled) Palette.accent else Palette.muted)
        val mode = player.repeatMode
        activity.setIcon(repeat, if (mode == Player.REPEAT_MODE_ONE) Symbol.REPEAT_ONE else Symbol.REPEAT,
            when (mode) { Player.REPEAT_MODE_ONE -> "Повтор трека"; Player.REPEAT_MODE_ALL -> "Повтор очереди"; else -> "Повтор выключен" },
            if (mode == Player.REPEAT_MODE_OFF) Palette.muted else Palette.accent)
        updateDownload()
        updateSoundButton()
        updateSleepButton()
        updateStatusIcons()
        queueAdapter?.notifyDataSetChanged()
        progress()
    }

    fun updateDownload() {
        val track = activity.currentTrack() ?: return
        val saved = store.files.has(track)
        val loading = store.files.status.value[track.id]
        val percent = loading?.percent
        if (!saved && percent != null) {
            download.visibility = View.INVISIBLE
            download.isEnabled = false
            downloadRing.visibility = View.VISIBLE
            downloadRing.progress = percent / 100f
            downloadSlot.contentDescription = "Скачивается: $percent%"
            downloadSlot.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        } else {
            downloadRing.visibility = View.GONE
            download.visibility = View.VISIBLE
            download.isEnabled = true
            downloadSlot.contentDescription = null
            downloadSlot.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
            activity.setIcon(download, if (saved) Symbol.CHECK_CIRCLE else Symbol.DOWNLOAD_CIRCLE,
                if (saved) "Удалить загрузку" else "Скачать трек",
                if (saved) Palette.accent else Palette.text)
        }
    }

    fun updateSleepButton() {
        val active = store.sleepTimer.value.active
        activity.setIcon(sleepButton, Symbol.MOON,
            if (active) "Таймер сна включён" else "Таймер сна",
            if (active) Palette.accent else 0xd9ffffff.toInt())
        sleepButton.background = if (active) ui.touch(0x24ffffff, 22) else ui.touch()
        updateStatusIcons()
    }

    private fun updateStatusIcons() {
        val track = activity.currentTrack() ?: return
        val uid = store.profile.value?.uid
        val edition = uid?.let { store.files.selectedVersion(it, track.id) } ?: "auto"
        val hasUncensor = uid != null && (
            (store.allowsAlternate(uid, track.id) && store.versions.hasAudio(track.id)) ||
                track.alternate ||
                store.files.hasReplacement(track)
            )
        val uncensored = when (edition) {
            "original" -> false
            "alternate", "local" -> hasUncensor
            else -> hasUncensor
        }
        val canSwitch = track.originalAvailable != false && !track.unavailable
        val hasLocal = store.files.hasReplacement(track)
        val soundActive = soundIsAdjusted(store.preferences)
        val speed = store.preferences.getFloat("speed", 1f).coerceIn(.5f, 2f)
        val speedChanged = abs(speed - 1f) > 0.001f
        val sleep = store.sleepTimer.value
        val sleepMinutes = sleep.stopAtMs?.let {
            ceil((it - System.currentTimeMillis()) / 60_000.0).toInt().coerceAtLeast(1)
        }
        val sig = "$hasUncensor:$uncensored:$canSwitch:$hasLocal:$soundActive:$speed:$sleepMinutes:${sleep.afterTrack}"
        if (sig == lastStatusSignature) return
        lastStatusSignature = sig

        statusRow.removeAllViews()
        if (!hasUncensor && !soundActive && !sleep.active) return

        val accentBg = ColorUtils.setAlphaComponent(Palette.accent, 46)
        val dimBg = 0x1fffffff
        val dimTint = 0xa6ffffff.toInt()
        fun addPill(symbol: Symbol, text: String?, active: Boolean, enabled: Boolean = true, label: String, click: () -> Unit) {
            val tint = if (active) Palette.accent else dimTint
            val pill = ui.row().apply {
                gravity = Gravity.CENTER
                setPadding(ui.dp(7), 0, ui.dp(7), 0)
                background = if (enabled) ui.touch(if (active) accentBg else dimBg, 10)
                else ui.shape(if (active) accentBg else dimBg, 10)
                contentDescription = label
                isEnabled = enabled
                isFocusable = enabled
                if (enabled) pressFeedback(.92f)
                addView(IconView(activity, symbol, tint).apply { iconSize = 11 },
                    LinearLayout.LayoutParams(ui.dp(12), ui.dp(12)))
                if (!text.isNullOrEmpty()) {
                    addView(ui.line(text, 10, tint, bold = true).apply {
                        fontFeatureSettings = "tnum"
                    }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = ui.dp(3) })
                }
                if (enabled) setOnClickListener { click() }
            }
            statusRow.addView(pill, LinearLayout.LayoutParams(-2, ui.dp(20)).apply {
                if (statusRow.childCount > 0) marginStart = ui.dp(6)
            })
        }

        if (hasUncensor) {
            addPill(
                if (uncensored) Symbol.UNLOCK else Symbol.LOCK,
                null,
                active = uncensored,
                enabled = canSwitch || hasLocal,
                label = if (uncensored) "Версия без цензуры" else "Версия Яндекса",
            ) {
                if (hasLocal) {
                    activity.chooseVersion(track)
                } else {
                    store.files.selectVersion(track, if (uncensored) "original" else "alternate")
                    activity.reloadVersion(track.id)
                    updateStatusIcons()
                }
            }
        }
        if (soundActive) {
            val speedLabel = if (speedChanged) {
                NumberFormat.getNumberInstance(Locale.forLanguageTag("ru")).apply {
                    maximumFractionDigits = 2
                }.format(speed) + "×"
            } else null
            addPill(Symbol.SETTINGS, speedLabel, active = true, label = "Настройки звука", click = ::showSound)
        }
        if (sleep.active) {
            val sleepLabel = sleepMinutes?.let { "${it}м" } ?: if (sleep.afterTrack) "1" else null
            addPill(Symbol.MOON, sleepLabel, active = true, label = "Таймер сна", click = ::sleepTimer)
        }
    }

    fun progress() {
        val player = player ?: return
        val length = player.duration.takeIf { it > 0 } ?: activity.currentTrack()?.duration ?: 0
        val currentPos = player.currentPosition.coerceIn(0, length.coerceAtLeast(0))
        val total = if (showRemaining && length > 0) "-${timeLabel((length - currentPos).coerceAtLeast(0))}" else timeLabel(length)
        if (!dragging && duration.text != total) duration.text = total
        seek.isEnabled = length > 0 && player.isCurrentMediaItemSeekable
        if (!dragging) {
            val position = timeLabel(player.currentPosition)
            if (elapsed.text != position) elapsed.text = position
            seek.progress = if (length > 0) (player.currentPosition * 1000 / length).toInt().coerceIn(0, 1000) else 0
            seek.secondaryProgress = if (length > 0) (player.bufferedPosition * 1000 / length).toInt().coerceAtLeast(0).coerceAtMost(1000) else 0
        }
        if (store.sleepTimer.value.stopAtMs != null) updateStatusIcons()
        if (!showingLyrics || !timedLyrics) return
        val now = player.currentPosition + 250
        val index = lyrics.indexOfLast { it.position?.let { position -> position <= now } == true }
        if (index != activeLine) {
            lyricRows.getOrNull(activeLine)?.let { highlight(it, false) }
            activeLine = index
            lyricRows.getOrNull(index)?.let { highlight(it, true) }
        }
        val anchor = index.coerceAtLeast(0)
        if ((anchor != lastAnchor || recenterLyrics) && !lyricScroll.isManuallyScrolling && lyricScroll.height > 0) {
            val row = lyricRows[anchor]
            if (row.height == 0) return
            val maxScroll = (lyricBody.height - lyricScroll.height).coerceAtLeast(0)
            val y = (row.top - (lyricScroll.height - row.height) / 2).coerceIn(0, maxScroll)
            if (lastAnchor < 0 || !ValueAnimator.areAnimatorsEnabled()) {
                scrollAnimator?.cancel()
                scrollTargetY = y
                lyricScroll.scrollTo(0, y)
            } else {
                animateLyricsScroll(y)
            }
            lastAnchor = anchor
            recenterLyrics = false
        }
    }

    private fun animateLyricsScroll(targetY: Int) {
        if (lyricScroll.scrollY == targetY) return
        if (scrollAnimator?.isRunning == true && scrollTargetY == targetY) return
        scrollAnimator?.cancel()
        scrollTargetY = targetY
        scrollAnimator = ValueAnimator.ofInt(lyricScroll.scrollY, targetY).apply {
            duration = 360
            interpolator = PathInterpolator(0.25f, 0.1f, 0.25f, 1f)
            addUpdateListener { lyricScroll.scrollTo(0, it.animatedValue as Int) }
            start()
        }
    }

    private fun highlight(row: View, active: Boolean) {
        val alpha = if (active) 1f else .32f
        val scale = if (active) 1f else .96f
        if (row.width > 0) {
            row.pivotX = row.width / 2f
            row.pivotY = row.height / 2f
        }
        row.animate().cancel()
        if (ValueAnimator.areAnimatorsEnabled()) {
            row.animate()
                .alpha(alpha)
                .scaleX(scale)
                .scaleY(scale)
                .setDuration(260)
                .setInterpolator(DecelerateInterpolator(1.6f))
                .start()
        } else {
            row.alpha = alpha
            row.scaleX = scale
            row.scaleY = scale
        }
    }

    private fun toggleLyrics() {
        val track = activity.currentTrack() ?: return
        showingLyrics = !showingLyrics
        view.keepScreenOn = showingLyrics
        lyricMotion?.let { motion -> lyricMotion = null; motion.cancel() }
        if (ValueAnimator.areAnimatorsEnabled()) {
            val wasArtHidden = art.visibility != View.VISIBLE
            val wasLyricsHidden = lyricPane.visibility != View.VISIBLE
            art.visibility = View.VISIBLE
            lyricPane.visibility = View.VISIBLE
            if (showingLyrics && wasLyricsHidden) lyricPane.alpha = 0f
            if (!showingLyrics && wasArtHidden) {
                art.alpha = 0f
                art.scaleX = .92f
                art.scaleY = .92f
            }
            lyricMotion = AnimatorSet().apply {
                playTogether(
                    ObjectAnimator.ofFloat(art, View.ALPHA, if (showingLyrics) 0f else 1f),
                    ObjectAnimator.ofFloat(art, View.SCALE_X, if (showingLyrics) .92f else 1f),
                    ObjectAnimator.ofFloat(art, View.SCALE_Y, if (showingLyrics) .92f else 1f),
                    ObjectAnimator.ofFloat(lyricPane, View.ALPHA, if (showingLyrics) 1f else 0f),
                )
                duration = 250
                interpolator = DecelerateInterpolator(1.3f)
                doOnEnd { if (lyricMotion === it) finishLyricsTransition() }
            }.also { it.start() }
        } else finishLyricsTransition()
        activity.setIcon(lyricButton, Symbol.LYRICS, if (showingLyrics) "Показать обложку" else "Текст песни",
            if (showingLyrics) Palette.accent else Palette.text)
        lyricButton.background = if (showingLyrics) ui.touch(0x24ffffff, 22) else ui.touch()
        if (showingLyrics) loadLyrics(track)
        else lyricJob?.cancel()
    }

    private fun finishLyricsTransition() {
        lyricPane.visibility = if (showingLyrics) View.VISIBLE else View.GONE
        lyricPane.alpha = if (showingLyrics) 1f else 0f
        art.visibility = if (showingLyrics) View.INVISIBLE else View.VISIBLE
        art.alpha = if (showingLyrics) 0f else 1f
        art.scaleX = 1f
        art.scaleY = 1f
        if (!showingLyrics) clearLyrics()
    }

    private fun clearLyrics() {
        lyricJob?.cancel()
        scrollAnimator?.cancel()
        scrollAnimator = null
        scrollTargetY = -1
        lyrics = emptyList()
        lyricRows = emptyList()
        timedLyrics = false
        activeLine = -1
        lastAnchor = -1
        recenterLyrics = false
        lyricBody.animate().cancel()
        lyricBody.alpha = 1f
        lyricBody.removeAllViews()
        lyricStatus.removeAllViews()
        lyricBody.setPadding(0, 0, 0, 0)
        lyricScroll.scrollTo(0, 0)
        lyricScroll.resetManualScroll()
    }

    private fun loadLyrics(track: Track) {
        clearLyrics()
        lyricStatus.addView(ProgressBar(activity).apply {
            contentDescription = "Загружается текст песни"
            indeterminateTintList = ColorStateList.valueOf(Color.WHITE)
        }, FrameLayout.LayoutParams(ui.dp(27), ui.dp(27), Gravity.CENTER))
        lyricJob = activity.action(onError = { emptyLyrics(track, "Текста нет") }) {
            val text = store.lyrics(track)
            val lines = text.lines
            if (lines.isEmpty()) {
                emptyLyrics(track, "Текста нет")
                return@action
            }
            lyricStatus.removeAllViews()
            timedLyrics = lines.any { it.position != null }
            lyrics = lines
            lyricRows = lines.map { line ->
                ui.text(line.text.ifEmpty { "♪" }, 24, Color.WHITE, bold = true).apply {
                    gravity = Gravity.CENTER
                    alpha = if (timedLyrics) .32f else 1f
                    scaleX = if (timedLyrics) .96f else 1f
                    scaleY = if (timedLyrics) .96f else 1f
                    typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
                    setPadding(ui.dp(8), ui.dp(2), ui.dp(8), ui.dp(2))
                    line.position?.let { time ->
                        background = null
                        setOnClickListener {
                            lyricScroll.resetManualScroll()
                            player?.seekTo(time)
                        }
                    }
                    lyricBody.addView(this, ui.full().apply { bottomMargin = ui.dp(18) })
                }
            }
            fun addCredit(label: String, size: Int, color: Int, margin: Int) {
                lyricBody.addView(ui.text(label, size, color).apply {
                    gravity = Gravity.CENTER
                    setPadding(ui.dp(24), 0, ui.dp(24), 0)
                }, ui.full().apply { topMargin = ui.dp(margin) })
            }
            val hasAuthors = text.authors.isNotEmpty()
            if (hasAuthors) addCredit("Авторы: ${text.authors.joinToString(", ")}", 14, 0x8cffffff.toInt(), 14)
            if (text.source != LyricsSource.YANDEX) {
                text.source?.label?.let { label ->
                    addCredit("Текст: $label", 13, 0x66ffffff, if (hasAuthors) 6 else 14)
                }
            }
            padLyrics()
            if (ValueAnimator.areAnimatorsEnabled()) {
                lyricBody.animate().cancel()
                lyricBody.alpha = 0f
                lyricBody.animate().alpha(1f).setDuration(220).start()
            }
            lyricScroll.post { progress() }
        }
    }

    private fun padLyrics() {
        val padding = if (timedLyrics) (lyricScroll.height / 2 - ui.dp(16)).coerceAtLeast(0) else ui.dp(16)
        lyricScroll.setFadingEdgeLength((lyricScroll.height * .16f).toInt())
        lyricBody.setPadding(0, padding, 0, padding)
    }

    private fun emptyLyrics(track: Track, message: String) {
        lyricStatus.removeAllViews()
        lyricStatus.addView(ui.column().apply {
            gravity = Gravity.CENTER
            addView(IconView(activity, Symbol.LYRICS, 0x99ffffff.toInt()).apply { iconSize = 34 },
                LinearLayout.LayoutParams(ui.dp(36), ui.dp(36)))
            addView(ui.space(10))
            addView(ui.text(message, 18, 0x99ffffff.toInt(), bold = true).apply { gravity = Gravity.CENTER })
            addView(ui.space(14))
            addView(ui.button("Повторить") { loadLyrics(track) }.apply {
                background = ui.touch(0x24ffffff, 20)
                minHeight = ui.dp(36)
                textSize = 15f
            }, LinearLayout.LayoutParams(-2, -2))
        }, FrameLayout.LayoutParams(-1, -1))
    }

    private inner class LyricsScroll : ScrollView(activity) {
        private var startY = 0f
        private var moving = false
        private var lastTouch = -3_000L
        val isManuallyScrolling get() = moving || android.os.SystemClock.uptimeMillis() - lastTouch < 3_000

        fun resetManualScroll() { moving = false; lastTouch = -3_000 }

        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> startY = event.y
                MotionEvent.ACTION_MOVE -> if (kotlin.math.abs(event.y - startY) > ViewConfiguration.get(activity).scaledTouchSlop) {
                    moving = true
                    scrollAnimator?.cancel()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (moving) {
                        lastTouch = android.os.SystemClock.uptimeMillis()
                        recenterLyrics = true
                    }
                    moving = false
                }
            }
            return super.dispatchTouchEvent(event)
        }
    }
    private fun toggleDownload() {
        val track = activity.currentTrack() ?: return
        activity.action {
            if (store.files.has(track)) {
                store.files.remove(track)
                activity.toast("Загрузка удалена")
            } else {
                store.files.enqueue(listOf(track))
                activity.toast("Трек добавлен в загрузки")
            }
            update()
        }
    }

    private fun output() {
        if (Build.VERSION.SDK_INT >= 34 && MediaRouter2.getInstance(activity).showSystemOutputSwitcher()) return
        try { activity.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
        catch (_: android.content.ActivityNotFoundException) { activity.toast("Настройки Bluetooth недоступны") }
    }

    private fun showTrackActions() {
        val track = activity.currentTrack() ?: return
        activity.trackMenu(track)
    }

    private fun showSound() {
        dialog?.dismiss()
        dialog = SoundSheet(activity).show {
            dialog = null
            updateSoundButton()
        }
    }

    private fun updateSoundButton() {
        val active = soundIsAdjusted(store.preferences)
        activity.setIcon(soundButton, Symbol.SETTINGS, "Настройки звука",
            if (active) Palette.accent else 0xd9ffffff.toInt())
        soundButton.background = if (active) ui.touch(0x24ffffff, 22) else ui.touch()
        updateStatusIcons()
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun skip(symbol: Symbol, label: String, step: Long, click: () -> Unit) = ui.icon(symbol, label, click = click).apply {
        val seekMore = object : Runnable {
            override fun run() {
                val player = player ?: return
                if (player.duration > 0 && player.isCurrentMediaItemSeekable) {
                    player.seekTo((player.currentPosition + step).coerceIn(0L, player.duration))
                }
                if (isPressed) postDelayed(this, 230)
            }
        }
        setOnLongClickListener {
            seekMore.run()
            true
        }
        setOnTouchListener { _, event ->
            if (event.actionMasked in setOf(MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL)) removeCallbacks(seekMore)
            false
        }
        addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = Unit
            override fun onViewDetachedFromWindow(view: View) { removeCallbacks(seekMore) }
        })
    }

    private enum class PlayerDrag { VERTICAL, HORIZONTAL, CHILD }

    private inner class PlayerSurface : FrameLayout(activity) {
        private var downX = 0f
        private var downY = 0f
        private var dragOriginX = 0f
        private var dragOriginY = 0f
        private var drag: PlayerDrag? = null
        private var startsInLyrics = false
        private var velocity: VelocityTracker? = null
        private val location = IntArray(2)
        private val slop = ViewConfiguration.get(activity).scaledTouchSlop

        private fun startedIn(child: View): Boolean {
            child.getLocationOnScreen(location)
            return downX >= location[0] && downX < location[0] + child.width &&
                downY >= location[1] && downY < location[1] + child.height
        }

        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            val action = event.actionMasked
            if (action == MotionEvent.ACTION_DOWN) {
                velocity?.recycle()
                velocity = VelocityTracker.obtain()
                downX = event.rawX
                downY = event.rawY
                dragOriginX = 0f
                dragOriginY = 0f
                startsInLyrics = showingLyrics && startedIn(lyricScroll)
                drag = if (startedIn(seek)) PlayerDrag.CHILD else null
            }
            val worldX = event.rawX - event.x
            val worldY = event.rawY - event.y
            event.offsetLocation(worldX, worldY)
            velocity?.addMovement(event)
            event.offsetLocation(-worldX, -worldY)
            val dx = event.rawX - downX
            val dy = event.rawY - downY
            if (drag == null && action == MotionEvent.ACTION_POINTER_DOWN) drag = PlayerDrag.CHILD
            if (drag == null && action == MotionEvent.ACTION_MOVE && maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy)) > slop) {
                drag = when {
                    kotlin.math.abs(dx) > kotlin.math.abs(dy) && !showingLyrics -> PlayerDrag.HORIZONTAL
                    kotlin.math.abs(dx) > kotlin.math.abs(dy) || dy < 0f ||
                        (!startedIn(toolbar) && layout.canScrollVertically(-1)) ||
                        startsInLyrics && lyricScroll.canScrollVertically(-1) -> PlayerDrag.CHILD
                    else -> PlayerDrag.VERTICAL
                }
                if (drag != PlayerDrag.CHILD) {
                    val cancel = MotionEvent.obtain(event).apply { this.action = MotionEvent.ACTION_CANCEL }
                    super.dispatchTouchEvent(cancel)
                    cancel.recycle()
                    animate().cancel()
                    if (drag == PlayerDrag.HORIZONTAL) {
                        dragOriginX = dx
                        art.startDrag()
                    } else {
                        dragOriginY = dy
                    }
                }
            }
            val handled = when (drag) {
                PlayerDrag.VERTICAL, PlayerDrag.HORIZONTAL -> {
                    if (action == MotionEvent.ACTION_MOVE) {
                        if (drag == PlayerDrag.VERTICAL) {
                            translationY = (dy - dragOriginY).coerceAtLeast(0f)
                            setSurfaceCornerRadius(if (translationY > 0f) ui.dp(36).toFloat() else 0f)
                        } else art.drag(dx - dragOriginX)
                    }
                    if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) settle(action, dx, dy)
                    true
                }
                else -> super.dispatchTouchEvent(event)
            }
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                velocity?.recycle()
                velocity = null
                drag = null
            }
            return handled
        }

        private fun setSurfaceCornerRadius(radius: Float) {
            if (backdrop.cornerRadius != radius) {
                backdrop.cornerRadius = radius
                clipToOutline = radius > 0f
                invalidateOutline()
            }
        }

        private fun settle(action: Int, dx: Float, dy: Float) {
            val complete = action == MotionEvent.ACTION_UP
            val moveX = dx - dragOriginX
            val moveY = dy - dragOriginY
            velocity?.computeCurrentVelocity(1000)
            if (drag == PlayerDrag.VERTICAL) {
                val projected = moveY + (velocity?.yVelocity ?: 0f).coerceAtLeast(0f) * .18f
                if (complete && (moveY > minOf(ui.dp(118).toFloat(), height * .25f) ||
                        (moveY > ui.dp(20) && projected > ui.dp(190)))) {
                    setSurfaceCornerRadius(ui.dp(36).toFloat())
                    activity.closePlayer(animate = true)
                } else animate().translationY(0f).setDuration(220).withEndAction {
                    if (translationY == 0f) setSurfaceCornerRadius(0f)
                }.start()
            } else {
                val projected = moveX + (velocity?.xVelocity ?: 0f) * .18f
                if (complete && (kotlin.math.abs(moveX) > ui.dp(70) ||
                        (kotlin.math.abs(moveX) > ui.dp(20) && kotlin.math.abs(projected) > ui.dp(220)))) {
                    if (moveX < 0) art.finishDrag(1, activity::next) else art.finishDrag(-1, activity::previousTrack)
                } else art.settle()
            }
        }

        override fun onDetachedFromWindow() {
            velocity?.recycle()
            velocity = null
            super.onDetachedFromWindow()
        }
    }

    private fun showQueue() {
        val player = player ?: return
        dialog?.dismiss()
        val body = ui.column()
        val list = ListView(activity).apply {
            divider = null
            selector = ColorDrawable(Color.TRANSPARENT)
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        var editing = false
        var syncQueueActions: () -> Unit = {}
        lateinit var adapter: BaseAdapter
        adapter = object : BaseAdapter() {
            override fun notifyDataSetChanged() {
                super.notifyDataSetChanged()
                syncQueueActions()
            }
            override fun getCount() = player.mediaItemCount
            override fun getItem(position: Int) = QueueItems.track(player.getMediaItemAt(position))
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val track = getItem(position) ?: return View(activity)
                val current = position == player.currentMediaItemIndex
                val row = ui.row().apply {
                    minimumHeight = ui.dp(58)
                    setPadding(ui.dp(16), 0, ui.dp(if (editing) 8 else 14), 0)
                    background = ui.touch()
                    addView(ui.art(track.cover, 100, corner = 8), LinearLayout.LayoutParams(ui.dp(46), ui.dp(46)))
                    addView(ui.column().apply {
                        setPadding(ui.dp(12), 0, ui.dp(6), 0)
                        addView(TrackTitle(ui, 16, badgeSize = 13, onDark = true).apply {
                            bind(track)
                            label.setTextColor(if (current) Palette.accent else Palette.text)
                        }, ui.full())
                        addView(ui.space(3)); addView(ui.line(track.artistText, 14, Palette.muted))
                    }, ui.weight())
                    if (editing) {
                        if (position > 0) addView(ui.icon(Symbol.ARROW_UP, "Переместить выше", Palette.muted) {
                            if (position in 1 until player.mediaItemCount) {
                                player.moveMediaItem(position, position - 1)
                                adapter.notifyDataSetChanged()
                            }
                        }.apply { iconSize = 16 }, LinearLayout.LayoutParams(ui.dp(34), ui.dp(40)))
                        if (position < player.mediaItemCount - 1) addView(ui.icon(Symbol.ARROW_DOWN, "Переместить ниже", Palette.muted) {
                            if (position in 0 until player.mediaItemCount - 1) {
                                player.moveMediaItem(position, position + 1)
                                adapter.notifyDataSetChanged()
                            }
                        }.apply { iconSize = 16 }, LinearLayout.LayoutParams(ui.dp(34), ui.dp(40)))
                        if (!current) addView(ui.icon(Symbol.MINUS_CIRCLE, "Убрать из очереди", 0xffff6a65.toInt()) {
                            if (position in 0 until player.mediaItemCount && position != player.currentMediaItemIndex) {
                                player.removeMediaItem(position)
                                adapter.notifyDataSetChanged()
                            }
                        }.apply { iconSize = 18 }, LinearLayout.LayoutParams(ui.dp(36), ui.dp(40)))
                        else addView(View(activity), LinearLayout.LayoutParams(ui.dp(36), ui.dp(40)))
                    } else {
                        if (current) addView(PlayingBars(activity, Palette.accent).apply {
                            running = player.isPlaying || (player.playWhenReady && player.playbackState == Player.STATE_BUFFERING)
                        },
                            LinearLayout.LayoutParams(ui.dp(18), ui.dp(18)).apply { marginStart = ui.dp(6) })
                        setOnClickListener { player.seekToDefaultPosition(position); player.play() }
                        setOnLongClickListener { queueMenu(position); true }
                    }
                }
                if (position < player.currentMediaItemIndex) row.alpha = .5f
                if (position != player.currentMediaItemIndex + 1) return row
                val isWave = QueueItems.context(player.currentMediaItem).kind == "radio"
                return ui.column().apply {
                    addView(ui.text(if (isWave) "ДАЛЕЕ В ВОЛНЕ" else "ДАЛЕЕ", 12, Palette.muted, bold = true).apply {
                        letterSpacing = .06f
                        setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(6))
                    }, ui.full())
                    addView(row, ui.full())
                }
            }
        }
        queueAdapter = adapter
        list.adapter = adapter
        val height = minOf(380, activity.resources.configuration.screenHeightDp - 200).coerceAtLeast(140)
        val sheet = activity.bottomSheet(body, dark = true)
        val queueTitle = QueueItems.context(player.currentMediaItem).name.ifBlank { "Очередь" }
        val editButton = ui.line("Изменить", 15, Palette.text, bold = true).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ui.dp(16), 0, ui.dp(12), 0)
            background = ui.touch()
            isFocusable = true
            pressFeedback(.94f)
            setOnClickListener {
                editing = !editing
                text = if (editing) "Готово" else "Изменить"
                setTextColor(if (editing) Palette.accent else Palette.text)
                adapter.notifyDataSetChanged()
            }
        }
        body.addView(FrameLayout(activity).apply {
            addView(ui.line(queueTitle, 17, bold = true).apply { gravity = Gravity.CENTER },
                FrameLayout.LayoutParams(-1, -1).apply { marginStart = ui.dp(92); marginEnd = ui.dp(92) })
            addView(editButton, FrameLayout.LayoutParams(-2, -1, Gravity.START))
            addView(ui.icon(Symbol.CLOSE, "Закрыть") { sheet.dismiss() },
                FrameLayout.LayoutParams(ui.dp(48), -1, Gravity.END).apply { marginEnd = ui.dp(4) })
        }, ui.full(52))
        body.addView(list, ui.full(height))
        fun queuePill(symbol: Symbol, title: String, click: () -> Unit) = ui.row().apply {
            gravity = Gravity.CENTER
            background = ui.touch(Palette.lifted, 23)
            pressFeedback(.95f)
            isFocusable = true
            addView(IconView(activity, symbol, Palette.text).apply { iconSize = 18 },
                LinearLayout.LayoutParams(ui.dp(19), ui.dp(19)).apply { marginEnd = ui.dp(8) })
            addView(ui.line(title, 15, Palette.text, bold = true))
            setOnClickListener { click() }
        }
        val saveButton = queuePill(Symbol.PLAYLIST_ADD, "В плейлист") {
            val tracks = (0 until player.mediaItemCount).mapNotNull { QueueItems.track(player.getMediaItemAt(it)) }
            val defaultTitle = QueueItems.context(player.currentMediaItem).name.ifBlank { "Очередь" }
            activity.input("Сохранить очередь", defaultTitle, "Название плейлиста") { title ->
                activity.action {
                    val uid = store.requireUser().uid
                    val playlist = store.online { createPlaylist(uid, title) }
                    store.changeLocalPlaylist(playlist)
                    store.online { editPlaylist(playlist, tracks) }
                    activity.toast("Очередь сохранена")
                }
            }
        }
        val clearButton = queuePill(Symbol.CLOSE, "Очистить") {
            val from = player.currentMediaItemIndex + 1
            if (from < player.mediaItemCount) {
                player.removeMediaItems(from, player.mediaItemCount)
                adapter.notifyDataSetChanged()
            }
        }
        syncQueueActions = {
            val hasUpcoming = player.currentMediaItemIndex + 1 < player.mediaItemCount
            clearButton.isEnabled = hasUpcoming
            clearButton.alpha = if (hasUpcoming) 1f else .4f
        }
        syncQueueActions()
        body.addView(ui.row().apply {
            setPadding(ui.dp(16), ui.dp(8), ui.dp(16), ui.dp(12))
            addView(saveButton, LinearLayout.LayoutParams(0, ui.dp(46), 1f).apply { marginEnd = ui.dp(10) })
            addView(clearButton, LinearLayout.LayoutParams(0, ui.dp(46), 1f))
        }, ui.full())
        sheet.show()
        sheet.window?.setLayout(-1, -2)
        dialog = sheet
        list.setSelectionFromTop(player.currentMediaItemIndex.coerceAtLeast(0), (ui.dp(height) / 2 - ui.dp(29)).coerceAtLeast(0))
        sheet.setOnDismissListener {
            queueAdapter = null
            if (dialog == sheet) dialog = null
        }
    }

    private fun queueMenu(position: Int) {
        val player = player ?: return
        if (position !in 0 until player.mediaItemCount) return
        val track = QueueItems.track(player.getMediaItemAt(position)) ?: return
        val commands = buildList<Pair<String, Int?>> {
            if (position > 0) add("Выше" to position - 1)
            if (position < player.mediaItemCount - 1) add("Ниже" to position + 1)
            if (position != player.currentMediaItemIndex) add("Убрать из очереди" to null)
        }
        if (commands.isEmpty()) return
        activity.choose(track.title, commands.map { it.first }) { index ->
            if (position !in 0 until player.mediaItemCount || player.getMediaItemAt(position).mediaId != track.id) {
                activity.toast("Очередь обновилась")
                return@choose
            }
            val target = commands[index].second
            if (target == null) player.removeMediaItem(position)
            else if (target in 0 until player.mediaItemCount) player.moveMediaItem(position, target)
        }
    }

    private fun sleepTimer() {
        val current = store.sleepTimer.value
        val title = when {
            current.stopAtMs != null -> {
                val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(current.stopAtMs))
                "Таймер сна · до $time"
            }
            current.afterTrack -> "Таймер сна · после трека"
            else -> "Таймер сна"
        }
        val options = mutableListOf(
            "15 минут" to 15,
            "30 минут" to 30,
            "45 минут" to 45,
            "1 час" to 60,
            "После этого трека" to -1,
        )
        if (current.active) options += "Выключить" to 0
        activity.choose(title, options.map { it.first }) { index ->
            val value = options[index].second
            val args = Bundle().apply { putInt("minutes", value) }
            player?.sendCustomCommand(SessionCommand(QueueItems.SLEEP, Bundle.EMPTY), args)
            activity.toast(when (value) { 0 -> "Таймер выключен"; -1 -> "Музыка остановится после трека"; else -> "Таймер запущен" })
        }
    }

    fun dismiss() {
        bufferRing.buffering = false
        lyricJob?.cancel()
        scrollAnimator?.cancel()
        toneAnimator?.cancel()
        seekThicknessAnimator?.cancel()
        dialog?.dismiss()
    }
}
