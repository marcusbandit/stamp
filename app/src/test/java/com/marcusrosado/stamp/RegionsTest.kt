package com.marcusrosado.stamp

import com.marcusrosado.stamp.data.RegionShapes
import com.marcusrosado.stamp.data.TravelState
import com.marcusrosado.stamp.data.VisitStore
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
}
