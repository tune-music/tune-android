package music.tune.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import androidx.core.content.ContextCompat

internal class PlaybackMotion(private val context: Context) {
    private val power = context.getSystemService(PowerManager::class.java)
    private val views = mutableSetOf<View>()
    private var started = false
    var enabled = false
        private set

    private val powerChanges = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = refresh()
    }
    private val scaleChanges = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) = refresh()
    }

    fun start() {
        if (started) return
        started = true
        ContextCompat.registerReceiver(context, powerChanges, IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED)
        context.contentResolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, scaleChanges,
        )
        refresh()
    }

    fun stop() {
        if (!started) return
        started = false
        context.unregisterReceiver(powerChanges)
        context.contentResolver.unregisterContentObserver(scaleChanges)
        refresh()
    }

    fun observe(view: View) {
        view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) {
                views.add(view)
                view.invalidate()
            }

            override fun onViewDetachedFromWindow(view: View) {
                views.remove(view)
            }
        })
    }

    private fun refresh() {
        val allowed = started && !power.isPowerSaveMode &&
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
        if (allowed == enabled) return
        enabled = allowed
        for (view in views) view.invalidate()
    }
}
