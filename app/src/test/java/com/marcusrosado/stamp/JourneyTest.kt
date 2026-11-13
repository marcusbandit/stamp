package com.marcusrosado.stamp

import com.marcusrosado.stamp.data.Airports
import com.marcusrosado.stamp.data.Homes
import com.marcusrosado.stamp.data.Leg
import com.marcusrosado.stamp.data.LegPath
import com.marcusrosado.stamp.data.PartialDate
import com.marcusrosado.stamp.data.Travel
import com.marcusrosado.stamp.data.TravelState
import com.marcusrosado.stamp.data.VisitStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class JourneyTest {
    private val dir: File = Files.createTempDirectory("stamp").toFile().apply { deleteOnExit() }
    private fun store() = VisitStore(File(dir, "visits.json"))
    private fun trip(s: VisitStore) = s.visits.value["JPN"]!!.trips[0]
    private val airports by lazy { File("src/main/assets/airports.tsv").bufferedReader().use { Airports.read(it) } }

    @Test fun theWayThereAndHomeWithTheirAirportsSurviveARestart() {
        val s = store()
        s.set("JPN", TravelState.Visited)
        val id = trip(s).id
        s.addCity("JPN", id, "tokyo"); s.addCity("JPN", id, "osaka")
        s.setLeg(id, Leg.START, "tokyo", listOf(Travel.Plane), listOf("CPH", "HEL", "HND"))
        s.setLeg(id, "osaka", Leg.END, listOf(Travel.Train, Travel.Plane), listOf("HND", "CPH"))
        s.setTripStart(id, "copenhagen")
        val t = trip(store())
        assertEquals(listOf("CPH", "HEL", "HND"), t.legBetween(Leg.START, "tokyo")!!.via)
        assertEquals(listOf(Travel.Train, Travel.Plane), t.waysBetween("osaka", Leg.END))
        assertEquals("copenhagen", t.start)
        assertNull(t.end)
    }

    @Test fun airportsOnlyStayWithAFlight() {
        val s = store()
        s.set("JPN", TravelState.Visited)
        val id = trip(s).id
        s.addCity("JPN", id, "tokyo")
        s.setLeg(id, Leg.START, "tokyo", listOf(Travel.Ferry), listOf("CPH", "HND"))
        assertTrue(trip(s).legBetween(Leg.START, "tokyo")!!.via.isEmpty())
    }

    @Test fun homeIsTheAddressLivedAtWhenTheTripBegan() {
        val s = store()
        s.set("DNK", TravelState.Lived)
        val stay = s.visits.value["DNK"]!!.trips[0].id
        s.addCity("DNK", stay, "odense"); s.addCity("DNK", stay, "aarhus")
        s.setStopDates(stay, "odense", PartialDate(2015), PartialDate(2019, 6))
        s.setStopDates(stay, "aarhus", PartialDate(2019, 7), null)
        s.setHome("DNK")
        val v = s.visits.value
        assertEquals("odense", Homes.at(v, PartialDate(2018, 3))?.place)
        assertEquals("aarhus", Homes.at(v, PartialDate(2023))?.place)
        assertEquals("aarhus", Homes.at(v, null)?.place)
    }

    @Test fun twoHomesWithNoMoveOutDateMeanTheLaterMoveWins() {
        val s = store()
        s.set("DNK", TravelState.Lived)
        val stay = s.visits.value["DNK"]!!.trips[0].id
        s.addCity("DNK", stay, "odense"); s.addCity("DNK", stay, "aarhus")
        s.setStopDates(stay, "odense", PartialDate(2019), null)
        s.setStopDates(stay, "aarhus", PartialDate(2022), null)
        s.setHome("DNK")
        assertEquals("aarhus", Homes.at(s.visits.value, PartialDate(2023))?.place)
        assertEquals("aarhus", Homes.at(s.visits.value, null)?.place)
        assertEquals("odense", Homes.at(s.visits.value, PartialDate(2020))?.place)
    }

    @Test fun aPinATripSetOutFromStaysWhenItsStopIsGone() {
        val s = store()
        s.set("DNK", TravelState.Lived)
        val stay = s.visits.value["DNK"]!!.trips[0].id
        s.addPin("DNK", stay, com.marcusrosado.stamp.data.Pin("Old flat", 55.0, 12.0))
        val pin = s.visits.value["DNK"]!!.trips[0].cities.first()
        s.set("JPN", TravelState.Visited)
        val id = trip(s).id
        s.addCity("JPN", id, "tokyo")
        s.setTripStart(id, pin)
        s.removeCity(stay, pin)
        assertTrue(s.visits.value["JPN"]!!.pins.containsKey(pin))
    }

    @Test fun aFlightWithLayoversHopsThroughEachAirport() {
        val home = 55.68 to 12.57
        val tokyo = 35.68 to 139.69
        val cph = 55.62 to 12.66; val hel = 60.32 to 24.96; val hnd = 35.55 to 139.79
        val r = LegPath.route(home, tokyo, listOf(Travel.Plane), listOf(cph, hel, hnd))
        val ways = r.map { it.second }
        // Home to the airport not said, two flights, the airport to the hotel not said.
        assertEquals(listOf(null, Travel.Plane, Travel.Plane, null), ways)
        fun near(a: Pair<Double, Double>, b: Pair<Double, Double>) = Math.abs(a.first - b.first) < 1e-6 && Math.abs(a.second - b.second) < 1e-6
        assertTrue(near(cph, r[1].first.first()))
        assertTrue(near(hnd, r[2].first.last()))
    }

    @Test fun aFlightCanGoTheOtherWayRoundTheGlobe() {
        val cph = 55.62 to 12.66; val hnd = 35.55 to 139.79
        assertTrue(LegPath.spansFar(cph, hnd))
        val short = LegPath.points(cph, hnd, Travel.Plane)
        val long = LegPath.points(cph, hnd, Travel.Plane, around = true)
        // The short way heads east over Asia; the other way west, over the Atlantic and America.
        assertTrue(short.all { it.second in 12.0..140.0 })
        assertTrue(long.last().second < -200)
        // A hop that could not have gone either way ignores it.
        val hel = 60.32 to 24.96
        assertEquals(LegPath.points(cph, hel, Travel.Plane), LegPath.points(cph, hel, Travel.Plane, around = true))
    }

    @Test fun theNearestBigAirportsComeFirst() {
        assertEquals("CPH", airports.near(55.68 to 12.57, 1).first().code)
        val tokyo = airports.near(35.68 to 139.69, 3).map { it.code }
        assertTrue(tokyo.toString(), "HND" in tokyo && "NRT" in tokyo)
        assertEquals("HEL", airports.search("hel").first().code)
        assertTrue(airports.search("sapporo").any { it.code == "CTS" })
    }
}
