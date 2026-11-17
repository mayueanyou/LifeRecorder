package io.github.mayueanyou.liferecorder.engine

import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Replays a file of real positions through the engine and writes what it
 * found, so the result can be compared with another implementation of the
 * same rules. The file is never part of the repository -- it is someone's
 * movements -- and the test is skipped when it is not given:
 *
 *     LR_FIXTURE=/path/fixes.json LR_OUT=/path/out.txt ./gradlew testDebugUnitTest
 */
class ReplayTest {
    @Test
    fun replay() {
        val fixture = System.getenv("LR_FIXTURE")
        assumeTrue("no fixture given", fixture != null && File(fixture).exists())
        val json = JSONObject(File(fixture!!).readText())
        val own = json.getJSONArray("places").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
        val knowledge = object : DayEngine.PlaceKnowledge {
            override fun own(lat: Double, lon: Double) = own
                .filter { Geo.metres(lat, lon, it.getDouble("latitude"), it.getDouble("longitude")) <= it.getDouble("radius") }
                .minByOrNull { Geo.metres(lat, lon, it.getDouble("latitude"), it.getDouble("longitude")) }
                ?.let { Triple(it.getString("name"), it.getDouble("latitude"), it.getDouble("longitude")) }
            override fun insideNamedBuilding(lat: Double, lon: Double) = false
        }
        val raw = json.getJSONArray("fixes")
        val fixes = (0 until raw.length()).map { raw.getJSONObject(it) }.map {
            Fix(it.getLong("t"), it.getDouble("lat"), it.getDouble("lon"),
                if (it.isNull("acc")) null else it.getDouble("acc").toFloat())
        }
        val result = DayEngine(knowledge).run(fixes, now = json.getLong("now"))
        val lines = result.stays.map { "STAY ${it.start} ${it.end} ${"%.5f".format(it.lat)} ${"%.5f".format(it.lon)}" } +
            result.journeys.map { "JOURNEY ${it.start} ${it.end} ${it.mode.name.lowercase()} ${it.distanceM.toInt()}" }
        File(System.getenv("LR_OUT") ?: "replay-out.txt").writeText(lines.sorted().joinToString("\n"))
    }
}
