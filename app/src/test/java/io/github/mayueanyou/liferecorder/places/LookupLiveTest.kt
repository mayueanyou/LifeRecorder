package io.github.mayueanyou.liferecorder.places

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Asks the real OpenStreetMap services to name a few spots and writes what
 * came back. Needs the network, so it only runs when asked:
 *
 *     LR_LOOKUP_SPOTS="lat,lon;lat,lon" LR_OUT=/path/out.txt ./gradlew testDebugUnitTest
 */
class LookupLiveTest {
    @Test
    fun nameSpots() {
        val spots = System.getenv("LR_LOOKUP_SPOTS")
        assumeTrue("no spots given", !spots.isNullOrBlank())
        val out = spots!!.split(";").map { spot ->
            val (lat, lon) = spot.split(",").map { it.trim().toDouble() }
            val started = System.currentTimeMillis()
            val found = Lookups.spotName(lat, lon)
            "$lat,$lon -> ${found?.first} (exact=${found?.second}) in ${System.currentTimeMillis() - started} ms"
        }
        File(System.getenv("LR_OUT") ?: "lookups-out.txt").writeText(out.joinToString("\n"))
    }
}
