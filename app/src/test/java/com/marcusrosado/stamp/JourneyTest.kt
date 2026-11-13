package com.marcusrosado.stamp

import com.marcusrosado.stamp.data.Airports
import com.marcusrosado.stamp.data.Homes
import com.marcusrosado.stamp.data.Leg
import com.marcusrosado.stamp.data.Step
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
        s.setLeg(id, Leg.START, "tokyo", listOf(Step(null, "apt:CPH"), Step(Travel.Plane, "apt:HEL"), Step(Travel.Plane, "apt:HND"), Step(null)))
        s.setLeg(id, "osaka", Leg.END, listOf(Step(Travel.Train, "apt:HND"), Step(Travel.Plane, "apt:CPH"), Step(null)))
        s.setTripStart(id, "copenhagen")
        val t = trip(store())
        assertEquals(listOf("apt:CPH", "apt:HEL", "apt:HND", null), t.legBetween(Leg.START, "tokyo")!!.steps.map { it.to })
        assertEquals(listOf(Travel.Train, Travel.Plane), t.waysBetween("osaka", Leg.END))
        assertEquals("copenhagen", t.start)
        assertNull(t.end)
    }

    @Test fun aLegSavedBeforeStepsKeepsItsAirports() {
        // Ways before the plane end at the first airport; the flight hops through the rest.
        val l = Leg.fromWays("a", "b", listOf(Travel.Train, Travel.Plane, Travel.Bus), listOf("CPH", "HEL", "HND"), false)!!
        assertEquals(
            listOf(Step(Travel.Train, "apt:CPH"), Step(Travel.Plane, "apt:HEL"), Step(Travel.Plane, "apt:HND"), Step(Travel.Bus)),
            l.steps,
        )
        // A flight with no ways around it: unsaid stretches to and from its airports.
        assertEquals(
            listOf(Step(null, "apt:CPH"), Step(Travel.Plane, "apt:HND"), Step(null)),
            Leg.fromWays("a", "b", listOf(Travel.Plane), listOf("CPH", "HND"), false)!!.steps,
        )
        // Without airports, each way is its own step.
        assertEquals(listOf(Step(Travel.Ferry), Step(Travel.Bus)), Leg.fromWays("a", "b", listOf(Travel.Ferry, Travel.Bus), emptyList(), false)!!.steps)
    }

    @Test fun anOldSaveFileReadsAsSteps() {
        File(dir, "visits.json").writeText(
            """{"version":3,"countries":{"JPN":{"state":"visited"}},"trips":[{"id":"t1","lands":["JPN"],"cities":["tokyo"],"legs":[{"from":"@start","to":"tokyo","ways":["plane","train"],"via":["cph","nrt"]}]}]}""",
        )
        val l = store().visits.value["JPN"]!!.trips[0].legBetween(Leg.START, "tokyo")!!
        assertEquals(listOf(Step(null, "apt:CPH"), Step(Travel.Plane, "apt:NRT"), Step(Travel.Train)), l.steps)
    }

    @Test fun aLegWithNothingSaidIsNoLeg() {
        val s = store()
        s.set("JPN", TravelState.Visited)
        val id = trip(s).id
        s.addCity("JPN", id, "tokyo")
        s.setLeg(id, Leg.START, "tokyo", listOf(Step(null), Step(null)))
        assertNull(trip(s).legBetween(Leg.START, "tokyo"))
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
        val at = mapOf("apt:CPH" to cph, "apt:HEL" to hel, "apt:HND" to hnd)
        val flight = listOf(Step(null, "apt:CPH"), Step(Travel.Plane, "apt:HEL"), Step(Travel.Plane, "apt:HND"), Step(null))
        val r = LegPath.route(home, tokyo, flight, { at[it] }, fromHome = true, toHome = true)
        // The way there and home: two flights, starting and ending at the airports, with nothing
        // drawn from the door.
        assertEquals(listOf(Travel.Plane, Travel.Plane), r.map { it.second })
        fun near(a: Pair<Double, Double>, b: Pair<Double, Double>) = Math.abs(a.first - b.first) < 1e-6 && Math.abs(a.second - b.second) < 1e-6
        assertTrue(near(cph, r[0].first.first()))
        assertTrue(near(hnd, r[1].first.last()))
        // Mid-trip, a stop stays joined to its airports.
        assertEquals(listOf(null, Travel.Plane, Travel.Plane, null), LegPath.route(home, tokyo, flight, { at[it] }).map { it.second })
    }

    @Test fun eachStepRunsBetweenItsOwnStops() {
        // Train from town to the airport, a flight, a train on from the other airport: the
        // trains run between the towns and the airports, not along the flight.
        val tokyo = 35.68 to 139.77; val nrt = 35.77 to 140.39; val hkd = 41.77 to 140.82; val sapporo = 43.06 to 141.35
        val at = mapOf("apt:NRT" to nrt, "apt:HKD" to hkd)
        val steps = listOf(Step(Travel.Train, "apt:NRT"), Step(Travel.Plane, "apt:HKD"), Step(Travel.Train))
        val seen = mutableListOf<Pair<Pair<Double, Double>, Pair<Double, Double>>>()
        val r = LegPath.route(tokyo, sapporo, steps, { at[it] }, follow = { _, x, y -> seen += x to y; null })
        assertEquals(listOf(Travel.Train, Travel.Plane, Travel.Train), r.map { it.second })
        assertEquals(listOf(tokyo to nrt, hkd to sapporo), seen)
        fun near(a: Pair<Double, Double>, b: Pair<Double, Double>) = Math.abs(a.first - b.first) < 1e-6 && Math.abs(a.second - b.second) < 1e-6
        assertTrue(near(nrt, r[1].first.first()))
        assertTrue(near(hkd, r[1].first.last()))
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
