package com.example.aqi.live

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.Build

/**
 * Android 16 起的 Live Updates（promoted ongoing notification）。
 *
 * ## 为什么需要它
 * 系统 AOD 不开放第三方接口，唯一能进入息屏界面的是「被系统提升的常驻通知」——
 * 也就是截图里 Live Alerts 那一类（地图、打车、外卖）。普通常驻通知在 AOD 上
 * 只剩一个单色小图标，放不下数值。
 *
 * ## 一个必须承认的前提
 * Google 的 Live Updates 准则要求「用户发起 + 进行中 + 时间敏感」，
 * 并明确把 **ambient information（用户所处环境的信息）排除在外**，空气质量正在此列。
 * 也就是说：这不是权限问题，是这类信息本来不在系统的推荐通道里。
 *
 * 系统只校验通知的**结构**（样式、ongoing、标题、无 RemoteViews、渠道非 MIN），
 * 不校验语义，所以请求依旧可能被采纳 —— 但**各 ROM 可以额外加白名单**。
 * 因此本类不假设成功，只负责请求与查询，把真实结果交回界面显示。
 */
object LiveUpdate {

    /**
     * `Notification.EXTRA_REQUEST_PROMOTED_ONGOING` 的字面值。
     *
     * 该常量从 API 36 才有，compileSdk 35 下引用不到；而 key 只是一个字符串，
     * 旧版本系统读到不认识的 extra 会直接忽略。这里写字面量，避免为了一个
     * 布尔值把整个工程升到 compileSdk 36。
     */
    const val EXTRA_REQUEST_PROMOTED = "android.requestPromotedOngoing"

    /** 系统是否具备这套机制。低于 Android 16 时请求完全无效。 */
    val supported: Boolean get() = Build.VERSION.SDK_INT >= 36

    /**
     * 用户是否允许本应用发实时通知（系统设置里每一项单独控制）。
     * 未授权时即使结构完全合格也不会被提升。
     */
    fun canPost(ctx: Context): Boolean = try {
        val mgr = ctx.getSystemService(NotificationManager::class.java)
        NotificationManager::class.java
            .getMethod("canPostPromotedNotifications")
            .invoke(mgr) as Boolean
    } catch (t: Throwable) {
        // 低版本没有这个方法；返回 false 会让界面显示「不支持」，语义正确
        false
    }

    /**
     * 这条通知**实际**被提升了吗。
     *
     * 这是整条链路上唯一可信的结论：请求发了、权限也有，系统仍可能因为
     * 类别不符或 ROM 白名单而拒绝。只有读回 flag 才知道。
     */
    fun isPromoted(n: Notification): Boolean = try {
        val flag = Notification::class.java.getField("FLAG_PROMOTED_ONGOING").getInt(null)
        n.flags and flag != 0
    } catch (t: Throwable) {
        false
    }

    /**
     * 当前这条常驻通知的提升状态，三态：
     * `null` = 现在根本没有这条通知（AQI 优/良且通知时机为「仅污染时」）；
     * `false` = 有通知，但系统没提升；`true` = 已被提升。
     *
     * 区分 null 与 false 是必要的：前者不是失败，只是没到该显示的时候，
     * 混在一起会让用户以为系统拒绝了请求。
     */
    fun promotedState(ctx: Context, id: Int): Boolean? = try {
        val mgr = ctx.getSystemService(NotificationManager::class.java)
        val active = mgr.activeNotifications.firstOrNull { it.id == id } ?: return null
        isPromoted(active.notification)
    } catch (t: Throwable) {
        null
    }
}
