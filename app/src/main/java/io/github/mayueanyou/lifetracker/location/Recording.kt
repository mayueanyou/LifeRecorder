package io.github.mayueanyou.lifetracker.location

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.NotificationCompat
import io.github.mayueanyou.lifetracker.R
import io.github.mayueanyou.lifetracker.ui.MainActivity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import io.github.mayueanyou.lifetracker.Diagnostics

/** Whether recording should be on, and whether Android currently allows it. */
object Recording {
    private const val PREFS = "recording"
    private const val KEY_ON = "on"
    private const val KEY_LAST = "last_fix_at"
    private const val KEY_USER_OFF = "off_by_you"
    private const val ALERTS = "alerts"
    private const val STOPPED_ID = 2

    fun wanted(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ON, false)

    fun setWanted(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ON, on).apply()
    }

    /** When the last position arrived, by any route -- what the watchdog and the health line read. */
    fun lastFixAt(context: Context): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_LAST, 0L)

    fun noteFix(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(KEY_LAST, System.currentTimeMillis()).apply()
    }

    fun batteryUnrestricted(context: Context): Boolean =
        context.getSystemService(android.os.PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

    fun hasForeground(context: Context) = granted(context, Manifest.permission.ACCESS_FINE_LOCATION)

    /** "Allow all the time": needed to keep recording with the app closed, and to restart after a reboot. */
    fun hasBackground(context: Context) =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            granted(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION)

    fun hasNotifications(context: Context) =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            granted(context, Manifest.permission.POST_NOTIFICATIONS)

    /**
     * Never throws. Android can refuse to start the recorder from the
     * background (a battery-restricted app usually is refused); the delivered
     * route is set up first so positions still arrive, and the refusal is
     * logged for the diagnostics report.
     */
    /** Whether you turned recording off yourself; until you do, it is on whenever Android allows it. */
    fun turnedOffByYou(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // Turned off before this was kept: a fresh install has no choice stored at all.
        return prefs.getBoolean(KEY_USER_OFF, false) || (prefs.contains(KEY_ON) && !prefs.getBoolean(KEY_ON, false))
    }

    fun start(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_USER_OFF, false).apply()
        setWanted(context, true)
        runCatching { LocationService.startDelivery(context) }
            .onFailure { Diagnostics.note(context, "recorder: delivered route refused -- ${it.javaClass.simpleName}: ${it.message}") }
        runCatching { ContextCompat.startForegroundService(context, LocationService.intent(context)) }
            .onFailure {
                Diagnostics.note(context, "recorder: Android would not start it -- ${it.javaClass.simpleName}: ${it.message}")
                notifyStopped(context)
            }
    }

    /** Opening the app is always allowed to start the recorder, so that is what this asks for. */
    private fun notifyStopped(context: Context) {
        if (!hasNotifications(context)) return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(ALERTS, "Recording stopped", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "When Android stops recording and will not let it restart by itself."
                },
            )
        }
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        manager.notify(
            STOPPED_ID,
            NotificationCompat.Builder(context, ALERTS)
                .setSmallIcon(R.drawable.ic_stat_recording)
                .setContentTitle("Recording stopped")
                .setContentText("Android stopped it. Tap to resume — set Battery to Unrestricted to prevent this.")
                .setStyle(NotificationCompat.BigTextStyle()
                    .bigText("Android stopped it. Tap to resume — set Battery to Unrestricted to prevent this."))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build(),
        )
    }

    fun clearStopped(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(STOPPED_ID)
    }

    fun stop(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_USER_OFF, true).apply()
        setWanted(context, false)
        LocationService.stopDelivery(context)
        context.stopService(LocationService.intent(context))
    }

    private fun granted(context: Context, permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
