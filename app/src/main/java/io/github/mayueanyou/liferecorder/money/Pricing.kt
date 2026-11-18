package io.github.mayueanyou.liferecorder.money

import io.github.mayueanyou.liferecorder.data.CostItemRow
import io.github.mayueanyou.liferecorder.data.PaymentRow
import io.github.mayueanyou.liferecorder.engine.Entry
import kotlin.math.abs

/** A bank charge as an entry shows it. [sameAsItem]: you typed it too, so it is not added twice. */
data class Charge(
    val payment: PaymentRow,
    val outsideByMin: Int = 0,
    val sameAsItem: Boolean = false,
    /** Other apps that reported the same purchase; counted once. */
    val alsoFrom: List<String> = emptyList(),
)

/** What one stay or journey cost: typed items plus the bank's charges. */
data class EntryMoney(
    val items: List<CostItemRow>,
    val charges: List<Charge>,
    val cleared: Boolean,
    val total: Double?,
    val currency: String?,
)

/** A day's money: per entry, what found no entry, and the total. */
data class DayMoney(
    val perEntry: Map<Int, EntryMoney>,
    val unplaced: List<Charge>,
    val total: Double?,
    val currency: String?,
    val mixed: Boolean,
) {
    companion object { val NONE = DayMoney(emptyMap(), emptyList(), null, null, false) }
}

/**
 * Gives each charge to the stay that was going on when it was made -- by time,
 * not by guessing from the merchant's name, which is what makes it work for a
 * charge that reads "SQ *BK 4417". The same rules as Portal's Record app:
 *
 * - A stay's recorded end is its last position, not the moment you walked
 *   out, so a charge up to 15 minutes either side still belongs to it; the
 *   closest stay in time wins.
 * - Stays only, when there is one to give it to: a charge during a drive was
 *   almost always made at one end of it. With no stay near, it goes to the
 *   entry under way at the time, and failing that it is still counted in the
 *   day, as not placed -- money is never silently dropped.
 * - A typed item and a bank charge of the same amount are one purchase.
 * - "No cost" on an entry ignores the bank's charges for it.
 */
object Pricing {
    private const val GRACE_MS = 15 * 60 * 1000L

    fun price(entries: List<Entry>, payments: List<PaymentRow>, items: List<CostItemRow>, now: Long,
              also: Map<Long, List<String>> = emptyMap()): DayMoney {
        if (payments.isEmpty() && items.isEmpty()) return DayMoney.NONE
        val charges = HashMap<Int, MutableList<Charge>>()
        val unplaced = ArrayList<Charge>()
        for (p in payments) {
            var best = -1
            var bestGap = Long.MAX_VALUE
            entries.forEachIndexed { i, e ->
                if (e !is Entry.StayEntry) return@forEachIndexed
                val end = if (e.stay.live) maxOf(e.end, now) else e.end
                val gap = when {
                    p.time < e.start -> e.start - p.time
                    p.time > end -> p.time - end
                    else -> 0L
                }
                if (gap <= GRACE_MS && gap < bestGap) { best = i; bestGap = gap }
            }
            if (best < 0) best = entries.indexOfFirst { p.time in it.start..it.end }
            val charge = Charge(p, outsideByMin = if (bestGap in 1..GRACE_MS) ((bestGap + 30_000) / 60_000).toInt() else 0,
                alsoFrom = also[p.id].orEmpty())
            if (best < 0) unplaced += charge else charges.getOrPut(best) { ArrayList() } += charge
        }

        val typed = HashMap<Int, MutableList<CostItemRow>>()
        val cleared = HashSet<Int>()
        val loose = ArrayList<CostItemRow>()
        for (item in items) {
            val i = entryAt(entries, item.at)
            when {
                i < 0 -> if (!item.cleared) loose += item
                item.cleared -> cleared += i
                else -> typed.getOrPut(i) { ArrayList() } += item
            }
        }

        val perEntry = HashMap<Int, EntryMoney>()
        for (i in (charges.keys + typed.keys + cleared)) {
            perEntry[i] = settle(typed[i].orEmpty(), charges[i].orEmpty(), i in cleared)
        }
        // A typed item whose entry has gone (the day was worked out differently) still counts.
        val looseMoney = settle(loose, unplaced, false)

        val parts = perEntry.values.mapNotNull { m -> m.total?.let { it to m.currency } } +
            listOfNotNull(looseMoney.total?.let { it to looseMoney.currency })
        val currencies = parts.mapNotNull { it.second }.toSet()
        return DayMoney(
            perEntry = perEntry,
            unplaced = looseMoney.charges,
            total = if (parts.isEmpty()) null else round2(parts.sumOf { it.first }),
            currency = currencies.singleOrNull(),
            mixed = currencies.size > 1,
        )
    }

    /** The entry an item belongs to: the one holding its moment, else the nearest. */
    fun entryAt(entries: List<Entry>, at: Long): Int {
        val inside = entries.indexOfFirst { at in it.start..it.end }
        if (inside >= 0) return inside
        val near = entries.withIndex().minByOrNull { (_, e) -> minOf(abs(e.start - at), abs(e.end - at)) } ?: return -1
        return if (minOf(abs(near.value.start - at), abs(near.value.end - at)) <= GRACE_MS) near.index else -1
    }

    private fun settle(items: List<CostItemRow>, charges: List<Charge>, cleared: Boolean): EntryMoney {
        val unmatched = items.toMutableList()
        val shown = charges.map { c ->
            val twin = unmatched.firstOrNull {
                abs(it.amount - c.payment.amount) < 0.005 && it.currency == (c.payment.currency ?: it.currency)
            }
            if (twin != null) { unmatched.remove(twin); c.copy(sameAsItem = true) } else c
        }
        // "No cost" still lists the bank's charges, so you can see what it ignores; it just does not add them.
        val parts = items.map { it.amount to it.currency } +
            if (cleared) emptyList() else shown.filter { !it.sameAsItem }.map { it.payment.amount to it.payment.currency }
        val currencies = parts.mapNotNull { it.second }.toSet()
        return EntryMoney(
            items = items,
            charges = shown,
            cleared = cleared,
            total = if (parts.isEmpty()) null else round2(parts.sumOf { it.first }),
            currency = currencies.singleOrNull(),
        )
    }

    private fun round2(v: Double) = Math.round(v * 100) / 100.0
}
