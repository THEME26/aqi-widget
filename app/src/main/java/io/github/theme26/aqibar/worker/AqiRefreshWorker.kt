package io.github.theme26.aqibar.worker

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import io.github.theme26.aqibar.core.Freshness
import io.github.theme26.aqibar.data.AqiPrefs
import io.github.theme26.aqibar.data.AqiRepository
import io.github.theme26.aqibar.render.RenderHub

class AqiRefreshWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

    override fun doWork(): Result = try {
        val force = inputData.getBoolean(RefreshScheduler.KEY_FORCE, false)
        val prefs = AqiPrefs(applicationContext)
        val state = AqiRepository(applicationContext).refresh(force)
        RenderHub.render(applicationContext, state)
        when {
            state.freshness == Freshness.NO_PERMISSION -> Result.success()
            state.freshness == Freshness.NO_TOKEN -> Result.success()
            state.freshness == Freshness.NO_LOCATION -> Result.success()
            prefs.lastSyncOk -> Result.success()
            runAttemptCount < MAX_ATTEMPTS -> Result.retry()
            else -> Result.success()
        }
    } catch (e: Exception) {
        if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.success()
    }

    private companion object {
        const val MAX_ATTEMPTS = 3
    }
}
