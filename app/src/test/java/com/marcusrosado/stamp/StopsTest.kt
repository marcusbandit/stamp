package com.marcusrosado.stamp

import com.marcusrosado.stamp.data.Leg
import com.marcusrosado.stamp.data.Travel
import com.marcusrosado.stamp.data.TravelState
import com.marcusrosado.stamp.data.VisitStore
import com.marcusrosado.stamp.data.placeIdOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class StopsTest {
    private val dir: File = Files.createTempDirectory("stamp").toFile().apply { deleteOnExit() }
    private fun store() = VisitStore(File(dir, "visits.json"))
    private fun trip(s: VisitStore) = s.visits.value["JPN"]!!.trips[0]

    @Test fun aTripCanGoBackToACityWithItsOwnDatesAndWay() {
        val s = store()
        s.set("JPN", TravelState.Visited)
        val id = trip(s).id
        for (c in listOf("tokyo", "sapporo", "sendai", "tokyo")) s.addCity("JPN", id, c)
        val stops = trip(s).cities
        assertEquals(4, stops.size)
        assertEquals(listOf("tokyo", "sapporo", "sendai", "tokyo"), stops.map(::placeIdOf))
        val back = stops.last()
        s.setLeg(id, "sendai", back, listOf(Travel.Train))
        s.setLeg(id, back, Leg.END, listOf(Travel.Plane))
        val t = trip(store())
        assertEquals(stops, t.cities)
        assertEquals(listOf(Travel.Train), t.waysBetween("sendai", back))
        assertEquals(listOf(Travel.Plane), t.waysBetween(back, Leg.END))
        // Still one city on the card.
        assertEquals(3, store().visits.value["JPN"]!!.cities.size)
    }

    @Test fun aDayPlaceCountsAndCanBeWhereTheUserSlept() {
        val s = store()
        s.set("JPN", TravelState.Visited)
        val id = trip(s).id
        s.addCity("JPN", id, "tokyo")
        s.addDayPlace(id, "tokyo", "kawaguchi")
        s.setSlept(id, "tokyo", "kawaguchi")
        val t = trip(store())
        assertEquals(listOf("kawaguchi"), t.dayPlaces["tokyo"])
        assertEquals("kawaguchi", t.bedOf("tokyo"))
        assertTrue("kawaguchi" in store().visits.value["JPN"]!!.cities)
        s.removeDayPlace(id, "tokyo", "kawaguchi")
        assertEquals("tokyo", trip(s).bedOf("tokyo"))
    }

    @Test fun removingAStopTakesItsDayPlaces() {
        val s = store()
        s.set("JPN", TravelState.Visited)
        val id = trip(s).id
        s.addCity("JPN", id, "tokyo"); s.addCity("JPN", id, "osaka")
        s.addDayPlace(id, "osaka", "nara")
        s.removeCity(id, "osaka")
        assertTrue(trip(s).dayPlaces.isEmpty())
        assertTrue("nara" !in s.visits.value["JPN"]!!.cities)
    }
}
