package music.tune.android

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Dialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
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
import kotlinx.coroutines.Job

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
    private val details = ui.line("", 12, Palette.muted)
    private val backdrop = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Palette.lifted, 0xff0d0d0d.toInt()))
    private val art = CoverTrackPager(ui) { tone -> backdrop.colors = intArrayOf(tone, 0xff0d0d0d.toInt()) }
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
        visibility = View.GONE
        alpha = 0f
        addView(lyricScroll, FrameLayout.LayoutParams(-1, -1))
        addView(lyricStatus, FrameLayout.LayoutParams(-1, -1))
    }
    private val elapsed = ui.text("0:00", 12, Palette.muted)
    private val duration = ui.text("0:00", 12, Palette.muted)
    private val seek = SeekBar(activity).apply {
        max = 1000
        progressTintList = ColorStateList.valueOf(Palette.text)
        progressBackgroundTintList = ColorStateList.valueOf(0x38ffffff)
        secondaryProgressTintList = ColorStateList.valueOf(0x26ffffff)
        thumbTintList = ColorStateList.valueOf(Color.TRANSPARENT)
        contentDescription = "Позиция трека"
        setPadding(0, 0, 0, 0)
        splitTrack = false
    }
    private val toggle = ui.icon(Symbol.PLAY, "Воспроизвести", Palette.background, activity::toggle).apply {
        layoutParams = LinearLayout.LayoutParams(ui.dp(76), ui.dp(76))
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
    private val download = ui.icon(Symbol.DOWNLOAD_CIRCLE, "Скачать трек", click = ::toggleDownload)
    private val soundButton = ui.icon(Symbol.SETTINGS, "Настройки звука", click = ::showSound)
    private val sleepButton = ui.icon(Symbol.MOON, "Таймер сна", click = ::sleepTimer)
    private var dragging = false
    private var dialog: Dialog? = null
    private var lyricJob: Job? = null
    private var showingLyrics = false
    private var lyricMotion: AnimatorSet? = null
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
            setPadding(0, 0, 0, ui.dp(12))
        }
        val config = activity.resources.configuration
        val size = minOf(config.screenWidthDp - 48, (config.screenHeightDp * .44f).toInt(), 540).coerceAtLeast(140)
        body.addView(FrameLayout(activity).apply {
            minimumHeight = ui.dp(size + 20)
            art.setCoverSize(ui.dp(size))
            addView(art, FrameLayout.LayoutParams(-1, -1))
            addView(lyricPane, FrameLayout.LayoutParams(-1, -1))
        }, LinearLayout.LayoutParams(-1, ui.dp(size + 20), 1f))
        body.addView(ui.row().apply {
            addView(ui.column().apply {
                addView(title.apply { label.setOnClickListener { activity.currentTrack()?.let(activity::openTrackAlbum) } }, ui.full())
                addView(ui.space(6))
                addView(artist.apply { setOnClickListener { activity.currentTrack()?.let(activity::openTrackArtist) } })
                addView(details.apply { setPadding(0, ui.dp(5), 0, 0) })
            }, ui.weight())
            addView(dislike)
            addView(like)
        }, ui.full())
        body.addView(ui.space(14))
        body.addView(seek, ui.full(28))
        body.addView(ui.row().apply {
            addView(elapsed, ui.weight())
            addView(duration)
        }, ui.full())
        body.addView(ui.space(14))
        body.addView(ui.row().apply {
            addView(shuffle)
            addView(View(activity), LinearLayout.LayoutParams(0, 1, 1f))
            addView(skip(Symbol.PREVIOUS, "Предыдущий", -5_000, activity::previous))
            addView(View(activity), LinearLayout.LayoutParams(0, 1, 1f))
            addView(toggle)
            addView(View(activity), LinearLayout.LayoutParams(0, 1, 1f))
            addView(skip(Symbol.NEXT, "Следующий", 5_000, activity::next))
            addView(View(activity), LinearLayout.LayoutParams(0, 1, 1f))
            addView(repeat)
        }, ui.full(76))
        body.addView(ui.space(18))
        body.addView(ui.row().apply {
            listOf(lyricButton, ui.icon(Symbol.OUTPUT, "Устройство вывода", click = ::output),
                soundButton, sleepButton, download, ui.icon(Symbol.QUEUE, "Очередь", click = ::showQueue)).forEach { icon ->
                icon.iconSize = 21
                addView(icon, LinearLayout.LayoutParams(0, ui.dp(44), 1f))
            }
        }, ui.full(44))
        addView(body, ViewGroup.LayoutParams(-1, -2))
    }

    val view: FrameLayout = PlayerSurface().apply {
        background = backdrop
        isClickable = true
        addView(ui.column().apply {
            setPadding(ui.dp(24), ui.dp(8), ui.dp(24), 0)
            addView(toolbar, ui.full(44))
            addView(layout, LinearLayout.LayoutParams(-1, 0, 1f))
        }, FrameLayout.LayoutParams(-1, -1))
    }

    init {
        lyricScroll.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop) {
                lyricScroll.setFadingEdgeLength((lyricScroll.height * .14f).toInt())
                if (lyricRows.isNotEmpty()) {
                    padLyrics()
                    lastAnchor = -1
                    lyricScroll.post(::progress)
                }
            }
        }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(bar: SeekBar) { dragging = true }
            override fun onStopTrackingTouch(bar: SeekBar) {
                val length = player?.duration ?: 0
                if (length > 0) player?.seekTo(length * bar.progress / 1000)
                dragging = false
            }
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val length = player?.duration ?: 0
                if (length > 0) {
                    val position = length * progress / 1000
                    elapsed.text = timeLabel(position)
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
            "album" -> "ИГРАЕТ АЛЬБОМ"
            "playlist" -> "ИГРАЕТ ПЛЕЙЛИСТ"
            "artist" -> "ИГРАЕТ ИСПОЛНИТЕЛЬ"
            else -> "ИГРАЕТ"
        }
        source.text = context.name
        source.visibility = if (context.name.isBlank()) View.GONE else View.VISIBLE
        title.bind(track)
        artist.text = track.artistText
        details.text = when {
            player.playbackState == Player.STATE_BUFFERING -> "Загрузка…"
            store.files.hasReplacement(track) -> "Подмена"
            else -> ""
        }
        details.visibility = if (details.text.isEmpty()) View.GONE else View.VISIBLE
        fun neighbor(index: Int) = if (index in 0 until player.mediaItemCount && index != player.currentMediaItemIndex)
            QueueItems.track(player.getMediaItemAt(index)) else null
        art.bind(neighbor(player.previousMediaItemIndex), track, neighbor(player.nextMediaItemIndex), player.isPlaying)
        val playing = player.isPlaying || player.playWhenReady && player.playbackState == Player.STATE_BUFFERING
        activity.setIcon(toggle, if (playing) Symbol.PAUSE else Symbol.PLAY, if (playing) "Пауза" else "Воспроизвести", Palette.background)
        val liked = track.id in store.likes.value
        activity.setIcon(like, if (liked) Symbol.HEART_FILLED else Symbol.HEART,
            if (liked) "Убрать из «Мне нравится»" else "Мне нравится", if (liked) Palette.accent else Palette.text)
        val disliked = track.id in store.dislikes.value
        activity.setIcon(dislike, Symbol.THUMB_DOWN, if (disliked) "Снять дизлайк" else "Не нравится",
            if (disliked) Palette.accent else Palette.text)
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
        queueAdapter?.notifyDataSetChanged()
        progress()
    }

    fun updateDownload() {
        val track = activity.currentTrack() ?: return
        val saved = store.files.has(track)
        val loading = store.files.status.value[track.id]
        activity.setIcon(download, if (saved) Symbol.CHECK_CIRCLE else Symbol.DOWNLOAD_CIRCLE, when {
            saved -> "Удалить загрузку"
            loading?.percent != null -> "Скачивается: ${loading.percent}%"
            else -> "Скачать трек"
        }, if (saved || loading?.percent != null) Palette.accent else Palette.text)
    }

    fun progress() {
        val player = player ?: return
        val length = player.duration.takeIf { it > 0 } ?: activity.currentTrack()?.duration ?: 0
        val total = timeLabel(length)
        if (duration.text != total) duration.text = total
        seek.isEnabled = length > 0 && player.isCurrentMediaItemSeekable
        if (!dragging) {
            val position = timeLabel(player.currentPosition)
            if (elapsed.text != position) elapsed.text = position
            seek.progress = if (length > 0) (player.currentPosition * 1000 / length).toInt().coerceIn(0, 1000) else 0
            seek.secondaryProgress = if (length > 0) (player.bufferedPosition * 1000 / length).toInt().coerceIn(0, 1000) else 0
        }
        if (!showingLyrics || !timedLyrics) return
        val now = player.currentPosition + 250
        val index = lyrics.indexOfLast { it.position?.let { position -> position <= now } == true }
        if (index != activeLine) {
            lyricRows.getOrNull(activeLine)?.let { highlight(it, .32f) }
            activeLine = index
            lyricRows.getOrNull(index)?.let { highlight(it, 1f) }
        }
        val anchor = index.coerceAtLeast(0)
        if ((anchor != lastAnchor || recenterLyrics) && !lyricScroll.isManuallyScrolling && lyricScroll.height > 0) {
            val row = lyricRows[anchor]
            if (row.height == 0) return
            val y = (row.top - (lyricScroll.height - row.height) / 2).coerceAtLeast(0)
            if (lastAnchor < 0 || !ValueAnimator.areAnimatorsEnabled()) lyricScroll.scrollTo(0, y)
            else lyricScroll.smoothScrollTo(0, y)
            lastAnchor = anchor
            recenterLyrics = false
        }
    }

    private fun highlight(row: View, alpha: Float) {
        row.isSelected = alpha == 1f
        if (ValueAnimator.areAnimatorsEnabled()) row.animate().alpha(alpha).setDuration(220).start()
        else row.alpha = alpha
    }

    private fun toggleLyrics() {
        val track = activity.currentTrack() ?: return
        showingLyrics = !showingLyrics
        view.keepScreenOn = showingLyrics
        lyricMotion?.let { motion -> lyricMotion = null; motion.cancel() }
        if (ValueAnimator.areAnimatorsEnabled()) {
            art.visibility = View.VISIBLE
            lyricPane.visibility = View.VISIBLE
            lyricMotion = AnimatorSet().apply {
                playTogether(
                    ObjectAnimator.ofFloat(art, View.ALPHA, if (showingLyrics) 0f else 1f),
                    ObjectAnimator.ofFloat(lyricPane, View.ALPHA, if (showingLyrics) 1f else 0f),
                )
                duration = 230
                doOnEnd { if (lyricMotion === it) finishLyricsTransition() }
            }.also { it.start() }
        } else finishLyricsTransition()
        activity.setIcon(lyricButton, Symbol.LYRICS, if (showingLyrics) "Показать обложку" else "Текст песни",
            if (showingLyrics) Palette.accent else Palette.text)
        if (showingLyrics) loadLyrics(track)
        else lyricJob?.cancel()
    }

    private fun finishLyricsTransition() {
        lyricPane.visibility = if (showingLyrics) View.VISIBLE else View.GONE
        lyricPane.alpha = if (showingLyrics) 1f else 0f
        art.visibility = if (showingLyrics) View.INVISIBLE else View.VISIBLE
        art.alpha = if (showingLyrics) 0f else 1f
        if (!showingLyrics) clearLyrics()
    }

    private fun clearLyrics() {
        lyricJob?.cancel()
        lyrics = emptyList()
        lyricRows = emptyList()
        timedLyrics = false
        activeLine = -1
        lastAnchor = -1
        recenterLyrics = false
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
                    typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
                    setPadding(ui.dp(8), 0, ui.dp(8), 0)
                    line.position?.let { time ->
                        background = ui.touch()
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
            text.source?.label?.let { label ->
                addCredit("Текст: $label", 13, 0x66ffffff, if (hasAuthors) 6 else 14)
            }
            padLyrics()
            lyricScroll.post { progress() }
        }
    }

    private fun padLyrics() {
        val padding = if (timedLyrics) (lyricScroll.height / 2 - ui.dp(16)).coerceAtLeast(0) else ui.dp(16)
        lyricScroll.setFadingEdgeLength((lyricScroll.height * .14f).toInt())
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
                MotionEvent.ACTION_MOVE -> if (kotlin.math.abs(event.y - startY) > ViewConfiguration.get(activity).scaledTouchSlop) moving = true
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
        activity.setIcon(soundButton, Symbol.SETTINGS, "Настройки звука",
            if (soundIsAdjusted(store.preferences)) Palette.accent else 0xd9ffffff.toInt())
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
                    if (drag == PlayerDrag.HORIZONTAL) art.startDrag()
                }
            }
            val handled = when (drag) {
                PlayerDrag.VERTICAL, PlayerDrag.HORIZONTAL -> {
                    if (action == MotionEvent.ACTION_MOVE) {
                        if (drag == PlayerDrag.VERTICAL) translationY = dy.coerceAtLeast(0f)
                        else art.drag(dx)
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

        private fun settle(action: Int, dx: Float, dy: Float) {
            val complete = action == MotionEvent.ACTION_UP
            velocity?.computeCurrentVelocity(1000)
            if (drag == PlayerDrag.VERTICAL) {
                val projected = dy + (velocity?.yVelocity ?: 0f).coerceAtLeast(0f) * .18f
                if (complete && (dy > minOf(ui.dp(118).toFloat(), height * .25f) ||
                        (dy > ui.dp(20) && projected > ui.dp(190)))) {
                    activity.closePlayer(animate = true)
                } else animate().translationY(0f).setDuration(220).start()
            } else {
                val projected = dx + (velocity?.xVelocity ?: 0f) * .18f
                if (complete && (kotlin.math.abs(dx) > ui.dp(70) ||
                        (kotlin.math.abs(dx) > ui.dp(20) && kotlin.math.abs(projected) > ui.dp(220)))) {
                    if (dx < 0) art.finishDrag(1, activity::next) else art.finishDrag(-1, activity::previous)
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
        val list = ListView(activity).apply { divider = null }
        val adapter = object : BaseAdapter() {
            override fun getCount() = player.mediaItemCount
            override fun getItem(position: Int) = QueueItems.track(player.getMediaItemAt(position))
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val track = getItem(position) ?: return View(activity)
                val current = position == player.currentMediaItemIndex
                val row = ui.row().apply {
                    minimumHeight = ui.dp(58)
                    setPadding(ui.dp(16), 0, ui.dp(4), 0)
                    background = ui.touch()
                    addView(ui.art(track.cover, 100), LinearLayout.LayoutParams(ui.dp(42), ui.dp(42)))
                    addView(ui.column().apply {
                        setPadding(ui.dp(10), 0, 0, 0)
                        addView(ui.line(track.title, 14, if (current) Palette.accent else Palette.text, true))
                        addView(ui.space(3)); addView(ui.line(track.artistText, 12, Palette.muted))
                    }, ui.weight())
                    addView(ui.icon(Symbol.MORE, "Действия с треком") { queueMenu(position) })
                    setOnClickListener { player.seekToDefaultPosition(position); player.play() }
                    setOnLongClickListener { queueMenu(position); true }
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
        val height = minOf(410, activity.resources.configuration.screenHeightDp - 150).coerceAtLeast(140)
        val sheet = activity.bottomSheet(body, dark = true)
        body.addView(ui.row().apply {
            setPadding(ui.dp(16), 0, ui.dp(6), 0)
            addView(ui.text("Очередь", 22, bold = true), ui.weight())
            addView(ui.icon(Symbol.MORE, "Действия с очередью") { queueActions() })
            addView(ui.icon(Symbol.CLOSE, "Закрыть") { sheet.dismiss() })
        }, ui.full(55))
        body.addView(list, ui.full(height))
        sheet.show()
        sheet.window?.setLayout(-1, -2)
        dialog = sheet
        list.setSelection((player.currentMediaItemIndex - 1).coerceAtLeast(0))
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
            add("Убрать из очереди" to null)
        }
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

    private fun queueActions() {
        val player = player ?: return
        activity.choose("Очередь", listOf("Сохранить как плейлист", "Очистить после текущего трека")) { index ->
            if (index == 0) {
                val tracks = (0 until player.mediaItemCount).mapNotNull { QueueItems.track(player.getMediaItemAt(it)) }
                activity.input("Сохранить очередь", "", "Название плейлиста") { title ->
                    activity.action {
                        val uid = store.requireUser().uid
                        val playlist = store.online { createPlaylist(uid, title) }
                        store.changeLocalPlaylist(playlist)
                        store.online { editPlaylist(playlist, tracks) }
                        activity.toast("Очередь сохранена")
                    }
                }
            } else {
                val from = player.currentMediaItemIndex + 1
                if (from < player.mediaItemCount) player.removeMediaItems(from, player.mediaItemCount)
            }
        }
    }

    private fun sleepTimer() {
        val minutes = listOf(15, 30, 45, 60, -1, 0)
        activity.choose("Таймер сна", listOf("15 минут", "30 минут", "45 минут", "1 час", "После этого трека", "Выключить")) { index ->
            val args = Bundle().apply { putInt("minutes", minutes[index]) }
            player?.sendCustomCommand(SessionCommand(QueueItems.SLEEP, Bundle.EMPTY), args)
            activity.toast(when (minutes[index]) { 0 -> "Таймер выключен"; -1 -> "Музыка остановится после трека"; else -> "Таймер запущен" })
        }
    }

    fun dismiss() {
        lyricJob?.cancel()
        dialog?.dismiss()
    }
}
