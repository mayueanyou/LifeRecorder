package io.github.mayueanyou.liferecorder.money

import io.github.mayueanyou.liferecorder.data.CostItemRow
import io.github.mayueanyou.liferecorder.data.PaymentRow
import io.github.mayueanyou.liferecorder.engine.Entry
import io.github.mayueanyou.liferecorder.engine.Mode
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
 * - What was going on when it was paid wins -- see [place]: a parking
 *   ticket paid on the way belongs to the drive. A charge near no entry is
 *   still counted in the day, as not placed -- money is never dropped.
 * - A typed item and a bank charge of the same amount are one purchase.
 * - "No cost" on an entry ignores the bank's charges for it.
 */
object Pricing {
    private const val GRACE_MS = 15 * 60 * 1000L
    private const val TILL_LAG_MS = 3 * 60 * 1000L
    private const val PARKED_MS = 5 * 60 * 1000L
    private const val TRAVEL_NEAR_MS = 10 * 60 * 1000L

    fun price(entries: List<Entry>, payments: List<PaymentRow>, items: List<CostItemRow>, now: Long,
              also: Map<Long, List<String>> = emptyMap()): DayMoney {
        if (payments.isEmpty() && items.isEmpty()) return DayMoney.NONE
        val charges = HashMap<Int, MutableList<Charge>>()
        val unplaced = ArrayList<Charge>()
        for (p in payments) {
            val (best, gap) = place(entries, p.time, now, travel = isTravel(p))
            val charge = Charge(p, outsideByMin = if (gap > 0) ((gap + 30_000) / 60_000).toInt() else 0,
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

    /**
     * Which entry a charge made at [t] belongs to, and how far outside it
     * that was. What was going on at the time wins:
     *
     * - during a stay, that stay;
     * - during a journey, that journey -- a parking ticket, a toll, a fare is
     *   paid on the way, and once went to the stay before it, the nearest
     *   stay in time. Two exceptions: a charge within [TILL_LAG_MS] of
     *   leaving a stay belongs to that stay (paid at the till, notified as you
     *   walked out); and one early in a walk straight after a drive belongs to
     *   the drive (paying for the parking just left);
     * - a journey still under way runs to now, so a charge just after
     *   parking lands on the drive before the app knows you have arrived;
     * - otherwise, the nearest stay within [GRACE_MS] -- a stay's recorded
     *   end is its last position, not when you walked out.
     */
    fun place(entries: List<Entry>, t: Long, now: Long, travel: Boolean = false): Pair<Int, Long> {
        val last = entries.lastIndex
        fun end(i: Int): Long {
            val e = entries[i]
            val live = (e as? Entry.StayEntry)?.stay?.live ?: (e as Entry.JourneyEntry).journey.live
            return if (live && i == last) maxOf(e.end, now) else e.end
        }
        // Parking, a toll, a fare: the drive's, if there is one near -- even
        // paid after parking, standing at the building.
        if (travel) {
            entries.indices.filter { (entries[it] as? Entry.JourneyEntry)?.journey?.mode == Mode.DRIVING }
                .map { it to (if (t < entries[it].start) entries[it].start - t else maxOf(0L, t - end(it))) }
                .filter { it.second <= TRAVEL_NEAR_MS }
                .minByOrNull { it.second }
                ?.let { return it.first to 0L }
        }
        entries.indices.firstOrNull { entries[it] is Entry.StayEntry && t in entries[it].start..end(it) }?.let { return it to 0L }
        entries.indices.firstOrNull { entries[it] is Entry.JourneyEntry && t in entries[it].start..end(it) }?.let { j ->
            val before = entries.getOrNull(j - 1)
            if (before is Entry.StayEntry && t - before.end in 0..TILL_LAG_MS) return (j - 1) to (t - before.end)
            val walk = (entries[j] as Entry.JourneyEntry).journey
            if (walk.mode == Mode.WALKING && before is Entry.JourneyEntry && before.journey.mode == Mode.DRIVING &&
                kotlin.math.abs(walk.start - before.end) <= 60_000 && t - walk.start <= PARKED_MS) return (j - 1) to 0L
            return j to 0L
        }
        var best = -1
        var bestGap = Long.MAX_VALUE
        entries.forEachIndexed { i, e ->
            if (e !is Entry.StayEntry) return@forEachIndexed
            val gap = if (t < e.start) e.start - t else t - end(i)
            if (gap <= GRACE_MS && gap < bestGap) { best = i; bestGap = gap }
        }
        return best to (if (best < 0) 0L else bestGap)
    }

    // What you pay for getting about rather than for being somewhere. Fuel is
    // not here: filling up is often a stop of its own, and the stay's.
    private val TRAVEL = Regex(
        "(?i)(?<![\\p{L}])(parking|parkmobile|park ?whiz|meter|toll|tolls|e-?z ?pass|sunpass|fastrak|i-?pass|passport ?parking|" +
            "flowbird|spothero|paybyphone|ringgo|justpark|city of|municipal|transit|metro ?card|" +
            "bus fare|fare|estacionamiento|parcheggio|parkhaus|parken|stationnement|p[ée]age|peaje|maut)(?![\\p{L}])",
    )

    fun isTravel(p: PaymentRow): Boolean =
        TRAVEL.containsMatchIn(listOfNotNull(p.merchant, p.title, p.text).joinToString(" "))

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
