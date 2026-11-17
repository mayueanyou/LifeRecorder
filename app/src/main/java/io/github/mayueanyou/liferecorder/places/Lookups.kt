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
 * Overpass is asked for named buildings and amenities around the point, and
 * the point tested against their outlines here -- so being *inside* a
 * building names it, not merely being near one. Nominatim is asked for the
 * street, once a second at most, as its usage policy requires.
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
    suspend fun fill(context: Context, buildings: List<Pair<Double, Double>>, streets: List<Pair<Double, Double>>): Int {
        if (!allowed(context)) return 0
        val dao = LifeDb.get(context).places()
        var answered = 0
        for ((lat, lon) in buildings.distinctBy { Places.key("building", it.first, it.second) }) {
            val result = runCatching { building(lat, lon) }.getOrNull() ?: continue   // failed: ask again later
            dao.upsertLookup(LookupRow(Places.key("building", lat, lon), "building", result.first, result.second, System.currentTimeMillis()))
            answered++
        }
        for ((lat, lon) in streets.distinctBy { Places.key("street", it.first, it.second) }) {
            val road = runCatching { street(lat, lon) }.getOrNull() ?: continue
            dao.upsertLookup(LookupRow(Places.key("street", lat, lon), "street", road.ifEmpty { null }, false, System.currentTimeMillis()))
            answered++
            delay(1100)
        }
        return answered
    }

    /** (name or null, exact). Null overall when the service could not answer. */
    private fun building(lat: Double, lon: Double): Pair<String?, Boolean>? {
        val query = "[out:json][timeout:25];(way(around:$SEARCH_M,$lat,$lon)[\"name\"][\"building\"];" +
            "way(around:$SEARCH_M,$lat,$lon)[\"name\"][\"amenity\"];);out geom tags;"
        val body = "data=" + URLEncoder.encode(query, "UTF-8")
        val text = http("https://overpass-api.de/api/interpreter", body) ?: return null
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
        val text = http(url, null) ?: return null
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

    private fun http(url: String, post: String?): String? {
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
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
