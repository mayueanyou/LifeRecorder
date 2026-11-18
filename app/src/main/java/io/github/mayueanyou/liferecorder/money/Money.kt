package io.github.mayueanyou.liferecorder.money

import android.content.Context
import io.github.mayueanyou.liferecorder.data.LifeDb
import io.github.mayueanyou.liferecorder.engine.DayBuilder
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.Currency
import java.util.Locale

/** Money for the screen: amounts written out, and a month's days added up. */
object Money {
    fun format(amount: Double, currency: String?): String {
        val symbol = currency?.let { code ->
            runCatching { Currency.getInstance(code).getSymbol(Locale.getDefault()) }.getOrNull()
        }
        val figure = String.format(Locale.getDefault(), "%,.2f", amount)
        return when {
            symbol == null -> figure
            symbol.length == 1 || symbol.endsWith("$") -> "$symbol$figure"
            else -> "$symbol $figure"
        }
    }

    data class Month(val byDay: Map<LocalDate, Double>, val total: Double?, val currency: String?)

    // A past day's total does not change unless its money or its positions
    // do, so it is kept against a fingerprint of those and reused.
    private val totals = HashMap<LocalDate, Pair<String, Pair<Double?, String?>>>()

    suspend fun month(context: Context, month: YearMonth, zone: ZoneId = ZoneId.systemDefault()): Month {
        val db = LifeDb.get(context)
        val today = LocalDate.now(zone)
        val byDay = LinkedHashMap<LocalDate, Double>()
        val currencies = HashSet<String>()
        val judge = Judge.load(context)
        val version = MoneyApps.version(context)
        for (d in 1..month.lengthOfMonth()) {
            val day = month.atDay(d)
            if (day > today) break
            val from = day.atStartOfDay(zone).toInstant().toEpochMilli()
            val until = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val payments = Purchases.between(context, from, until, judge).payments
            val items = db.money().items(from, until)
            if (payments.isEmpty() && items.isEmpty()) continue     // nothing to add up: skip the day's replay
            val print = "$version|${payments.map { it.id }}|${items.map { it.id }}|${db.fixes().countBetween(from, until)}"
            val result = synchronized(totals) { totals[day]?.takeIf { it.first == print && day != today }?.second }
                ?: DayBuilder.build(context, day, zone, withScreen = false).money.let { it.total to it.currency }
                    .also { r -> synchronized(totals) { totals[day] = print to r } }
            result.first?.let { byDay[day] = it }
            result.second?.let { currencies += it }
        }
        return Month(byDay, if (byDay.isEmpty()) null else Math.round(byDay.values.sum() * 100) / 100.0,
            currencies.singleOrNull())
    }
}
