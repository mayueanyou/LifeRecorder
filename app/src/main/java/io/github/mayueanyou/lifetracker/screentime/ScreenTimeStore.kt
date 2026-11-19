package io.github.mayueanyou.lifetracker.screentime

import android.content.Context
import io.github.mayueanyou.lifetracker.data.LifeDb
import io.github.mayueanyou.lifetracker.data.ScreenAppRow
import io.github.mayueanyou.lifetracker.data.ScreenDayRow
import io.github.mayueanyou.lifetracker.data.ScreenSessionRow
import java.time.LocalDate

/**
 * A day's screen time, from Android while it still has the detail, and from
 * the app's own copy after that.
 *
 * Android keeps per-app foreground events for roughly a week. Each time a day
 * is read live it is saved, and a background job saves the last week every
 * few hours, so history outlasts Android's window. Nothing saved is ever
 * deleted: every day stays on the phone for good.
 */
class ScreenTimeStore(context: Context) {
    private val reader = UsageReader(context)
    private val dao = LifeDb.get(context).screenTime()

    fun hasAccess() = reader.hasAccess()

    suspend fun day(day: LocalDate): DayScreenTime? {
        val age = LocalDate.now().toEpochDay() - day.toEpochDay()
        if (age < 0) return null
        if (age <= LIVE_DAYS && reader.hasAccess()) {
            val live = reader.read(day)
            if (age == 0L) { save(live); return live }
            if (live.apps.isNotEmpty()) save(live)
        }
        return saved(day)
    }

    /** Re-read the most recent days and save them. Run in the background. */
    suspend fun snapshot(days: Int = SNAPSHOT_DAYS) {
        if (!reader.hasAccess()) return
        val today = LocalDate.now()
        for (back in 0 until days) {
            val live = reader.read(today.minusDays(back.toLong()))
            if (live.apps.isNotEmpty() || back == 0) save(live)
        }
    }

    /**
     * Never makes a past day smaller. At the edge of its week Android forgets a
     * day from the morning on, and a fresh read then holds less than the copy
     * saved while it still had it all; the saved copy stands. (Today only ever
     * grows, so it is always written.)
     */
    private suspend fun save(day: DayScreenTime) {
        val key = day.day.toString()
        val kept = dao.day(key)
        if (kept != null && day.day != LocalDate.now() && day.totalMillis < kept.totalMillis) {
            // A day saved before sessions were kept still gains whatever sessions Android has left.
            if (day.sessions.isNotEmpty() && dao.sessions(key).isEmpty()) {
                dao.upsertSessions(day.sessions.map { ScreenSessionRow(key, it.packageName, it.label, it.start, it.end) })
            }
            return
        }
        dao.replace(
            ScreenDayRow(key, day.totalMillis, day.unlocks, System.currentTimeMillis()),
            day.apps.map { ScreenAppRow(key, it.packageName, it.label, it.millis) },
            day.sessions.map { ScreenSessionRow(key, it.packageName, it.label, it.start, it.end) },
        )
    }

    private suspend fun saved(day: LocalDate): DayScreenTime? {
        val key = day.toString()
        val row = dao.day(key) ?: return null
        val apps = dao.apps(key).map { AppTime(it.packageName, it.label, it.millis) }
        val sessions = dao.sessions(key).map { AppSession(it.packageName, it.label, it.startAt, it.endAt) }
        return DayScreenTime(day, row.totalMillis, row.unlocks, apps, sessions)
    }

    companion object {
        private const val LIVE_DAYS = 6L
        // The whole week Android still holds, so when each app was used is saved before it forgets.
        private const val SNAPSHOT_DAYS = 7
    }
}
