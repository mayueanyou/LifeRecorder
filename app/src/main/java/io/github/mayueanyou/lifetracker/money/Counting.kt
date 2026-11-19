package io.github.mayueanyou.lifetracker.money

import android.content.Context
import io.github.mayueanyou.lifetracker.data.LifeDb
import io.github.mayueanyou.lifetracker.data.MoneyRuleRow
import io.github.mayueanyou.lifetracker.data.PaymentRow
import kotlin.math.abs

/**
 * Which notifications count, and each purchase once.
 *
 * Your word on one notification comes first, then a rule you made for
 * messages like it, then whether its app counts. Then copies: one purchase
 * reported by the bank, the wallet that tapped it and the bank's alert email
 * is one purchase -- the same amount within half an hour from different apps
 * -- and the bank's copy is the one kept.
 */
class Judge(private val on: Set<String>, private val off: Set<String>, private val rules: List<MoneyRuleRow>) {
    private val byApp = rules.groupBy { it.pkg }

    fun rule(p: PaymentRow): MoneyRuleRow? =
        byApp[p.pkg]?.let { list -> Reading.pattern(p.title, p.text).let { pat -> list.firstOrNull { it.pattern == pat } } }

    fun counts(p: PaymentRow): Boolean {
        if (p.verdict == 1) return true
        if (p.verdict == -1) return false
        rule(p)?.let { return it.count }
        if (p.kind != PaymentRow.KIND_PURCHASE) return false
        return MoneyApps.counts(p.pkg, on, off)
    }

    companion object {
        suspend fun load(context: Context) =
            Judge(MoneyApps.on(context), MoneyApps.off(context), LifeDb.get(context).money().rules())
    }
}

object Copies {
    private const val WITHIN_MS = 30 * 60 * 1000L

    /** Each purchase once; and, per kept purchase, the other apps that reported it too. */
    fun once(payments: List<PaymentRow>): Pair<List<PaymentRow>, Map<Long, List<String>>> {
        val kept = ArrayList<PaymentRow>()
        val also = HashMap<Long, MutableList<String>>()
        val matched = HashSet<Pair<Long, String>>()     // a kept purchase takes one copy per other app
        for (p in payments.sortedWith(compareBy({ KnownApps.rank(it.pkg) }, { it.time }))) {
            val twin = kept.firstOrNull {
                it.pkg != p.pkg && (it.id to p.pkg) !in matched &&
                    abs(it.amount - p.amount) < 0.005 && abs(it.time - p.time) <= WITHIN_MS &&
                    (it.currency == null || p.currency == null || it.currency == p.currency)
            }
            if (twin != null) {
                matched += twin.id to p.pkg
                also.getOrPut(twin.id) { ArrayList() } += MoneyApps.name(p.pkg, p.app)
            } else {
                kept += p
            }
        }
        return kept.sortedBy { it.time } to also
    }
}

/** A stretch of time's purchases, as they count. */
object Purchases {
    data class Loaded(val payments: List<PaymentRow>, val also: Map<Long, List<String>>)

    suspend fun between(context: Context, from: Long, until: Long, judge: Judge? = null): Loaded {
        val j = judge ?: Judge.load(context)
        val (kept, also) = Copies.once(LifeDb.get(context).money().payments(from, until).filter { j.counts(it) })
        return Loaded(kept, also)
    }
}
