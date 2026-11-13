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
    /** The flight went the long way round the globe, as said where it could have gone either way. */
    val around: Boolean = false,
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
     * A flight draws as the same gentle arc wherever it goes: a curve in the map's own flat
     * projection that bows to the left of the way it flew by a fixed share of its length, so a
     * short hop reads as a flight, a long one never swings up to the pole, and the way back
     * draws beside the way out. With [around] a flight that spans much of the globe goes the
     * other way round it. Everything else is a straight line.
     */
    fun points(a: Pair<Double, Double>, b: Pair<Double, Double>, way: Travel?, around: Boolean = false): List<Pair<Double, Double>> {
        if (way != Travel.Plane || a == b) return listOf(a, nearest(a, b))
        val end = if (around && spansFar(a, b)) farthest(a, b) else nearest(a, b)
        val x1 = a.second; val x2 = end.second
        val y1 = mercY(a.first); val y2 = mercY(end.first)
        val dx = x2 - x1
        val dy = y2 - y1
        val len = hypot(dx, dy)
        if (len == 0.0) return listOf(a, end)
        // Left of the direction of travel; a quadratic curve whose middle sits BOW out.
        val cx = (x1 + x2) / 2 - dy / len * len * BOW * 2
        val cy = (y1 + y2) / 2 + dx / len * len * BOW * 2
        val n = 48
        return (0..n).map { i ->
            val t = i.toDouble() / n
            val u = 1 - t
            val x = u * u * x1 + 2 * u * t * cx + t * t * x2
            val y = u * u * y1 + 2 * u * t * cy + t * t * y2
            // Kept off the poles, where the map's projection ends.
            latOf(y).coerceIn(-85.0, 85.0) to x
        }
    }

    /** True when a flight between [a] and [b] spans far enough round the globe to have gone either way. */
    fun spansFar(a: Pair<Double, Double>, b: Pair<Double, Double>): Boolean =
        kotlin.math.abs(nearest(a, b).second - a.second) >= FAR_DEGREES

    /** [b] with its longitude moved to go the long way round from [a]. */
    private fun farthest(a: Pair<Double, Double>, b: Pair<Double, Double>): Pair<Double, Double> {
        val short = nearest(a, b).second
        return b.first to (if (short >= a.second) short - 360 else short + 360)
    }

    /** Latitude as the map's flat projection spaces it, in degree-like units. */
    private fun mercY(lat: Double) = Math.toDegrees(kotlin.math.ln(kotlin.math.tan(PI / 4 + Math.toRadians(lat.coerceIn(-85.0, 85.0)) / 2)))

    private fun latOf(y: Double) = Math.toDegrees(2 * kotlin.math.atan(kotlin.math.exp(Math.toRadians(y))) - PI / 2)

    /** How far apart in longitude two ends must be before a flight could have gone either way. */
    const val FAR_DEGREES = 100.0

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
        around: Boolean = false,
    ): List<Pair<List<Pair<Double, Double>>, Travel?>> {
        val plane = ways.indexOf(Travel.Plane)
        if (plane < 0 || via.size < 2) {
            // A flight with no airports named goes straight from place to place.
            if (around && ways == listOf(Travel.Plane)) return listOf(points(a, b, Travel.Plane, around = true) to Travel.Plane)
            return parts(a, b, ways, follow)
        }
        fun ground(x: Pair<Double, Double>, y: Pair<Double, Double>, w: List<Travel>) =
            if (x == y || Distance.km(x, y) < 1.0) emptyList()
            else if (w.isEmpty()) listOf(listOf(x, nearest(x, y)) to null) else parts(x, y, w, follow)
        return ground(a, via.first(), ways.take(plane)) +
            via.zipWithNext().map { (x, y) -> points(x, y, Travel.Plane, around) to Travel.Plane } +
            ground(via.last(), b, ways.drop(plane + 1))
    }

    /** [b] with its longitude moved by whole turns to lie within half a turn of [a]'s, so a line between them takes the short way, across the date line if need be. */
    fun nearest(a: Pair<Double, Double>, b: Pair<Double, Double>): Pair<Double, Double> {
        var lon = b.second
        while (lon - a.second > 180) lon -= 360
        while (lon - a.second < -180) lon += 360
        return b.first to lon
    }

    /** How far a flight bows out, as a share of its length. */
    const val BOW = 0.08
}
