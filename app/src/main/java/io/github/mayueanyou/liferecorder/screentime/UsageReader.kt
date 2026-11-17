package io.github.mayueanyou.liferecorder.screentime

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Process
import java.time.LocalDate
import java.time.ZoneId

/** How long one app was on screen during a day. */
data class AppTime(val packageName: String, val label: String, val millis: Long)

/** A day's screen time: the total, the apps behind it, and how often the phone was unlocked. */
data class DayScreenTime(
    val day: LocalDate,
    val totalMillis: Long,
    val unlocks: Int,
    val apps: List<AppTime>,
)

/**
 * Screen time worked out from Android's own record of which app was in the
 * foreground, and when.
 *
 * The rule is the one Digital Wellbeing uses: an app's time runs from the
 * moment it comes to the front until something else does, or the screen goes
 * off or locks. The launcher is not counted -- looking at the home screen is
 * not using an app. A session that began before the day did is cut at
 * midnight, so each day holds only its own hours.
 *
 * Android keeps this detail for about a week; older days come from what
 * [ScreenTimeStore] saved while they were still available.
 */
class UsageReader(private val context: Context) {

    private val manager = context.getSystemService(UsageStatsManager::class.java)

    fun hasAccess(): Boolean {
        val ops = context.getSystemService(AppOpsManager::class.java)
        val mode = ops.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName,
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun read(day: LocalDate, zone: ZoneId = ZoneId.systemDefault()): DayScreenTime {
        val dayStart = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val dayEnd = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val now = System.currentTimeMillis()
        val until = minOf(dayEnd, now)
        if (until <= dayStart) return DayScreenTime(day, 0, 0, emptyList())

        // From a few hours before midnight, so whatever was already open at
        // the start of the day is known; its time is clipped to the day below.
        val events = manager.queryEvents(dayStart - LOOK_BACK_MILLIS, until)
        val excluded = launchers() + IGNORED
        val totals = HashMap<String, Long>()
        var unlocks = 0
        var current: String? = null
        var since = 0L

        fun close(at: Long) {
            val pkg = current ?: return
            val from = maxOf(since, dayStart)
            val to = minOf(at, until)
            if (to > from && pkg !in excluded) totals[pkg] = (totals[pkg] ?: 0L) + (to - from)
            current = null
        }

        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val t = event.timeStamp
            when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> {
                    if (current != event.packageName) {
                        close(t)
                        current = event.packageName
                        since = t
                    }
                }
                UsageEvents.Event.ACTIVITY_PAUSED,
                UsageEvents.Event.ACTIVITY_STOPPED -> if (current == event.packageName) close(t)
                UsageEvents.Event.SCREEN_NON_INTERACTIVE,
                UsageEvents.Event.KEYGUARD_SHOWN,
                UsageEvents.Event.DEVICE_SHUTDOWN -> close(t)
                UsageEvents.Event.KEYGUARD_HIDDEN -> if (t in dayStart until until) unlocks++
            }
        }
        // Still in use right now (today), or open when the day ended.
        close(until)

        val apps = totals.entries
            .filter { it.value >= MIN_MILLIS }
            .map { AppTime(it.key, labelOf(it.key), it.value) }
            .sortedByDescending { it.millis }
        return DayScreenTime(day, apps.sumOf { it.millis }, unlocks, apps)
    }

    private fun launchers(): Set<String> {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return context.packageManager.queryIntentActivities(home, 0)
            .map { it.activityInfo.packageName }.toSet()
    }

    private fun labelOf(packageName: String): String = try {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    } catch (e: Exception) {
        packageName.substringAfterLast('.').replaceFirstChar { it.uppercase() }
    }

    companion object {
        private const val LOOK_BACK_MILLIS = 6 * 60 * 60 * 1000L
        // Under a second is a flicker between apps, not use.
        private const val MIN_MILLIS = 1000L
        private val IGNORED = setOf("com.android.systemui", "android")
    }
}
