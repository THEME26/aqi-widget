package com.example.aqi.worker

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.example.aqi.data.AqiPrefs
import java.util.concurrent.TimeUnit

/**
 * 用 WorkManager 做 30/60/120 分钟周期刷新（最小 15 分钟）。
 * 刻意不设置 NetworkType.CONNECTED 约束：无网时也要跑一次，
 * 才能让「数据陈旧 / 暂时无法更新」这些异常态被渲染出来，而不是静默停在旧数值。
 */
object RefreshScheduler {

    private const val UNIQUE_PERIODIC = "aqi_periodic_refresh"

    /** 小部件自查节流的落盘位置（与缓存分开，缓存被清不影响节流） */
    private const val PREFS = "aqi_scheduler"
    private const val KEY_LAST_HEAL = "last_self_heal_at"
    private const val HEAL_GAP_MS = 5 * 60_000L

    /** 手动触发（保存 token、点立即刷新、授权完成）必须绕过位移/间隔去重 */
    const val KEY_FORCE = "force"

    fun ensurePeriodic(ctx: Context) = enqueue(ctx, ExistingPeriodicWorkPolicy.KEEP)

    /** 用户改了刷新间隔时调用，用 UPDATE 替换已有任务 */
    fun reschedule(ctx: Context) = enqueue(ctx, ExistingPeriodicWorkPolicy.UPDATE)

    private fun enqueue(ctx: Context, policy: ExistingPeriodicWorkPolicy) {
        val minutes = AqiPrefs(ctx).intervalMinutes.coerceAtLeast(15).toLong()
        val req = PeriodicWorkRequestBuilder<AqiRefreshWorker>(minutes, TimeUnit.MINUTES)
            .setBackoffCriteria(BackoffPolicy.LINEAR, 15, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(UNIQUE_PERIODIC, policy, req)
    }

    fun refreshNow(ctx: Context) {
        val req = OneTimeWorkRequestBuilder<AqiRefreshWorker>()
            .setInputData(workDataOf(KEY_FORCE to true))
            .build()
        WorkManager.getInstance(ctx).enqueue(req)
    }

    /**
     * 小部件自查补刷（v0.4.8）：**只在数据已陈旧/从未取到**时被调用。
     *
     * 为什么要这条路：`updatePeriodMillis` 让系统每 30 分钟叫一次 onUpdate，
     * 但 onUpdate 只会**重画**，不会取数。系统把 WorkManager 掐掉以后
     * （国产 ROM 的省电策略、Force stop、崩溃后重建失败），卡片会永远停在
     * 最后一次成功时的样子 —— 而且因为那时是新鲜的，连「陈旧」都不会出现。
     * 让 onUpdate 顺手补一次，就等于给后台刷新留了一条不依赖 WorkManager 的退路。
     *
     * 为什么要节流：四个尺寸各有自己的 AppWidgetProvider，同一时刻会连着收到
     * 4 次 onUpdate。`refreshNow` 带 `force=true`，会绕过 Repository 的位移/间隔去重，
     * 不去重就是 4 次真实网络请求。
     *
     * 5 分钟：比任何可设的刷新间隔（最小 15 分钟）都短，不会漏掉刚变陈旧的那一刻，
     * 又足以把 4 个 Provider 的连发吞掉。四个 onUpdate 都在主线程串行执行，
     * `apply()` 会同步更新内存里的值，所以第二个 Provider 一定读得到。
     */
    fun selfHeal(ctx: Context): Boolean {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - sp.getLong(KEY_LAST_HEAL, 0L) < HEAL_GAP_MS) return false
        sp.edit().putLong(KEY_LAST_HEAL, now).apply()
        refreshNow(ctx)
        return true
    }

    fun cancelAll(ctx: Context) = WorkManager.getInstance(ctx).cancelUniqueWork(UNIQUE_PERIODIC)
}
