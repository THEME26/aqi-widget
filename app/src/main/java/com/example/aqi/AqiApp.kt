package com.example.aqi

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import com.example.aqi.render.AqiNotifier
import com.example.aqi.worker.RefreshScheduler

class AqiApp : Application() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        RefreshScheduler.ensurePeriodic(this)
    }

    private fun createNotificationChannel() {
        // minSdk 26 = O，通知渠道 API 在所有目标设备上都存在，无需版本判断
        val mgr = getSystemService(NotificationManager::class.java)
        // IMPORTANCE_DEFAULT + 静音：IMPORTANCE_LOW 在图标区拥挤时会被折叠成一个小点，
        // IMPORTANCE_MIN 更是完全不显示状态栏图标（还会额外弹「应用正在后台运行」）。
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
        // v0.5.1 删掉了自绘息屏。升级用户的通知设置里会残留一个「息屏显示」渠道，
        // 它再也不会发通知 —— 留着只会让人以为 App 还在后台常驻。删掉是幂等的。
        mgr.deleteNotificationChannel("aqi_aod")
    }
}
