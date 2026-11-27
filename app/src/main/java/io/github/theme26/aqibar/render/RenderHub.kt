package io.github.theme26.aqibar.render

import android.content.Context
import io.github.theme26.aqibar.core.AqiState
import io.github.theme26.aqibar.data.AqiPrefs
import io.github.theme26.aqibar.ui.Shortcuts
import io.github.theme26.aqibar.widget.WidgetUpdater

object RenderHub {
    fun render(ctx: Context, state: AqiState) {
        val notifier = AqiNotifier(ctx)
        if (AqiPrefs(ctx).notificationEnabled) notifier.show(state) else notifier.cancel()
        WidgetUpdater.updateAll(ctx, state)
        Shortcuts.publishReading(ctx, state)
    }
}
