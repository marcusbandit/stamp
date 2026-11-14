package com.marcusrosado.stamp

import com.marcusrosado.stamp.data.Geo
import com.marcusrosado.stamp.data.PartialDate
import com.marcusrosado.stamp.data.RegionShapes
import com.marcusrosado.stamp.data.TravelState
import com.marcusrosado.stamp.data.Trip
import com.marcusrosado.stamp.data.Visit
import com.marcusrosado.stamp.data.VisitStore
import com.marcusrosado.stamp.ui.countedTrips
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class RegionsTest {
    private val dir: File = Files.createTempDirectory("stamp").toFile().apply { deleteOnExit() }
    private fun store() = VisitStore(File(dir, "visits.json"))

    // Invented cities: one on the mainland, one on an island part of the same country.
    private val geo = object : Geo {
        val at = mapOf("town" to "ITA", "village" to "ESP", "isle" to "FRA-REU", "port" to "FRA")
        override fun landOfCity(cityId: String) = at[cityId]
        override fun landOf(lat: Double, lon: Double) = null
        override fun countryOf(land: String) = land.substringBefore('-')
    }
    private fun storeWithGeo() = store().also { it.geo = geo }

    @Test fun aSplitCountryKeepsItsRegionsWhenJoinedAgain() {
        val s = store()
        s.set("JPN", TravelState.Visited)
        s.setSplitRegions("JPN", true)
        s.setRegion("JPN", "JP.01", TravelState.Visited)
        s.setRegion("JPN", "JP.02", TravelState.Want)
        s.setSplitRegions("JPN", false)
        val v = store().visits.value["JPN"]!!
        assertFalse(v.splitRegions)
        assertEquals(listOf("JP.01"), v.regions)
        assertEquals(listOf("JP.02"), v.wantRegions)
        s.setSplitRegions("JPN", true)
        assertTrue(store().visits.value["JPN"]!!.splitRegions)
    }

    @Test fun aRegionMovesBetweenBeenAndWanted() {
        val s = store()
        s.set("JPN", TravelState.Visited)
        s.setRegion("JPN", "JP.01", TravelState.Want)
        s.setRegion("JPN", "JP.01", TravelState.Visited)
        var v = s.visits.value["JPN"]!!
        assertEquals(listOf("JP.01"), v.regions)
        assertTrue(v.wantRegions.isEmpty())
        s.setRegion("JPN", "JP.01", null)
        v = s.visits.value["JPN"]!!
        assertTrue(v.regions.isEmpty() && v.wantRegions.isEmpty())
    }

    @Test fun aRegionMarkedInAnUnmarkedCountryMarksTheCountry() {
        val s = store()
        s.setRegion("ITA", "IT.01", TravelState.Want)
        assertEquals(TravelState.Want, s.visits.value["ITA"]!!.state)
        s.setRegion("ITA", "IT.02", TravelState.Visited)
        assertEquals(TravelState.Visited, s.visits.value["ITA"]!!.state)
        // Clearing a region in a country with no mark marks nothing.
        s.setRegion("FRA", "FR.01", null)
        assertNull(s.visits.value["FRA"])
    }

    @Test fun aWantedCitySurvivesARestartAndCountsAsDetail() {
        val s = store()
        s.setCityWant("ITA", "3169070", true)
        val v = store().visits.value["ITA"]!!
        assertEquals(listOf("3169070"), v.wantCities)
        assertTrue(v.hasDetails)
        s.setCityWant("ITA", "3169070", false)
        assertTrue(store().visits.value["ITA"]!!.wantCities.isEmpty())
    }

    @Test fun theBundledRegionsReadForACountry() {
        val shapes = RegionShapes { cc -> File("src/main/assets/regions/$cc.bin").takeIf { it.exists() }?.inputStream() }
        val dk = shapes.of("DK")
        assertEquals(5, dk.size)
        assertTrue(dk.all { it.id.startsWith("DK.") && it.polygons.isNotEmpty() })
        // Every ring closes and sits in Denmark.
        for (r in dk) for (ring in r.polygons.flatten()) {
            assertEquals(ring[0], ring[ring.size - 2], 1e-9)
            assertTrue(ring.indices.step(2).all { ring[it] in 54.0..58.0 })
        }
        assertTrue(shapes.of("ZZ").isEmpty())
    }

    @Test fun splittingAnUnmarkedCountryMarksNothing() {
        val s = store()
        s.setSplitRegions("ITA", true)
        assertNull(s.visits.value["ITA"])
    }

    @Test fun aCityBeenToInAWantedCountryMarksTheCountryVisited() {
        val s = storeWithGeo()
        s.set("ITA", TravelState.Want)
        s.setCity("ITA", "town", TravelState.Visited)
        val v = s.visits.value["ITA"]!!
        assertEquals(TravelState.Visited, v.state)
        assertEquals(1, v.visitsTo("town"))
        // A lived country stays lived.
        s.set("ESP", TravelState.Lived)
        s.setCity("ESP", "village", TravelState.Visited)
        assertEquals(TravelState.Lived, s.visits.value["ESP"]!!.state)
    }

    @Test fun aRegionAndACityLivedInMarkTheirCountryTheSameWay() {
        val s = storeWithGeo()
        s.setRegion("ITA", "IT.01", TravelState.Lived)
        s.setCity("ESP", "village", TravelState.Lived)
        assertEquals(TravelState.Lived, s.visits.value["ITA"]!!.state)
        assertEquals(TravelState.Lived, s.visits.value["ESP"]!!.state)
        // From want to go, too.
        s.set("JPN", TravelState.Want)
        s.setRegion("JPN", "JP.01", TravelState.Lived)
        assertEquals(TravelState.Lived, s.visits.value["JPN"]!!.state)
    }

    @Test fun aTripOnAnIslandCountsOnceForItsCity() {
        val s = storeWithGeo()
        s.setCity("FRA", "isle", TravelState.Visited)
        s.set("FRA", TravelState.Visited)
        val vs = listOf("FRA", "FRA-REU").mapNotNull { s.visits.value[it] }
        assertEquals(2, vs.size)
        // The country's visit holds the island's trip too, so summing would say two.
        assertEquals(2, vs.sumOf { it.visitsTo("isle") })
        assertEquals(1, countedTrips(vs).count { "isle" in it.places })
        // A wanted part's trips do not count.
        val t = Trip("t1", cities = listOf("port"), lands = listOf("FRA"))
        assertEquals(0, countedTrips(listOf(Visit(TravelState.Want, trips = listOf(t)))).size)
    }

    @Test fun aCityVisitWithDatesCannotBeTakenBack() {
        val s = storeWithGeo()
        s.setCity("ITA", "town", TravelState.Visited)
        val trip = s.visits.value["ITA"]!!.trips.single { "town" in it.cities }
        assertTrue(trip.isBareVisitTo("town"))
        s.updateTrip(trip.copy(date = PartialDate.parse("2019-05")))
        val dated = s.visits.value["ITA"]!!.trips.single { "town" in it.cities }
        assertFalse(dated.isBareVisitTo("town"))
        assertFalse(s.forgetCityVisit("town"))
        assertEquals(1, s.visits.value["ITA"]!!.visitsTo("town"))
        // A bare one beside it can go.
        s.newTripAt("town")
        assertTrue(s.visits.value["ITA"]!!.trips.any { it.isBareVisitTo("town") })
        assertTrue(s.forgetCityVisit("town"))
        assertEquals(1, s.visits.value["ITA"]!!.visitsTo("town"))
    }
}
