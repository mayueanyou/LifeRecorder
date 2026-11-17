package io.github.mayueanyou.liferecorder.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert

/** One day's totals. Days are stored as ISO dates, "2026-11-17". */
@Entity(tableName = "screen_day", primaryKeys = ["day"])
data class ScreenDayRow(val day: String, val totalMillis: Long, val unlocks: Int, val savedAt: Long)

/** One app's time on one day. */
@Entity(tableName = "screen_app", primaryKeys = ["day", "packageName"])
data class ScreenAppRow(val day: String, val packageName: String, val label: String, val millis: Long)

@Dao
interface ScreenTimeDao {
    @Query("SELECT * FROM screen_day WHERE day = :day")
    suspend fun day(day: String): ScreenDayRow?

    @Query("SELECT * FROM screen_app WHERE day = :day ORDER BY millis DESC")
    suspend fun apps(day: String): List<ScreenAppRow>

    @Query("SELECT * FROM screen_day ORDER BY day DESC LIMIT :limit")
    suspend fun recentDays(limit: Int): List<ScreenDayRow>

    @Upsert suspend fun upsertDay(row: ScreenDayRow)
    @Upsert suspend fun upsertApps(rows: List<ScreenAppRow>)

    @Query("DELETE FROM screen_app WHERE day = :day")
    suspend fun clearApps(day: String)

    /** A day is always replaced whole, so an app that vanished from it does not linger. */
    @Transaction
    suspend fun replace(day: ScreenDayRow, apps: List<ScreenAppRow>) {
        upsertDay(day)
        clearApps(day.day)
        upsertApps(apps)
    }
}

/**
 * Everything Life Recorder keeps, on the phone only. Screen time is the first
 * table; places, journeys and costs will join it here.
 */
@Database(entities = [ScreenDayRow::class, ScreenAppRow::class], version = 1, exportSchema = false)
abstract class LifeDb : RoomDatabase() {
    abstract fun screenTime(): ScreenTimeDao

    companion object {
        @Volatile private var instance: LifeDb? = null

        fun get(context: Context): LifeDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, LifeDb::class.java, "life.db")
                .build().also { instance = it }
        }
    }
}
