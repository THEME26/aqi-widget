package com.example.aqi.live

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.Build

object LiveUpdate {

    const val EXTRA_REQUEST_PROMOTED = "android.requestPromotedOngoing"

    val supported: Boolean get() = Build.VERSION.SDK_INT >= 36

    fun canPost(ctx: Context): Boolean = try {
        val mgr = ctx.getSystemService(NotificationManager::class.java)
        NotificationManager::class.java
            .getMethod("canPostPromotedNotifications")
            .invoke(mgr) as Boolean
    } catch (t: Throwable) {
        false
    }

    fun isPromoted(n: Notification): Boolean = try {
        val flag = Notification::class.java.getField("FLAG_PROMOTED_ONGOING").getInt(null)
        n.flags and flag != 0
    } catch (t: Throwable) {
        false
    }

    fun promotedState(ctx: Context, id: Int): Boolean? = try {
        val mgr = ctx.getSystemService(NotificationManager::class.java)
        val active = mgr.activeNotifications.firstOrNull { it.id == id } ?: return null
        isPromoted(active.notification)
    } catch (t: Throwable) {
        null
    }
}
