package com.example.aqi.worker

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.example.aqi.data.AqiRepository
import com.example.aqi.render.RenderHub

class AqiRefreshWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

    override fun doWork(): Result = try {
        val force = inputData.getBoolean(RefreshScheduler.KEY_FORCE, false)
        val state = AqiRepository(applicationContext).refresh(force)
        RenderHub.render(applicationContext, state)
        Result.success()
    } catch (e: Exception) {
        if (runAttemptCount < 3) Result.retry() else Result.failure()
    }
}
