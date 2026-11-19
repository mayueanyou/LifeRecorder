package io.github.mayueanyou.lifetracker.places

import android.content.Context
import io.github.mayueanyou.lifetracker.data.LifeDb
import io.github.mayueanyou.lifetracker.data.LookupRow
import io.github.mayueanyou.lifetracker.data.PlaceRow
import io.github.mayueanyou.lifetracker.engine.DayEngine
import io.github.mayueanyou.lifetracker.engine.Geo
import java.util.Locale

/**
 * Names for places, in the order the Record app uses them:
 *
 * 1. places you named yourself -- exact, instant, offline, and the only
 *    source that knows which building you mean;
 * 2. OpenStreetMap: a named building that contains the point, else the
 *    most likely named place within 150 m, else the nearest address --
 *    all but the first marked approximate;
 * 3. for where you got out of a car and walked on, the street.
 *
 * Lookups are done once per spot and kept (see [Lookups]); everything here
 * reads only what is already on the phone.
 */
class Places internal constructor(private val own: List<PlaceRow>, private val looked: Map<String, LookupRow>) {

    data class Name(val name: String, val source: String, val exact: Boolean)

    // Every lookup by kind, with the spot it was made for, so a name can be found by distance.
    private class Spot(val lat: Double, val lon: Double, val row: LookupRow)
    private val spots: Map<String, List<Spot>> = looked.values.mapNotNull { row ->
        val (lat, lon) = row.key.substringAfter(':').split(',').map { it.toDoubleOrNull() ?: return@mapNotNull null }
        row.kind to Spot(lat, lon, row)
    }.groupBy({ it.first }, { it.second })

    /**
     * The lookup for a spot: its own, else the nearest named one within
     * [SAME_PLACE_M]. A stay's centre drifts a few metres as positions come in
     * and the day is worked out again; keyed only by its exact centre, a
     * place that had a name lost it -- "Unknown place" until it was looked up
     * again, and sometimes then a different name. Once found, a name stays.
     */
    private fun near(kind: String, lat: Double, lon: Double): LookupRow? {
        val exact = looked[key(kind, lat, lon)]
        if (exact?.name != null) return exact
        return spots[kind].orEmpty().filter { it.row.name != null }
            .map { it to Geo.metres(lat, lon, it.lat, it.lon) }
            .filter { it.second <= SAME_PLACE_M }
            .minByOrNull { it.second }?.first?.row ?: exact
    }

    fun ownPlace(lat: Double, lon: Double): PlaceRow? =
        own.filter { Geo.metres(lat, lon, it.latitude, it.longitude) <= it.radius }
            .minByOrNull { Geo.metres(lat, lon, it.latitude, it.longitude) }

    /** What a spot was found to be called -- see [Lookups.spotName]. */
    fun building(lat: Double, lon: Double): LookupRow? = near("spot", lat, lon)
    fun street(lat: Double, lon: Double): LookupRow? = near("street", lat, lon)

    fun stayName(lat: Double, lon: Double): Name? {
        ownPlace(lat, lon)?.let { return Name(it.name, "yours", true) }
        building(lat, lon)?.name?.let { return Name(it, "osm", building(lat, lon)!!.exact) }
        return null
    }

    fun transferName(lat: Double, lon: Double): String? =
        ownPlace(lat, lon)?.name ?: street(lat, lon)?.name ?: building(lat, lon)?.takeIf { it.exact }?.name

    fun knowledge() = object : DayEngine.PlaceKnowledge {
        override fun own(lat: Double, lon: Double) = ownPlace(lat, lon)?.let { Triple(it.name, it.latitude, it.longitude) }
        override fun insideNamedBuilding(lat: Double, lon: Double) = building(lat, lon)?.let { it.exact && it.name != null } == true
    }

    /** Whether a spot still needs looking up. */
    fun missing(kind: String, lat: Double, lon: Double) =
        ownPlace(lat, lon) == null && key(kind, lat, lon) !in looked && near(kind, lat, lon)?.name == null

    companion object {
        suspend fun load(context: Context): Places {
            val dao = LifeDb.get(context).places()
            return Places(dao.places(), dao.lookups().associateBy { it.key })
        }

        /** How far a stay's centre may drift and still be the place already named. */
        const val SAME_PLACE_M = 50.0

        /** ~11 m of coordinate: standing still reuses one lookup. */
        fun key(kind: String, lat: Double, lon: Double) =
            "$kind:" + String.format(Locale.ROOT, "%.4f,%.4f", lat, lon)
    }
}
