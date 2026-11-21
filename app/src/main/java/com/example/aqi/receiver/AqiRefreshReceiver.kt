package com.example.aqi.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.aqi.data.AqiCache
import com.example.aqi.data.AqiPrefs
import com.example.aqi.render.RenderHub
import com.example.aqi.worker.RefreshScheduler

/** 小部件上的刷新按钮：立即入队一次 Worker，同时用缓存重绘给出即时反馈 */
class AqiRefreshReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_REFRESH) return
        val p = AqiPrefs(context)
        val cached = AqiCache(context).loadForDisplay(p.intervalMinutes, p.standard, p.token.isBlank())
        RenderHub.render(context, cached)
        RefreshScheduler.refreshNow(context)
    }

    companion object {
        const val ACTION_REFRESH = "com.example.aqi.ACTION_REFRESH"
    }
}
