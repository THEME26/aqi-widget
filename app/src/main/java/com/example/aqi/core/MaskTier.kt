package com.example.aqi.core

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.example.aqi.R

/**
 * v0.1.6「要不要带口罩」三档决策。
 *
 * 为什么不用原来的 6 档填充方框：状态栏 smallIcon 是 alpha-only 白色蒙版，
 * 24dp 下靠「数填充度」几乎分辨不出来；而 ○ / ● / ⊘ 的**形状差异**一眼可辨，
 * 且一一对应一个动作。底层 6 档 AQI / 颜色 / 断点 / 平均窗口**全部保留**，
 * 桌面小部件、通知数值、锁屏卡片、屏保继续用 6 档颜色与数值。
 *
 * 切点对齐 EPA 官方分级（0/100/200），US EPA 2024 与 HJ 633-2012 的分级界限相同，
 * 所以两套标准共用这一套映射，不用分开处理。
 *
 * 这是**数值分级提示，不是医疗建议**，见 strings.xml 的 mask_disclaimer。
 */
enum class MaskTier(
    val symbol: String,
    @DrawableRes val iconRes: Int,
    @StringRes val labelRes: Int,
    @StringRes val adviceRes: Int,
) {
    /** 0–100：优 / 良（不戴口罩的笑脸） */
    GO_OUT("○", R.drawable.ic_face_ok, R.string.mask_tier_go, R.string.mask_tier_go_advice),

    /** 101–200：敏感人群不健康 / 不健康（戴口罩的脸） */
    MASK("●", R.drawable.ic_face_mask, R.string.mask_tier_mask, R.string.mask_tier_mask_advice),

    /** ≥201：非常不健康 / 危险（口罩脸 + 禁止斜杠） */
    STAY_IN("⊘", R.drawable.ic_face_stay, R.string.mask_tier_stay, R.string.mask_tier_stay_advice),
    ;

    companion object {
        /** 无数据 / 无 Token 时的占位图标：圆内一横，不伪装成任何一档。 */
        @DrawableRes
        val UNKNOWN_ICON: Int = R.drawable.ic_mask_unknown

        /**
         * v0.1.9 深/浅底两套图标。
         *
         * 桌面小部件跑在 launcher 进程里，拿不到 tint（RemoteViews 无 colorFilter 通道），
         * 而白图标在浅色玻璃卡上等于隐形，所以预生成了一套深墨版 PNG。
         */
        @DrawableRes
        fun iconFor(tier: MaskTier?, night: Boolean): Int {
            if (tier == null) {
                return if (night) R.drawable.ic_mask_unknown else R.drawable.ic_mask_unknown_ink
            }
            if (night) return tier.iconRes
            return when (tier) {
                GO_OUT -> R.drawable.ic_face_ok_ink
                MASK -> R.drawable.ic_face_mask_ink
                STAY_IN -> R.drawable.ic_face_stay_ink
            }
        }

        /** value < 0（无数据）返回 null，调用方必须退到 [UNKNOWN_ICON]，绝不猜档。 */
        fun of(value: Int): MaskTier? = when {
            value < 0 -> null
            value <= 100 -> GO_OUT
            value <= 200 -> MASK
            else -> STAY_IN
        }
    }
}
