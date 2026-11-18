package io.github.mayueanyou.liferecorder.money

import io.github.mayueanyou.liferecorder.data.CostItemRow
import io.github.mayueanyou.liferecorder.data.PaymentRow
import io.github.mayueanyou.liferecorder.engine.Entry
import io.github.mayueanyou.liferecorder.engine.Journey
import io.github.mayueanyou.liferecorder.engine.Mode
import io.github.mayueanyou.liferecorder.engine.Stay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MoneyTest {
    @Test fun bankChargeIsRead() {
        val a = Reading.purchase("Credit card charge", "\$16.19 Credit card 1234 KFC G135340")!!
        assertEquals(16.19, a.value, 1e-9)
        assertEquals("USD", a.currency)
        assertEquals("KFC", Reading.merchant("BofA", "Credit card charge", "\$16.19 Credit card 1234 KFC G135340"))
    }

    @Test fun runTogetherTextIsRead() {
        // How the shade sometimes flattens title and text.
        val text = "Credit card charge\$45.15Credit card 1234COSTCO GAS "
        assertEquals(45.15, Reading.purchase("BofA", text)!!.value, 1e-9)
        assertEquals("Costco Gas", Reading.merchant("BofA", "BofA", text))
    }

    @Test fun thousandsAndStoreNumbers() {
        assertEquals(1234.5, Reading.purchase("Debit card purchase", "\$1,234.50 at SHOP #12")!!.value, 1e-9)
        assertEquals("Tops Markets", Reading.tidy("TOPS MARKETS #362"))
        assertEquals("Asia Food Market 2", Reading.tidy("ASIA FOOD MARKET 2"))
        assertEquals("KFC", Reading.merchant("Bank", "Purchase", "You spent \$16.19 at KFC on 11/17"))
    }

    @Test fun notSpending() {
        assertNull(Reading.purchase("BofA", "Payment of \$1,357.41 posted on 09/30/2026 for credit card 1234."))
        assertNull(Reading.purchase("Upgrade to a new phone from \$999", "Get \$100 off with trade-in."))
        assertNull(Reading.purchase("Weather", "62° and sunny, 3 alerts"))
        assertNull(Reading.purchase("BofA", "Your available balance is \$2,000.00"))
        assertNull(Reading.purchase("BofA", "Refund of \$20.00 to credit card 1234"))
        assertNull(Reading.purchase("Receipt", "Order PIZZA HUT 039665 confirmed"))      // no currency
    }

    private fun stay(start: Long, end: Long, live: Boolean = false) =
        Entry.StayEntry(Stay(start, end, 43.0, -76.1, 10, live, place = "Unknown place"))

    private fun drive(start: Long, end: Long) =
        Entry.JourneyEntry(Journey(start, end, 43.0, -76.1, 43.1, -76.2, Mode.DRIVING, 5000.0, emptyList()))

    private fun pay(time: Long, amount: Double, merchant: String? = "Shop") =
        PaymentRow(id = time, time = time, pkg = "bank", app = "Bank", title = "", text = "", amount = amount,
            currency = "USD", merchant = merchant)

    private val min = 60_000L

    @Test fun chargeGoesToTheStayAtThatTime() {
        val entries = listOf(stay(0, 30 * min), drive(30 * min, 50 * min), stay(50 * min, 90 * min))
        val m = Pricing.price(entries, listOf(pay(10 * min, 5.0), pay(60 * min, 7.5)), emptyList(), 100 * min)
        assertEquals(5.0, m.perEntry[0]!!.total!!, 1e-9)
        assertEquals(7.5, m.perEntry[2]!!.total!!, 1e-9)
        assertEquals(12.5, m.total!!, 1e-9)
    }

    @Test fun chargeDuringDriveGoesToTheNearerStay() {
        val entries = listOf(stay(0, 30 * min), drive(30 * min, 50 * min), stay(50 * min, 90 * min))
        val m = Pricing.price(entries, listOf(pay(33 * min, 9.0)), emptyList(), 100 * min)
        assertEquals(9.0, m.perEntry[0]!!.total!!, 1e-9)
        assertEquals(3, m.perEntry[0]!!.charges.single().outsideByMin)
    }

    @Test fun chargeFarFromAnyStayIsStillCounted() {
        val entries = listOf(stay(0, 10 * min), drive(10 * min, 120 * min))
        val m = Pricing.price(entries, listOf(pay(80 * min, 4.0)), emptyList(), 130 * min)
        assertEquals(4.0, m.perEntry[1]!!.total!!, 1e-9)          // the drive under way
        val none = Pricing.price(emptyList(), listOf(pay(80 * min, 4.0)), emptyList(), 130 * min)
        assertEquals(4.0, none.total!!, 1e-9)
        assertEquals(1, none.unplaced.size)
    }

    @Test fun typedTwinOfABankChargeCountsOnce() {
        val entries = listOf(stay(0, 60 * min))
        val items = listOf(CostItemRow(id = 1, at = 30 * min, amount = 12.0, currency = "USD", what = "Lunch"),
            CostItemRow(id = 2, at = 30 * min, amount = 3.0, currency = "USD", what = "Tip"))
        val m = Pricing.price(entries, listOf(pay(20 * min, 12.0)), items, 70 * min)
        assertEquals(15.0, m.perEntry[0]!!.total!!, 1e-9)
        assertTrue(m.perEntry[0]!!.charges.single().sameAsItem)
    }

    @Test fun noCostIgnoresTheBank() {
        val entries = listOf(stay(0, 60 * min), stay(70 * min, 120 * min))
        val items = listOf(CostItemRow(id = 1, at = 30 * min, amount = 0.0, currency = "USD", what = null, cleared = true))
        // A cleared stay still claims its charge, so it cannot drift to the next stay.
        val m = Pricing.price(entries, listOf(pay(55 * min, 8.0)), items, 130 * min)
        assertNull(m.perEntry[0]!!.total)
        assertTrue(m.perEntry[0]!!.cleared)
        assertEquals(1, m.perEntry[0]!!.charges.size)              // still listed, just not added
        assertNull(m.perEntry[1])
        assertNull(m.total)
    }
}
