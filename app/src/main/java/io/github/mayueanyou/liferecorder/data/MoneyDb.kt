package io.github.mayueanyou.liferecorder.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert

/**
 * A purchase read from a notification: kept only when it reads as money
 * spent, so nothing else a notification says is stored. [time] is when the
 * notification was posted; that is what matches it to where you were.
 */
@Entity(tableName = "payment", indices = [Index("time")])
data class PaymentRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val time: Long,
    val pkg: String,
    val app: String,
    val title: String,
    val text: String,
    val amount: Double,
    val currency: String?,
    val merchant: String?,
    /** "purchase", or "maybe": an amount with nothing saying it was spent, kept a week for review. */
    @ColumnInfo(defaultValue = "purchase") val kind: String = KIND_PURCHASE,
    /** Your word on this one: 1 it is a purchase, -1 it is not, 0 not said. */
    @ColumnInfo(defaultValue = "0") val verdict: Int = 0,
) {
    companion object {
        const val KIND_PURCHASE = "purchase"
        const val KIND_MAYBE = "maybe"
    }
}

/**
 * "Count messages like this from this app" (count = true) or "never count
 * them" (false), where [pattern] is the notification's shape with its numbers
 * taken out -- see [io.github.mayueanyou.liferecorder.money.Reading.pattern].
 */
@Entity(tableName = "money_rule", primaryKeys = ["pkg", "pattern"])
data class MoneyRuleRow(val pkg: String, val pattern: String, val count: Boolean)

/**
 * A cost typed in for a stay or journey. It carries a moment inside that
 * entry rather than an id, because the day's entries are worked out afresh
 * each time: the item goes to whichever entry holds [at], just as a bank
 * charge goes to whichever stay holds its time. [cleared] marks "No cost" --
 * the entry ignores the bank's charges.
 */
@Entity(tableName = "cost_item", indices = [Index("at")])
data class CostItemRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val at: Long,
    val amount: Double,
    val currency: String,
    val what: String?,
    val cleared: Boolean = false,
)

@Dao
interface MoneyDao {
    @Insert suspend fun insertPayment(row: PaymentRow): Long

    @Query("SELECT * FROM payment WHERE time >= :from AND time < :until ORDER BY time")
    suspend fun payments(from: Long, until: Long): List<PaymentRow>

    /** The same notification seen again -- reposted, or read once more on reconnecting. */
    @Query("SELECT COUNT(*) FROM payment WHERE pkg = :pkg AND text = :text AND ABS(time - :time) < :within")
    suspend fun countSame(pkg: String, text: String, time: Long, within: Long): Int

    @Query("SELECT pkg, app, COUNT(*) AS count FROM payment WHERE kind = 'purchase' GROUP BY pkg ORDER BY count DESC")
    suspend fun apps(): List<MoneyApp>

    /** The last thing an app said that read as money -- shown in Settings, so a reading can be checked. */
    @Query("SELECT * FROM payment WHERE pkg = :pkg AND kind = 'purchase' ORDER BY time DESC LIMIT 1")
    suspend fun lastFrom(pkg: String): PaymentRow?

    /** Amounts from your money apps that did not read as a purchase, newest first, for review. */
    @Query("SELECT * FROM payment WHERE kind = 'maybe' AND verdict = 0 AND time >= :since ORDER BY time DESC")
    suspend fun review(since: Long): List<PaymentRow>

    @Query("DELETE FROM payment WHERE kind = 'maybe' AND verdict = 0 AND time < :before")
    suspend fun pruneReview(before: Long)

    @Query("UPDATE payment SET verdict = :verdict WHERE id = :id")
    suspend fun setVerdict(id: Long, verdict: Int)

    @Query("SELECT * FROM money_rule") suspend fun rules(): List<MoneyRuleRow>
    @Upsert suspend fun upsertRule(rule: MoneyRuleRow)
    @Query("DELETE FROM money_rule WHERE pkg = :pkg AND pattern = :pattern") suspend fun deleteRule(pkg: String, pattern: String)

    @Query("SELECT COUNT(*) FROM payment") suspend fun paymentCount(): Int

    @Insert suspend fun insertItem(row: CostItemRow): Long

    @Query("SELECT * FROM cost_item WHERE at >= :from AND at < :until ORDER BY at, id")
    suspend fun items(from: Long, until: Long): List<CostItemRow>

    @Query("DELETE FROM cost_item WHERE id = :id") suspend fun deleteItem(id: Long)

    @Query("SELECT MIN(time) FROM payment") suspend fun firstPayment(): Long?
    @Query("SELECT MIN(at) FROM cost_item") suspend fun firstItem(): Long?

    /** An item already here -- restoring the same backup twice adds nothing. */
    @Query("SELECT COUNT(*) FROM cost_item WHERE at = :at AND amount = :amount AND cleared = :cleared AND IFNULL(what, '') = IFNULL(:what, '')")
    suspend fun countItem(at: Long, amount: Double, what: String?, cleared: Boolean): Int

    @Query("DELETE FROM cost_item WHERE at >= :from AND at <= :until AND cleared = 1")
    suspend fun deleteCleared(from: Long, until: Long)
}

data class MoneyApp(val pkg: String, val app: String, val count: Int)
