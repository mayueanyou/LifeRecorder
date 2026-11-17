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
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

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
@Database(
    entities = [ScreenDayRow::class, ScreenAppRow::class, FixRow::class, PlaceRow::class, LookupRow::class],
    version = 3, exportSchema = true,
)
abstract class LifeDb : RoomDatabase() {
    abstract fun screenTime(): ScreenTimeDao
    abstract fun fixes(): FixDao
    abstract fun places(): PlaceDao

    companion object {
        @Volatile private var instance: LifeDb? = null

        fun get(context: Context): LifeDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, LifeDb::class.java, "life.db")
                .addMigrations(ADD_FIXES, ADD_PLACES)
                .build().also { instance = it }
        }

        /** 0.1 -> 0.2: positions join screen time; nothing already saved is touched. */
        private val ADD_FIXES = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `fix` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`time` INTEGER NOT NULL, `latitude` REAL NOT NULL, `longitude` REAL NOT NULL, " +
                        "`accuracy` REAL, `altitude` REAL, `speed` REAL, `bearing` REAL, `provider` TEXT)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_fix_time` ON `fix` (`time`)")
            }
        }

        /** 0.2 -> 0.3: your named places, and names looked up for spots. */
        private val ADD_PLACES = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `place` (`name` TEXT NOT NULL, `latitude` REAL NOT NULL, " +
                        "`longitude` REAL NOT NULL, `radius` REAL NOT NULL, PRIMARY KEY(`name`))",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `lookup` (`key` TEXT NOT NULL, `kind` TEXT NOT NULL, " +
                        "`name` TEXT, `exact` INTEGER NOT NULL, `fetchedAt` INTEGER NOT NULL, PRIMARY KEY(`key`))",
                )
            }
        }
    }
}
