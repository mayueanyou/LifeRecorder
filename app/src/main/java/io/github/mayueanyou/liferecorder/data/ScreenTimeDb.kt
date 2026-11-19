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

/** One stretch of one app on screen. Kept because Android forgets the detail after about a week. */
@Entity(tableName = "screen_session", primaryKeys = ["day", "packageName", "startAt"])
data class ScreenSessionRow(val day: String, val packageName: String, val label: String, val startAt: Long, val endAt: Long)

@Dao
interface ScreenTimeDao {
    @Query("SELECT * FROM screen_day WHERE day = :day")
    suspend fun day(day: String): ScreenDayRow?

    @Query("SELECT * FROM screen_app WHERE day = :day ORDER BY millis DESC")
    suspend fun apps(day: String): List<ScreenAppRow>

    @Query("SELECT MIN(day) FROM screen_day") suspend fun firstDay(): String?

    @Query("SELECT * FROM screen_day ORDER BY day DESC LIMIT :limit")
    suspend fun recentDays(limit: Int): List<ScreenDayRow>

    @Upsert suspend fun upsertDay(row: ScreenDayRow)
    @Upsert suspend fun upsertApps(rows: List<ScreenAppRow>)

    @Query("DELETE FROM screen_app WHERE day = :day")
    suspend fun clearApps(day: String)

    @Query("SELECT * FROM screen_session WHERE day = :day ORDER BY startAt")
    suspend fun sessions(day: String): List<ScreenSessionRow>

    @Upsert suspend fun upsertSessions(rows: List<ScreenSessionRow>)

    @Query("DELETE FROM screen_session WHERE day = :day")
    suspend fun clearSessions(day: String)

    /** A day is always replaced whole, so an app that vanished from it does not linger. */
    @Transaction
    suspend fun replace(day: ScreenDayRow, apps: List<ScreenAppRow>, sessions: List<ScreenSessionRow>? = null) {
        upsertDay(day)
        clearApps(day.day)
        upsertApps(apps)
        // A day saved before sessions were kept has none; it is left so rather than emptied.
        if (sessions != null) {
            clearSessions(day.day)
            upsertSessions(sessions)
        }
    }
}

/**
 * Everything Life Tracker keeps, on the phone only. Screen time is the first
 * table; places, journeys and costs will join it here.
 */
@Database(
    entities = [ScreenDayRow::class, ScreenAppRow::class, FixRow::class, PlaceRow::class, LookupRow::class,
        PaymentRow::class, CostItemRow::class, ScreenSessionRow::class, MoneyRuleRow::class],
    version = 7, exportSchema = true,
)
abstract class LifeDb : RoomDatabase() {
    abstract fun screenTime(): ScreenTimeDao
    abstract fun fixes(): FixDao
    abstract fun places(): PlaceDao
    abstract fun money(): MoneyDao

    companion object {
        @Volatile private var instance: LifeDb? = null

        fun get(context: Context): LifeDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, LifeDb::class.java, "life.db")
                .addMigrations(ADD_FIXES, ADD_PLACES, ADD_MONEY, ADD_SESSIONS, ADD_MONEY_REVIEW, PLACE_IDS)
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

        /** 0.3 -> 0.4: purchases read from notifications, and costs typed in. */
        private val ADD_MONEY = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `payment` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`time` INTEGER NOT NULL, `pkg` TEXT NOT NULL, `app` TEXT NOT NULL, `title` TEXT NOT NULL, " +
                        "`text` TEXT NOT NULL, `amount` REAL NOT NULL, `currency` TEXT, `merchant` TEXT)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_payment_time` ON `payment` (`time`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `cost_item` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`at` INTEGER NOT NULL, `amount` REAL NOT NULL, `currency` TEXT NOT NULL, `what` TEXT, " +
                        "`cleared` INTEGER NOT NULL)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_cost_item_at` ON `cost_item` (`at`)")
            }
        }

        /** 0.5 -> 0.6: when each app was used, not only for how long. */
        private val ADD_SESSIONS = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `screen_session` (`day` TEXT NOT NULL, `packageName` TEXT NOT NULL, " +
                        "`label` TEXT NOT NULL, `startAt` INTEGER NOT NULL, `endAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`day`, `packageName`, `startAt`))",
                )
            }
        }

        /** 0.9 -> 0.10: your places get ids, so two may share a name. Every place is kept. */
        private val PLACE_IDS = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `place_new` (`name` TEXT NOT NULL, `latitude` REAL NOT NULL, " +
                        "`longitude` REAL NOT NULL, `radius` REAL NOT NULL, `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL)",
                )
                db.execSQL("INSERT INTO `place_new` (`name`, `latitude`, `longitude`, `radius`) SELECT `name`, `latitude`, `longitude`, `radius` FROM `place`")
                db.execSQL("DROP TABLE `place`")
                db.execSQL("ALTER TABLE `place_new` RENAME TO `place`")
            }
        }

        /** 0.7 -> 0.8: purchases you confirm or reject, amounts to review, and your rules per app. */
        private val ADD_MONEY_REVIEW = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `payment` ADD COLUMN `kind` TEXT NOT NULL DEFAULT 'purchase'")
                db.execSQL("ALTER TABLE `payment` ADD COLUMN `verdict` INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `money_rule` (`pkg` TEXT NOT NULL, `pattern` TEXT NOT NULL, " +
                        "`count` INTEGER NOT NULL, PRIMARY KEY(`pkg`, `pattern`))",
                )
            }
        }
    }
}
