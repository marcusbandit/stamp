package com.marcusrosado.stamp.data

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/** A way of getting from one stop to the next. The key is what the save file holds. */
enum class Travel(val key: String, val label: String) {
    Plane("plane", "Plane"),
    Train("train", "Train"),
    Bus("bus", "Bus"),
    Transit("transit", "Public transport"),
    Bike("bike", "Bike"),
    Walk("walk", "Walk"),
    Car("car", "Car"),
    Ferry("ferry", "Ferry");

    /** The label where room is short, such as a tile in the picker. */
    val short: String get() = if (this == Transit) "Transit" else label

    companion object {
        fun of(key: String): Travel? = entries.firstOrNull { it.key == key }
    }
}

/**
 * How the user got from stop [from] to stop [to] of a trip. Only a leg between two stops that
 * follow each other counts; one left behind by a reorder is dropped when the trip is saved.
 * [from] is [START] for the way there from where the trip set out, and [to] is [END] for the
 * way home.
 */
data class Leg(
    val from: String,
    val to: String,
    val ways: List<Travel>,
    /**
     * The airports a flight went through, by IATA code: where it took off, any layovers, and
     * where it landed. Ghost points: they draw the line and mark nothing.
     */
    val via: List<String> = emptyList(),
) {
    companion object {
        /** Where a trip set out from, as the start of its first leg. */
        const val START = "@start"
        /** Where a trip went back to, as the end of its last leg. */
        const val END = "@end"
    }
}

/** Finds the real line a way of travel took between two points, or null to draw it straight. */
typealias Follow = (Travel, Pair<Double, Double>, Pair<Double, Double>) -> List<Pair<Double, Double>>?

/** The line a leg draws on the map, as latitude and longitude. */
object LegPath {
    /**
     * A flight follows the great circle and bows a little to the left of the way it flew, so
     * even a short hop reads as a flight and the way back draws beside the way out. Everything
     * else is a straight line.
     */
    fun points(a: Pair<Double, Double>, b: Pair<Double, Double>, way: Travel?): List<Pair<Double, Double>> {
        if (way != Travel.Plane || a == b) return listOf(a, nearest(a, b))
        val n = 48
        val arc = greatCircle(a, b, n)
        // The bow, in a flat frame where a degree of longitude is shrunk to its width at the
        // middle latitude, so it is the same size whichever way the flight goes.
        val k = cos(Math.toRadians((a.first + b.first) / 2)).coerceAtLeast(0.2)
        val dx = (arc.last().second - arc.first().second) * k
        val dy = arc.last().first - arc.first().first
        val len = hypot(dx, dy)
        if (len == 0.0) return arc
        // Left of the direction of travel.
        val nx = -dy / len
        val ny = dx / len
        val bow = len * BOW
        return arc.mapIndexed { i, (lat, lon) ->
            val s = sin(PI * i / n) * bow
            // Kept off the poles, where the map's projection ends.
            (lat + ny * s).coerceIn(-85.0, 85.0) to (lon + nx * s / k)
        }
    }

    /**
     * A leg taken several ways, cut into that many even stretches along the line between its
     * stops, each drawn its own way. One way, or none, is the whole leg.
     */
    fun parts(
        a: Pair<Double, Double>, b: Pair<Double, Double>, ways: List<Travel>, follow: Follow? = null,
    ): List<Pair<List<Pair<Double, Double>>, Travel?>> {
        // One way from place to place can follow the real line; split stretches stay straight,
        // since the points between them are only guesses.
        if (ways.size == 1) follow?.invoke(ways[0], a, b)?.let { return listOf(it to ways[0]) }
        if (ways.size <= 1) return listOf(points(a, b, ways.firstOrNull()) to ways.firstOrNull())
        val end = nearest(a, b)
        fun at(f: Double) = (a.first + (end.first - a.first) * f) to (a.second + (end.second - a.second) * f)
        return ways.mapIndexed { i, w ->
            points(at(i.toDouble() / ways.size), at((i + 1).toDouble() / ways.size), w) to w
        }
    }

    /**
     * A leg with the airports its flight went through, [via], departure first: the ways before
     * the plane run from [a] to the first airport, the flight hops from airport to airport, and
     * the ways after it run on from the last airport to [b]. A stretch with no way said, such as
     * home to the airport, draws as a leg not said. Without airports it is [parts].
     */
    fun route(
        a: Pair<Double, Double>, b: Pair<Double, Double>, ways: List<Travel>, via: List<Pair<Double, Double>>,
        follow: Follow? = null,
    ): List<Pair<List<Pair<Double, Double>>, Travel?>> {
        val plane = ways.indexOf(Travel.Plane)
        if (plane < 0 || via.size < 2) return parts(a, b, ways, follow)
        fun ground(x: Pair<Double, Double>, y: Pair<Double, Double>, w: List<Travel>) =
            if (x == y || Distance.km(x, y) < 1.0) emptyList()
            else if (w.isEmpty()) listOf(listOf(x, nearest(x, y)) to null) else parts(x, y, w, follow)
        return ground(a, via.first(), ways.take(plane)) +
            via.zipWithNext().map { (x, y) -> points(x, y, Travel.Plane) to Travel.Plane } +
            ground(via.last(), b, ways.drop(plane + 1))
    }

    /** Points along the great circle, with longitudes kept continuous across the date line. */
    fun greatCircle(a: Pair<Double, Double>, b: Pair<Double, Double>, n: Int): List<Pair<Double, Double>> {
        val la1 = Math.toRadians(a.first); val lo1 = Math.toRadians(a.second)
        val la2 = Math.toRadians(b.first); val lo2 = Math.toRadians(b.second)
        val d = 2 * asin(sqrt(sin((la2 - la1) / 2).let { it * it } + cos(la1) * cos(la2) * sin((lo2 - lo1) / 2).let { it * it }))
        if (d < 1e-9) return listOf(a, b)
        var last = a.second
        return (0..n).map { i ->
            val f = i.toDouble() / n
            val p = sin((1 - f) * d) / sin(d)
            val q = sin(f * d) / sin(d)
            val x = p * cos(la1) * cos(lo1) + q * cos(la2) * cos(lo2)
            val y = p * cos(la1) * sin(lo1) + q * cos(la2) * sin(lo2)
            val z = p * sin(la1) + q * sin(la2)
            var lon = Math.toDegrees(atan2(y, x))
            while (lon - last > 180) lon -= 360
            while (lon - last < -180) lon += 360
            last = lon
            Math.toDegrees(atan2(z, hypot(x, y))) to lon
        }
    }

    /** [b] with its longitude moved by whole turns to lie within half a turn of [a]'s, so a line between them takes the short way, across the date line if need be. */
    fun nearest(a: Pair<Double, Double>, b: Pair<Double, Double>): Pair<Double, Double> {
        var lon = b.second
        while (lon - a.second > 180) lon -= 360
        while (lon - a.second < -180) lon += 360
        return b.first to lon
    }

    /** How far a flight bows out, as a share of its length. */
    const val BOW = 0.12
}
