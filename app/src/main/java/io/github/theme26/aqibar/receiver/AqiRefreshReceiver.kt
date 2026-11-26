package io.github.theme26.aqibar.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.theme26.aqibar.data.AqiCache
import io.github.theme26.aqibar.data.AqiPrefs
import io.github.theme26.aqibar.render.RenderHub
import io.github.theme26.aqibar.worker.RefreshScheduler

class AqiRefreshReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_REFRESH) return
        val p = AqiPrefs(context)
        val cached = AqiCache(context).loadForDisplay(p.intervalMinutes, p.standard, p.token.isBlank())
        RenderHub.render(context, cached)
        RefreshScheduler.refreshNow(context)
    }

    companion object {
        const val ACTION_REFRESH = "io.github.theme26.aqibar.ACTION_REFRESH"
    }
}
