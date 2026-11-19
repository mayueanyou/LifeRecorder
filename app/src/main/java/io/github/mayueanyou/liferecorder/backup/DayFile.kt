package io.github.mayueanyou.liferecorder.backup

import android.content.Context
import io.github.mayueanyou.liferecorder.data.CostItemRow
import io.github.mayueanyou.liferecorder.data.FixRow
import io.github.mayueanyou.liferecorder.data.LifeDb
import io.github.mayueanyou.liferecorder.data.PaymentRow
import io.github.mayueanyou.liferecorder.data.MoneyRuleRow
import io.github.mayueanyou.liferecorder.money.MoneyApps
import io.github.mayueanyou.liferecorder.places.Lookups
import io.github.mayueanyou.liferecorder.data.PlaceRow
import io.github.mayueanyou.liferecorder.data.ScreenAppRow
import io.github.mayueanyou.liferecorder.data.ScreenDayRow
import io.github.mayueanyou.liferecorder.data.ScreenSessionRow
import io.github.mayueanyou.liferecorder.engine.DayBuilder
import io.github.mayueanyou.liferecorder.engine.Entry
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * One day as a plain JSON file -- readable by anyone, in any editor, without
 * this app. The raw positions, purchases, typed costs and screen time are
 * what a restore reads back; the stays and journeys are written alongside so
 * the file makes sense on its own, and are worked out afresh after a restore.
 */
object DayFile {
    const val FORMAT = 1
    const val APP = "Life Tracker"
    // Files written before the app was renamed say "Life Recorder"; they restore all the same.
    private val KNOWN_APPS = setOf(APP, "Life Recorder")

    /** What decides whether a day changed since it was last written: everything a restore reads back. */
    suspend fun fingerprint(context: Context, day: LocalDate, zone: ZoneId = ZoneId.systemDefault()): String? {
        val db = LifeDb.get(context)
        val (from, until) = bounds(day, zone)
        val fixes = db.fixes().countBetween(from, until)
        val payments = db.money().payments(from, until).map { "${it.id}:${it.verdict}" }
        val items = db.money().items(from, until).map { it.id }
        val screen = db.screenTime().day(day.toString())
        if (fixes == 0 && payments.isEmpty() && items.isEmpty() && screen == null) return null
        return "$fixes|$payments|$items|${screen?.savedAt}"
    }

    suspend fun write(context: Context, day: LocalDate, zone: ZoneId = ZoneId.systemDefault()): String {
        val db = LifeDb.get(context)
        val (from, until) = bounds(day, zone)
        val record = DayBuilder.build(context, day, zone)
        val iso = { t: Long -> Instant.ofEpochMilli(t).atZone(zone).toOffsetDateTime().toString() }

        val positions = JSONArray()
        db.fixes().between(from, until).forEach { f ->
            positions.put(JSONObject().put("t", f.time).put("lat", f.latitude).put("lon", f.longitude)
                .putOpt("acc", f.accuracy).putOpt("alt", f.altitude).putOpt("speed", f.speed)
                .putOpt("bearing", f.bearing).putOpt("provider", f.provider))
        }
        val stays = JSONArray()
        val journeys = JSONArray()
        record.entries.forEachIndexed { i, e ->
            val cost = record.money.perEntry[i]?.total
            when (e) {
                is Entry.StayEntry -> stays.put(JSONObject().put("start", iso(e.start)).put("end", iso(e.end))
                    .put("place", e.stay.place).put("lat", e.stay.lat).put("lon", e.stay.lon).putOpt("cost", cost))
                is Entry.JourneyEntry -> journeys.put(JSONObject().put("start", iso(e.start)).put("end", iso(e.end))
                    .put("mode", e.journey.mode.name.lowercase()).put("from", e.journey.fromPlace).put("to", e.journey.toPlace)
                    .put("distance_m", Math.round(e.journey.distanceM)).putOpt("cost", cost))
            }
        }
        val purchases = JSONArray()
        db.money().payments(from, until).forEach { p ->
            purchases.put(JSONObject().put("t", p.time).put("app", p.app).put("package", p.pkg).put("title", p.title)
                .put("text", p.text).put("amount", p.amount).putOpt("currency", p.currency).putOpt("merchant", p.merchant)
                // Whether it read as a purchase, and your word on it -- "Not a purchase" survives a restore.
                .put("kind", p.kind).put("verdict", p.verdict))
        }
        val items = JSONArray()
        db.money().items(from, until).forEach { c ->
            items.put(JSONObject().put("at", c.at).put("amount", c.amount).put("currency", c.currency)
                .putOpt("what", c.what).put("no_cost", c.cleared))
        }
        val screen = db.screenTime().day(day.toString())?.let { d ->
            JSONObject().put("total_ms", d.totalMillis).put("unlocks", d.unlocks).put("saved_at", d.savedAt)
                .put("apps", JSONArray().also { a ->
                    db.screenTime().apps(day.toString()).forEach { app ->
                        a.put(JSONObject().put("package", app.packageName).put("label", app.label).put("ms", app.millis))
                    }
                })
                .put("sessions", JSONArray().also { a ->
                    db.screenTime().sessions(day.toString()).forEach { s ->
                        a.put(JSONObject().put("package", s.packageName).put("label", s.label)
                            .put("start", s.startAt).put("end", s.endAt))
                    }
                })
        }
        return JSONObject()
            .put("app", APP).put("format", FORMAT).put("date", day.toString()).put("time_zone", zone.id)
            .put("money", JSONObject().putOpt("total", record.money.total).putOpt("currency", record.money.currency)
                .put("purchases", purchases).put("items", items))
            .put("stays", stays).put("journeys", journeys)
            .putOpt("screen_time", screen)
            .put("positions", positions)
            .toString(1)
    }

