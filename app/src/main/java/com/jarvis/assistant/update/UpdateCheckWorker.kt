package com.jarvis.assistant.update

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * Periodic background update check (every 6 h, network-connected). Never blocks the UI and
 * never forces anything by itself — it only refreshes the cached manifest and posts a
 * notification; the force gate is enforced by the UI and the foreground service.
 */
class UpdateCheckWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = try {
        UpdateCoordinator.checkNow(applicationContext)
        Result.success()
    } catch (t: Throwable) {
        if (runAttemptCount < 3) Result.retry() else Result.success()
    }

    companion object {
        const val UNIQUE_PERIODIC = "jarvis-update-check-periodic"
        const val UNIQUE_ONE_TIME = "jarvis-update-check-once"
    }
}
