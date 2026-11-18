package com.marcusrosado.stamp

import com.marcusrosado.stamp.data.Distance
import com.marcusrosado.stamp.data.Leg
import com.marcusrosado.stamp.data.PartialDate
import com.marcusrosado.stamp.data.Step
import com.marcusrosado.stamp.data.TravelState
import com.marcusrosado.stamp.data.TravelStats
import com.marcusrosado.stamp.data.Travel
import com.marcusrosado.stamp.data.Trip
import com.marcusrosado.stamp.data.Visit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class StatsTest {
    private val today = LocalDate.of(2026, 6, 1)
    private fun day(y: Int, m: Int, d: Int) = PartialDate(y, m, d)

    @Test fun greatCirclesMatchKnownDistances() {
        // One degree along the equator, and the same across the date line.
        assertEquals(111.19, Distance.greatCircleKm(0.0 to 0.0, 0.0 to 1.0), 0.01)
        assertEquals(111.19, Distance.greatCircleKm(0.0 to 179.5, 0.0 to -179.5), 0.01)
        // London to New York, about 5,570 km, where the flat sum is far off.
        val ny = Distance.greatCircleKm(51.5074 to -0.1278, 40.7128 to -74.0060)
        assertEquals(5570.0, ny, 5.0)
        assertTrue("flat-earth overshoots an ocean crossing", Distance.km(51.5074 to -0.1278, 40.7128 to -74.0060) > ny + 50)
        assertEquals(0.0, Distance.greatCircleKm(10.0 to 20.0, 10.0 to 20.0), 1e-9)
        // Pole to pole is half the way round.
        assertEquals(Math.PI * 6371.0, Distance.greatCircleKm(90.0 to 0.0, -90.0 to 0.0), 0.01)
    }

    @Test fun aSpanReadsVagueDatesAsShortAsTheyCanBe() {
        assertNull(TravelStats.span(Trip("a")))
        assertEquals(9, TravelStats.span(Trip("a", date = day(2023, 4, 2), until = day(2023, 4, 10)))!!.count())
        // A month alone, or a year alone, is the one day it surely holds.
        assertEquals(1, TravelStats.span(Trip("a", date = PartialDate(2023, 4)))!!.count())
        assertEquals(1, TravelStats.span(Trip("a", date = PartialDate(2019)))!!.count())
        // April to May: the last of April and the first of May.
        assertEquals(2, TravelStats.span(Trip("a", date = PartialDate(2023, 4), until = PartialDate(2023, 5)))!!.count())
        // Stops' own dates stretch the trip.
        val stops = Trip(
            "a", date = day(2023, 4, 2), cities = listOf("x", "y"),
            arrived = mapOf("y" to day(2023, 4, 5)), left = mapOf("y" to day(2023, 4, 8)),
        )
        assertEquals(7, TravelStats.span(stops)!!.count())
    }

    @Test fun daysAbroadCountEachDayOnceAndLeaveHomeOut() {
        val trips = listOf(
            Trip("rome", date = day(2023, 4, 1), until = day(2023, 4, 10), lands = listOf("ITA")),
            // Overlaps Rome by five days: only its last five are new.
            Trip("alps", date = day(2023, 4, 6), until = day(2023, 4, 15), lands = listOf("CHE")),
            Trip("home", date = day(2023, 5, 1), until = day(2023, 5, 20), lands = listOf("DNK")),
            Trip("living", date = day(2020, 1, 1), now = true, lands = listOf("DNK")),
            Trip("undated", lands = listOf("ESP")),
            // Planned, not yet been: nothing after today counts.
            Trip("later", date = day(2026, 5, 30), until = day(2026, 6, 10), lands = listOf("JPN")),
        )
        val abroad = { t: Trip -> "DNK" !in t.lands }
        assertEquals(15 + 3, TravelStats.daysAbroad(trips, abroad, today))
        assertEquals(15, TravelStats.daysAbroad(trips, abroad, today, TravelStats.yearDays(2023)))
        assertEquals(0, TravelStats.daysAbroad(trips, abroad, today, TravelStats.yearDays(2024)))
        // A trip over New Year splits between the two years.
        val nye = listOf(Trip("nye", date = day(2022, 12, 30), until = day(2023, 1, 2), lands = listOf("ITA")))
        assertEquals(2, TravelStats.daysAbroad(nye, abroad, today, TravelStats.yearDays(2022)))
        assertEquals(2, TravelStats.daysAbroad(nye, abroad, today, TravelStats.yearDays(2023)))
    }

    @Test fun kilometresFollowTheLegsAndOnlyTheWaysThereThatWereSaid() {
        val at = mapOf(
            "a" to (0.0 to 0.0), "b" to (0.0 to 1.0), "c" to (0.0 to 2.0),
            Leg.START to (0.0 to -1.0), Leg.END to (0.0 to -1.0), "apt:XXX" to (1.0 to 1.0),
        )
        val one = 111.19
        val plain = Trip("t", cities = listOf("a", "b", "c"))
        assertEquals(2 * one, TravelStats.tripKm(plain, { at[it] }, { at[it] }), 0.1)
        // Said how they got there: the way there counts, the way home still does not.
        val there = plain.copy(legs = listOf(Leg(Leg.START, "a", listOf(Step(Travel.Train)))))
        assertEquals(3 * one, TravelStats.tripKm(there, { at[it] }, { at[it] }), 0.1)
        // A leg through an airport goes by way of it.
        val via = plain.copy(legs = listOf(Leg("a", "b", listOf(Step(Travel.Plane, "apt:XXX"), Step(Travel.Bus)))))
        val expected = Distance.greatCircleKm(0.0 to 0.0, 1.0 to 1.0) + Distance.greatCircleKm(1.0 to 1.0, 0.0 to 1.0) + one
        assertEquals(expected, TravelStats.tripKm(via, { at[it] }, { at[it] }), 0.1)
        // A stop that cannot be placed takes its legs with it.
        assertEquals(0.0, TravelStats.tripKm(plain, { if (it == "b") null else at[it] }, { at[it] }), 1e-9)
    }

    @Test fun placesBeenCountCitiesRegionsAndLivedPlacesOnce() {
        val region = mapOf("c1" to "JP.13", "c2" to "JP.13", "c3" to "JP.26")
        val visits = listOf(
            Visit(
                TravelState.Visited,
                trips = listOf(Trip("t1", cities = listOf("c1", "c2")), Trip("t2", cities = listOf("c2", "pin-x"))),
                regions = listOf("JP.01"),
            ),
            Visit(TravelState.Lived, trips = listOf(Trip("t3", cities = listOf("c3"))), livedPlaces = listOf("c9", "DK.84")),
            // Wanted places are not places been.
            Visit(TravelState.Want, trips = listOf(Trip("t4", cities = listOf("c7"))), regions = listOf("JP.40")),
        )
        val (cities, regions) = TravelStats.placesBeen(visits) { region[it] }
        assertEquals(setOf("c1", "c2", "pin-x", "c3", "c9"), cities)
        assertEquals(setOf("JP.13", "JP.26", "JP.01", "DK.84"), regions)
    }

    @Test fun aYearsVisitsHoldOnlyThatYearsTrips() {
        val a = Trip("a", date = PartialDate(2023))
        val b = Trip("b", date = PartialDate(2024))
        val visits = mapOf(
            "ITA" to Visit(TravelState.Visited, trips = listOf(a, b), regions = listOf("IT.07")),
            "FRA" to Visit(TravelState.Visited, trips = listOf(b)),
            "ISL" to Visit(TravelState.Want, trips = listOf(a)),
        )
        val y = TravelStats.visitsOf(visits, setOf("a"))
        assertEquals(setOf("ITA"), y.keys)
        assertEquals(listOf(a), y.getValue("ITA").trips)
        assertTrue("a hand-added region has no year", y.getValue("ITA").regions.isEmpty())
    }
}