    /** Adds what the file holds and the phone does not; nothing on the phone is replaced or removed. */
    suspend fun read(context: Context, text: String): Int {
        val json = JSONObject(text)
        if (json.optString("app") !in KNOWN_APPS) return 0
        val db = LifeDb.get(context)
        var added = 0
        json.optJSONArray("positions")?.let { a ->
            for (i in 0 until a.length()) {
                val f = a.getJSONObject(i)
                val t = f.getLong("t")
                if (db.fixes().countAt(t) > 0) continue
                db.fixes().insert(FixRow(time = t, latitude = f.getDouble("lat"), longitude = f.getDouble("lon"),
                    accuracy = f.optNullDouble("acc")?.toFloat(), altitude = f.optNullDouble("alt"),
                    speed = f.optNullDouble("speed")?.toFloat(), bearing = f.optNullDouble("bearing")?.toFloat(),
                    provider = f.optString("provider").ifEmpty { null }))
                added++
            }
        }
        json.optJSONObject("money")?.let { m ->
            m.optJSONArray("purchases")?.let { a ->
                for (i in 0 until a.length()) {
                    val p = a.getJSONObject(i)
                    val pkg = p.getString("package")
                    val txt = p.getString("text")
                    val t = p.getLong("t")
                    if (db.money().countSame(pkg, txt, t, 1) > 0) continue
                    db.money().insertPayment(PaymentRow(time = t, pkg = pkg, app = p.getString("app"), title = p.getString("title"),
                        text = txt, amount = p.getDouble("amount"), currency = p.optString("currency").ifEmpty { null },
                        merchant = p.optString("merchant").ifEmpty { null },
                        kind = p.optString("kind").ifEmpty { PaymentRow.KIND_PURCHASE }, verdict = p.optInt("verdict", 0)))
                    added++
                }
            }
            m.optJSONArray("items")?.let { a ->
                for (i in 0 until a.length()) {
                    val c = a.getJSONObject(i)
                    val what = c.optString("what").ifEmpty { null }
                    val cleared = c.optBoolean("no_cost")
                    if (db.money().countItem(c.getLong("at"), c.getDouble("amount"), what, cleared) > 0) continue
                    db.money().insertItem(CostItemRow(at = c.getLong("at"), amount = c.getDouble("amount"),
                        currency = c.getString("currency"), what = what, cleared = cleared))
                    added++
                }
            }
        }
        json.optJSONObject("screen_time")?.let { s ->
            val day = json.getString("date")
            val sessionsIn = s.optJSONArray("sessions") ?: JSONArray()
            val sessions = (0 until sessionsIn.length()).map { sessionsIn.getJSONObject(it) }.map {
                ScreenSessionRow(day, it.getString("package"), it.getString("label"), it.getLong("start"), it.getLong("end"))
            }
            if (db.screenTime().day(day) == null) {
                val apps = s.optJSONArray("apps") ?: JSONArray()
                db.screenTime().replace(
                    ScreenDayRow(day, s.getLong("total_ms"), s.getInt("unlocks"), s.optLong("saved_at")),
                    (0 until apps.length()).map { apps.getJSONObject(it) }
                        .map { ScreenAppRow(day, it.getString("package"), it.getString("label"), it.getLong("ms")) },
                    sessions,
                )
                added++
            } else if (sessions.isNotEmpty() && db.screenTime().sessions(day).isEmpty()) {
                db.screenTime().upsertSessions(sessions)
                added++
            }
        }
        return added
    }

