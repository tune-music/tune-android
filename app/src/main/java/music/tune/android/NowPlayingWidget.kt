package music.tune.android

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.RemoteViews
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken

@OptIn(UnstableApi::class)
class NowPlayingWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val prefs = context.getSharedPreferences("tune", Context.MODE_PRIVATE)
        show(context, manager, ids, prefs.getString("widgetTitle", null), prefs.getString("widgetArtist", null), false)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val next = intent.action == NEXT
        if (!next && intent.action != TOGGLE) return
        val state = goAsync()
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener({
            try {
                val player = future.get()
                if (player.currentMediaItem == null) return@addListener
                if (next) {
                    if (player.hasNextMediaItem()) player.seekToNextMediaItem()
                    else player.sendCustomCommand(SessionCommand(QueueItems.EXTEND_WAVE, Bundle.EMPTY), Bundle.EMPTY)
                } else if (player.playWhenReady) player.pause()
                else {
                    if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
                    if (player.playbackState == Player.STATE_IDLE) player.prepare()
                    player.play()
                }
            } catch (_: Exception) {
                // A session is only available after the account and player have been loaded.
            } finally {
                MediaController.releaseFuture(future)
                state.finish()
            }
        }, ContextCompat.getMainExecutor(context))
    }

    companion object {
        private const val TOGGLE = "music.tune.android.WIDGET_TOGGLE"
        private const val NEXT = "music.tune.android.WIDGET_NEXT"

        internal fun update(context: Context, track: Track?, playing: Boolean) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, NowPlayingWidget::class.java))
            if (ids.isEmpty()) return
            context.getSharedPreferences("tune", Context.MODE_PRIVATE).edit()
                .putString("widgetTitle", track?.title).putString("widgetArtist", track?.artistText).apply()
            show(context, manager, ids, track?.title, track?.artistText, playing)
        }

        private fun show(context: Context, manager: AppWidgetManager, ids: IntArray, title: String?, artist: String?, playing: Boolean) {
            fun launch(action: String, id: Int) = PendingIntent.getActivity(context, id,
                Intent(context, MainActivity::class.java).setAction(action).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            fun command(action: String, id: Int) = PendingIntent.getBroadcast(context, id,
                Intent(context, NowPlayingWidget::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE)
            val views = RemoteViews(context.packageName, R.layout.now_playing_widget)
            views.setTextViewText(R.id.widget_title, title ?: "Tune")
            views.setTextViewText(R.id.widget_artist, artist.orEmpty())
            views.setImageViewResource(R.id.widget_play, if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play)
            views.setContentDescription(R.id.widget_play, context.getString(if (playing) R.string.widget_pause else R.string.widget_play))
            views.setOnClickPendingIntent(R.id.widget_open, launch(MainActivity.OPEN_PLAYER, 40))
            views.setOnClickPendingIntent(R.id.widget_wave, launch("music.tune.android.SHORTCUT_WAVE", 41))
            views.setOnClickPendingIntent(R.id.widget_search, launch("music.tune.android.SHORTCUT_SEARCH", 42))
            views.setOnClickPendingIntent(R.id.widget_likes, launch("music.tune.android.SHORTCUT_LIKES", 43))
            views.setOnClickPendingIntent(R.id.widget_play, if (title == null) launch("music.tune.android.SHORTCUT_WAVE", 45) else command(TOGGLE, 45))
            views.setOnClickPendingIntent(R.id.widget_next, command(NEXT, 46))
            manager.updateAppWidget(ids, views)
        }
    }
}
