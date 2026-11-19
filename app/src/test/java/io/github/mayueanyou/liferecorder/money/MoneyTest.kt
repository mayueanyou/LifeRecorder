package io.github.mayueanyou.liferecorder.money

import io.github.mayueanyou.liferecorder.data.CostItemRow
import io.github.mayueanyou.liferecorder.data.PaymentRow
import io.github.mayueanyou.liferecorder.data.MoneyRuleRow
import io.github.mayueanyou.liferecorder.engine.Entry
import io.github.mayueanyou.liferecorder.engine.Journey
import io.github.mayueanyou.liferecorder.engine.Mode
import io.github.mayueanyou.liferecorder.engine.Stay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test fun otherCountriesAndLanguages() {
        fun r(title: String, text: String, home: String = "USD") = Reading.read(title, text, home)
        r("CaixaBank", "Compra aprobada por 45,90 € en MERCADONA")!!.let {
            assertEquals(Reading.Kind.PURCHASE, it.kind); assertEquals(45.90, it.amount.value, 1e-9); assertEquals("EUR", it.amount.currency)
        }
        assertEquals("Mercadona", Reading.merchant("CaixaBank", "CaixaBank", "Compra aprobada por 45,90 € en MERCADONA"))
        r("Sparkasse", "Kartenzahlung 1.234,56 € bei REWE")!!.let { assertEquals(1234.56, it.amount.value, 1e-9) }
        r("Nubank", "Compra de R$ 89,90 aprovada")!!.let { assertEquals("BRL", it.amount.currency); assertEquals(89.90, it.amount.value, 1e-9) }
        r("HDFC Bank", "Rs. 500.00 debited from A/c XX1234 on 12-11-26 at AMAZON")!!.let {
            assertEquals("INR", it.amount.currency); assertEquals(500.0, it.amount.value, 1e-9)
        }
        assertEquals("Amazon", Reading.merchant("HDFC Bank", "HDFC Bank", "Rs. 500.00 debited from A/c XX1234 on 12-11-26 at AMAZON"))
        r("Alipay", "支付成功 ¥1,200")!!.let { assertEquals("CNY", it.amount.currency); assertEquals(1200.0, it.amount.value, 1e-9) }
        assertEquals("JPY", r("カード", "ご利用 ¥1,200", home = "JPY")!!.amount.currency)
        assertEquals("CAD", r("RBC", "Purchase of $12.00", home = "CAD")!!.amount.currency)
        // Not purchases, in any language.
        assertNull(r("Bank", "您的验证码是 123456，消费 ¥50"))
        assertNull(r("Bank", "Your OTP for txn of Rs 500 is 123456"))
        assertNull(r("Bank", "Saldo disponible: 1.234,00 €"))
        assertNull(r("Shop", "Your order of $45.00 has shipped"))
        assertNull(r("Bank", "Reembolso de 20,00 € recibido"))
    }

    @Test fun shopNamesFromBankTexts() {
        fun shop(text: String, title: String = "73981") = Reading.merchant("Messages", title, text, useTitle = false)
        assertEquals("Costco Gas", shop("BofA: Your credit card ending in 9999 was charged \$45.15 at COSTCO GAS on 11/18."))
        assertEquals("Starbucks", shop("Chase: You made a \$25.00 transaction with STARBUCKS on Nov 18, 2026 at 9:30 AM ET."))
        assertEquals("Starbucks", shop("\$12.40 @ STARBUCKS"))
        assertEquals("Amazon", shop("Rs 500.00 spent on card XX9999 at AMAZON on 18-11-26. Avl bal Rs 2000"))
        assertNull(shop("Alert: purchase of \$9.99 on card 9999"))          // no shop named -- and not the sender either
        // A wallet still names the shop from its title.
        assertEquals("McDonalds", Reading.merchant("Google Wallet", "McDonalds 10238", "\$10.00 with BofA Visa ••1234"))
    }

    @Test fun anAmountAtAShopIsAPurchase() {
        assertEquals(Reading.Kind.PURCHASE, Reading.read("Chase", "$25.00 at STARBUCKS")!!.kind)
        assertEquals(Reading.Kind.MAYBE, Reading.read("Chase", "Account alert: $25.00")!!.kind)
    }

    @Test fun shapeLeavesTheNumbersOut() {
        assertEquals(Reading.pattern("BofA", "Payment of $1,357.41 posted on 09/30/2026"),
            Reading.pattern("BofA", "Payment of $20.00 posted on 10/01/2026"))
    }

    private fun row(id: Long, pkg: String, time: Long, amount: Double, text: String = "Purchase $amount",
                    kind: String = PaymentRow.KIND_PURCHASE, verdict: Int = 0) =
        PaymentRow(id = id, time = time, pkg = pkg, app = pkg, title = "", text = text, amount = amount,
            currency = "USD", merchant = null, kind = kind, verdict = verdict)

    @Test fun onePurchaseFromSeveralAppsCountsOnce() {
        val bank = "com.infonow.bofa"
        val wallet = "com.google.android.apps.walletnfcrel"
        val (kept, also) = Copies.once(listOf(
            row(1, wallet, 0, 10.0), row(2, bank, 2 * min, 10.0),     // one purchase, two reports: the bank's kept
            row(3, bank, 3 * min, 5.0), row(4, bank, 4 * min, 5.0),   // two coffees from the bank: both
        ))
        assertEquals(listOf(2L, 3L, 4L), kept.map { it.id })
        assertEquals(listOf("Google Wallet"), also[2L])
    }

    @Test fun yourWordThenYourRulesThenTheApp() {
        val bank = "com.infonow.bofa"
        val shop = "com.example.shop"
        val posted = row(1, bank, 0, 50.0, text = "Payment of $50.00 posted")
        val judge = Judge(on = emptySet(), off = emptySet(),
            rules = listOf(MoneyRuleRow(bank, Reading.pattern("", "Payment of $50.00 posted"), false)))
        assertFalse(judge.counts(posted))                                  // your rule
        assertTrue(judge.counts(row(2, bank, 0, 9.0)))                     // a bank counts without asking
        assertFalse(judge.counts(row(3, shop, 0, 9.0)))                    // another app waits for a yes
        assertTrue(judge.counts(row(4, shop, 0, 9.0, verdict = 1)))        // ...unless you said this one is
        assertFalse(judge.counts(row(5, bank, 0, 9.0, kind = PaymentRow.KIND_MAYBE)))   // a maybe waits for review
        assertTrue(Judge(setOf(shop), emptySet(), emptyList()).counts(row(6, shop, 0, 9.0)))
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

    private fun walk(start: Long, end: Long) =
        Entry.JourneyEntry(Journey(start, end, 43.1, -76.2, 43.11, -76.21, Mode.WALKING, 400.0, emptyList()))

    @Test fun parkingPaidOnTheWayGoesToTheDrive() {
        // Home until 0:30, drive to school 0:30-0:50, school from 0:50.
        val entries = listOf(stay(0, 30 * min), drive(30 * min, 50 * min), stay(50 * min, 90 * min))
        val m = Pricing.price(entries, listOf(pay(45 * min, 8.0, "Parking")), emptyList(), 100 * min)
        assertEquals(8.0, m.perEntry[1]!!.total!!, 1e-9)
        assertNull(m.perEntry[0])
    }

    @Test fun parkingPaidJustAfterGettingOutGoesToTheDrive() {
        // Drive, then a walk from the car to the building.
        val entries = listOf(stay(0, 30 * min), drive(30 * min, 50 * min), walk(50 * min, 58 * min), stay(58 * min, 90 * min))
        val m = Pricing.price(entries, listOf(pay(52 * min, 8.0, "Parking")), emptyList(), 100 * min)
        assertEquals(8.0, m.perEntry[1]!!.total!!, 1e-9)
        // Later in the walk -- a coffee on the way in -- it is the walk's.
        val later = Pricing.price(entries, listOf(pay(57 * min, 4.0, "Coffee")), emptyList(), 100 * min)
        assertEquals(4.0, later.perEntry[2]!!.total!!, 1e-9)
    }

    @Test fun parkingPaidAtTheBuildingGoesToTheDrive() {
        // Home until 12:44, drive 12:44-12:51, school from 12:51; the city's parking charge at 12:53.
        val entries = listOf(stay(0, 44 * min), drive(44 * min, 51 * min), stay(51 * min, 120 * min))
        val parking = pay(53 * min, 4.0, "City Of Syracuse")
        assertEquals(4.0, Pricing.price(entries, listOf(parking), emptyList(), 130 * min).perEntry[1]!!.total!!, 1e-9)
        // A coffee at the same moment is the school's.
        val coffee = pay(53 * min, 4.0, "Starbucks")
        assertEquals(4.0, Pricing.price(entries, listOf(coffee), emptyList(), 130 * min).perEntry[2]!!.total!!, 1e-9)
    }

    @Test fun chargeDuringATripStillUnderWayGoesToTheTrip() {
        val liveDrive = Entry.JourneyEntry(Journey(30 * min, 40 * min, 43.0, -76.1, 43.1, -76.2, Mode.DRIVING, 5000.0,
            emptyList(), live = true))
        val m = Pricing.price(listOf(stay(0, 30 * min), liveDrive), listOf(pay(44 * min, 8.0)), emptyList(), 45 * min)
        assertEquals(8.0, m.perEntry[1]!!.total!!, 1e-9)
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
