package com.example.aqi.core

import androidx.annotation.StringRes
import com.example.aqi.R

/**
 * v0.1.8 通知时机策略。
 *
 * 为什么改成「只在需要口罩时推送」：该 ROM 把常驻通知的 smallIcon 强制替换成
 * launcher 图标（v0.1.6 / v0.1.7 两次换图标均无效，已由用户截图取证）。
 * 既然图标形状不可控，就把「有没有这条通知」本身当作信息位——
 * 状态栏出现它，就是「该戴口罩了」。这比继续和 ROM 抢图标更可靠。
 *
 * [ALERT_ONLY] 的切点用 101（即 AQI ≥ 101 才推送）：100 及以下在美国 EPA 分级里
 * 是 Good / Moderate，对应「可出门」，与 [MaskTier] 的两档切点完全一致。
 */
enum class NotifPolicy(
    val id: String,
    @StringRes val labelRes: Int,
) {
    /** 默认：仅 AQI ≥ 101 推送，优/良时不占状态栏。 */
    ALERT_ONLY("alert_only", R.string.notif_policy_alert_only),

    /** 始终常驻，数值照常显示（用户想一直看到数字时选它）。 */
    ALWAYS("always", R.string.notif_policy_always),

    /** 完全关闭通知，只留桌面小部件与 App 内数值。 */
    NEVER("never", R.string.notif_policy_never),
    ;

    /**
     * 是否该显示常驻通知。
     *
     * `value < 0`（无数据）在 [ALERT_ONLY] 下**不推送**：没有数值时弹出「口罩脸」
     * 会造成「该戴口罩」的误判，比不提示更危险。失败信息由 App 内与小部件的
     * 占位状态承担。
     */
    fun shouldNotify(value: Int): Boolean = when (this) {
        NEVER -> false
        ALWAYS -> true
        ALERT_ONLY -> value > 100
    }

    companion object {
        fun fromId(id: String?): NotifPolicy = entries.firstOrNull { it.id == id } ?: ALERT_ONLY
    }
}
