package io.github.theme26.aqibar.core

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import io.github.theme26.aqibar.R

enum class MaskTier(
    val symbol: String,
    @DrawableRes val iconRes: Int,
    @StringRes val labelRes: Int,
    @StringRes val adviceRes: Int,
) {
    GO_OUT("○", R.drawable.ic_face_ok, R.string.mask_tier_go, R.string.mask_tier_go_advice),

    MASK("●", R.drawable.ic_face_mask, R.string.mask_tier_mask, R.string.mask_tier_mask_advice),

    STAY_IN("⊘", R.drawable.ic_face_stay, R.string.mask_tier_stay, R.string.mask_tier_stay_advice),
    ;

    companion object {
        @DrawableRes
        val UNKNOWN_ICON: Int = R.drawable.ic_mask_unknown

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

        fun of(value: Int): MaskTier? = when {
            value < 0 -> null
            value <= 100 -> GO_OUT
            value <= 200 -> MASK
            else -> STAY_IN
        }
    }
}
