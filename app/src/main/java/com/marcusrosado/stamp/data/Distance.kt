package com.marcusrosado.stamp.data

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object Distance {
    /** The Earth's mean radius, the one great-circle distances are usually given in. */
    private const val EARTH_KM = 6371.0

    /** Kilometres between two points, flat-earth close, which is plenty below a few hundred km. */
    fun km(a: Pair<Double, Double>, b: Pair<Double, Double>): Double {
        val k = cos(Math.toRadians((a.first + b.first) / 2))
        // The short way round: 179.9 and -179.9 are neighbours.
        val dLon = ((a.second - b.second + 540) % 360) - 180
        val dx = dLon * k * 111.0
        val dy = (a.first - b.first) * 111.0
        return sqrt(dx * dx + dy * dy)
    }

    /**
     * Kilometres along the Earth's surface between two points, the short way round. Flat-earth
     * [km] is a good deal off for a flight across an ocean, which is what a total of the
     * distance travelled is mostly made of.
     */
    fun greatCircleKm(a: Pair<Double, Double>, b: Pair<Double, Double>): Double {
        val lat1 = Math.toRadians(a.first)
        val lat2 = Math.toRadians(b.first)
        val dLat = lat2 - lat1
        val dLon = Math.toRadians(b.second - a.second)
        val h = sin(dLat / 2).let { it * it } + cos(lat1) * cos(lat2) * sin(dLon / 2).let { it * it }
        return 2 * EARTH_KM * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }
}
