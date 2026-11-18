package com.marcusrosado.stamp

import com.marcusrosado.stamp.data.Pin
import com.marcusrosado.stamp.data.Seen
import com.marcusrosado.stamp.data.SeenFrom
import com.marcusrosado.stamp.data.SeenPoint
import com.marcusrosado.stamp.data.mergeBackup
import com.marcusrosado.stamp.data.TravelState
import com.marcusrosado.stamp.data.VisitStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class VisitStoreMergeTest {
    private val dir: File = Files.createTempDirectory("stamp-merge").toFile().apply { deleteOnExit() }
    private var n = 0
    private fun store() = VisitStore(File(dir, "visits-${n++}.json"))

    @Test fun mergeAddsWhatIsMissingAndKeepsWhatIsHere() {
        val file = store()
        file.set("PRT", TravelState.Visited)
        file.renameTrip(file.visits.value.getValue("PRT").trips.single().id, "Porto")
        file.set("JPN", TravelState.Want)
        file.setLoved("PRT", true)

        val phone = store()
        phone.set("PRT", TravelState.Lived)
        phone.set("ESP", TravelState.Visited)
        val before = phone.visits.value.getValue("PRT")

        assertEquals("JPN, and the Porto trip", 2, phone.merge(file.backup()))
        val v = phone.visits.value
        assertEquals(TravelState.Visited, v["ESP"]?.state)
        assertEquals("a place only in the file comes in", TravelState.Want, v["JPN"]?.state)
        // The phone's own mark of Portugal stays as it was, heart and all.
        assertEquals(TravelState.Lived, v["PRT"]?.state)
        assertEquals(before.loved, v["PRT"]?.loved)
        val names = v.getValue("PRT").trips.map { it.name }
        assertTrue("the file's trip joins the phone's", "Porto" in names)
        assertEquals(before.trips.size + 1, v.getValue("PRT").trips.size)
    }

    @Test fun mergingTheSameFileTwiceAddsNothing() {
        val file = store()
        file.set("ITA", TravelState.Visited)
        file.addTrip("ITA")
        val phone = store()
        phone.restore(file.backup())
        phone.set("FRA", TravelState.Visited)
        assertEquals(0, phone.merge(file.backup()))
        assertEquals(2, phone.visits.value.getValue("ITA").trips.size)
        assertEquals(TravelState.Visited, phone.visits.value["FRA"]?.state)
    }

    @Test fun aBareVisitInTheFileIsNotASecondVisit() {
        val file = store()
        file.set("ITA", TravelState.Visited)
        val phone = store()
        phone.set("ITA", TravelState.Visited)
        assertEquals(0, phone.merge(file.backup()))
        assertEquals(1, phone.visits.value.getValue("ITA").trips.size)
    }

    @Test fun aMergedTripBringsItsPinsAndThePhoneKeepsItsOwn() {
        val file = store()
        file.addPin("ISL", null, Pin("Hut", 64.1, -21.9))
        val phone = store()
        phone.set("DNK", TravelState.Visited)
        assertEquals(2, phone.merge(file.backup()))
        val isl = phone.visits.value.getValue("ISL")
        assertEquals("Hut", isl.pins.values.single().name)
        assertEquals(TravelState.Visited, isl.state)
        // Read again from disk: the merge was saved.
        val again = VisitStore(File(dir, "visits-${n - 1}.json")).visits.value
        assertEquals("Hut", again.getValue("ISL").pins.values.single().name)
    }

    @Test fun aFileThatIsNoBackupChangesNothing() {
        val phone = store()
        phone.set("ITA", TravelState.Visited)
        val before = phone.visits.value
        assertNull(phone.merge("not json"))
        assertNull(phone.merge("{\"version\": 99, \"countries\": {}}"))
        assertEquals(before, phone.visits.value)
    }

    @Test fun theFilesCurrentStayComesInAsAPastOne() {
        val file = store()
        file.setHome("PRT")
        val phone = store()
        phone.setHome("DNK")
        assertEquals(2, phone.merge(file.backup()))
        assertEquals("DNK", phone.home.value)
        assertEquals("one home at a time", 1, phone.allTrips.value.count { it.now })
        assertEquals(TravelState.Lived, phone.visits.value["PRT"]?.state)
        // With no stay of its own going on, the phone takes the file's.
        val empty = store()
        empty.merge(file.backup())
        assertEquals("PRT", empty.home.value)
    }

    @Test fun pointsForTheHeatMapCountAsSomethingNew() {
        val file = store()
        file.set("ITA", TravelState.Visited)
        val fileSeen = Seen(File(dir, "seen-file.tsv"))
        fileSeen.add(listOf(SeenPoint(1_700_000_000_000, 41.9, 12.5, SeenFrom.Hourly), SeenPoint(1_700_090_000_000, 45.4, 9.2, SeenFrom.Photo)))
        val text = org.json.JSONObject(file.backup()).put("seen", fileSeen.json()).toString()

        val phone = store()
        phone.restore(file.backup())
        val seen = Seen(File(dir, "seen-phone.tsv"))
        assertEquals("only the points are new", 2, mergeBackup(phone, seen, text))
        assertEquals(2, seen.points.value.size)
        assertEquals("and the second time nothing is", 0, mergeBackup(phone, seen, text))
        assertNull(mergeBackup(phone, seen, "not json"))
    }
}
