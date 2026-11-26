package io.github.theme26.aqibar

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import io.github.theme26.aqibar.render.AqiNotifier
import io.github.theme26.aqibar.worker.RefreshScheduler

class AqiApp : Application() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        RefreshScheduler.ensureCurrentSpec(this)
    }

    private fun createNotificationChannel() {
        val mgr = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            AqiNotifier.CHANNEL_ID,
            getString(R.string.channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = getString(R.string.channel_desc)
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        }
        mgr.createNotificationChannel(channel)
        mgr.deleteNotificationChannel("aqi_aod")
    }
}
