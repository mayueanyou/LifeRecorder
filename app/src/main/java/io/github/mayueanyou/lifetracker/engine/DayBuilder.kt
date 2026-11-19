package io.github.mayueanyou.lifetracker.engine

import android.content.Context
import io.github.mayueanyou.lifetracker.data.LifeDb
import io.github.mayueanyou.lifetracker.money.DayMoney
import io.github.mayueanyou.lifetracker.money.Purchases
import io.github.mayueanyou.lifetracker.money.Pricing
import io.github.mayueanyou.lifetracker.places.Places
import io.github.mayueanyou.lifetracker.screentime.AppSession
import io.github.mayueanyou.lifetracker.screentime.ScreenTimeStore
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** A day, ready to show: its entries in time order, and the spots still waiting for a name. */
class DayRecord(
    val day: LocalDate,
    val entries: List<Entry>,
    val missingBuildings: List<Pair<Double, Double>>,
    val missingStreets: List<Pair<Double, Double>>,
    val lastFix: Fix?,
    val money: DayMoney = DayMoney.NONE,
    /** Per entry, the apps on screen during it, each cut to the entry's own time. */
    val screen: Map<Int, List<AppSession>> = emptyMap(),
)

/**
 * Builds one day from the positions on the phone.
 *
 * The replay starts the evening before and runs a few hours past midnight, so
 * whatever was going on at either end of the day is known; the result is then
 * cut to the day itself. A piece under a minute either side of midnight is
 * dropped -- a drive that ended nine seconds after midnight is not a record
 * of the next day.
 */
object DayBuilder {
    private const val BEFORE_MS = 18 * 60 * 60 * 1000L
    private const val AFTER_MS = 6 * 60 * 60 * 1000L
    private const val MIN_PIECE_MS = 60 * 1000L

    /** [withScreen] false skips reading screen time -- for adding up money across a month. */
    suspend fun build(context: Context, day: LocalDate, zone: ZoneId = ZoneId.systemDefault(), withScreen: Boolean = true): DayRecord {
        val dayStart = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val dayEnd = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val now = System.currentTimeMillis()
        val until = min(now, dayEnd + AFTER_MS)
        val fixes = LifeDb.get(context).fixes().between(dayStart - BEFORE_MS, until)
            .map { Fix(it.time, it.latitude, it.longitude, it.accuracy) }
        val places = Places.load(context)
        val result = DayEngine(places.knowledge()).run(fixes, now = until)
        val today = until == now

        val stays = result.stays.mapNotNull { s ->
            val a = max(s.start, dayStart)
            val b = min(s.end, dayEnd)
            if (b - a < MIN_PIECE_MS) null else s.copy(start = a, end = b, live = s.live && today && b == s.end)
        }
        val journeys = result.journeys.mapNotNull { j ->
            val a = max(j.start, dayStart)
            val b = min(j.end, dayEnd)
            if (b - a < MIN_PIECE_MS) return@mapNotNull null
            val route = if (a == j.start && b == j.end) j.route else j.route.filter { it.time in a..b }
            val distance = if (route === j.route) j.distanceM else Geo.length(route)
            j.copy(start = a, end = b, route = route.ifEmpty { j.route }, distanceM = distance,
                live = j.live && today && b == j.end)
        }

        // Names: a stay by what is there; a journey by the stays it joins, or,
        // where it met another journey, by the street.
        val missingBuildings = ArrayList<Pair<Double, Double>>()
        val missingStreets = ArrayList<Pair<Double, Double>>()
        for (s in stays) {
            val found = places.stayName(s.lat, s.lon)
            s.place = found?.name ?: "Unknown place"
            s.placeSource = found?.source
            s.exact = found?.exact == true
            if (places.missing("spot", s.lat, s.lon)) missingBuildings += s.lat to s.lon
        }
        for (j in journeys) {
            j.fromPlace = endName(places, stays, j.fromTransfer, j.fromLat, j.fromLon, j.start, atStart = true, missingStreets)
            j.toPlace = endName(places, stays, j.toTransfer, j.toLat, j.toLon, j.end, atStart = false, missingStreets)
        }

        val entries = (stays.map { Entry.StayEntry(it) } + journeys.map { Entry.JourneyEntry(it) })
            .sortedWith(compareBy<Entry>({ it.start }, { it.end }))

        val moneyDao = LifeDb.get(context).money()
        val counted = Purchases.between(context, dayStart, dayEnd)
        val money = Pricing.price(entries, counted.payments, moneyDao.items(dayStart, dayEnd), now, counted.also)
        // A shop the map cannot name is still named on the card -- but only by a
        // charge made inside the stay: one matched in the minutes after it may
        // be from the next shop you walked to.
        money.perEntry.forEach { (i, m) ->
            val stay = (entries[i] as? Entry.StayEntry)?.stay ?: return@forEach
            if (stay.place != "Unknown place") return@forEach
            m.charges.firstOrNull { it.outsideByMin == 0 && it.payment.merchant != null }?.let {
                stay.place = it.payment.merchant
                stay.placeSource = "card"
                stay.exact = true
            }
        }
        val sessions = if (!withScreen) emptyList()
            else runCatching { ScreenTimeStore(context).day(day)?.sessions }.getOrNull().orEmpty()
        return DayRecord(day, entries, missingBuildings, missingStreets, fixes.lastOrNull(), money, screenDuring(entries, sessions))
    }

    /** Each app session given to the entries it overlaps, cut to each one's time -- a sitting across a departure is split. */
    private fun screenDuring(entries: List<Entry>, sessions: List<AppSession>): Map<Int, List<AppSession>> {
        if (sessions.isEmpty()) return emptyMap()
        val out = HashMap<Int, List<AppSession>>()
        entries.forEachIndexed { i, e ->
            val during = sessions.mapNotNull { s ->
                val a = max(s.start, e.start)
                val b = min(s.end, e.end)
                if (b - a >= 1000) s.copy(start = a, end = b) else null
            }
            if (during.isNotEmpty()) out[i] = during
        }
        return out
    }

    private fun endName(places: Places, stays: List<Stay>, transfer: Boolean, lat: Double, lon: Double,
                        time: Long, atStart: Boolean, missing: MutableList<Pair<Double, Double>>): String {
        if (transfer) {
            places.transferName(lat, lon)?.let { return it }
            if (places.missing("street", lat, lon)) missing += lat to lon
            return "Unknown place"
        }
        val stay = stays.firstOrNull { abs((if (atStart) it.end else it.start) - time) <= 1000 }
        return stay?.place ?: places.stayName(lat, lon)?.name ?: "Unknown place"
    }
}
