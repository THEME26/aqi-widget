package com.example.aqi.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.aqi.data.AqiPrefs
import com.example.aqi.worker.RefreshScheduler

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return

        RefreshScheduler.ensureCurrentSpec(context)
        if (!AqiPrefs(context).notificationEnabled) return
        RefreshScheduler.refreshWhenOnline(context)
    }
}
