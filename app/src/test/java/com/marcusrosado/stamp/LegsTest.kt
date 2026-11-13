package com.marcusrosado.stamp

import com.marcusrosado.stamp.data.LegPath
import com.marcusrosado.stamp.data.Step
import com.marcusrosado.stamp.data.Travel
import com.marcusrosado.stamp.data.TravelState
import com.marcusrosado.stamp.data.VisitStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class LegsTest {
    private val dir: File = Files.createTempDirectory("stamp").toFile().apply { deleteOnExit() }
    private fun store() = VisitStore(File(dir, "visits.json"))

    private fun trip(s: VisitStore) = s.visits.value["JPN"]!!.trips[0]

    @Test fun waysSurviveARestart() {
        val s = store()
        s.set("JPN", TravelState.Visited)
        val id = trip(s).id
        for (c in listOf("tokyo", "sapporo", "sendai", "kanazawa")) s.addCity("JPN", id, c)
        s.setLeg(id, "tokyo", "sapporo", listOf(Step(Travel.Plane)))
        s.setLeg(id, "sendai", "kanazawa", listOf(Step(Travel.Train), Step(Travel.Bus)))
        val t = trip(store())
        assertEquals(listOf(Travel.Plane), t.waysBetween("tokyo", "sapporo"))
        assertEquals(listOf(Travel.Train, Travel.Bus), t.waysBetween("sendai", "kanazawa"))
        assertEquals(emptyList<Travel>(), t.waysBetween("sapporo", "sendai"))
    }

    @Test fun noWaysClearsALeg() {
        val s = store()
        s.set("JPN", TravelState.Visited)
        val id = trip(s).id
        s.addCity("JPN", id, "tokyo"); s.addCity("JPN", id, "osaka")
        s.setLeg(id, "tokyo", "osaka", listOf(Step(Travel.Train)))
        s.setLeg(id, "tokyo", "osaka", emptyList())
        assertTrue(trip(store()).legs.isEmpty())
    }

    @Test fun aLegLeftBehindByAReorderIsNotSaved() {
        val s = store()
        s.set("JPN", TravelState.Visited)
        val id = trip(s).id
        for (c in listOf("tokyo", "osaka", "kyoto")) s.addCity("JPN", id, c)
        s.setLeg(id, "tokyo", "osaka", listOf(Step(Travel.Train)))
        s.setLeg(id, "osaka", "kyoto", listOf(Step(Travel.Walk)))
        s.moveStop(id, "kyoto", -1) // tokyo, kyoto, osaka
        val t = trip(store())
        assertTrue(t.legs.isEmpty())
    }

    @Test fun aRemovedStopTakesItsLegsWithItInThisSessionToo() {
        val s = store()
        s.set("JPN", TravelState.Visited)
        val id = trip(s).id
        s.addCity("JPN", id, "tokyo"); s.addCity("JPN", id, "osaka")
        s.setLeg(id, "tokyo", "osaka", listOf(Step(Travel.Train)))
        s.removeCity(id, "osaka")
        s.addCity("JPN", id, "osaka")
        assertEquals(emptyList<Travel>(), trip(s).waysBetween("tokyo", "osaka"))
    }

    @Test fun aLegTakenSeveralWaysAcrossTheDateLineSplitsOnTheShortWay() {
        val tokyo = 35.68 to 139.78
        val honolulu = 21.31 to -157.86
        val parts = LegPath.route(tokyo, honolulu, listOf(Step(Travel.Ferry), Step(Travel.Ferry)))
        val mid = parts[0].first.last()
        // Halfway between them over the Pacific, past 180 east, not over the Atlantic.
        assertTrue("mid ${mid.second}", mid.second > 170 && mid.second < 190)
    }

    @Test fun aFlightNearThePoleStaysOnTheMap() {
        val pts = LegPath.points(61.2 to -149.9, 59.9 to 10.7, Travel.Plane)
        assertTrue(pts.all { it.first in -85.0..85.0 })
    }

    @Test fun aLegFollowsItsStopWhenItBecomesAnAddress() {
        val s = store()
        s.set("JPN", TravelState.Visited)
        val id = trip(s).id
        s.addCity("JPN", id, "tokyo"); s.addCity("JPN", id, "osaka")
        s.setLeg(id, "tokyo", "osaka", listOf(Step(Travel.Bus)))
        s.replaceStopWithPin(id, "osaka", com.marcusrosado.stamp.data.Pin("A street", 34.6, 135.5))
        val t = trip(store())
        assertEquals(listOf(Travel.Bus), t.waysBetween("tokyo", t.cities[1]))
    }

    @Test fun onlyAFlightCurves() {
        val a = 35.68 to 139.69
        val b = 43.06 to 141.35
        assertEquals(listOf(a, b), LegPath.points(a, b, Travel.Train))
        assertEquals(listOf(a, b), LegPath.points(a, b, null))
        val flight = LegPath.points(a, b, Travel.Plane)
        assertTrue(flight.size > 10)
        assertEquals(a.first, flight.first().first, 1e-9)
        assertEquals(b.second, flight.last().second, 1e-9)
        // Flying north, it bows to the left: west of the straight line at the middle.
        val mid = flight[flight.size / 2]
        assertTrue(mid.second < (a.second + b.second) / 2)
    }

    @Test fun theWayBackBowsTheOtherWay() {
        val a = 55.62 to 12.65
        val b = 35.55 to 139.78
        val out = LegPath.points(a, b, Travel.Plane)
        val back = LegPath.points(b, a, Travel.Plane).reversed()
        val i = out.size / 2
        assertTrue(out[i] != back[i])
    }

    @Test fun aFlightAcrossTheDateLineStaysInOnePiece() {
        val pts = LegPath.points(35.55 to 139.78, 21.32 to -157.92, Travel.Plane)
        assertTrue(pts.zipWithNext().all { (p, q) -> kotlin.math.abs(q.second - p.second) < 20 })
    }

    @Test fun severalWaysCutTheLegIntoEvenStretches() {
        val parts = LegPath.route(0.0 to 0.0, 0.0 to 30.0, listOf(Step(Travel.Train), Step(Travel.Bus), Step(Travel.Walk)))
        assertEquals(listOf(Travel.Train, Travel.Bus, Travel.Walk), parts.map { it.second })
        assertEquals(10.0, parts[0].first.last().second, 1e-9)
        assertEquals(20.0, parts[2].first.first().second, 1e-9)
    }
}
