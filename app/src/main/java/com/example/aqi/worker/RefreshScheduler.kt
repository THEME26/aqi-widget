package com.example.aqi.worker

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
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
    private const val KEY_SPEC_V2 = "spec_v2"
    private const val HEAL_GAP_MS = 5 * 60_000L

    const val KEY_FORCE = "force"

    fun ensurePeriodic(ctx: Context) = enqueue(ctx, ExistingPeriodicWorkPolicy.KEEP)

    fun reschedule(ctx: Context) = enqueue(ctx, ExistingPeriodicWorkPolicy.UPDATE)

    fun ensureCurrentSpec(ctx: Context) {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (sp.getBoolean(KEY_SPEC_V2, false)) return
        enqueue(ctx, ExistingPeriodicWorkPolicy.UPDATE)
        sp.edit().putBoolean(KEY_SPEC_V2, true).apply()
    }

    private fun enqueue(ctx: Context, policy: ExistingPeriodicWorkPolicy) {
        val minutes = AqiPrefs(ctx).intervalMinutes.coerceAtLeast(15).toLong()
        val req = PeriodicWorkRequestBuilder<AqiRefreshWorker>(minutes, TimeUnit.MINUTES)
            .setConstraints(networkConstraint())
            .setBackoffCriteria(BackoffPolicy.LINEAR, 15, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(UNIQUE_PERIODIC, policy, req)
    }

    fun refreshNow(ctx: Context) = enqueueOnce(ctx, online = false)

    fun refreshWhenOnline(ctx: Context) = enqueueOnce(ctx, online = true)

    private fun enqueueOnce(ctx: Context, online: Boolean) {
        val b = OneTimeWorkRequestBuilder<AqiRefreshWorker>()
        if (online) b.setConstraints(networkConstraint())
        WorkManager.getInstance(ctx).enqueue(b.setInputData(workDataOf(KEY_FORCE to true)).build())
    }

    private fun networkConstraint(): Constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    fun selfHeal(ctx: Context): Boolean {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - sp.getLong(KEY_LAST_HEAL, 0L) < HEAL_GAP_MS) return false
        sp.edit().putLong(KEY_LAST_HEAL, now).apply()
        refreshWhenOnline(ctx)
        return true
    }

    fun cancelAll(ctx: Context) = WorkManager.getInstance(ctx).cancelUniqueWork(UNIQUE_PERIODIC)
}
