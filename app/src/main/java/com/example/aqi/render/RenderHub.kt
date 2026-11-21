package com.example.aqi.render

import android.content.Context
import com.example.aqi.core.AqiState
import com.example.aqi.data.AqiPrefs
import com.example.aqi.widget.WidgetUpdater

/** 单一分发点：一次刷新，同时更新通知与所有桌面小部件，杜绝两端数值不一致 */
object RenderHub {
    fun render(ctx: Context, state: AqiState) {
        val notifier = AqiNotifier(ctx)
        if (AqiPrefs(ctx).notificationEnabled) notifier.show(state) else notifier.cancel()
        WidgetUpdater.updateAll(ctx, state)
        // 息屏屏保在 onAttachedToWindow / 每分钟 tick 时自己读缓存，不需要主动推送
    }
}
