package io.github.mayueanyou.liferecorder.location

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
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
import io.github.mayueanyou.liferecorder.R
import io.github.mayueanyou.liferecorder.data.FixRow
import io.github.mayueanyou.liferecorder.data.LifeDb
import io.github.mayueanyou.liferecorder.ui.MainActivity
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
    private val dao by lazy { LifeDb.get(this).fixes() }

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val rows = result.locations.map { l ->
                FixRow(
                    time = l.time,
                    latitude = l.latitude,
                    longitude = l.longitude,
                    accuracy = if (l.hasAccuracy()) l.accuracy else null,
                    altitude = if (l.hasAltitude()) l.altitude else null,
                    speed = if (l.hasSpeed()) l.speed else null,
                    bearing = if (l.hasBearing()) l.bearing else null,
                    provider = l.provider,
                )
            }
            scope.launch { rows.forEach { dao.insert(it) } }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!Recording.hasForeground(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), type)
        startUpdates()
        // Restarted by Android if it ever has to stop the service for memory.
        return START_STICKY
    }

    private fun startUpdates() {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, INTERVAL_MILLIS)
            .setMinUpdateDistanceMeters(MIN_DISTANCE_M)
            .setMinUpdateIntervalMillis(FASTEST_MILLIS)
            .setWaitForAccurateLocation(false)
            .build()
        try {
            client.removeLocationUpdates(callback)
            client.requestLocationUpdates(request, callback, Looper.getMainLooper())
        } catch (e: SecurityException) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        client.removeLocationUpdates(callback)
        scope.cancel()
        super.onDestroy()
    }

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Recording", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown while Life Recorder is recording your location."
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
        private const val INTERVAL_MILLIS = 10_000L
        private const val FASTEST_MILLIS = 2_000L

        fun intent(context: Context) = Intent(context, LocationService::class.java)
    }
}
