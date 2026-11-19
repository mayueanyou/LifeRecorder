package io.github.mayueanyou.lifetracker.engine

import kotlin.math.max
import kotlin.math.min

/**
 * Turns a run of positions into stays and journeys.
 *
 * The rules are the ones the self-hosted Record app arrived at over many
 * real days, each written down where it applies. They are applied by
 * replaying the positions in order, from scratch, every time a day is shown:
 * nothing is carried between runs, so a stay can never be left half-closed,
 * duplicated or orphaned, and midnight is a matter of cutting the finished
 * day ([DaySlicer]) rather than of state that has to roll over correctly.
 *
 * The phone's own clock times every position, so none of the NUC's handling
 * of queued or repeated messages is needed here.
 */
class DayEngine(private val places: PlaceKnowledge) {

    /** What the engine needs to know about places, without asking the network. */
    interface PlaceKnowledge {
        /** Your own named place containing this point, as (name, centre lat, centre lon). */
        fun own(lat: Double, lon: Double): Triple<String, Double, Double>?
        /** Whether this point is inside a named building (from places already looked up). */
        fun insideNamedBuilding(lat: Double, lon: Double): Boolean
    }

    class Result(val stays: List<Stay>, val journeys: List<Journey>)

    private class Candidate(fix: Fix) {
        var anchorLat = fix.lat
        var anchorLon = fix.lon
        var lat = fix.lat
        var lon = fix.lon
        val start = fix.time
        var last = fix.time
        var fixes = 1
        var walked = 0.0
        var walkLat: Double? = null
        var walkLon: Double? = null
        var centred = false
        var reanchored = false
        var confirmed = false
    }

    private class Step(val lat: Double, val lon: Double, val time: Long, val metres: Double, val seconds: Double) {
        val kmh get() = if (seconds > 0) metres / seconds * 3.6 else 0.0
    }

    private class OpenJourney(val start: Long, val fromLat: Double, val fromLon: Double, lat: Double, lon: Double) {
        var lastLat = lat
        var lastLon = lon
        var lastTime = start
        var distance = 0.0
        val steps = ArrayList<Step>()
        val trace = arrayListOf(RoutePoint(lat, lon, start))
    }

    private val stays = ArrayList<Stay>()
    private val journeys = ArrayList<Journey>()
    private var candidate: Candidate? = null
    private var journey: OpenJourney? = null

    /** Replay [fixes] (in time order); [now] decides what is still in progress. */
    fun run(fixes: List<Fix>, now: Long): Result {
        for (fix in fixes) observe(fix)
        val c = candidate
        val liveStay = c?.let {
            // Where you are now, the quiet since the last position counts as
            // time here: the phone records a position only once it has moved
            // 10 m, so sitting still sends nothing, and timing the stay by its
            // positions alone kept a long stay from ever becoming one.
            // (Leaving is still timed by positions -- walking out sends them.)
            val until = if (now > it.last && now - it.last <= SILENCE_LIMIT_MS) now else it.last
            val seconds = (until - it.start) / 1000.0
            if (isStay(it, seconds)) {
                // Confirmed only now: the journey that brought you here is over.
                if (!it.confirmed) finishJourney(it.start, it.lat, it.lon)
                // Silence is not absence: a still phone has nothing to report,
                // so a stay runs on to now unless the phone has been quiet for
                // longer than any ordinary day allows.
                val end = if (now - it.last <= SILENCE_LIMIT_MS) max(it.last, now) else it.last
                Stay(it.start, end, it.lat, it.lon, it.fixes, live = now - it.last <= SILENCE_LIMIT_MS)
            } else null
        }
        // A trip still under way is cut into drive and walk the same way a
        // finished one is: parked and walking on, you see the drive done and
        // the walk going, not one long drive. Only the last piece is live.
        val liveJourneys = journey?.let { j ->
            if (j.distance >= MIN_JOURNEY_M) {
                val pieces = segments(j, j.lastTime, j.lastLat, j.lastLon)
                pieces.dropLast(1) + pieces.last().copy(live = true)
            } else null
        }.orEmpty()
        return Result(
            stays + listOfNotNull(liveStay),
            journeys + liveJourneys,
        )
    }

