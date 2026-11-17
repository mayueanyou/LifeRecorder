package io.github.mayueanyou.liferecorder.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * One position, as the phone reported it. Times are epoch milliseconds;
 * accuracy is the phone's own radius of uncertainty in metres -- the same
 * figure the NUC's stay rules use to ignore rough readings.
 */
@Entity(tableName = "fix", indices = [Index("time")])
data class FixRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val time: Long,
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float?,
    val altitude: Double?,
    val speed: Float?,
    val bearing: Float?,
    val provider: String?,
)

@Dao
interface FixDao {
    @Insert suspend fun insert(fix: FixRow): Long

    @Query("SELECT * FROM fix WHERE time >= :from AND time < :until ORDER BY time")
    suspend fun between(from: Long, until: Long): List<FixRow>

    @Query("SELECT COUNT(*) FROM fix WHERE time >= :from AND time < :until")
    suspend fun countBetween(from: Long, until: Long): Int

    @Query("SELECT * FROM fix ORDER BY time DESC LIMIT :limit")
    suspend fun latest(limit: Int): List<FixRow>
}
