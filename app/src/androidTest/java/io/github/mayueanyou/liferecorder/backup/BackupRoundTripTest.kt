package io.github.mayueanyou.liferecorder.backup

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.mayueanyou.liferecorder.data.CostItemRow
import io.github.mayueanyou.liferecorder.data.FixRow
import io.github.mayueanyou.liferecorder.data.LifeDb
import io.github.mayueanyou.liferecorder.data.MoneyRuleRow
import io.github.mayueanyou.liferecorder.data.PaymentRow
import io.github.mayueanyou.liferecorder.data.PlaceRow
import io.github.mayueanyou.liferecorder.money.MoneyApps
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneId

/**
 * What a backup holds comes back exactly: positions, purchases with your word
 * on them, typed costs, places, and your money decisions and rules. This is
 * what moving to a new phone -- or to a newer app -- depends on.
 */
@RunWith(AndroidJUnit4::class)
class BackupRoundTripTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun everythingComesBack() = runBlocking {
        val db = LifeDb.get(context)
        db.clearAllTables()
        context.getSharedPreferences("money", 0).edit().clear().commit()
        val day = LocalDate.of(2026, 1, 15)
        val t0 = day.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() + 9 * 3600_000L
        repeat(5) { i -> db.fixes().insert(FixRow(time = t0 + i * 60_000, latitude = 47.6, longitude = -122.3, accuracy = 5f,
            altitude = null, speed = null, bearing = null, provider = "recorder")) }
        db.money().insertPayment(PaymentRow(time = t0 + 60_000, pkg = "com.infonow.bofa", app = "BofA", title = "Purchase",
            text = "You spent $9.99 at CAFE", amount = 9.99, currency = "USD", merchant = "Cafe", verdict = -1))
        db.money().insertPayment(PaymentRow(time = t0 + 120_000, pkg = "com.example.wallet", app = "Wallet", title = "",
            text = "Amount $3.00", amount = 3.0, currency = "USD", merchant = null, kind = PaymentRow.KIND_MAYBE))
        db.money().insertItem(CostItemRow(at = t0 + 90_000, amount = 2.5, currency = "USD", what = "Tip"))
        db.money().upsertRule(MoneyRuleRow("com.infonow.bofa", "payment of $# posted on", false))
        db.places().upsert(PlaceRow("Home", 47.6, -122.3, 60.0))
        MoneyApps.set(context, "com.example.wallet", true)
        MoneyApps.set(context, "com.example.shop", false)

        val dayFile = DayFile.write(context, day)
        val places = DayFile.writePlaces(context)
        val settings = DayFile.writeSettings(context)

        db.clearAllTables()
        context.getSharedPreferences("money", 0).edit().clear().commit()
        DayFile.readPlaces(context, places)
        DayFile.read(context, dayFile)
        DayFile.readSettings(context, settings)

        val from = t0 - 3600_000L
        assertEquals(5, db.fixes().countBetween(from, from + 24 * 3600_000L))
        val pays = db.money().payments(from, from + 24 * 3600_000L).sortedBy { it.time }
        assertEquals(listOf(-1, 0), pays.map { it.verdict })
        assertEquals(listOf(PaymentRow.KIND_PURCHASE, PaymentRow.KIND_MAYBE), pays.map { it.kind })
        assertEquals(1, db.money().items(from, from + 24 * 3600_000L).size)
        assertEquals(listOf("payment of $# posted on"), db.money().rules().map { it.pattern })
        assertEquals(listOf("Home"), db.places().places().map { it.name })
        assertTrue(MoneyApps.counts(context, "com.example.wallet"))
        assertFalse(MoneyApps.counts(context, "com.example.shop"))

        // Reading the same backup again adds nothing.
        DayFile.read(context, dayFile)
        assertEquals(2, db.money().payments(from, from + 24 * 3600_000L).size)
    }
}