    private fun observe(fix: Fix) {
        val rough = fix.accuracy != null && fix.accuracy > ACCURACY_LIMIT_M
        val c = candidate
        if (c == null) {
            if (!rough) candidate = Candidate(fix)
            return
        }

        // Judged before anything is recorded, so a bad fix touches nothing.
        val awayNow = Geo.metres(c.anchorLat, c.anchorLon, fix.lat, fix.lon)
        if (rough && awayNow > RADIUS_M) return
        val j = journey
        val (refLat, refLon, refTime) =
            if (j != null) Triple(j.lastLat, j.lastLon, j.lastTime) else Triple(c.lat, c.lon, c.last)
        val elapsed = (fix.time - refTime) / 1000.0
        val jump = Geo.metres(refLat, refLon, fix.lat, fix.lon)
        if (jump > RADIUS_M && elapsed > 0 && jump / elapsed * 3.6 > JUMP_KMH) return

        // A fix while travelling belongs to the journey too, until the
        // candidate is confirmed. A rough one is never part of a route.
        if (!rough) journeyObserve(fix)

        val gap = fix.time - c.last
        // "Left" only when outside by more than the fix's own margin of error,
        // capped so a bad reading cannot stretch a place indefinitely.
        val margin = min(fix.accuracy?.toDouble() ?: 0.0, 50.0)
        if (awayNow - margin <= RADIUS_M && gap <= SILENCE_LIMIT_MS) {
            stillHere(c, fix, rough)
            return
        }

        val finished = close(c)
        if (finished != null) {
            // Leaving starts the journey away -- timed at the last fix seen there.
            journey = OpenJourney(finished.end, finished.lat, finished.lon, finished.lat, finished.lon)
        } else if (journey == null) {
            // A pause that did not count, with nothing running: on the move all
            // the same, so a journey starts from where the pause began.
            journey = OpenJourney(c.start, c.anchorLat, c.anchorLon, c.lat, c.lon)
        }
        candidate = if (rough) null else Candidate(fix)
    }

    private fun stillHere(c: Candidate, fix: Fix, rough: Boolean) {
        if (!rough) {
            val wl = c.walkLat
            val wo = c.walkLon
            if (wl != null && wo != null) c.walked += Geo.metres(wl, wo, fix.lat, fix.lon)
            c.walkLat = fix.lat
            c.walkLon = fix.lon
        }
        // The centre is nudged toward the fix rather than moved to it.
        c.fixes++
        c.lat += (fix.lat - c.lat) / c.fixes
        c.lon += (fix.lon - c.lon) / c.fixes
        if (fix.time > c.last) c.last = fix.time
        // Re-centred once, a few minutes in, on where the fixes actually are:
        // the first fix is taken on the way in, at the edge.
        if (!c.centred && !c.reanchored && fix.time - c.start >= CENTRE_AFTER_MS && c.fixes >= 5) {
            c.anchorLat = c.lat
            c.anchorLon = c.lon
            c.centred = true
        }
        val seconds = (fix.time - c.start) / 1000.0
        if (isStay(c, seconds)) {
            c.confirmed = true
            // The journey that brought you here ended when you arrived.
            finishJourney(c.start, c.lat, c.lon)
            if (!c.reanchored) {
                places.own(c.lat, c.lon)?.let { (_, lat, lon) ->
                    c.anchorLat = lat
                    c.anchorLon = lon
                    c.reanchored = true
                }
            }
        }
    }

    /** Whether a candidate that has lasted [seconds] is a stay, not a pause in a walk. */
    private fun isStay(c: Candidate, seconds: Double): Boolean {
        // Once a stay, always a stay.
        if (c.confirmed) return seconds > 0
        if (seconds * 1000 < MIN_DWELL_MS) return false
        if (seconds * 1000 >= PAUSE_MAX_MS || c.reanchored) return true
        // Still is measurable: a still phone sends next to nothing.
        if (c.walked / (seconds / 60) <= STILL_M_PER_MIN) return true
        // Moving about, briefly: a stay only somewhere with a name of its own.
        return places.own(c.lat, c.lon) != null || places.insideNamedBuilding(c.lat, c.lon)
    }

    private fun close(c: Candidate): Stay? {
        val seconds = (c.last - c.start) / 1000.0
        if (!isStay(c, seconds)) return null
        return Stay(c.start, c.last, c.lat, c.lon, c.fixes).also { stays += it }
    }

    // ---------------------------------------------------------- journeys

