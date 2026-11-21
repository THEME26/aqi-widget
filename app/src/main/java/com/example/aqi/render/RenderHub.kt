package com.example.aqi.render

import android.content.Context
import com.example.aqi.core.AqiState
import com.example.aqi.data.AqiPrefs
import com.example.aqi.widget.WidgetUpdater

object RenderHub {
    fun render(ctx: Context, state: AqiState) {
        val notifier = AqiNotifier(ctx)
        if (AqiPrefs(ctx).notificationEnabled) notifier.show(state) else notifier.cancel()
        WidgetUpdater.updateAll(ctx, state)
    }
}
