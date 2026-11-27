package io.github.theme26.aqibar.ui

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.os.Build
import io.github.theme26.aqibar.R
import io.github.theme26.aqibar.core.AqiState

/**
 * 桌面与锁屏上的快捷入口。
 *
 * ColorOS 的锁屏「添加小组件」列表读的就是应用快捷方式，微信的扫一扫、付款码
 * 和支付宝的乘车码都在里面——那是 Shortcut，不是 AppWidget。所以在那个列表里
 * 出现的前提是先声明 shortcut。
 */
object Shortcuts {
    const val ACTION_REFRESH = "io.github.theme26.aqibar.action.REFRESH"
    const val ACTION_SETTINGS = "io.github.theme26.aqibar.action.SETTINGS"

    private const val ID_READING = "reading"

    /** 把当前读数做成一个动态快捷方式，标题即数值。 */
    fun publishReading(ctx: Context, state: AqiState) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) return
        if (!state.hasValue) return
        val mgr = ctx.getSystemService(ShortcutManager::class.java) ?: return
        if (mgr.isRateLimitingActive) return

        val level = state.level.label(ctx, state.standard)
        val short = ctx.getString(R.string.shortcut_reading_short, state.value)
        val long = ctx.getString(R.string.shortcut_reading_long, state.value, level)
        val intent = Intent(ctx, MainActivity::class.java)
            .setAction(ACTION_REFRESH)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        val shortcut = ShortcutInfo.Builder(ctx, ID_READING)
            .setShortLabel(short)
            .setLongLabel(long)
            .setIcon(Icon.createWithResource(ctx, R.mipmap.ic_launcher))
            .setIntent(intent)
            .build()

        runCatching { mgr.setDynamicShortcuts(listOf(shortcut)) }
    }
}
