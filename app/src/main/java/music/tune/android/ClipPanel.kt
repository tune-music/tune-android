package music.tune.android

import android.view.Gravity
import android.widget.LinearLayout
import androidx.annotation.OptIn
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
    private val video = PlayerView(activity)
    private val status = ui.note("Загрузка клипа…")
    val view = ui.column().apply {
        setBackgroundColor(Palette.background)
        addView(ui.row().apply {
            addView(ui.icon(Symbol.BACK, "Назад", click = activity::closeVideo))
            addView(ui.line(card.title, 19, bold = true), ui.weight())
            card.url?.let { url -> addView(ui.icon(Symbol.SHARE, "Поделиться") { activity.share(url) }) }
        }, ui.full(56))
        addView(video, LinearLayout.LayoutParams(-1, 0, 1f))
        addView(status, ui.full())
        card.url?.let { url ->
            addView(ui.button("Открыть в Яндекс Музыке") { activity.browser(url) }, LinearLayout.LayoutParams(-2, -2).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = ui.dp(14)
            })
        }
    }

    fun load() {
        job = activity.action(onError = { status.text = it }) {
            val (url, mime) = activity.store.online { clipStream(card) }
            player = ExoPlayer.Builder(activity).setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).build(), true)
                .build().also {
                    it.setHandleAudioBecomingNoisy(true)
                    video.player = it
                    it.addListener(object : Player.Listener {
                        override fun onPlayerError(error: PlaybackException) { status.text = "Не удалось воспроизвести клип" }
                        override fun onPlaybackStateChanged(state: Int) {
                            if (state == Player.STATE_READY) status.text = card.subtitle
                        }
                    })
                    it.setMediaItem(MediaItem.Builder().setUri(url).setMimeType(mime).build())
                    it.prepare()
                    it.playWhenReady = activity.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)
                }
        }
    }

    fun pause() { player?.pause() }

    fun release() {
        job?.cancel()
        video.player = null
        player?.release()
        player = null
    }
}
