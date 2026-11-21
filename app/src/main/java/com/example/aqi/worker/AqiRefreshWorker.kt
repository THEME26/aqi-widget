package com.example.aqi.worker

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.example.aqi.data.AqiRepository
import com.example.aqi.render.RenderHub

/** 同步 Worker：doWork 已在后台线程，一次取坐标 + 一次 GET，完成后统一分发渲染 */
class AqiRefreshWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

    override fun doWork(): Result = try {
        val force = inputData.getBoolean(RefreshScheduler.KEY_FORCE, false)
        val state = AqiRepository(applicationContext).refresh(force)
        RenderHub.render(applicationContext, state)
        // AqiRepository 已把结果写进 AqiPrefs 同步状态；
        // 设置页通过 SharedPreferences 监听即时刷新，不依赖用户切后台再回来
        Result.success()
    } catch (e: Exception) {
        if (runAttemptCount < 3) Result.retry() else Result.failure()
    }
}
