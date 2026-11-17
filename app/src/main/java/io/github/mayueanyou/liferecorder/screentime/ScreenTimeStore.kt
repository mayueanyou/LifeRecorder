package io.github.mayueanyou.liferecorder.screentime

import android.content.Context
import io.github.mayueanyou.liferecorder.data.LifeDb
import io.github.mayueanyou.liferecorder.data.ScreenAppRow
import io.github.mayueanyou.liferecorder.data.ScreenDayRow
import java.time.LocalDate

/**
 * A day's screen time, from Android while it still has the detail, and from
 * the app's own copy after that.
 *
 * Android keeps per-app foreground events for roughly a week. Each time a day
 * is read live it is saved, and a background job saves the last few days
 * every few hours, so history outlasts Android's window.
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
            // An empty live read of an older day means Android no longer has
            // it -- keep whatever was saved rather than overwrite it.
            if (live.apps.isNotEmpty() || age == 0L) save(live)
            if (live.apps.isNotEmpty() || age == 0L) return live
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

    private suspend fun save(day: DayScreenTime) {
        val key = day.day.toString()
        dao.replace(
            ScreenDayRow(key, day.totalMillis, day.unlocks, System.currentTimeMillis()),
            day.apps.map { ScreenAppRow(key, it.packageName, it.label, it.millis) },
        )
    }

    private suspend fun saved(day: LocalDate): DayScreenTime? {
        val key = day.toString()
        val row = dao.day(key) ?: return null
        val apps = dao.apps(key).map { AppTime(it.packageName, it.label, it.millis) }
        return DayScreenTime(day, row.totalMillis, row.unlocks, apps)
    }

    companion object {
        private const val LIVE_DAYS = 6L
        private const val SNAPSHOT_DAYS = 3
    }
}
