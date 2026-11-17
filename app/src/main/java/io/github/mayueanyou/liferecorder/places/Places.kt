package io.github.mayueanyou.liferecorder.places

import android.content.Context
import io.github.mayueanyou.liferecorder.data.LifeDb
import io.github.mayueanyou.liferecorder.data.LookupRow
import io.github.mayueanyou.liferecorder.data.PlaceRow
import io.github.mayueanyou.liferecorder.engine.DayEngine
import io.github.mayueanyou.liferecorder.engine.Geo
import java.util.Locale

/**
 * Names for places, in the order the Record app uses them:
 *
 * 1. places you named yourself -- exact, instant, offline, and the only
 *    source that knows which building you mean;
 * 2. OpenStreetMap: a named building that contains the point, else the
 *    nearest name within a short distance (marked approximate);
 * 3. for where you got out of a car and walked on, the street.
 *
 * Lookups are done once per spot and kept (see [Lookups]); everything here
 * reads only what is already on the phone.
 */
class Places private constructor(private val own: List<PlaceRow>, private val looked: Map<String, LookupRow>) {

    data class Name(val name: String, val source: String, val exact: Boolean)

    fun ownPlace(lat: Double, lon: Double): PlaceRow? =
        own.filter { Geo.metres(lat, lon, it.latitude, it.longitude) <= it.radius }
            .minByOrNull { Geo.metres(lat, lon, it.latitude, it.longitude) }

    fun building(lat: Double, lon: Double): LookupRow? = looked[key("building", lat, lon)]
    fun street(lat: Double, lon: Double): LookupRow? = looked[key("street", lat, lon)]

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
        ownPlace(lat, lon) == null && key(kind, lat, lon) !in looked

    companion object {
        suspend fun load(context: Context): Places {
            val dao = LifeDb.get(context).places()
            return Places(dao.places(), dao.lookups().associateBy { it.key })
        }

        /** ~11 m of coordinate: standing still reuses one lookup. */
        fun key(kind: String, lat: Double, lon: Double) =
            "$kind:" + String.format(Locale.ROOT, "%.4f,%.4f", lat, lon)
    }
}
