package io.github.mayueanyou.liferecorder.location

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.android.gms.location.LocationResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Positions Android delivers to the app directly. Unlike the recorder's own
 * callback, these arrive even when the recorder's process has been stopped
 * or frozen -- Android starts the app just to hand them over.
 */
class LocationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val result = LocationResult.extractResult(intent) ?: return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                FixSaver.save(context.applicationContext, result.locations, "delivered")
            } finally {
                pending.finish()
            }
        }
    }
}
