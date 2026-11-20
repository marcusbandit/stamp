package com.marcusrosado.stamp.data

/**
 * A place the user's photos put them during a trip that is not one of its stops: a town they
 * forgot to add. [at] is where it goes among the trip's stops, counted from 0, worked out from
 * when the photos were taken next to the stops around it.
 */
data class PhotoStop(
    val city: City,
    val at: Int,
    val firstMs: Long,
    val lastMs: Long,
    /** How many places seen put the user there. */
    val spots: Int,
)

/** Finds the stops a trip's photos show and the trip does not have yet. */
object PhotoStops {
    /** A stop of the trip: every place it covers, and when the user said they got there. */
    class Stop(val spots: List<Pair<Double, Double>>, val arrivedMs: Long?)

    /**
     * The towns [points] show the user in, other than [stops], in the order they go on the
     * trip. A point near a stop belongs to it, and one near [avoid] (home, where the trip set
     * out from) to nothing. Every other point goes to the town [cityAt] names for it; a town
     * seen only once is left out, since one photo from a train window is no stop. [skip] turns
     * down a town, such as one the user said was no stop.
     */
    fun find(
        points: List<SeenPoint>,
        stops: List<Stop>,
        cityAt: (Double, Double) -> City?,
        skip: (City) -> Boolean = { false },
        avoid: List<Pair<Double, Double>> = emptyList(),
    ): List<PhotoStop> {
        // The first time each stop shows in the photos, so a town found between two of them goes
        // between them. A date the user gave counts the same.
        val seenAt = arrayOfNulls<Long>(stops.size)
        class Found(val city: City, var first: Long, var last: Long, var spots: Int)
        val found = LinkedHashMap<String, Found>()
        for (p in points.sortedBy { it.ms }) {
            val at = p.lat to p.lon
            if (avoid.any { Distance.km(it, at) <= NEAR_KM }) continue
            val stop = stops.indices
                .map { i -> i to (stops[i].spots.minOfOrNull { Distance.km(it, at) } ?: Double.MAX_VALUE) }
                .filter { it.second <= NEAR_KM }
                .minByOrNull { it.second }?.first
            if (stop != null) {
                if (seenAt[stop] == null) seenAt[stop] = p.ms
                continue
            }
            val city = cityAt(p.lat, p.lon) ?: continue
            val f = found.getOrPut(city.id) { Found(city, p.ms, p.ms, 0) }
            f.last = p.ms
            f.spots++
        }
        val anchors = stops.indices.map { i -> listOfNotNull(seenAt[i], stops[i].arrivedMs).minOrNull() }
        return found.values
            .filter { it.spots >= MIN_SPOTS && !skip(it.city) }
            .map { f ->
                // After the last stop the user was at before these photos; with nothing to go
                // by, at the end, where a stop added by hand goes too.
                val at = if (anchors.all { it == null }) stops.size
                    else anchors.indexOfLast { it != null && it <= f.first } + 1
                PhotoStop(f.city, at, f.first, f.last, f.spots)
            }
            .sortedWith(compareBy({ it.at }, { it.firstMs }))
            .take(MAX)
    }

    /**
     * The town a photo at [lat], [lon] was taken in, from [cities]: the one that best weighs
     * size against distance, so a photo in a city's suburb names the city and one in a village
     * far from anywhere names the village. Null past [MAX_KM].
     */
    fun townOf(lat: Double, lon: Double, cities: Iterable<City>): City? {
        var best: City? = null
        var score = 0.0
        // A degree of latitude is 111 km; a box well past the reach rules out most cities cheaply.
        val box = MAX_KM / 111.0 * 2
        for (c in cities) {
            if (kotlin.math.abs(c.lat - lat) > box) continue
            val d = Distance.km(lat to lon, c.lat to c.lon)
            if (d > MAX_KM) continue
            val s = c.population.coerceAtLeast(1) / ((1 + d) * (1 + d))
            if (s > score) { score = s; best = c }
        }
        return best
    }

    /** How close a photo must be to a stop, or to home, to count as there. */
    const val NEAR_KM = 15.0

    /** How far a town may be from a photo and still be where it was taken. */
    const val MAX_KM = 30.0

    private const val MIN_SPOTS = 2

    /** At most this many suggestions on one trip. */
    private const val MAX = 6
}
