package io.github.mayueanyou.lifetracker.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A still phone records nothing: the place you are at now must still become a stay. */
class StillTest {
    private val nowhere = object : DayEngine.PlaceKnowledge {
        override fun own(lat: Double, lon: Double): Triple<String, Double, Double>? = null
        override fun insideNamedBuilding(lat: Double, lon: Double) = false
    }
    private val min = 60_000L

    // Sitting at A, then a drive north at about 36 km/h, arriving at B with one position.
    private fun drive(): List<Fix> {
        val out = ArrayList<Fix>()
        for (i in 0..10) out += Fix(i * min, 43.0, -76.1, 8f)                       // at A, 10 min
        var t = 10 * min
        for (k in 1..60) { t += 10_000; out += Fix(t, 43.0 + k * 0.0009, -76.1, 8f) } // ~6 km
        return out
    }

    @Test fun quietAfterArrivingIsAStay() {
        val fixes = drive()
        val arrived = fixes.last().time
        val r = DayEngine(nowhere).run(fixes, now = arrived + 12 * min)
        val live = r.stays.last()
        assertTrue(live.live)
        assertEquals(arrived, live.start)
        assertEquals(arrived + 12 * min, live.end)
        // The drive is finished, not still under way.
        assertFalse(r.journeys.any { it.live })
        assertEquals(Mode.DRIVING, r.journeys.last().mode)
    }

    @Test fun notYetFiveMinutes() {
        val fixes = drive()
        val r = DayEngine(nowhere).run(fixes, now = fixes.last().time + 3 * min)
        assertTrue(r.journeys.last().live)        // could be a red light; still travelling
        assertEquals(1, r.stays.size)              // only A
    }

    @Test fun walkingOnAfterParkingShowsTheDriveDone() {
        // Drove, parked, and are still walking: the drive is finished, the walk is under way.
        val fixes = drive().toMutableList()
        var t = fixes.last().time + 60_000
        val lat = fixes.last().lat
        for (k in 1..60) { t += 7_000; fixes += Fix(t, lat, -76.1 + k * 0.00013, 6f) }   // ~10 m every 7 s
        val r = DayEngine(nowhere).run(fixes, now = t + 10_000)
        val (car, walk) = r.journeys.takeLast(2)
        assertEquals(Mode.DRIVING, car.mode)
        assertFalse(car.live)
        assertEquals(Mode.WALKING, walk.mode)
        assertTrue(walk.live)
    }

    @Test fun stopAndGoDrivingIsOneDrive() {
        // Short fast runs between stop signs are one drive, starting at the first of them.
        val out = ArrayList<Fix>()
        for (i in 0..10) out += Fix(i * min, 43.0, -76.1, 8f)
        var t = 10 * min
        var lat = 43.0
        repeat(120) { lat += 0.00009; t += 7_000; out += Fix(t, lat, -76.1, 8f) }        // walk ~1.2 km at ~5 km/h, then:
        val drivingFrom = t
        repeat(6) {                                                                         // six blocks: 100 m fast, then a 40 s stop
            repeat(5) { lat += 0.0002; t += 2_000; out += Fix(t, lat, -76.1, 6f) }          // ~22 m every 2 s = 40 km/h
            t += 40_000; lat += 0.00002; out += Fix(t, lat, -76.1, 6f)
        }
        repeat(40) { lat += 0.0003; t += 2_000; out += Fix(t, lat, -76.1, 6f) }
        val r = DayEngine(nowhere).run(out, now = t + 20 * min)
        val modes = r.journeys.map { it.mode }
        assertEquals(listOf(Mode.WALKING, Mode.DRIVING), modes)
        // The drive starts at the first block, not at the long run after the last stop.
        assertTrue(kotlin.math.abs(r.journeys.last().start - drivingFrom) <= 10_000)
    }

    @Test fun aShortStopIsNotAStay() {
        // Three quiet minutes at a light, then on again: under 5 minutes is never a stay.
        val fixes = drive().toMutableList()
        var t = fixes.last().time + 3 * min
        for (k in 61..90) { t += 10_000; fixes += Fix(t, 43.0 + k * 0.0009, -76.1, 8f) }
        val r = DayEngine(nowhere).run(fixes, now = t + 60_000)
        assertEquals(1, r.stays.size)
    }
}
