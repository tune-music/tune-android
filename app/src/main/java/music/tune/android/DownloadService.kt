package music.tune.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class DownloadService : Service() {
    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private val store get() = (application as TuneApp).store
    private val notifications get() = getSystemService(NotificationManager::class.java)
    private var watching = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, "Загрузки Tune", NotificationManager.IMPORTANCE_LOW))
        if (!watching) {
            watching = true
            startForeground(NOTIFICATION, notification(null))
            scope.launch {
                combine(store.files.status, store.files.paused, store.files.networkWait, store.offlineMode) { downloads, paused, wait, offline ->
                    val current = downloads.values.firstOrNull { it.error == null }
                    Triple(current, paused || offline, wait)
                }.collect { (current, stopped, wait) ->
                    if (current == null || stopped) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    } else if (android.os.Build.VERSION.SDK_INT < 33 ||
                        checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        notifications.notify(NOTIFICATION, notification(current, wait))
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun notification(current: DownloadStatus?, wait: DownloadWait? = null): Notification {
        val open = PendingIntent.getActivity(this, 8, Intent(this, MainActivity::class.java)
            .setAction(MainActivity.OPEN_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        val builder = Notification.Builder(this, CHANNEL).setContentTitle(wait?.label ?: "Загрузки Tune")
            .setSmallIcon(android.R.drawable.stat_sys_download).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
        if (current != null) {
            builder.setContentText(current.track.title)
            if (wait == null) builder.setProgress(100, current.percent ?: 0, current.percent == null || current.percent == 0)
        }
        return builder.build()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        store.files.pauseForSystemTimeout()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf(startId)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "tune_downloads"
        private const val NOTIFICATION = 24
    }
}
