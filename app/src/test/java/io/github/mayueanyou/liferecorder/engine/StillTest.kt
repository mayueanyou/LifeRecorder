package io.github.mayueanyou.liferecorder.engine

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

    @Test fun notYetEightMinutes() {
        val fixes = drive()
        val r = DayEngine(nowhere).run(fixes, now = fixes.last().time + 5 * min)
        assertTrue(r.journeys.last().live)        // could be a red light; still travelling
        assertEquals(1, r.stays.size)              // only A
    }

    @Test fun aShortStopIsNotAStay() {
        // Five quiet minutes in traffic, then on again: under 8 minutes is never a stay.
        val fixes = drive().toMutableList()
        var t = fixes.last().time + 5 * min
        for (k in 61..90) { t += 10_000; fixes += Fix(t, 43.0 + k * 0.0009, -76.1, 8f) }
        val r = DayEngine(nowhere).run(fixes, now = t + 60_000)
        assertEquals(1, r.stays.size)
    }
}
