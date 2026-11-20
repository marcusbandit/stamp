package com.marcusrosado.stamp

import com.marcusrosado.stamp.data.AutoTrips
import com.marcusrosado.stamp.data.City
import com.marcusrosado.stamp.data.PartialDate
import com.marcusrosado.stamp.data.SeenFrom
import com.marcusrosado.stamp.data.SeenPoint
import com.marcusrosado.stamp.data.TownIndex
import com.marcusrosado.stamp.data.Trip
import com.marcusrosado.stamp.data.TravelState
import com.marcusrosado.stamp.data.VisitStore
import com.marcusrosado.stamp.ui.addLabel
import com.marcusrosado.stamp.ui.townsLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class AutoTripsTest {
    private val home = City("1", "Hometown", "Hometown", "AA", "01", 10.0, 10.0, 50_000)
    private val bay = City("2", "Baytown", "Baytown", "BB", "01", 12.0, 14.0, 200_000)
    private val hill = City("3", "Hillville", "Hillville", "BB", "02", 12.1, 14.2, 8_000)
    private val far = City("4", "Farport", "Farport", "CC", "01", -20.0, 100.0, 90_000)
    private val towns = TownIndex(listOf(home, bay, hill, far))
    private val utc = ZoneOffset.UTC

    private fun at(day: LocalDate, hour: Int, c: City, jitter: Double = 0.0) =
        SeenPoint(day.atTime(hour, 0).toInstant(utc).toEpochMilli(), c.lat + jitter, c.lon + jitter, SeenFrom.Photo)

    /** A month of photos at home, so home can be guessed. */
    private fun homeDays(from: LocalDate, n: Int) = (0 until n).map { at(from.plusDays(it.toLong()), 12, home) }

    @Test fun daysAwayInARowMakeOneTripWithItsTownsInOrder() {
        val d = LocalDate.of(2023, 4, 1)
        val pts = homeDays(d, 30) + listOf(
            at(d.plusDays(31), 10, bay), at(d.plusDays(31), 15, bay, 0.01),
            at(d.plusDays(32), 10, hill), at(d.plusDays(32), 11, hill, 0.01),
            // A day with no photo inside the trip keeps it one trip.
            at(d.plusDays(34), 10, bay),
        ) + homeDays(d.plusDays(35), 5)
        val trips = AutoTrips.find(pts, towns, utc)
        assertEquals(1, trips.size)
        assertEquals(listOf("Baytown", "Hillville"), trips[0].stops.map { it.city.name })
        assertEquals(3, trips[0].stops[0].spots)
    }

    @Test fun quietDaysInOneCountryKeepOneTrip() {
        val d = LocalDate.of(2023, 4, 1)
        val pts = homeDays(d, 30) + listOf(
            at(d.plusDays(31), 10, bay), at(d.plusDays(31), 11, bay),
            // Five days with no photo, still in the same country.
            at(d.plusDays(37), 10, hill), at(d.plusDays(37), 11, hill),
            // Five more, then another country: a new trip.
            at(d.plusDays(43), 10, far), at(d.plusDays(43), 11, far),
        )
        assertEquals(listOf(listOf("Baytown", "Hillville"), listOf("Farport")), AutoTrips.find(pts, towns, utc).map { t -> t.stops.map { it.city.name } })
    }

    @Test fun aDayAtHomeSplitsTwoTrips() {
        val d = LocalDate.of(2023, 4, 1)
        val pts = homeDays(d, 30) + listOf(
            at(d.plusDays(31), 10, bay), at(d.plusDays(31), 11, bay),
            at(d.plusDays(32), 12, home),
            at(d.plusDays(33), 10, hill), at(d.plusDays(33), 11, hill),
        )
        assertEquals(2, AutoTrips.find(pts, towns, utc).size)
    }

    @Test fun oneShotFromATrainIsNoStop() {
        val d = LocalDate.of(2023, 4, 1)
        val pts = homeDays(d, 30) + listOf(
            at(d.plusDays(31), 9, hill),
            at(d.plusDays(31), 12, bay), at(d.plusDays(31), 13, bay),
        )
        assertEquals(listOf("Baytown"), AutoTrips.find(pts, towns, utc).single().stops.map { it.city.name })
    }

    @Test fun aHomeTheUserGaveWinsOverTheGuess() {
        val d = LocalDate.of(2023, 4, 1)
        // Most photos are in Baytown, but the user said they live in Hometown.
        val pts = (0 until 30).flatMap { listOf(at(d.plusDays(it.toLong()), 10, bay), at(d.plusDays(it.toLong()), 11, bay)) }
        val trips = AutoTrips.find(pts, towns, utc, homeAt = { home.lat to home.lon })
        assertEquals(listOf("Baytown"), trips.single().stops.map { it.city.name })
        assertTrue(AutoTrips.find(pts, towns, utc).isEmpty())
    }

    @Test fun nearbyPhotosNameTheBigTown() {
        // Near Hillville but in Baytown's reach too, Baytown is ten times bigger.
        assertEquals("Baytown", towns.townOf(12.08, 14.17)?.name)
        assertEquals("Hillville", towns.townOf(12.1, 14.2)?.name)
        assertEquals(null, towns.townOf(40.0, 40.0)?.name)
    }

    @Test fun aTripTheUserHasIsKnown() {
        val d = LocalDate.of(2023, 5, 2)
        val pts = homeDays(LocalDate.of(2023, 4, 1), 30) + listOf(at(d, 10, far), at(d.plusDays(1), 10, far))
        val trip = AutoTrips.find(pts, towns, utc).single()
        val lands = setOf("CC")
        assertTrue(AutoTrips.known(trip, lands, listOf(Trip("a", date = PartialDate(2023, 5, 3), lands = listOf("CC"))), utc))
        assertTrue(AutoTrips.known(trip, lands, listOf(Trip("a", date = PartialDate(2023, 5), lands = listOf("CC"))), utc))
        assertFalse(AutoTrips.known(trip, lands, listOf(Trip("a", date = PartialDate(2023, 7), lands = listOf("CC"))), utc))
        assertFalse(AutoTrips.known(trip, lands, listOf(Trip("a", date = PartialDate(2023, 5, 3), lands = listOf("BB"))), utc))
        // A logged trip whose stay runs across the found one, though none of its dates is inside it.
        val around = Trip(
            "a", date = PartialDate(2023, 4, 20), cities = listOf("4"),
            arrived = mapOf("4" to PartialDate(2023, 4, 20)), left = mapOf("4" to PartialDate(2023, 5, 20)), lands = listOf("CC"),
        )
        assertTrue(AutoTrips.known(trip, lands, listOf(around), utc))
    }

    @Test fun aFoundTripTakesThePlaceOfTheEmptyOne() {
        val dir = java.nio.file.Files.createTempDirectory("stamp").toFile().apply { deleteOnExit() }
        val s = VisitStore(java.io.File(dir, "visits.json"))
        s.set("BBB", TravelState.Visited)
        assertEquals(1, s.visits.value["BBB"]!!.trips.size)
        val d = LocalDate.of(2023, 4, 1)
        val pts = homeDays(d, 30) + listOf(
            at(d.plusDays(31), 10, bay), at(d.plusDays(31), 11, bay),
            at(d.plusDays(32), 10, hill), at(d.plusDays(33), 11, hill),
        )
        val found = AutoTrips.find(pts, towns, utc).map { it.copy(land = "BBB") }
        s.addFoundTrips(found)
        val trip = s.visits.value["BBB"]!!.trips.single()
        assertEquals(listOf("2", "3"), trip.cities)
        assertEquals(PartialDate(2023, 5, 3), trip.arrived["3"])
        assertEquals(PartialDate(2023, 5, 4), trip.left["3"])
    }

    @Test fun theButtonSaysWhatItAdds() {
        assertEquals("Done", addLabel(0, 0))
        assertEquals("Add 1 country", addLabel(1, 0))
        assertEquals("Add 2 countries and 1 trip", addLabel(2, 1))
        assertEquals("Add 3 trips", addLabel(0, 3))
        assertEquals("Baytown, Hillville", townsLine(listOf("Baytown", "Hillville")))
        assertEquals("A, B and 3 more", townsLine(listOf("A", "B", "C", "D", "E")))
    }

    @Test fun visitsCountedByHandStay() {
        val dir = java.nio.file.Files.createTempDirectory("stamp").toFile().apply { deleteOnExit() }
        val s = VisitStore(java.io.File(dir, "visits.json"))
        s.set("BBB", TravelState.Visited)
        val blank = s.visits.value["BBB"]!!.trips[0].id
        s.addTrip("BBB")
        s.addTrip("BBB")
        assertEquals(3, s.visits.value["BBB"]!!.trips.size)
        val d = LocalDate.of(2023, 4, 1)
        val pts = homeDays(d, 30) + listOf(at(d.plusDays(31), 10, bay), at(d.plusDays(31), 11, bay))
        s.addFoundTrips(AutoTrips.find(pts, towns, utc).map { it.copy(land = "BBB") })
        assertEquals(4, s.visits.value["BBB"]!!.trips.size)
        assertTrue(s.visits.value["BBB"]!!.trips.any { it.id == blank })
    }

    @Test fun monthsAwayWithFewPhotosAreAStay() {
        val d = LocalDate.of(2023, 1, 1)
        // A photo pair every six days for half a year in one country, home never seen.
        val pts = (0 until 30).flatMap { i -> listOf(at(d.plusDays(i * 6L), 10, bay), at(d.plusDays(i * 6L), 11, hill)) }
        assertTrue(AutoTrips.find(pts, towns, utc, homeAt = { home.lat to home.lon }).isEmpty())
    }

    private fun day(y: Int, m: Int, d: Int) = PartialDate(y, m, d)

    /** A found trip with [stops] as (town, first day, last day) in May 2023. */
    private fun foundOf(vararg stops: Triple<City, Int, Int>): com.marcusrosado.stamp.data.FoundTrip {
        fun ms(d: Int) = LocalDate.of(2023, 5, d).atTime(12, 0).toInstant(utc).toEpochMilli()
        val list = stops.map { (c, a, b) -> com.marcusrosado.stamp.data.FoundStop(c, ms(a), ms(b), 3) }
        return com.marcusrosado.stamp.data.FoundTrip(list.minOf { it.firstMs }, list.maxOf { it.lastMs }, list, 9, "BB", listOf("BB"))
    }

    @Test fun aTownThatFitsBetweenTwoStopsGoesBetweenThem() {
        // The user has Baytown 1 to 3 May and Farport 8 to 9 May; the photos show Hillville 4 to 6 May.
        val mine = Trip(
            "t", date = day(2023, 5, 1), cities = listOf("2", "4"),
            arrived = mapOf("2" to day(2023, 5, 1), "4" to day(2023, 5, 8)), left = mapOf("2" to day(2023, 5, 3)),
            lands = listOf("BB"),
        )
        val u = AutoTrips.update(mine, foundOf(Triple(bay, 1, 3), Triple(hill, 4, 6)), utc)!!
        assertEquals(listOf(1), u.stops.map { it.at })
        assertEquals("Hillville", u.stops.single().city.name)
        assertEquals(day(2023, 5, 4), u.stops.single().arrived)
        // The days the user gave stay; only the missing one is filled.
        assertTrue(u.arrived.isEmpty())
        assertTrue(u.left.isEmpty())
    }

    @Test fun aTownThatOverlapsAStopIsNotClearAndStaysOut() {
        val mine = Trip(
            "t", cities = listOf("2", "4"),
            arrived = mapOf("2" to day(2023, 5, 1), "4" to day(2023, 5, 5)), left = mapOf("2" to day(2023, 5, 4)),
            lands = listOf("BB"),
        )
        // Hillville 3 to 6 May runs into both stops.
        val u = AutoTrips.update(mine, foundOf(Triple(hill, 3, 6)), utc)
        assertTrue(u == null || u.stops.isEmpty())
    }

    @Test fun aStopWithNoDaysGetsThePhotosDays() {
        val mine = Trip("t", cities = listOf("2"), lands = listOf("BB"))
        val u = AutoTrips.update(mine, foundOf(Triple(bay, 2, 4)), utc)!!
        assertEquals(day(2023, 5, 2), u.arrived["2"])
        assertEquals(day(2023, 5, 4), u.left["2"])
        assertEquals(day(2023, 5, 2), u.date)
    }

    @Test fun aTownTheUserTurnedDownStaysOut() {
        val mine = Trip(
            "t", cities = listOf("2"), arrived = mapOf("2" to day(2023, 5, 1)), left = mapOf("2" to day(2023, 5, 2)),
            date = day(2023, 5, 1), lands = listOf("BB"), notStops = setOf("3"),
        )
        assertEquals(null, AutoTrips.update(mine, foundOf(Triple(bay, 1, 2), Triple(hill, 3, 4)), utc))
    }

    @Test fun theStoreFillsATripInAndKeepsWhatTheUserGave() {
        val dir = java.nio.file.Files.createTempDirectory("stamp").toFile().apply { deleteOnExit() }
        val s = VisitStore(java.io.File(dir, "visits.json"))
        s.set("BB", TravelState.Visited)
        val id = s.visits.value["BB"]!!.trips[0].id
        s.addCity("BB", id, "2")
        s.addCity("BB", id, "4")
        s.setStopDates(id, "2", day(2023, 5, 1), day(2023, 5, 3))
        s.setStopDates(id, "4", day(2023, 5, 8), null)
        val mine = s.visits.value["BB"]!!.trips.single()
        val u = AutoTrips.update(mine, foundOf(Triple(hill, 4, 6), Triple(far, 8, 9)), utc)!!
        s.applyTripUpdates(listOf(u))
        val t = s.visits.value["BB"]!!.trips.single()
        assertEquals(listOf("2", "3", "4"), t.cities)
        assertEquals(day(2023, 5, 8), t.arrived["4"])
        assertEquals(day(2023, 5, 9), t.left["4"])
    }
}