    /**
     * Your money decisions, so a restore -- onto a new phone, or into a newer
     * app -- does not ask again or count again what you already settled:
     * which apps count, the rules you made, and whether names are looked up.
     */
    suspend fun writeSettings(context: Context): String {
        val rules = JSONArray()
        LifeDb.get(context).money().rules().forEach {
            rules.put(JSONObject().put("package", it.pkg).put("pattern", it.pattern).put("count", it.count))
        }
        return JSONObject().put("app", APP).put("format", FORMAT)
            .put("money_on", JSONArray(MoneyApps.on(context).toList()))
            .put("money_off", JSONArray(MoneyApps.off(context).toList()))
            .put("money_asked", JSONArray(MoneyApps.asked(context).toList()))
            .put("rules", rules)
            .put("look_up_names", Lookups.allowed(context))
            .toString(1)
    }

    /** Settles what this phone has not settled itself; a choice made here is never overridden. */
    suspend fun readSettings(context: Context, text: String): Int {
        val json = JSONObject(text)
        if (json.optString("app") !in KNOWN_APPS) return 0
        var added = 0
        fun list(key: String) = json.optJSONArray(key)?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
        val on = MoneyApps.on(context); val off = MoneyApps.off(context)
        list("money_on").filter { it !in on && it !in off }.forEach { MoneyApps.set(context, it, true); added++ }
        list("money_off").filter { it !in on && it !in off }.forEach { MoneyApps.set(context, it, false); added++ }
        MoneyApps.markAsked(context, list("money_asked"))
        val dao = LifeDb.get(context).money()
        val have = dao.rules().map { it.pkg to it.pattern }.toSet()
        json.optJSONArray("rules")?.let { a ->
            for (i in 0 until a.length()) {
                val r = a.getJSONObject(i)
                if ((r.getString("package") to r.getString("pattern")) in have) continue
                dao.upsertRule(MoneyRuleRow(r.getString("package"), r.getString("pattern"), r.getBoolean("count")))
                added++
            }
        }
        if (json.has("look_up_names")) Lookups.setAllowed(context, json.getBoolean("look_up_names"))
        return added
    }

    suspend fun writePlaces(context: Context): String {
        val places = JSONArray()
        LifeDb.get(context).places().places().forEach {
            places.put(JSONObject().put("name", it.name).put("lat", it.latitude).put("lon", it.longitude).put("radius", it.radius))
        }
        return JSONObject().put("app", APP).put("format", FORMAT).put("places", places).toString(1)
    }

    suspend fun readPlaces(context: Context, text: String): Int {
        val a = JSONObject(text).optJSONArray("places") ?: return 0
        val dao = LifeDb.get(context).places()
        val have = dao.places()
        var added = 0
        for (i in 0 until a.length()) {
            val p = a.getJSONObject(i)
            // Already here: the same name at the same spot.
            if (have.any { it.name == p.getString("name") &&
                    io.github.mayueanyou.liferecorder.engine.Geo.metres(it.latitude, it.longitude, p.getDouble("lat"), p.getDouble("lon")) < 50 }) continue
            dao.upsert(PlaceRow(p.getString("name"), p.getDouble("lat"), p.getDouble("lon"), p.getDouble("radius")))
            added++
        }
        return added
    }

    private fun bounds(day: LocalDate, zone: ZoneId) =
        day.atStartOfDay(zone).toInstant().toEpochMilli() to day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

    private fun JSONObject.optNullDouble(key: String): Double? = if (isNull(key)) null else optDouble(key)
}
