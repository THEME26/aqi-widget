package com.example.aqi.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.aqi.data.AqiPrefs
import com.example.aqi.worker.RefreshScheduler

/**
 * WorkManager 自己会在重启后恢复周期任务，这里只是加固。
 *
 * v0.5.1 起不再拉起任何服务：息屏由系统 Live Updates 承担，本 App 无常驻进程。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return

        if (!AqiPrefs(context).notificationEnabled) return
        RefreshScheduler.ensurePeriodic(context)
        RefreshScheduler.refreshNow(context)
    }
}
