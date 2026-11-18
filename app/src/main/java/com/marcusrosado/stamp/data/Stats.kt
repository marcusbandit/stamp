package com.marcusrosado.stamp.data

import java.time.LocalDate
import java.time.YearMonth

/**
 * The numbers the Passport swipes through, beyond the countries: worked out on the phone from
 * the user's own marks and trips, with nothing guessed. Where a date or a place is not known,
 * it counts for less, never for more.
 */
object TravelStats {
    /** The first day a date as precise as the user remembers it can mean. */
    fun earliest(d: PartialDate): LocalDate = LocalDate.of(d.year, d.month ?: 1, d.day ?: 1)

    /** The last day it can mean: "Apr 2023" ends on the 30th, "2019" on New Year's Eve. */
    fun latest(d: PartialDate): LocalDate = when {
        d.month != null && d.day != null -> LocalDate.of(d.year, d.month, d.day)
        d.month != null -> YearMonth.of(d.year, d.month).atEndOfMonth()
        else -> LocalDate.of(d.year, 12, 31)
    }

    /**
     * The days [t] surely covered, as epoch days, or null when it has no date at all. Every date
     * on it counts, the trip's own and each stop's. A vague date is read the way that makes the
     * trip shortest: it began no earlier than the latest day its first date can mean and ended
     * no later than the earliest its last can. A trip whose dates leave no span is one day, the
     * least it can have been.
     */
    fun span(t: Trip): LongRange? {
        val dates = listOfNotNull(t.date, t.until) + t.arrived.values + t.left.values
        if (dates.isEmpty()) return null
        val lo = dates.minOf { latest(it).toEpochDay() }
        val hi = dates.maxOf { earliest(it).toEpochDay() }
        return if (hi < lo) lo..lo else lo..hi
    }

    /**
     * Days spent on the [trips] that [abroad] says were away from home, each day once however
     * many trips cover it, up to [today] and, with [within], only the days inside it. The stay
     * lived in now is home, so it never counts. Trips without a date add nothing.
     */
    fun daysAbroad(trips: List<Trip>, abroad: (Trip) -> Boolean, today: LocalDate, within: LongRange? = null): Int {
        val last = minOf(today.toEpochDay(), within?.last ?: Long.MAX_VALUE)
        val first = within?.first ?: Long.MIN_VALUE
        val spans = trips.asSequence()
            .filter { !it.now && abroad(it) }
            .mapNotNull { span(it) }
            .map { maxOf(it.first, first)..minOf(it.last, last) }
            .filter { !it.isEmpty() }
            .sortedBy { it.first }
            .toList()
        var days = 0L
        var end: Long? = null
        for (s in spans) {
            // Only the part after what is counted already: overlapping trips share their days.
            val from = end?.let { maxOf(s.first, it + 1) } ?: s.first
            if (s.last >= from) days += s.last - from + 1
            end = maxOf(end ?: s.last, s.last)
        }
        return days.toInt()
    }

    /** The days of [year], as epoch days, for [daysAbroad]'s `within`. */
    fun yearDays(year: Int): LongRange = LocalDate.of(year, 1, 1).toEpochDay()..LocalDate.of(year, 12, 31).toEpochDay()

    /**
     * The trips that count for the numbers: those on a place the user has been to or lived in,
     * by the land they started from. A trip planned to a place only wanted is no travel yet.
     */
    fun counted(trips: List<Trip>, visits: Map<String, Visit>): List<Trip> =
        trips.filter { t -> t.home?.let { visits[it] }?.state?.counts == true }

    /**
     * Kilometres along the Earth between the stops of [t], through the airports and stations
     * each leg names, in great circles. [endPoint] places a stop, or [Leg.START] and [Leg.END];
     * [stopPoint] places a stop inside a leg. The way there and the way home count only where
     * the user said how they went, as the map draws them, and a leg with an end that cannot be
     * placed counts nothing. With [today], nothing that is surely still to come counts: a trip
     * whose every date is later adds nothing, and nor does a leg to a stop reached later.
     */
    fun tripKm(
        t: Trip,
        endPoint: (String) -> Pair<Double, Double>?,
        stopPoint: (String) -> Pair<Double, Double>?,
        today: LocalDate? = null,
    ): Double {
        if (today != null) {
            val dates = listOfNotNull(t.date, t.until) + t.arrived.values + t.left.values
            if (dates.isNotEmpty() && dates.all { earliest(it) > today }) return 0.0
        }
        var km = 0.0
        for ((a, b) in t.legEnds) {
            val leg = t.legBetween(a, b)
            if ((a == Leg.START || b == Leg.END) && leg == null) continue
            if (today != null) {
                // When the leg was taken, as near as the trip says: leaving one stop, or reaching the next.
                val at = t.left[a] ?: (if (b == Leg.END) t.until else (t.arrived[b] ?: (if (a == Leg.START) t.date else null)))
                if (at != null && earliest(at) > today) continue
            }
            val from = endPoint(a) ?: continue
            val to = endPoint(b) ?: continue
            val via = leg?.steps.orEmpty().mapNotNull { s -> s.to?.let(stopPoint) }
            km += (listOf(from) + via + to).zipWithNext().sumOf { (x, y) -> Distance.greatCircleKm(x, y) }
        }
        return km
    }

    /**
     * The cities and the regions the user has been to, each once, over every visit that counts:
     * the places on its trips and the ones lived in, and for regions the ones added by hand and
     * those the cities are in, as [regionOf] finds them. Pins count as cities, since they are
     * places the city list lacks.
     */
    fun placesBeen(visits: Collection<Visit>, regionOf: (String) -> String?): Pair<Set<String>, Set<String>> {
        val cities = HashSet<String>()
        val regions = HashSet<String>()
        for (v in visits) {
            if (!v.state.counts) continue
            cities += v.cities
            for (p in v.livedPlaces) if ('.' in p) regions += p else cities += p
            regions += v.regions
        }
        for (c in cities) regionOf(c)?.let { regions += it }
        return cities to regions
    }

    /**
     * [visits] as they stood in one year: only the trips in [tripIds], and only the places that
     * had one. What was said of a place without a date, such as a region added by hand, is not
     * of any year and is left out.
     */
    fun visitsOf(visits: Map<String, Visit>, tripIds: Set<String>): Map<String, Visit> =
        visits.mapNotNull { (id, v) ->
            if (!v.state.counts) return@mapNotNull null
            val ts = v.trips.filter { it.id in tripIds }
            if (ts.isEmpty()) null
            else id to v.copy(trips = ts, regions = emptyList(), livedPlaces = emptyList(), wantRegions = emptyList(), wantCities = emptyList())
        }.toMap()
}
