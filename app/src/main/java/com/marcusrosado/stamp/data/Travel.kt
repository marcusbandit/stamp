package com.marcusrosado.stamp.data

import kotlin.math.PI
import kotlin.math.hypot

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
 * One stretch of a leg: how it went, or null when the user has not said, and where it ended. [to]
 * is an airport as "apt:" and its code, or a city or pin id; null is the leg's own end, or, for
 * a step inside a leg saved before stops could be named, somewhere along the way.
 */
data class Step(val way: Travel?, val to: String? = null) {
    companion object {
        const val AIRPORT = "apt:"
        fun airport(code: String) = AIRPORT + code.uppercase()
        /** The IATA code of an airport stop, or null for any other stop. */
        fun codeOf(stop: String?): String? = stop?.takeIf { it.startsWith(AIRPORT) }?.removePrefix(AIRPORT)
    }
}

/**
 * How the user got from stop [from] to stop [to] of a trip, as the [steps] it took in order:
 * a train to the airport, a flight to another, a train on to the town. Only a leg between two
 * stops that follow each other counts; one left behind by a reorder is dropped when the trip is
 * saved. [from] is [START] for the way there from where the trip set out, and [to] is [END] for
 * the way home.
 */
data class Leg(
    val from: String,
    val to: String,
    val steps: List<Step>,
    /** The longest flight went the long way round the globe, as said where it could have gone either way. */
    val around: Boolean = false,
) {
    /** Every way the leg was taken, once each, in the order first taken. */
    val ways: List<Travel> get() = steps.mapNotNull { it.way }.distinct()

    /** The ways in order as told, a way taken twice in a row once. */
    val told: List<Travel> get() = steps.mapNotNull { it.way }.fold(emptyList()) { acc, w -> if (acc.lastOrNull() == w) acc else acc + w }

    companion object {
        /** Where a trip set out from, as the start of its first leg. */
        const val START = "@start"
        /** Where a trip went back to, as the end of its last leg. */
        const val END = "@end"

        /**
         * A leg as saved before it had steps: its ways, and the airports of its one flight. The
         * ways before the plane end at the first airport, the flight hops airport to airport,
         * and the ways after it run on to the leg's end. Without a flight each way is a step to
         * somewhere along the way.
         */
        fun fromWays(from: String, to: String, ways: List<Travel>, via: List<String>, around: Boolean): Leg? {
            if (ways.isEmpty()) return null
            val plane = ways.indexOf(Travel.Plane)
            if (plane < 0 || via.size < 2) return Leg(from, to, ways.map { Step(it) }, around && plane >= 0)
            val before = ways.take(plane)
            val steps = ArrayList<Step>()
            if (before.isEmpty()) steps += Step(null, Step.airport(via[0]))
            else before.forEachIndexed { i, w -> steps += Step(w, if (i == before.lastIndex) Step.airport(via[0]) else null) }
            for (code in via.drop(1)) steps += Step(Travel.Plane, Step.airport(code))
            val after = ways.drop(plane + 1)
            if (after.isEmpty()) steps += Step(null) else after.forEach { steps += Step(it) }
            return Leg(from, to, steps, around)
        }

        /**
         * [steps] as a leg keeps them: the last ends at the leg's end, and a leg with no way said
         * and no stop named is no leg.
         */
        fun tidy(steps: List<Step>): List<Step> {
            if (steps.isEmpty()) return steps
            val s = if (steps.last().to == null) steps else steps + Step(null)
            return if (s.all { it.way == null } && s.none { it.to != null }) emptyList() else s
        }
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
        val cx = (x1 + x2) / 2 - dy * BOW * 2
        val cy = (y1 + y2) / 2 + dx * BOW * 2
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

    /** Latitude as the map's flat projection spaces it, in radians; the map's gliding icons use it too. */
    fun mercator(lat: Double) = kotlin.math.ln(kotlin.math.tan(PI / 4 + Math.toRadians(lat.coerceIn(-85.0, 85.0)) / 2))

    /** The latitude of a height on the map's flat projection, the inverse of [mercator]. */
    fun latitude(y: Double) = Math.toDegrees(2 * kotlin.math.atan(kotlin.math.exp(y)) - PI / 2)

    // The same in degree-like units, so a height compares with longitude.
    private fun mercY(lat: Double) = Math.toDegrees(mercator(lat))

    private fun latOf(y: Double) = latitude(Math.toRadians(y))

    /** How far apart in longitude two ends must be before a flight could have gone either way. */
    const val FAR_DEGREES = 100.0

    /**
     * The line of a leg from [a] to [b], each step drawn its own way between its own stops, as
     * [pointOf] places them. A stop that cannot be placed sits evenly between those that can.
     * A flight arcs from airport to airport, the long way round on its longest hop with
     * [around]; a train or a road follows the real line between two stops the user named; a
     * step with no way said is a straight line, or nothing where it leaves from or comes back to
     * the trip's own start and end ([fromHome], [toHome]), so the way there begins at the
     * airport, not the front door.
     */
    fun route(
        a: Pair<Double, Double>, b: Pair<Double, Double>, steps: List<Step>,
        pointOf: (String) -> Pair<Double, Double>? = { null },
        follow: Follow? = null,
        around: Boolean = false,
        fromHome: Boolean = false,
        toHome: Boolean = false,
    ): List<Pair<List<Pair<Double, Double>>, Travel?>> {
        val s = steps.ifEmpty { listOf(Step(null)) }
        val n = s.size
        val (known, at) = place(a, b, s, pointOf)
        // The other way round is one long flight across the globe, not every hop of it.
        val longest = s.indices.filter { s[it].way == Travel.Plane }
            .maxByOrNull { kotlin.math.abs(nearest(at[it], at[it + 1]).second - at[it].second) }
        return s.indices.mapNotNull { k ->
            val x = at[k]; val y = at[k + 1]
            val w = s[k].way
            if (n > 1 && (x == y || Distance.km(x, y) < 1.0)) return@mapNotNull null
            when {
                w == null && ((k == 0 && fromHome) || (k == n - 1 && toHome)) -> null
                w == null -> listOf(x, nearest(x, y)) to null
                w == Travel.Plane -> points(x, y, w, around && k == longest) to w
                // Only between two real stops: a guessed one is no place a line runs through.
                known[k] != null && known[k + 1] != null -> (followed(follow, w, x, y) ?: points(x, y, w)) to w
                else -> points(x, y, w) to w
            }
        }
    }

    /**
     * Where each stop of a leg from [a] to [b] sits as [route] draws it, the leg's own ends first
     * and last: a stop [pointOf] cannot place sits evenly between those that can.
     */
    fun stops(
        a: Pair<Double, Double>, b: Pair<Double, Double>, steps: List<Step>,
        pointOf: (String) -> Pair<Double, Double>?,
    ): List<Pair<Double, Double>> = place(a, b, steps.ifEmpty { listOf(Step(null)) }, pointOf).second.toList()

    /**
     * Every stop of the leg, on the short way round from [a]: as [pointOf] places it, null where
     * it cannot; and with those filled in evenly between their placed neighbours.
     */
    private fun place(
        a: Pair<Double, Double>, b: Pair<Double, Double>, s: List<Step>,
        pointOf: (String) -> Pair<Double, Double>?,
    ): Pair<Array<Pair<Double, Double>?>, Array<Pair<Double, Double>>> {
        val n = s.size
        val known = arrayOfNulls<Pair<Double, Double>>(n + 1)
        known[0] = a
        known[n] = nearest(a, b)
        for (i in 1 until n) known[i] = s[i - 1].to?.let(pointOf)?.let { nearest(a, it) }
        val at = Array(n + 1) { known[it] ?: a }
        var i = 1
        while (i < n) {
            if (known[i] != null) { i++; continue }
            var j = i
            while (known[j] == null) j++
            val x = at[i - 1]; val y = known[j]!!
            for (k in i until j) {
                val f = (k - i + 1).toDouble() / (j - i + 1)
                at[k] = (x.first + (y.first - x.first) * f) to (x.second + (y.second - x.second) * f)
            }
            i = j
        }
        return known to at
    }

    /**
     * The real line between [x] and [y] as [follow] finds it, asked with longitudes the network
     * knows and handed back on the same turn of the globe as [x], which the leg may have moved.
     */
    private fun followed(follow: Follow?, w: Travel, x: Pair<Double, Double>, y: Pair<Double, Double>): List<Pair<Double, Double>>? {
        follow ?: return null
        fun wrap(lon: Double) = if (lon in -180.0..180.0) lon else ((lon + 540.0) % 360.0) - 180.0
        val shift = x.second - wrap(x.second)
        val line = follow(w, x.first to wrap(x.second), y.first to wrap(y.second)) ?: return null
        return if (shift == 0.0) line else line.map { it.first to it.second + shift }
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
