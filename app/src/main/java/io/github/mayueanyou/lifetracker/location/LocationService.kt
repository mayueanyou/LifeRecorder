package io.github.mayueanyou.lifetracker.location

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import com.google.android.gms.location.LocationAvailability
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import io.github.mayueanyou.lifetracker.Diagnostics
import io.github.mayueanyou.lifetracker.R
import io.github.mayueanyou.lifetracker.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Records where the phone is, in the background, for as long as recording is on.
 *
 * Set up to match how the NUC is fed today: a new position after every 10 m
 * of movement at GPS accuracy, and nothing while the phone is still. Each
 * position goes straight into the database on the phone -- no network is
 * involved, so nothing is lost to a bad signal or a stuck VPN, the two things
 * that cost the NUC version whole evenings.
 *
 * A foreground service with its ongoing notification is what Android
 * requires for location in the background; it is also what keeps Doze from
 * holding the recording back.
 */
class LocationService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val locations = result.locations
            scope.launch {
                runCatching { FixSaver.save(this@LocationService, locations, "recorder") }
                    .onFailure { Diagnostics.note(this@LocationService, "recorder: could not save -- ${Diagnostics.trace(it)}") }
            }
        }

        override fun onLocationAvailability(availability: LocationAvailability) {
            Diagnostics.note(this@LocationService, "recorder: location ${if (availability.isLocationAvailable) "available" else "UNAVAILABLE"}")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!Recording.hasForeground(this)) {
            Diagnostics.note(this, "recorder: not started, location permission missing")
            stopSelf()
            return START_NOT_STICKY
        }
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), type)
        } catch (e: Exception) {
            // Android 14+ refuses a location service started from the
            // background without "Allow all the time".
            Diagnostics.note(this, "recorder: Android refused to start it -- ${e.javaClass.simpleName}: ${e.message}")
            stopSelf()
            return START_NOT_STICKY
        }
        startUpdates()
        if (!running) Diagnostics.note(this, "recorder: started")
        Recording.clearStopped(this)
        running = true
        // Restarted by Android if it ever has to stop the service for memory.
        return START_STICKY
    }

    private fun startUpdates() {
        try {
            client.removeLocationUpdates(callback)
            client.requestLocationUpdates(request(), callback, Looper.getMainLooper())
            startDelivery(this)
        } catch (e: SecurityException) {
            Diagnostics.note(this, "recorder: location updates refused -- ${e.message}")
            stopSelf()
        }
    }

    override fun onDestroy() {
        // The delivered route is left running on purpose: if Android stopped
        // this service, positions keep arriving. Only "off" removes it.
        Diagnostics.note(this, "recorder: stopped" + if (Recording.wanted(this)) " by Android (recording is still on)" else "")
        running = false
        client.removeLocationUpdates(callback)
        scope.cancel()
        super.onDestroy()
    }

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Recording", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown while Life Tracker is recording your location."
                    setShowBadge(false)
                },
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_recording)
            .setContentTitle("Recording your day")
            .setContentText("Location stays on this phone.")
            .setOngoing(true)
            .setContentIntent(open)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        private const val CHANNEL = "recording"
        private const val NOTIFICATION_ID = 1
        private const val MIN_DISTANCE_M = 10f
        // As often as every second while moving -- the 10 m minimum is what
        // keeps a still phone quiet. A 10-second interval left a car at city
        // speed with one position every ~170 m, far coarser than the NUC's feed.
        private const val INTERVAL_MILLIS = 2_000L
        private const val FASTEST_MILLIS = 1_000L

        /** Whether the recorder is running in this process -- false after Android has killed it. */
        @Volatile var running = false
            private set

        fun intent(context: Context) = Intent(context, LocationService::class.java)

        private fun request() = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, INTERVAL_MILLIS)
            .setMinUpdateDistanceMeters(MIN_DISTANCE_M)
            .setMinUpdateIntervalMillis(FASTEST_MILLIS)
            .setWaitForAccurateLocation(false)
            .build()

        // The second route: Android delivers to LocationReceiver even when
        // the service is stopped and Android will not let it start again.
        // Without the service it is throttled to a few positions an hour --
        // far from the full trace, but not an evening of nothing.
        private fun delivery(context: Context) = PendingIntent.getBroadcast(
            context, 1, Intent(context, LocationReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )

        /** Registering again replaces the earlier registration, so this is safe to repeat. */
        @SuppressLint("MissingPermission")
        fun startDelivery(context: Context) {
            if (!Recording.hasForeground(context)) return
            LocationServices.getFusedLocationProviderClient(context).requestLocationUpdates(request(), delivery(context))
        }

        /** Turning recording off stops the delivered route too. */
        fun stopDelivery(context: Context) {
            LocationServices.getFusedLocationProviderClient(context).removeLocationUpdates(delivery(context))
        }
    }
}
