package music.tune.android

import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import androidx.annotation.OptIn
import androidx.core.graphics.Insets
import androidx.lifecycle.Lifecycle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Job

@OptIn(UnstableApi::class)
internal class ClipPanel(private val activity: MainActivity, private val card: Card) {
    private val ui = TuneUi(activity)
    private var job: Job? = null
    private var player: ExoPlayer? = null
    private val video = PlayerView(activity).apply { visibility = View.INVISIBLE }
    private val status = FrameLayout(activity)
    val view = ui.column().apply {
        setBackgroundColor(Palette.background)
        addView(ui.row().apply {
            addView(ui.icon(Symbol.BACK, "Назад", click = activity::closeVideo))
            addView(ui.line(card.title, 19, bold = true), ui.weight())
            card.url?.let { url -> addView(ui.icon(Symbol.SHARE, "Поделиться") { activity.share(url) }) }
        }, ui.full(56))
        addView(FrameLayout(activity).apply {
            addView(video, FrameLayout.LayoutParams(-1, -1))
            addView(status, FrameLayout.LayoutParams(-1, -1))
        }, LinearLayout.LayoutParams(-1, 0, 1f))
        card.url?.let { url ->
            addView(ui.button("Открыть в Яндекс Музыке") { activity.browser(url) }, LinearLayout.LayoutParams(-2, -2).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = ui.dp(14)
            })
        }
    }

    fun applyInsets(bars: Insets) {
        view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
    }

    fun load() {
        job?.cancel()
        clearPlayer()
        showLoading()
        job = activity.action(onError = ::showError) {
            val sources = activity.store.online { clipStreams(card) }.iterator()
            val instance = ExoPlayer.Builder(activity)
                .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).build(), true).build()
            player = instance
            instance.setHandleAudioBecomingNoisy(true)
            video.player = instance
            fun prepareNext() {
                val source = sources.next()
                instance.setMediaItem(MediaItem.Builder().setUri(source.url).setMimeType(source.mime).build())
                instance.prepare()
                instance.playWhenReady = activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            }
            instance.addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    video.post {
                        if (player !== instance) return@post
                        if (sources.hasNext()) {
                            showLoading()
                            prepareNext()
                        } else {
                            clearPlayer()
                            showError("Не удалось воспроизвести клип")
                        }
                    }
                }

                override fun onPlaybackStateChanged(state: Int) {
                    if (state != Player.STATE_READY || player !== instance) return
                    status.visibility = View.GONE
                    status.removeAllViews()
                    video.visibility = View.VISIBLE
                }
            })
            prepareNext()
        }
    }

    private fun showLoading() {
        video.visibility = View.INVISIBLE
        status.visibility = View.VISIBLE
        status.removeAllViews()
        status.addView(ProgressBar(activity).apply {
            contentDescription = "Загружаем клип"
            indeterminateTintList = android.content.res.ColorStateList.valueOf(Palette.text)
        }, FrameLayout.LayoutParams(ui.dp(30), ui.dp(30), Gravity.CENTER))
    }

    private fun showError(message: String) {
        video.visibility = View.INVISIBLE
        status.visibility = View.VISIBLE
        status.removeAllViews()
        status.addView(ui.pagePlaceholder(Symbol.VIDEO, "Не удалось открыть клип", message, ::load),
            FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
    }

    fun pause() { player?.pause() }

    fun release() {
        job?.cancel()
        clearPlayer()
    }

    private fun clearPlayer() {
        val previous = player
        player = null
        video.player = null
        previous?.release()
    }
}
