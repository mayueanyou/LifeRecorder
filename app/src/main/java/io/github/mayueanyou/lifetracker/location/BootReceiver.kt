package io.github.mayueanyou.lifetracker.location

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Picks recording back up after the phone restarts, if it was on. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        // Android only lets a location service start in the background with
        // "Allow all the time"; without it, recording resumes when the app is opened.
        if (Recording.wanted(context) && Recording.hasForeground(context) && Recording.hasBackground(context)) {
            Recording.start(context)
        }
    }
}
