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

object RefreshScheduler {

    private const val UNIQUE_PERIODIC = "aqi_periodic_refresh"

    private const val PREFS = "aqi_scheduler"
    private const val KEY_LAST_HEAL = "last_self_heal_at"
    private const val HEAL_GAP_MS = 5 * 60_000L

    const val KEY_FORCE = "force"

    fun ensurePeriodic(ctx: Context) = enqueue(ctx, ExistingPeriodicWorkPolicy.KEEP)

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