    private fun journeyObserve(fix: Fix) {
        val j = journey ?: return
        val gap = (fix.time - j.lastTime) / 1000.0
        val step = Geo.metres(j.lastLat, j.lastLon, fix.lat, fix.lon)
        j.distance += step
        if (gap > 0) j.steps += Step(fix.lat, fix.lon, fix.time, step, gap)
        j.lastLat = fix.lat
        j.lastLon = fix.lon
        j.lastTime = fix.time
        val tail = j.trace.last()
        if (Geo.metres(tail.lat, tail.lon, fix.lat, fix.lon) >= TRACE_MIN_STEP_M) {
            j.trace += RoutePoint(fix.lat, fix.lon, fix.time)
        }
    }

    private fun finishJourney(arrivedAt: Long, toLat: Double, toLon: Double) {
        val j = journey ?: return
        journey = null
        // Sitting still with a wandering GPS, not a trip.
        if (j.distance < MIN_JOURNEY_M) return
        journeys += segments(j, arrivedAt, toLat, toLon)
    }

    private class Point(val lat: Double, val lon: Double, val time: Long)

    /** The finished journey, cut where you got into or out of a vehicle. */
    private fun segments(j: OpenJourney, arrivedAt: Long, toLat: Double, toLon: Double): List<Journey> {
        val startPt = Point(j.fromLat, j.fromLon, j.start)
        val endPt = Point(toLat, toLon, arrivedAt)
        val steps = j.steps.filter { it.time <= arrivedAt }
        val vehicle = vehicleRuns(steps)
        if (vehicle.isEmpty()) return listOf(segment(j, startPt, endPt, steps, null, false, false))

        // The vehicle part runs from the first sustained run to the last,
        // widened across slower steps still above walking pace.
        var first = vehicle.first().first
        var last = vehicle.last().second - 1
        while (first > 0 && steps[first - 1].kmh > WALK_MAX_KMH) first--
        while (last + 1 < steps.size && steps[last + 1].kmh > WALK_MAX_KMH) last++
        val carStart = if (first == 0) startPt else steps[first - 1].let { Point(it.lat, it.lon, it.time) }
        val carEnd = steps[last].let { Point(it.lat, it.lon, it.time) }

        val leadWalk = walkBetween(j, startPt, carStart)
        val tailWalk = walkBetween(j, carEnd, endPt)
        val out = ArrayList<Journey>()
        if (leadWalk) out += segment(j, startPt, carStart, steps.subList(0, first), Mode.WALKING, false, true)
        val carSteps = if (leadWalk || tailWalk) steps.subList(first, last + 1) else steps
        out += segment(j, if (leadWalk) carStart else startPt, if (tailWalk) carEnd else endPt,
            carSteps, null, leadWalk, tailWalk)
        if (tailWalk) out += segment(j, carEnd, endPt, steps.subList(last + 1, steps.size), Mode.WALKING, true, false)
        return out
    }

    private fun segment(j: OpenJourney, a: Point, b: Point, steps: List<Step>, mode: Mode?,
                        fromTransfer: Boolean, toTransfer: Boolean): Journey {
        val seconds = max(0.0, (b.time - a.time) / 1000.0)
        val route = routeBetween(j, a, b)
        // Measured along the route, which holds the whole way.
        val distance = Geo.length(route).takeIf { it > 0 } ?: steps.sumOf { it.metres }.takeIf { it > 0 }
            ?: Geo.metres(a.lat, a.lon, b.lat, b.lon)
        return Journey(a.time, b.time, a.lat, a.lon, b.lat, b.lon,
            mode ?: robustMode(steps, distance, seconds), distance, route,
            fromTransfer = fromTransfer, toTransfer = toTransfer)
    }

    private fun routeBetween(j: OpenJourney, a: Point, b: Point): List<RoutePoint> {
        val inner = j.trace.filter { it.time in a.time..b.time }
        val out = ArrayList<RoutePoint>()
        if (inner.isEmpty() || Geo.metres(inner.first().lat, inner.first().lon, a.lat, a.lon) > 1) out += RoutePoint(a.lat, a.lon, a.time)
        out += inner
        if (Geo.metres(out.last().lat, out.last().lon, b.lat, b.lon) > 1) out += RoutePoint(b.lat, b.lon, b.time)
        return out
    }

    /** A real walk: judged by ground covered along the route, not the straight line. */
    private fun walkBetween(j: OpenJourney, a: Point, b: Point): Boolean {
        val seconds = (b.time - a.time) / 1000.0
        if (seconds <= 0) return false
        val far = max(Geo.metres(a.lat, a.lon, b.lat, b.lon), Geo.length(routeBetween(j, a, b)))
        return far >= WALK_SPLIT_MIN_M && seconds >= WALK_SPLIT_MIN_S && far / seconds * 3.6 <= WALK_MAX_KMH
    }

