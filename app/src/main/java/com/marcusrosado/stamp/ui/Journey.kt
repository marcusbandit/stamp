package com.marcusrosado.stamp.ui

import androidx.compose.runtime.compositionLocalOf
import com.marcusrosado.stamp.data.Airport
import com.marcusrosado.stamp.data.Airports
import com.marcusrosado.stamp.data.Home
import com.marcusrosado.stamp.data.Homes
import com.marcusrosado.stamp.data.Leg
import com.marcusrosado.stamp.data.LegPath
import com.marcusrosado.stamp.data.Places
import com.marcusrosado.stamp.data.Step
import com.marcusrosado.stamp.data.Travel
import com.marcusrosado.stamp.data.Trip
import com.marcusrosado.stamp.data.Visit
import com.marcusrosado.stamp.data.isPin
import com.marcusrosado.stamp.data.placeIdOf

/**
 * What a trip's journey needs from beyond its own country: every visit, for the homes it
 * sets out from and pins kept elsewhere, the cities, and the airports.
 */
class Journey(
    val visits: Map<String, Visit>,
    val places: Places?,
    val airports: Airports?,
    /** The rail and road network of the countries the trips go through, when it is on the phone. */
    val lines: com.marcusrosado.stamp.data.RouteLines? = null,
) {
    /** A city, or a pin on any visit, by id. */
    fun place(stop: String): PlaceInfo? {
        val id = placeIdOf(stop)
        return if (isPin(id)) visits.values.firstNotNullOfOrNull { v -> if (id in v.pins) placeOf(id, v, places) else null }
        else placeOf(id, null, places)
    }

    /** The home lived in when [trip] began, or null when no address was given. */
    fun homeFor(trip: Trip): Home? = Homes.at(visits, trip.date)

    /** Where [trip] set out from: the place it names, else the home town when it began. */
    fun startOf(trip: Trip): PlaceInfo? =
        trip.start?.let { place(it) } ?: homeFor(trip)?.let { townOf(it) }

    /**
     * The town a home is in, as a trip sets out from it: the biggest within 20 km in the same
     * country, where the main station and the airport are, not the front door. A home that is a
     * town is itself. Worked out once per home and kept while the city list stays the same.
     */
    fun townOf(home: Home): PlaceInfo? {
        val at = placeOf(home.place, home.visit, places) ?: return null
        val p = places ?: return at
        if (!isPin(home.place)) return at
        val key = "${home.place}@${at.lat},${at.lon}"
        synchronized(townCache) {
            val kept = townCache[p]?.get(key)
            if (kept != null) return placeOf(kept, null, p) ?: at
        }
        // A cheap box first: 20 km is under 0.2 degrees of latitude.
        val near = p.byId.values.filter { kotlin.math.abs(it.lat - at.lat) < 0.2 }
            .map { it to com.marcusrosado.stamp.data.Distance.km(at.lat to at.lon, it.lat to it.lon) }
        val country = near.minByOrNull { it.second }?.first?.country
        val town = near.filter { (c, km) -> km < 20 && c.country == country }.maxByOrNull { it.first.population }?.first
            ?: return at
        synchronized(townCache) { townCache.getOrPut(p) { HashMap() }[key] = town.id }
        return placeOf(town.id, null, p) ?: at
    }

    private companion object {
        /** Each home's town by city id, per city list, shared by every Journey built from it. */
        val townCache = java.util.WeakHashMap<Places, HashMap<String, String>>()
    }

    /** The home lived in when [trip] ended. */
    fun homeAtEnd(trip: Trip): Home? = Homes.at(visits, trip.until ?: trip.date)

    /** Where [trip] went back to: the place it names, else home when it ended. */
    fun endOf(trip: Trip): PlaceInfo? =
        trip.end?.let { place(it) } ?: homeAtEnd(trip)?.let { townOf(it) }

    /** One end of a leg: a stop, or where the trip set out from or went back to. */
    fun endPoint(trip: Trip, visit: Visit?, id: String): PlaceInfo? = when (id) {
        Leg.START -> startOf(trip)
        Leg.END -> endOf(trip)
        // A leg arrives where the user slept, which may be next to the town the stop is about.
        else -> trip.bedOf(id).let { placeOf(it, visit, places) ?: place(it) }
    }

    fun airport(code: String): Airport? = airports?.get(code)

    /** A stop inside a leg, an airport or a place, where it is on the map. */
    fun stopPoint(stop: String, visit: Visit? = null): Pair<Double, Double>? =
        Step.codeOf(stop)?.let { code -> airport(code)?.let { it.lat to it.lon } }
            ?: (placeOf(stop, visit, places) ?: place(stop))?.let { it.lat to it.lon }

    /** A stop inside a leg as its strip labels it: an airport's code, a town's name. */
    fun stopLabel(stop: String, visit: Visit? = null): String? =
        Step.codeOf(stop) ?: (placeOf(stop, visit, places) ?: place(stop))?.name?.substringBefore(',')?.trim()

    /**
     * Every leg of [trip] the map draws, with each line's way, plus the airports it passes
     * through as ghost points. The way there and the way home only draw once the user said how.
     */
    fun routes(trip: Trip, visit: Visit?): List<MapRoute> {
        // Homes are where the user lived, not a journey: nothing to draw between them.
        if (visit?.state == com.marcusrosado.stamp.data.TravelState.Lived) return emptyList()
        // A stop that cannot be placed is skipped and its neighbours joined by a plain line that
        // is no leg of the trip, so a tap on it opens the trip. The way there and the way home
        // only draw when the first and last stops themselves are placed.
        val all = listOf(Leg.START) + trip.cities + Leg.END
        val placed = all.indices.filter { it == 0 || it == all.lastIndex || endPoint(trip, visit, all[it]) != null }
        val stops = placed.drop(1).dropLast(1).map { all[it] }
        if (stops.isEmpty()) return emptyList()
        return placed.zipWithNext().flatMap { (i, j) -> legRoute(trip, visit, all[i], all[j], joined = j != i + 1) } +
            stops.flatMap { dayRoutes(trip, visit, it) }
    }

    /**
     * The day trips from a stop: out from where the user slept to each other place of the stop
     * and back, drawn as a leg not said, each place a dot of its own.
     */
    private fun dayRoutes(trip: Trip, visit: Visit?, stop: String): List<MapRoute> {
        val bed = trip.bedOf(stop)
        val base = placeOf(bed, visit, places) ?: place(bed) ?: return emptyList()
        val others = trip.placesAt(stop).filter { it != bed }
        return others.mapNotNull { id ->
            val p = placeOf(id, visit, places) ?: place(id) ?: return@mapNotNull null
            MapRoute(listOf(base.lat to base.lon, LegPath.nearest(base.lat to base.lon, p.lat to p.lon)), null, trip = trip.id)
        }
    }

    /**
     * The line from [a] to [b]. A [joined] line runs across a stop that cannot be placed: it is
     * no leg the trip keeps, so it draws plain and a tap on it opens the trip. The way there or
     * home across such a stop ends nowhere the map can draw, so it is left out.
     */
    private fun legRoute(trip: Trip, visit: Visit?, a: String, b: String, joined: Boolean): List<MapRoute> {
        if (joined && (a == Leg.START || b == Leg.END)) return emptyList()
        val leg = if (joined) null else trip.legBetween(a, b)
        if ((a == Leg.START || b == Leg.END) && leg == null) return emptyList()
        val from = endPoint(trip, visit, a) ?: return emptyList()
        val to = endPoint(trip, visit, b) ?: return emptyList()
        val steps = leg?.steps.orEmpty()
        // Each stop between looked up once, for the line and for its dot alike.
        val at = steps.mapNotNull { it.to }.distinct().associateWith { stopPoint(it, visit) }
        val follow = lines?.let { net -> { w: Travel, x: Pair<Double, Double>, y: Pair<Double, Double> -> net.follow(w, x, y) } }
        return LegPath.route(
            from.lat to from.lon, to.lat to to.lon, steps, { at[it] }, follow, leg?.around == true,
            fromHome = a == Leg.START, toHome = b == Leg.END,
        )
            .map { (points, way) -> MapRoute(points, way, trip = trip.id, leg = if (joined) null else a to b) } +
            // The stops between, airports and stations, as points that mark nothing.
            steps.dropLast(1).mapNotNull { st -> st.to?.let { at[it] } }
                .map { MapRoute(listOf(it), null, ghost = true, trip = trip.id) }
    }
}

val LocalJourney = compositionLocalOf { Journey(emptyMap(), null, null) }

/** Every trip's lines as the maps draw them, worked out once for all of them. */
val LocalMapRoutes = compositionLocalOf { emptyList<MapRoute>() }
