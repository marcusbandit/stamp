package com.marcusrosado.stamp.data

import kotlin.math.cos
import kotlin.math.sqrt

object Distance {
    /** Kilometres between two points, flat-earth close, which is plenty below a few hundred km. */
    fun km(a: Pair<Double, Double>, b: Pair<Double, Double>): Double {
        val k = cos(Math.toRadians((a.first + b.first) / 2))
        // The short way round: 179.9 and -179.9 are neighbours.
        val dLon = ((a.second - b.second + 540) % 360) - 180
        val dx = dLon * k * 111.0
        val dy = (a.first - b.first) * 111.0
        return sqrt(dx * dx + dy * dy)
    }
}
