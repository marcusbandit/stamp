package com.marcusrosado.stamp

import com.marcusrosado.stamp.data.City
import com.marcusrosado.stamp.data.PhotoStops
import com.marcusrosado.stamp.data.SeenFrom
import com.marcusrosado.stamp.data.SeenPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoStopsTest {
    private fun city(id: String, lat: Double, lon: Double, pop: Int) = City(id, id, id, "JP", "01", lat, lon, pop)

    // Three made-up towns on a line, 1 degree of longitude apart, and a village between two of them.
    private val west = city("west", 35.0, 135.0, 500_000)
    private val middle = city("middle", 35.0, 136.0, 800_000)
    private val village = city("village", 35.0, 136.5, 2_000)
    private val east = city("east", 35.0, 137.0, 900_000)
    private val all = listOf(west, middle, village, east)

    private fun at(c: City, hour: Long, dLon: Double = 0.0) = SeenPoint(hour * 3_600_000L, c.lat, c.lon + dLon, SeenFrom.Photo)
    private fun stop(c: City, arrived: Long? = null) = PhotoStops.Stop(listOf(c.lat to c.lon), arrived)
    private fun town(lat: Double, lon: Double) = PhotoStops.townOf(lat, lon, all)

    @Test fun aTownSeenBetweenTwoStopsGoesBetweenThem() {
        val points = listOf(at(west, 1), at(west, 2), at(middle, 30), at(middle, 31, 0.01), at(east, 60))
        val found = PhotoStops.find(points, listOf(stop(west), stop(east)), ::town)
        assertEquals(listOf("middle"), found.map { it.city.id })
        assertEquals(1, found[0].at)
        assertEquals(30 * 3_600_000L, found[0].firstMs)
        assertEquals(31 * 3_600_000L, found[0].lastMs)
    }

    @Test fun aStopsOwnDateOrdersItWithNoPhotosOfIt() {
        // No photos of either stop: the dates the user gave place the new one.
        val points = listOf(at(middle, 30), at(middle, 31, 0.01))
        val found = PhotoStops.find(points, listOf(stop(west, 0), stop(east, 50 * 3_600_000L)), ::town)
        assertEquals(1, found.single().at)
    }

    @Test fun beforeTheFirstStopAndWithNothingToGoByAtTheEnd() {
        val points = listOf(at(middle, 1), at(middle, 2, 0.01), at(east, 10))
        assertEquals(0, PhotoStops.find(points, listOf(stop(east)), ::town).single().at)
        val undated = listOf(at(middle, 1), at(middle, 2, 0.01))
        assertEquals(1, PhotoStops.find(undated, listOf(stop(east)), ::town).single().at)
    }

    @Test fun oneSpotHomeAndTurnedDownTownsAreLeftOut() {
        val once = listOf(at(middle, 30))
        assertTrue(PhotoStops.find(once, listOf(stop(west)), ::town).isEmpty())
        val twice = listOf(at(middle, 30), at(middle, 31, 0.01))
        assertTrue(PhotoStops.find(twice, listOf(stop(west)), ::town, avoid = listOf(middle.lat to middle.lon)).isEmpty())
        assertTrue(PhotoStops.find(twice, listOf(stop(west)), ::town, skip = { it.id == "middle" }).isEmpty())
    }

    @Test fun aSuburbNamesTheCityAndFarOffNamesNothing() {
        // A small place 7 km out of a big town is the town; far from any town, the village.
        assertEquals("middle", town(35.0, 136.08)?.id)
        assertEquals("village", town(35.0, 136.5)?.id)
        assertEquals(null, town(40.0, 136.0))
    }
}
