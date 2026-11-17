package io.github.mayueanyou.liferecorder.places

import android.content.Context
import io.github.mayueanyou.liferecorder.data.LifeDb
import io.github.mayueanyou.liferecorder.data.LookupRow
import io.github.mayueanyou.liferecorder.engine.Geo
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * The only part of the app that talks to the network, and only when the
 * user allows it: asks OpenStreetMap what a spot is called. Each spot is
 * asked about once; the answer is kept on the phone.
 *
 * A stay is named by the first of these that answers:
 *
 * 1. a named building the point is *inside* (Overpass, outlines tested here);
 * 2. the most likely named place within 150 m (Photon, which returns the
 *    nearest few at once) -- a shop or cafe before an office, and never a
 *    car park, bench or bin;
 * 3. the nearest address, "near 120 Main Street" (Nominatim).
 *
 * Only 1 counts as exact; the rest are marked approximate. Overpass is the
 * slowest and least reliable of the three, so it is given a short time and
 * skipped rather than waited on. Nominatim is asked once a second at most,
 * as its usage policy requires.
 */
object Lookups {
    private const val AGENT = "LifeRecorder/0.3 (Android; https://github.com/mayueanyou/LifeRecorder)"
    private const val SEARCH_M = 90
    private const val NEAR_M = 35.0

    fun allowed(context: Context) =
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean("lookups", true)

    fun setAllowed(context: Context, on: Boolean) =
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putBoolean("lookups", on).apply()

    /** Look up whatever is missing; returns how many were answered. */
    suspend fun fill(context: Context, spots: List<Pair<Double, Double>>, streets: List<Pair<Double, Double>>): Int {
        if (!allowed(context)) return 0
        val dao = LifeDb.get(context).places()
        var answered = 0
        for ((lat, lon) in spots.distinctBy { Places.key("spot", it.first, it.second) }) {
            val result = runCatching { spotName(lat, lon) }.getOrNull() ?: continue   // nothing answered: ask again later
            dao.upsertLookup(LookupRow(Places.key("spot", lat, lon), "spot", result.first, result.second, System.currentTimeMillis()))
            answered++
            delay(1100)
        }
        for ((lat, lon) in streets.distinctBy { Places.key("street", it.first, it.second) }) {
            val road = runCatching { street(lat, lon) }.getOrNull() ?: continue
            dao.upsertLookup(LookupRow(Places.key("street", lat, lon), "street", road.ifEmpty { null }, false, System.currentTimeMillis()))
            answered++
            delay(1100)
        }
        return answered
    }

    /** A spot's name and whether it is exact; null when no service could answer. */
    fun spotName(lat: Double, lon: Double): Pair<String?, Boolean>? {
        val inside = runCatching { building(lat, lon) }.getOrNull()
        if (inside?.second == true) return inside
        nearby(lat, lon)?.let { return it to false }
        val address = runCatching { address(lat, lon) }.getOrNull()
        return when {
            address != null -> address to false
            inside != null -> inside       // Overpass answered, with a nearby name or none
            else -> null
        }
    }

    // How likely a kind of place is to be where you stopped.
    private val SKIP = setOf(
        "parking", "parking_entrance", "parking_space", "motorcycle_parking", "bicycle_parking", "bench",
        "waste_basket", "waste_disposal", "recycling", "toilets", "post_box", "vending_machine", "atm",
        "telephone", "charging_station", "fountain", "drinking_water", "shelter", "bus_stop", "platform",
        "stop_position", "crossing", "traffic_signals", "clock",
    )
    private val STOP_FOR = setOf("restaurant", "cafe", "fast_food", "bar", "pub", "food_court", "ice_cream",
        "supermarket", "convenience", "greengrocer", "bakery", "mall", "department_store")

    private fun weight(key: String?, value: String?): Double = when {
        value in SKIP -> 0.0
        value in STOP_FOR -> 1.0
        key in setOf("amenity", "shop", "leisure", "tourism", "healthcare") -> 0.9
        key == "building" -> if (value == "university" || value == "college" || value == "school") 0.85 else 0.8
        key in setOf("office", "craft") -> 0.6
        else -> 0.0
    }

