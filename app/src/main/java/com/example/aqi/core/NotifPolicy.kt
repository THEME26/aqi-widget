package com.example.aqi.core

import androidx.annotation.StringRes
import com.example.aqi.R

enum class NotifPolicy(
    val id: String,
    @StringRes val labelRes: Int,
) {
    ALERT_ONLY("alert_only", R.string.notif_policy_alert_only),

    ALWAYS("always", R.string.notif_policy_always),

    NEVER("never", R.string.notif_policy_never),
    ;

    fun shouldNotify(value: Int): Boolean = when (this) {
        NEVER -> false
        ALWAYS -> true
        ALERT_ONLY -> value > 100
    }

    companion object {
        fun fromId(id: String?): NotifPolicy = entries.firstOrNull { it.id == id } ?: ALERT_ONLY
    }
}
