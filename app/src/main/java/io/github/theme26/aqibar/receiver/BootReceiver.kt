package io.github.theme26.aqibar.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.theme26.aqibar.data.AqiPrefs
import io.github.theme26.aqibar.worker.RefreshScheduler

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return

        RefreshScheduler.ensureCurrentSpec(context)
        if (!AqiPrefs(context).notificationEnabled) return
        RefreshScheduler.refreshWhenOnline(context)
    }
}
