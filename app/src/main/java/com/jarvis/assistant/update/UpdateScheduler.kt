package com.jarvis.assistant.update

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/** Schedules the updater's WorkManager jobs. Battery-friendly: connected-network constraint only. */
object UpdateScheduler {

    private const val PERIOD_HOURS = 6L

    private val constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    /** Enqueue the 6-hour periodic check (kept if already scheduled). */
    fun schedulePeriodic(context: Context) {
        val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(PERIOD_HOURS, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()
        runCatching {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UpdateCheckWorker.UNIQUE_PERIODIC,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }

    /** One immediate check (app resume / "Check now"). Replaces any pending one. */
    fun checkOnce(context: Context) {
        val request = OneTimeWorkRequestBuilder<UpdateCheckWorker>()
            .setConstraints(constraints)
            .build()
        runCatching {
            WorkManager.getInstance(context).enqueueUniqueWork(
                UpdateCheckWorker.UNIQUE_ONE_TIME,
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }
    }
}
