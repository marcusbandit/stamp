package com.marcusrosado.stamp

import com.marcusrosado.stamp.data.Memory
import com.marcusrosado.stamp.data.TravelState
import com.marcusrosado.stamp.data.VisitStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.file.Files

class TripMemoriesTest {
    private val dir: File = Files.createTempDirectory("stamp").toFile().apply { deleteOnExit() }
    private val file = File(dir, "visits.json")
    private fun store() = VisitStore(file)
    private fun trip(s: VisitStore) = s.visits.value.getValue("JPN").trips.single()

    /** A visit to Japan with two stops, two photos, a video and a written moment. */
    private fun seeded(): Pair<VisitStore, String> {
        val s = store()
        s.set("JPN", TravelState.Visited)
        val id = trip(s).id
        s.addCity("JPN", id, "tokyo")
        s.addCity("JPN", id, "kyoto")
        s.addMemories(id, listOf(
            Memory("a", place = "tokyo", media = "content://a", takenMs = 1),
            Memory("b", place = "tokyo", media = "content://b", takenMs = 2),
            Memory("v", place = "kyoto", media = "content://v", video = true, takenMs = 3),
            Memory("t", text = "Ramen at midnight", takenMs = 4),
        ))
        return s to id
    }

    @Test fun theCoverIsTheFirstPhotoUntilOneIsPicked() {
        val (s, id) = seeded()
        assertEquals("a", trip(s).coverPhoto?.id)
        s.setCover(id, "b")
        assertEquals("b", trip(s).coverPhoto?.id)
    }

    @Test fun aPickedCoverSurvivesARestart() {
        val (s, id) = seeded()
        s.setCover(id, "b")
        s.flush()
        val again = trip(store())
        assertEquals("b", again.cover)
        assertEquals("b", again.coverPhoto?.id)
    }

    @Test fun aSaveFromBeforeCoversReadsWithNone() {
        val (s, _) = seeded()
        s.flush()
        assertEquals(false, file.readText().contains("\"cover\""))
        val again = trip(store())
        assertNull(again.cover)
        assertEquals("a", again.coverPhoto?.id)
    }

    @Test fun onlyAPhotoOfTheTripCanBeTheCover() {
        val (s, id) = seeded()
        s.setCover(id, "v")
        s.setCover(id, "t")
        s.setCover(id, "elsewhere")
        assertNull(trip(s).cover)
        s.setCover(id, "b")
        s.setCover(id, null)
        assertNull(trip(s).cover)
        assertEquals("a", trip(s).coverPhoto?.id)
    }

    @Test fun removingTheCoverGoesBackToTheFirstPhoto() {
        val (s, id) = seeded()
        s.setCover(id, "b")
        s.removeMemory(id, "b")
        assertNull(trip(s).cover)
        assertEquals("a", trip(s).coverPhoto?.id)
        s.flush()
        assertNull(trip(store()).cover)
    }

    @Test fun aCopiedTripHasNoCover() {
        val (s, id) = seeded()
        s.setCover(id, "b")
        val copy = s.duplicateTrip(id)!!
        assertNull(s.visits.value.getValue("JPN").trips.first { it.id == copy }.cover)
    }

    @Test fun aMemoryMovesToAnotherStopAndBack() {
        val (s, id) = seeded()
        s.moveMemory(id, "a", "kyoto")
        assertEquals("kyoto", trip(s).memories.first { it.id == "a" }.place)
        s.moveMemory(id, "t", "tokyo")
        assertEquals("tokyo", trip(s).memories.first { it.id == "t" }.place)
        // Null is the trip as a whole.
        s.moveMemory(id, "a", null)
        assertNull(trip(s).memories.first { it.id == "a" }.place)
        s.flush()
        val again = trip(store()).memories.associate { it.id to it.place }
        assertEquals(mapOf("a" to null, "b" to "tokyo", "v" to "kyoto", "t" to "tokyo"), again)
    }

    @Test fun aMemoryNeverMovesToAStopTheTripDoesNotHave() {
        val (s, id) = seeded()
        s.moveMemory(id, "a", "osaka")
        assertEquals("tokyo", trip(s).memories.first { it.id == "a" }.place)
        // Moving keeps everything else about it.
        s.moveMemory(id, "v", "tokyo")
        val v = trip(s).memories.first { it.id == "v" }
        assertEquals(Memory("v", place = "tokyo", media = "content://v", video = true, takenMs = 3), v)
    }
}