    private fun runs(steps: List<Step>, test: (Step) -> Boolean): List<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>()
        var start = -1
        steps.forEachIndexed { i, s ->
            if (test(s)) { if (start < 0) start = i } else if (start >= 0) { out += start to i; start = -1 }
        }
        if (start >= 0) out += start to steps.size
        return out
    }

    /**
     * The stretches driven. City driving is broken by stop signs, lights and
     * corners, each cutting a fast run short; judged one by one, those runs
     * were too short to count, the drive seemed to start minutes late, and the
     * walk to the car -- now apparently containing driving -- was too fast to
     * be a walk, so the whole trip became one drive. Fast runs separated by
     * less than [STOP_MAX_S] of slower going are one drive; a GPS jump while
     * walking is a lone burst, minutes from any driving, and still too short.
     */
    private fun vehicleRuns(steps: List<Step>): List<Pair<Int, Int>> {
        val merged = ArrayList<Pair<Int, Int>>()
        for ((a, b) in runs(steps) { it.kmh > VEHICLE_KMH }) {
            val prev = merged.lastOrNull()
            if (prev != null && steps.subList(prev.second, a).sumOf { it.seconds } <= STOP_MAX_S) {
                merged[merged.size - 1] = prev.first to b
            } else {
                merged += a to b
            }
        }
        return merged.filter { (a, b) -> sustained(steps, a, b) }
    }

    /** A vehicle run must go somewhere: GPS wobble covers three short steps easily. */
    private fun sustained(steps: List<Step>, a: Int, b: Int): Boolean {
        val run = steps.subList(a, b)
        return b - a >= SUSTAINED_STEPS && run.sumOf { it.metres } >= SUSTAINED_MIN_M &&
            run.sumOf { it.seconds } >= SUSTAINED_MIN_S
    }

    private fun robustMode(steps: List<Step>, distance: Double, seconds: Double): Mode {
        // A short hop at walking pace overall is a walk.
        if (distance < 1000 && seconds > 0 && distance / seconds * 3.6 <= WALK_MAX_KMH) return Mode.WALKING
        if (steps.size < 6) return classify(steps.map { it.kmh }, distance, seconds)
        var best = 0.0
        for ((a, b) in runs(steps) { it.kmh > WALK_MAX_KMH }) {
            if (sustained(steps, a, b)) best = max(best, median(steps.subList(a, b).map { it.kmh }))
        }
        return when {
            best == 0.0 -> Mode.WALKING
            best <= CYCLE_MAX_KMH -> Mode.CYCLING
            else -> Mode.DRIVING
        }
    }

    private fun classify(speeds: List<Double>, distance: Double, seconds: Double): Mode {
        if (seconds <= 0) return Mode.MOVING
        val fast = if (speeds.size < 3) distance / seconds * 3.6 else percentile(speeds, 0.85)
        return when {
            fast <= WALK_MAX_KMH -> Mode.WALKING
            fast <= CYCLE_MAX_KMH -> Mode.CYCLING
            else -> Mode.DRIVING
        }
    }

    private fun median(values: List<Double>) = values.sorted().let { it[it.size / 2] }
    private fun percentile(values: List<Double>, fraction: Double): Double {
        val sorted = values.sorted()
        return sorted[min(sorted.size - 1, (fraction * (sorted.size - 1)).toInt())]
    }

    companion object {
        const val RADIUS_M = 120.0
        const val ACCURACY_LIMIT_M = 50f
        const val JUMP_KMH = 250.0
        const val MIN_DWELL_MS = 5 * 60 * 1000L
        const val PAUSE_MAX_MS = 20 * 60 * 1000L
        const val STILL_M_PER_MIN = 10.0
        const val CENTRE_AFTER_MS = 3 * 60 * 1000L
        const val SILENCE_LIMIT_MS = 12 * 60 * 60 * 1000L
        const val MIN_JOURNEY_M = 250.0
        const val TRACE_MIN_STEP_M = 5.0
        const val WALK_MAX_KMH = 9.0
        const val CYCLE_MAX_KMH = 25.0
        const val VEHICLE_KMH = 20.0
        const val SUSTAINED_STEPS = 3
        const val SUSTAINED_MIN_M = 300.0
        const val SUSTAINED_MIN_S = 30.0
        const val WALK_SPLIT_MIN_M = 150.0
        const val WALK_SPLIT_MIN_S = 90.0
        const val STOP_MAX_S = 150.0
    }
}