    /** The most likely named place within 150 m, from Photon's nearest few. */
    private fun nearby(lat: Double, lon: Double): String? {
        val text = http("https://photon.komoot.io/reverse?lat=$lat&lon=$lon&limit=15&radius=0.15&lang=en", null, 10_000)
            ?: return null
        val features = JSONObject(text).optJSONArray("features") ?: return null
        var best: Pair<Double, String>? = null
        for (i in 0 until features.length()) {
            val f = features.getJSONObject(i)
            val p = f.optJSONObject("properties") ?: continue
            val name = p.optString("name").takeIf { it.isNotBlank() } ?: continue
            val w = weight(p.optString("osm_key"), p.optString("osm_value"))
            if (w <= 0) continue
            val c = f.optJSONObject("geometry")?.optJSONArray("coordinates") ?: continue
            val away = Geo.metres(lat, lon, c.getDouble(1), c.getDouble(0))
            if (away > 150) continue
            val score = w / (1 + away / 30)
            if (best == null || score > best.first) best = score to name
        }
        return best?.second
    }

    /** "near 120 Main Street", or the name of the nearest object when it is a real place. */
    private fun address(lat: Double, lon: Double): String? {
        val text = http("https://nominatim.openstreetmap.org/reverse?format=jsonv2&zoom=18&addressdetails=1&lat=$lat&lon=$lon", null, 10_000)
            ?: return null
        val json = JSONObject(text)
        val name = json.optString("name").takeIf { it.isNotBlank() }
        if (name != null && json.optString("type") !in SKIP) return name
        val a = json.optJSONObject("address") ?: return null
        val street = listOf(a.optString("house_number"), a.optString("road")).filter { it.isNotBlank() }.joinToString(" ")
        return if (street.isNotBlank()) "near $street" else null
    }

    /** (name or null, exact). Null overall when the service could not answer. */
    private fun building(lat: Double, lon: Double): Pair<String?, Boolean>? {
        val query = "[out:json][timeout:25];(way(around:$SEARCH_M,$lat,$lon)[\"name\"][\"building\"];" +
            "way(around:$SEARCH_M,$lat,$lon)[\"name\"][\"amenity\"];);out geom tags;"
        val body = "data=" + URLEncoder.encode(query, "UTF-8")
        // Short: the public Overpass servers are often slow, and a name is
        // not worth holding everything else up for.
        val text = http("https://overpass-api.de/api/interpreter", body, 8_000) ?: return null
        val elements = JSONObject(text).optJSONArray("elements") ?: return null
        // An empty answer means the service was struggling, not that nothing is there.
        if (elements.length() == 0) return null
        var near: Pair<Double, String>? = null
        for (i in 0 until elements.length()) {
            val el = elements.getJSONObject(i)
            val name = el.optJSONObject("tags")?.optString("name")?.takeIf { it.isNotBlank() } ?: continue
            val geom = el.optJSONArray("geometry") ?: continue
            if (geom.length() < 3) continue
            val ring = (0 until geom.length()).map { geom.getJSONObject(it).let { p -> p.getDouble("lat") to p.getDouble("lon") } }
            if (inside(lat, lon, ring)) return name to true
            val away = ring.minOf { Geo.metres(lat, lon, it.first, it.second) }
            if (away <= NEAR_M && (near == null || away < near.first)) near = away to name
        }
        return (near?.second) to false
    }

    private fun street(lat: Double, lon: Double): String? {
        val url = "https://nominatim.openstreetmap.org/reverse?format=jsonv2&zoom=17&addressdetails=1&lat=$lat&lon=$lon"
        val text = http(url, null, 10_000) ?: return null
        return JSONObject(text).optJSONObject("address")?.optString("road") ?: ""
    }

    private fun inside(lat: Double, lon: Double, ring: List<Pair<Double, Double>>): Boolean {
        var inside = false
        for (i in ring.indices) {
            val (ay, ax) = ring[i]
            val (by, bx) = ring[(i + 1) % ring.size]
            if ((ay > lat) != (by > lat)) {
                val x = ax + (lat - ay) * (bx - ax) / ((by - ay).takeIf { it != 0.0 } ?: 1e-12)
                if (lon < x) inside = !inside
            }
        }
        return inside
    }

    private fun http(url: String, post: String?, timeoutMs: Int): String? {
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.setRequestProperty("User-Agent", AGENT)
            conn.setRequestProperty("Accept", "application/json")
            if (post != null) {
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.outputStream.use { it.write(post.toByteArray()) }
            }
            if (conn.responseCode !in 200..299) null else conn.inputStream.bufferedReader().readText()
        } finally {
            conn.disconnect()
        }
    }
}
