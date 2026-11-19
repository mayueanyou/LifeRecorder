package io.github.mayueanyou.lifetracker.screentime

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/** Saves the last few days of screen time every few hours, so history outlasts Android's week. */
class SnapshotWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        ScreenTimeStore(applicationContext).snapshot()
        return Result.success()
    }

    companion object {
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<SnapshotWorker>(3, TimeUnit.HOURS).build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork("screen-time-snapshot", ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
