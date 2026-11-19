package io.github.mayueanyou.lifetracker.location

import android.annotation.SuppressLint
import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import io.github.mayueanyou.lifetracker.Diagnostics
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * Every 15 minutes: if recording is on but nothing has arrived for a while,
 * take a position here and save it, then start the recorder again. At worst
 * that is a position every quarter of an hour -- not an evening lost because
 * Android stopped the recorder without a word.
 */
class Watchdog(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    @SuppressLint("MissingPermission")
    override suspend fun doWork(): Result {
        val context = applicationContext
        if (!Recording.wanted(context) || !Recording.hasForeground(context)) return Result.success()
        val quietFor = System.currentTimeMillis() - Recording.lastFixAt(context)
        if (quietFor < QUIET_MS) return Result.success()

        // Quiet with the recorder running is normal -- a still phone sends
        // nothing. A position now confirms that; a stopped recorder is restarted.
        val stopped = !LocationService.running
        if (stopped) Diagnostics.note(context, "watchdog: recorder not running, nothing for ${quietFor / 60_000} min -- restarting it")
        if (stopped) Recording.start(context)
        val client = LocationServices.getFusedLocationProviderClient(context)
        val location = withTimeoutOrNull(60_000) {
            suspendCancellableCoroutine<android.location.Location?> { cont ->
                client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                    .addOnSuccessListener { cont.resume(it) }
                    .addOnFailureListener { cont.resume(null) }
            }
        }
            // Android may not give a fresh position to an app in the background;
            // the last one it knows is still worth having if it is newer than ours.
            ?: runCatching {
                suspendCancellableCoroutine<android.location.Location?> { cont ->
                    client.lastLocation.addOnSuccessListener { cont.resume(it) }.addOnFailureListener { cont.resume(null) }
                }
            }.getOrNull()?.takeIf { it.time > Recording.lastFixAt(context) }
        if (location != null) FixSaver.save(context, listOf(location), "watchdog")
        else Diagnostics.note(context, "watchdog: Android gave no position (recorder ${if (stopped) "stopped" else "running"})")
        return Result.success()
    }

    companion object {
        private const val QUIET_MS = 20 * 60 * 1000L

        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "recording-watchdog", ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<Watchdog>(15, TimeUnit.MINUTES).build(),
            )
        }
    }
}
