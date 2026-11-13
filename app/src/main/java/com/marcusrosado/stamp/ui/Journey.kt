package com.marcusrosado.stamp.ui

import androidx.compose.runtime.compositionLocalOf
import com.marcusrosado.stamp.data.Airport
import com.marcusrosado.stamp.data.Airports
import com.marcusrosado.stamp.data.Home
import com.marcusrosado.stamp.data.Homes
import com.marcusrosado.stamp.data.Leg
import com.marcusrosado.stamp.data.LegPath
import com.marcusrosado.stamp.data.Places
import com.marcusrosado.stamp.data.Travel
import com.marcusrosado.stamp.data.Trip
import com.marcusrosado.stamp.data.Visit
import com.marcusrosado.stamp.data.isPin
import com.marcusrosado.stamp.data.placeIdOf

/**
 * What a trip's journey needs from beyond its own country: every visit, for the homes it
 * sets out from and pins kept elsewhere, the cities, and the airports.
 */
class Journey(val visits: Map<String, Visit>, val places: Places?, val airports: Airports?) {
    /** A city, or a pin on any visit, by id. */
    fun place(stop: String): PlaceInfo? {
        val id = placeIdOf(stop)
        return if (isPin(id)) visits.values.firstNotNullOfOrNull { v -> if (id in v.pins) placeOf(id, v, places) else null }
        else placeOf(id, null, places)
    }

    /** The home lived in when [trip] began, or null when no address was given. */
    fun homeFor(trip: Trip): Home? = Homes.at(visits, trip.date)

    /** Where [trip] set out from: the place it names, else home when it began. */
    fun startOf(trip: Trip): PlaceInfo? =
        trip.start?.let { place(it) } ?: homeFor(trip)?.let { placeOf(it.place, it.visit, places) }

    /** The home lived in when [trip] ended. */
    fun homeAtEnd(trip: Trip): Home? = Homes.at(visits, trip.until ?: trip.date)

    /** Where [trip] went back to: the place it names, else home when it ended. */
    fun endOf(trip: Trip): PlaceInfo? =
        trip.end?.let { place(it) } ?: homeAtEnd(trip)?.let { placeOf(it.place, it.visit, places) }

    /** One end of a leg: a stop, or where the trip set out from or went back to. */
    fun endPoint(trip: Trip, visit: Visit?, id: String): PlaceInfo? = when (id) {
        Leg.START -> startOf(trip)
        Leg.END -> endOf(trip)
        // A leg arrives where the user slept, which may be next to the town the stop is about.
        else -> trip.bedOf(id).let { placeOf(it, visit, places) ?: place(it) }
    }

    fun airport(code: String): Airport? = airports?.get(code)

    /**
     * Every leg of [trip] the map draws, with each line's way, plus the airports it passes
     * through as ghost points. The way there and the way home only draw once the user said how.
     */
    fun routes(trip: Trip, visit: Visit?): List<MapRoute> {
        // A stop that cannot be placed is skipped and its neighbours joined; the way there and
        // the way home stay with whichever stop is now first or last.
        val stops = trip.cities.filter { endPoint(trip, visit, it) != null }
        if (stops.isEmpty()) return emptyList()
        val ends = (listOf(Leg.START) + stops + Leg.END).zipWithNext()
        return ends.flatMap { (a, b) -> legRoute(trip, visit, a, b) } + stops.flatMap { dayRoutes(trip, visit, it) }
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
            MapRoute(listOf(base.lat to base.lon, LegPath.nearest(base.lat to base.lon, p.lat to p.lon)), null)
        }
    }

    private fun legRoute(trip: Trip, visit: Visit?, a: String, b: String): List<MapRoute> {
        val leg = when {
            a == Leg.START -> trip.legBetween(a, trip.cities.first())
            b == Leg.END -> trip.legBetween(trip.cities.last(), b)
            else -> trip.legBetween(a, b)
        }
        if ((a == Leg.START || b == Leg.END) && leg == null) return emptyList()
        val from = endPoint(trip, visit, a) ?: return emptyList()
        val to = endPoint(trip, visit, b) ?: return emptyList()
        val via = leg?.via.orEmpty().mapNotNull { airport(it)?.let { ap -> ap.lat to ap.lon } }
        return LegPath.route(from.lat to from.lon, to.lat to to.lon, leg?.ways.orEmpty(), via)
            .map { (points, way) -> MapRoute(points, way) } +
            via.map { MapRoute(listOf(it), null, ghost = true) }
    }
}

val LocalJourney = compositionLocalOf { Journey(emptyMap(), null, null) }
