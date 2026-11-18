package com.marcusrosado.stamp

import com.marcusrosado.stamp.data.TravelState
import com.marcusrosado.stamp.data.VisitStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class UndoTest {
    private val dir: File = Files.createTempDirectory("stamp").toFile().apply { deleteOnExit() }
    private fun store() = VisitStore(File(dir, "visits.json"))

    @Test fun revertBringsBackAClearedCountryWithItsTripAndCities() {
        val s = store()
        s.set("JPN", TravelState.Visited)
        val id = s.visits.value["JPN"]!!.trips[0].id
        s.addCity("JPN", id, "tokyo")
        s.setRating("JPN", 4)
        val before = s.snapshot()
        s.set("JPN", null)
        assertNull(s.visits.value["JPN"])
        assertFalse(s.unchangedSince(before))
        s.revert(before)
        val v = s.visits.value["JPN"]!!
        assertEquals(TravelState.Visited, v.state)
        assertEquals(4, v.rating)
        assertEquals(id, v.trips.single().id)
        assertEquals(1, v.cities.size)
        // And it is on disk, not only in memory.
        s.flush()
        assertEquals(TravelState.Visited, store().visits.value["JPN"]!!.state)
    }

    @Test fun aSnapshotSeesNoChangeWhenNothingHappened() {
        val s = store()
        s.set("FRA", TravelState.Want)
        val before = s.snapshot()
        assertTrue(s.unchangedSince(before))
        s.set("FRA", TravelState.Visited)
        assertFalse(s.unchangedSince(before))
    }
}
