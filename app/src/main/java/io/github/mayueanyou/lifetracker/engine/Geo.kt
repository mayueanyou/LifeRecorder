package io.github.mayueanyou.lifetracker.engine

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

object Geo {
    /** Great-circle distance in metres. */
    fun metres(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = Math.PI / 180
        val a = sin((lat2 - lat1) * r / 2).pow(2) +
            cos(lat1 * r) * cos(lat2 * r) * sin((lon2 - lon1) * r / 2).pow(2)
        return 2 * 6_371_000 * asin(sqrt(a))
    }

    fun length(points: List<RoutePoint>): Double =
        points.zipWithNext { a, b -> metres(a.lat, a.lon, b.lat, b.lon) }.sum()
}
