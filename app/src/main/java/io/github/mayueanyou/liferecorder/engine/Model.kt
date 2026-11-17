package io.github.mayueanyou.liferecorder.engine

/** One position as recorded. Times are epoch milliseconds. */
data class Fix(val time: Long, val lat: Double, val lon: Double, val accuracy: Float?)

data class RoutePoint(val lat: Double, val lon: Double, val time: Long)

enum class Mode(val icon: String) { WALKING("🚶"), CYCLING("🚲"), DRIVING("🚗"), MOVING("➜") }

/** A place you were, from when to when. [live] while you are still there. */
data class Stay(
    val start: Long,
    val end: Long,
    val lat: Double,
    val lon: Double,
    val fixes: Int,
    val live: Boolean = false,
    var place: String? = null,
    var placeSource: String? = null,
    var exact: Boolean = false,
)

/** Getting from one place to the next, by one means. [live] while still under way. */
data class Journey(
    val start: Long,
    val end: Long,
    val fromLat: Double,
    val fromLon: Double,
    val toLat: Double,
    val toLon: Double,
    val mode: Mode,
    val distanceM: Double,
    val route: List<RoutePoint>,
    val live: Boolean = false,
    /** Where it began or ended was a change of transport, not a stay -- named by its street. */
    val fromTransfer: Boolean = false,
    val toTransfer: Boolean = false,
    var fromPlace: String? = null,
    var toPlace: String? = null,
) {
    val seconds get() = (end - start) / 1000.0
    val avgKmh get() = if (seconds > 0) distanceM / seconds * 3.6 else 0.0
}

/** What a day holds, in time order. */
sealed interface Entry {
    val start: Long
    val end: Long
    data class StayEntry(val stay: Stay) : Entry {
        override val start get() = stay.start
        override val end get() = stay.end
    }
    data class JourneyEntry(val journey: Journey) : Entry {
        override val start get() = journey.start
        override val end get() = journey.end
    }
}
