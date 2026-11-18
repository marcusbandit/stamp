package com.marcusrosado.stamp

import com.marcusrosado.stamp.data.PartialDate
import com.marcusrosado.stamp.data.Step
import com.marcusrosado.stamp.data.Travel
import com.marcusrosado.stamp.data.TravelState
import com.marcusrosado.stamp.data.VisitStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class DuplicateTripTest {
    private val dir: File = Files.createTempDirectory("stamp").toFile().apply { deleteOnExit() }

    @Test fun aCopyKeepsTheRouteAndDropsTheDatesAndName() {
        val s = VisitStore(File(dir, "visits.json"))
        s.set("JPN", TravelState.Visited)
        val t = s.visits.value["JPN"]!!.trips[0]
        s.addCity("JPN", t.id, "tokyo")
        s.addCity("JPN", t.id, "kyoto")
        s.setLeg(t.id, "tokyo", "kyoto", listOf(Step(Travel.Train)))
        s.renameTrip(t.id, "Spring")
        s.updateTrip(s.visits.value["JPN"]!!.trips[0].copy(date = PartialDate(2020, 4)))
        val copyId = s.duplicateTrip(t.id)!!
        val trips = s.visits.value["JPN"]!!.trips
        assertEquals(2, trips.size)
        val copy = trips.first { it.id == copyId }
        assertNotEquals(t.id, copyId)
        assertEquals(listOf("tokyo", "kyoto"), copy.cities)
        assertEquals(listOf(Travel.Train), copy.waysBetween("tokyo", "kyoto"))
        assertNull(copy.date)
        assertEquals("", copy.name)
        assertTrue(copy.memories.isEmpty())
    }
}
