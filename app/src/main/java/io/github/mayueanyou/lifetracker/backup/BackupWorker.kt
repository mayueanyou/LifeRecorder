package io.github.mayueanyou.lifetracker.backup

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.mayueanyou.lifetracker.Diagnostics
import java.time.Duration
import java.time.LocalTime
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/** Each night, at a random moment between 1 and 5 AM so that not every phone calls Drive at once. */
class BackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val context = applicationContext
        if (!GoogleAccount.signedIn(context)) return Result.success()
        val token = runCatching { GoogleAccount.silentToken(context) }.getOrNull()
        if (token == null) {
            // Google needs you to confirm again; Settings says so.
            Diagnostics.note(context, "backup: Google needs you to sign in again")
            context.getSharedPreferences("backup_files", Context.MODE_PRIVATE).edit()
                .putString("last_error", "Sign in again to keep backing up").apply()
            return Result.success()
        }
        return try {
            Backup.run(context, token)
            Result.success()
        } catch (e: Exception) {
            if (runAttemptCount < 3) Result.retry() else Result.success()
        }
    }

    companion object {
        private const val NAME = "drive-backup"

        fun schedule(context: Context) {
            val now = ZonedDateTime.now()
            var next = now.with(LocalTime.of(1, 0)).plusMinutes(Random.nextLong(0, 240))
            if (!next.isAfter(now)) next = next.plusDays(1)
            val request = PeriodicWorkRequestBuilder<BackupWorker>(24, TimeUnit.HOURS)
                .setInitialDelay(Duration.between(now, next).toMinutes(), TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context) = WorkManager.getInstance(context).cancelUniqueWork(NAME)
    }
}
