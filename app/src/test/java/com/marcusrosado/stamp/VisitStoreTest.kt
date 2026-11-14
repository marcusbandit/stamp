package com.marcusrosado.stamp

import com.marcusrosado.stamp.data.Note
import com.marcusrosado.stamp.data.PartialDate
import com.marcusrosado.stamp.data.Geo
import com.marcusrosado.stamp.data.Memory
import com.marcusrosado.stamp.data.Pin
import com.marcusrosado.stamp.data.Trip
import com.marcusrosado.stamp.data.isPin
import com.marcusrosado.stamp.data.PhotoEvidence
import com.marcusrosado.stamp.data.TravelState
import com.marcusrosado.stamp.data.VisitStore
import com.marcusrosado.stamp.data.SetAside
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class VisitStoreTest {
    private val dir: File = Files.createTempDirectory("stamp").toFile().apply { deleteOnExit() }
    private fun store() = VisitStore(File(dir, "visits.json"))

    @Test fun photosUpgradeWantButNotLived() {
        val s = store()
        s.set("JPN", TravelState.Want)
        s.set("DNK", TravelState.Lived)
        val ev = PhotoEvidence(100, 200)
        s.addFromPhotos(mapOf("JPN" to ev, "DNK" to ev, "PRT" to ev, "ISL" to ev), mark = setOf("JPN", "PRT"))
        val v = s.visits.value
        assertEquals(TravelState.Visited, v["JPN"]?.state)
        assertEquals(TravelState.Lived, v["DNK"]?.state)
        assertEquals(TravelState.Visited, v["PRT"]?.state)
        assertNull("unticked and unmarked stays out", v["ISL"])
        assertEquals(ev, v["DNK"]?.evidence)
    }

    @Test fun marksSurviveARestart() {
        store().apply { set("PER", TravelState.Visited); setHome("DNK") }
        val again = store()
        assertEquals(TravelState.Visited, again.visits.value["PER"]?.state)
        assertEquals("DNK", again.home.value)
        assertEquals(TravelState.Lived, again.visits.value["DNK"]?.state)
    }

    @Test fun anUnreadableFileIsKeptAsideNotOverwritten() {
        File(dir, "visits.json").writeText("{\"version\":1,\"countries\":{\"PER\":")
        val s = store()
        assertTrue(s.visits.value.isEmpty())
        s.set("JPN", TravelState.Visited)
        assertTrue(dir.listFiles()!!.any { it.name.startsWith("visits.json.unreadable-") })
    }

    @Test fun homeIsAlwaysLived() {
        val s = store()
        s.set("DNK", TravelState.Visited)
        s.setHome("DNK")
        assertEquals(TravelState.Lived, s.visits.value["DNK"]?.state)
    }

    @Test fun detailsSurviveARestartAndAStateChange() {
        store().apply {
            set("JPN", TravelState.Visited)
            setLoved("JPN", true)
            setRating("JPN", 4)
            addTrip("JPN")
            val t = visits.value["JPN"]!!.trips
            updateTrip(t[1].copy(date = PartialDate(2019, 3), cities = listOf("1850147")))
            updateTrip(t[0].copy(date = PartialDate(2023)))
            set("JPN", TravelState.Lived)
        }
        val v = store().visits.value["JPN"]!!
        assertEquals(TravelState.Lived, v.state)
        assertTrue(v.loved)
        assertEquals(4, v.rating)
        assertEquals(listOf(PartialDate(2019, 3), PartialDate(2023)), v.trips.map { it.date })
        assertEquals(listOf("1850147"), v.trips[0].cities)
    }

    @Test fun editingAnUnmarkedCountryDoesNothing() {
        val s = store()
        s.setRating("PER", 5)
        assertNull(s.visits.value["PER"])
    }

    @Test fun removingATripLeavesTheOthers() {
        val s = store()
        s.set("ISL", TravelState.Visited)
        repeat(2) { s.addTrip("ISL") }
        val mid = s.visits.value["ISL"]!!.trips[1].id
        s.removeTrip("ISL", mid)
        assertEquals(2, s.visits.value["ISL"]!!.trips.size)
        assertFalse(s.visits.value["ISL"]!!.trips.any { it.id == mid })
    }

    @Test fun partialDatesRoundTripAndSort() {
        val dates = listOf(PartialDate(2023, 4, 2), PartialDate(2023), PartialDate(2023, 4), PartialDate(1999, 12))
        dates.forEach { assertEquals(it, PartialDate.parse(it.key)) }
        assertEquals(listOf("1999-12", "2023", "2023-04", "2023-04-02"), dates.sorted().map { it.key })
        assertNull(PartialDate.parse("soon"))
        assertNull(PartialDate.parse("2023-13"))
    }

    @Test fun aDamagedVisitDoesNotTakeTheCountryWithIt() {
        File(dir, "visits.json").writeText(
            """{"version":2,"countries":{"JPN":{"state":"visited","trips":[{"date":"2023"},"junk",{"id":"a","date":"-005-04"}]},"PER":{"state":"want"}}}""",
        )
        val v = store().visits.value
        assertEquals(TravelState.Want, v["PER"]?.state)
        assertEquals(listOf(PartialDate(2023), null), v["JPN"]!!.trips.map { it.date })
    }

    @Test fun japanTwiceIsOneVisitToEachCity() {
        val s = store()
        s.set("JPN", TravelState.Visited)
        val first = s.visits.value["JPN"]!!.trips[0].id
        s.addCity("JPN", first, "tokyo")
        s.addCity("JPN", first, "sapporo")
        s.addCity("JPN", null, "kyoto")
        val second = s.visits.value["JPN"]!!.trips[1].id
        s.addCity("JPN", second, "kanazawa")
        s.setCityNote("JPN", "kyoto", com.marcusrosado.stamp.data.Note(loved = true, rating = 5))
        val v = store().visits.value["JPN"]!!
        assertEquals(2, v.trips.size)
        assertEquals(listOf("tokyo", "sapporo", "kyoto", "kanazawa"), v.cities)
        assertEquals(1, v.visitsTo("kyoto"))
        assertEquals(5, v.cityNotes["kyoto"]?.rating)
        s.removeCity(second, "kyoto")
        assertNull("a city on no visit loses its note", s.visits.value["JPN"]!!.cityNotes["kyoto"])
    }

    @Test fun citySearchIgnoresAccentsAndPutsPrefixesFirst() {
        val cities = listOf(
            "1\tMalmö\tMalmo\tSE\t27\t55.6\t13.0\t300000",
            "2\tStockholm\tStockholm\tSE\t26\t59.3\t18.0\t900000",
            "3\tSödermalm\tSodermalm\tSE\t26\t59.3\t18.0\t100000",
        ).joinToString("\n")
        val p = com.marcusrosado.stamp.data.Places.read(cities.reader().buffered(), "SE\t27\tSkåne".reader().buffered())
        assertEquals(listOf("1"), p.search("se", "malmo").map { it.id })
        assertEquals("prefix match first, then contains", listOf("1", "3"), p.search("SE", "malm").map { it.id })
        assertEquals("Skåne", p.regionOf(p.byId["1"]!!))
    }

    @Test fun regionsComeFromCitiesAndByHand() {
        val cities = listOf(
            "1\tKanazawa\tKanazawa\tJP\t15\t36.6\t136.6\t466029",
            "2\tTokyo\tTokyo\tJP\t40\t35.7\t139.7\t9733276",
        ).joinToString("\n")
        val regions = listOf("JP\t15\tIshikawa", "JP\t40\tTokyo", "JP\t12\tHokkaido").joinToString("\n")
        val p = com.marcusrosado.stamp.data.Places.read(cities.reader().buffered(), regions.reader().buffered())
        val s = store()
        s.set("JPN", TravelState.Visited)
        s.addCity("JPN", null, "1")
        s.addRegion("JPN", "JP.12")
        val v = store().visits.value["JPN"]!!
        val seen = p.regionsVisited(v)
        assertEquals(setOf("JP.15", "JP.12"), seen.keys)
        assertEquals("1", seen["JP.15"]?.id)
        assertNull(seen["JP.12"])
        assertEquals(3, p.regionsIn("jp").size)
        s.removeRegion("JPN", "JP.12")
        assertEquals(emptyList<String>(), s.visits.value["JPN"]!!.regions)
    }

    @Test fun markingVisitedMeansOneVisitAndTheLastOneStays() {
        val s = store()
        s.set("PER", TravelState.Visited)
        val v = s.visits.value["PER"]!!
        assertEquals(1, v.trips.size)
        s.removeTrip("PER", v.trips[0].id)
        assertEquals(1, s.visits.value["PER"]!!.trips.size)
    }

    @Test fun oneHomeAtATime() {
        val s = store()
        s.setHome("DNK")
        assertEquals("DNK", s.home.value)
        s.set("JPN", TravelState.Lived)
        val stay = s.visits.value["JPN"]!!.trips[0]
        s.updateTrip(stay.copy(date = PartialDate(2020), until = PartialDate(2021), now = true))
        assertEquals("JPN", s.home.value)
        assertNull("living here now drops until", s.visits.value["JPN"]!!.trips[0].until)
        assertFalse(s.visits.value["DNK"]!!.trips.any { it.now })
        s.set("JPN", TravelState.Visited)
        assertNull("a visited country is nobody's home", s.home.value)
    }

    @Test fun anOldHomeFileMovesOntoAStay() {
        File(dir, "home.txt").writeText("ISL")
        val s = store()
        assertEquals("ISL", s.home.value)
        assertTrue(s.visits.value["ISL"]!!.trips.single().now)
        assertFalse(File(dir, "home.txt").exists())
    }

    @Test fun homeNeverThrowsAwayAnEndDate() {
        val s = store()
        s.set("GBR", TravelState.Lived)
        val stay = s.visits.value["GBR"]!!.trips[0]
        s.updateTrip(stay.copy(date = PartialDate(2010), until = PartialDate(2012)))
        s.setHome("GBR")
        val trips = s.visits.value["GBR"]!!.trips
        assertEquals(2, trips.size)
        assertEquals(PartialDate(2012), trips.first { !it.now }.until)
        assertTrue(trips.any { it.now })
    }

    @Test fun anEmptyFirstVisitIsNotADetail() {
        val s = store()
        s.set("PER", TravelState.Visited)
        assertFalse(s.visits.value["PER"]!!.hasDetails)
    }

    @Test fun untilBeforeFrom() {
        assertTrue(PartialDate(2015).endsBefore(PartialDate(2020)))
        assertFalse(PartialDate(2019).endsBefore(PartialDate(2019, 4)))
        assertTrue(PartialDate(2019, 3).endsBefore(PartialDate(2019, 4)))
        assertFalse(PartialDate(2019, 4).endsBefore(PartialDate(2019, 4, 20)))
    }

    @Test fun aPinSurvivesARestartAndGoesWithItsLastVisit() {
        val s = store()
        s.set("JPN", TravelState.Visited)
        val trip = s.visits.value["JPN"]!!.trips.single().id
        s.addPin("JPN", trip, Pin("Hilltop shrine", 34.5, 131.8))
        val pinId = s.visits.value["JPN"]!!.cities.single()
        assertTrue(isPin(pinId))
        assertEquals(Pin("Hilltop shrine", 34.5, 131.8), store().visits.value["JPN"]!!.pins[pinId])
        s.renamePin(pinId, "  Old shrine ")
        assertEquals("Old shrine", s.visits.value["JPN"]!!.pins[pinId]?.name)
        s.removeCity(trip, pinId)
        assertTrue(s.visits.value["JPN"]!!.pins.isEmpty())
    }

    @Test fun aTownBecomesAStreetAddressAndKeepsItsDatesAndPlace() {
        val s = store()
        s.set("DNK", TravelState.Lived)
        val stay = s.visits.value["DNK"]!!.trips.single().id
        s.addCity("DNK", stay, "2618425")
        s.addCity("DNK", stay, "2624652")
        s.setStopDates(stay, "2618425", PartialDate(2017, 12), null)
        s.setCityNote("DNK", "2618425", Note(loved = true, rating = 4))
        s.replaceStopWithPin(stay, "2618425", Pin("Example Street 1", 55.6, 12.6))
        val trip = s.visits.value["DNK"]!!.trips.single()
        val pinId = trip.cities.first()
        assertTrue(isPin(pinId))
        assertEquals("2624652", trip.cities[1])
        assertEquals(PartialDate(2017, 12), trip.arrived[pinId])
        assertEquals("Example Street 1", s.visits.value["DNK"]!!.pins[pinId]?.name)
        assertEquals(Note(loved = true, rating = 4), s.visits.value["DNK"]!!.cityNotes[pinId])
    }

    @Test fun aDamagedPinIsDroppedAloneAndLeavesNoNamelessCity() {
        File(dir, "visits.json").writeText(
            """{"version":2,"countries":{"JPN":{"state":"visited","pins":{"pin-a":{"name":"Shrine","lat":"x","lon":1}},""" +
                """"trips":[{"id":"t1","cities":["pin-a","1850147"]}]},"PER":{"state":"visited"}}}""",
        )
        val v = store().visits.value
        assertEquals(listOf("1850147"), v["JPN"]!!.trips.single().cities)
        assertTrue(v["JPN"]!!.pins.isEmpty())
        assertEquals(TravelState.Visited, v["PER"]?.state)
    }

    @Test fun aPinForAVanishedVisitIsDropped() {
        val s = store()
        s.set("JPN", TravelState.Visited)
        s.addPin("JPN", "gone", Pin("Nowhere", 1.0, 2.0))
        assertTrue(s.visits.value["JPN"]!!.pins.isEmpty())
    }

    @Test fun stopDatesFillInFromTheVisitAndTheNextStop() {
        val t = Trip(
            "t", date = PartialDate(2025, 1, 3), until = PartialDate(2025, 1, 20),
            cities = listOf("tokyo", "kyoto", "osaka"),
            arrived = mapOf("kyoto" to PartialDate(2025, 1, 9)),
        )
        val (tokyoIn, tokyoOut) = t.stopDates("tokyo")
        assertEquals(PartialDate(2025, 1, 3) to true, tokyoIn)
        assertEquals(PartialDate(2025, 1, 9) to true, tokyoOut)
        assertEquals(PartialDate(2025, 1, 9) to false, t.stopDates("kyoto").first)
        assertEquals(null to false, t.stopDates("kyoto").second)
        assertEquals(PartialDate(2025, 1, 20) to true, t.stopDates("osaka").second)
    }

    @Test fun stopsMoveAndKeepTheirDatesAndMemories() {
        val s = store()
        s.set("JPN", TravelState.Visited)
        val trip = s.visits.value["JPN"]!!.trips.single().id
        for (c in listOf("a", "b", "c")) s.addCity("JPN", trip, c)
        s.setStopDates(trip, "c", PartialDate(2025, 2, 1), null)
        s.setStopOrder(trip, listOf("c", "a", "b"))
        // A list that is not the same stops changes nothing.
        s.setStopOrder(trip, listOf("c", "a"))
        s.addMemories(trip, listOf(Memory("", place = "c", text = "Snow on the temple roofs", takenMs = 5)))
        val again = store().visits.value["JPN"]!!.trips.single()
        assertEquals(listOf("c", "a", "b"), again.cities)
        assertEquals(PartialDate(2025, 2, 1), again.arrived["c"])
        assertEquals("Snow on the temple roofs", again.memories.single().text)
        s.removeCity(trip, "c")
        val after = s.visits.value["JPN"]!!.trips.single()
        assertNull(after.memories.single().place)
        assertFalse("c" in after.arrived)
    }

    @Test fun aBackupRestoresEverythingAndABadOneChangesNothing() {
        val s = store()
        s.set("JPN", TravelState.Visited)
        s.setRating("JPN", 4)
        s.setHome("DNK")
        val saved = s.backup()
        s.set("PER", TravelState.Want)
        assertEquals(2, s.backupSize(saved))
        assertTrue(s.restore(saved))
        assertNull(s.visits.value["PER"])
        assertEquals(4, s.visits.value["JPN"]?.rating)
        assertEquals("DNK", s.home.value)
        assertFalse(s.restore("this is not a backup"))
        assertNull(s.backupSize("{}"))
        assertEquals(2, s.visits.value.size)
    }

    /** Three made-up places: two cities in Japan's main islands, one on its southern islands, one in Korea. */
    private val geo = object : Geo {
        val at = mapOf("tokyo" to "JPN", "osaka" to "JPN", "naha" to "JPN-JPO", "seoul" to "KOR")
        override fun landOfCity(cityId: String) = at[cityId]
        override fun landOf(lat: Double, lon: Double) = if (lat < 27) "JPN-JPO" else "JPN"
        override fun countryOf(land: String) = land.substringBefore('-')
    }

    @Test fun aStopOnAnIslandMarksTheIslandAndStaysOneJapanTrip() {
        val s = store()
        s.geo = geo
        s.set("JPN", TravelState.Visited)
        val trip = s.visits.value["JPN"]!!.trips.single().id
        s.addCity("JPN", trip, "tokyo")
        s.addCity("JPN", trip, "naha")
        val v = s.visits.value
        assertEquals(TravelState.Visited, v["JPN-JPO"]?.state)
        assertEquals(listOf(trip), v["JPN"]!!.trips.map { it.id })
        assertEquals(listOf(trip), v["JPN-JPO"]!!.trips.map { it.id })
        assertEquals(listOf("JPN", "JPN-JPO"), v["JPN"]!!.trips.single().lands)
    }

    @Test fun removingASharedTripFromOneCountryKeepsItForTheOther() {
        val s = store()
        s.geo = geo
        s.set("JPN", TravelState.Visited)
        val trip = s.visits.value["JPN"]!!.trips.single().id
        s.addCity("JPN", trip, "osaka")
        s.addCity("JPN", trip, "seoul")
        s.addTrip("KOR")
        s.removeTrip("KOR", trip)
        assertEquals(listOf("osaka"), s.visits.value["JPN"]!!.trips.single { it.id == trip }.cities)
        assertTrue(s.visits.value["KOR"]!!.trips.none { it.id == trip })
    }

    @Test fun clearingBeforeTheMapLoadsStaysCleared() {
        val s = store()
        s.geo = geo
        s.set("JPN", TravelState.Visited)
        val trip = s.visits.value["JPN"]!!.trips.single().id
        s.addCity("JPN", trip, "osaka")
        s.addCity("JPN", trip, "seoul")
        val fresh = store()
        fresh.set("KOR", null)
        fresh.geo = geo
        assertNull(fresh.visits.value["KOR"])
        assertEquals(listOf("osaka"), fresh.visits.value["JPN"]!!.trips.single().cities)
    }

    @Test fun aPlaceKeepsTheNameTheUserGaveIt() {
        val s = store()
        s.set("DNK", TravelState.Lived)
        val stay = s.visits.value["DNK"]!!.trips.single().id
        s.addPin("DNK", stay, Pin("Example Street 1", 55.6, 12.6))
        val pinId = s.visits.value["DNK"]!!.cities.single()
        s.setCityNote("DNK", pinId, Note(name = "Mum's place"))
        assertEquals("Mum's place", store().visits.value["DNK"]!!.cityNotes[pinId]?.name)
    }

    @Test fun wantToGoOnACountryATripPassedThroughSticks() {
        val s = store()
        s.geo = geo
        s.set("JPN", TravelState.Visited)
        val trip = s.visits.value["JPN"]!!.trips.single().id
        s.addCity("JPN", trip, "osaka")
        s.addCity("JPN", trip, "seoul")
        s.set("KOR", TravelState.Want)
        assertEquals(TravelState.Want, s.visits.value["KOR"]?.state)
        assertEquals(TravelState.Want, store().also { it.geo = geo }.visits.value["KOR"]?.state)
        assertEquals(listOf("osaka"), s.visits.value["JPN"]!!.trips.single().cities)
    }

    @Test fun homeInAPlaceOnlyVisitedStartsANewStay() {
        val s = store()
        s.set("PRT", TravelState.Visited)
        val holiday = s.visits.value["PRT"]!!.trips.single()
        s.updateTrip(holiday.copy(date = PartialDate(2019, 6)))
        s.setHome("PRT")
        val trips = s.visits.value["PRT"]!!.trips
        assertEquals(2, trips.size)
        assertFalse("the holiday is not a stay", trips.first { it.id == holiday.id }.now)
        assertTrue(trips.single { it.id != holiday.id }.now)
    }

    @Test fun anImpossibleDayIsNotADate() {
        assertNull(PartialDate.parse("2023-02-31"))
        assertEquals(PartialDate(2024, 2, 29), PartialDate.parse("2024-02-29"))
    }

    @Test fun aDamagedFileLeavesTheStoreMarkedDamaged() {
        File(dir, "visits.json").writeText("{ not json")
        assertTrue(store().damaged)
        assertFalse(store().damaged)
    }

    @Test fun aTripAcrossABorderShowsOnBothCountries() {
        val s = store()
        s.geo = geo
        s.set("JPN", TravelState.Visited)
        val trip = s.visits.value["JPN"]!!.trips.single().id
        s.addCity("JPN", trip, "osaka")
        s.addCity("JPN", trip, "seoul")
        val v = s.visits.value
        assertEquals(TravelState.Visited, v["KOR"]?.state)
        assertEquals(trip, v["KOR"]!!.trips.single().id)
        assertEquals(listOf("osaka", "seoul"), v["KOR"]!!.trips.single().cities)
        // Clearing Korea takes Seoul off the trip; the Japan part stays.
        s.set("KOR", null)
        assertEquals(listOf("osaka"), s.visits.value["JPN"]!!.trips.single().cities)
    }

    @Test fun stopsAddedBeforeTheMapLoadedAreMarkedOnceItHas() {
        store().apply {
            set("JPN", TravelState.Visited)
            addCity("JPN", visits.value["JPN"]!!.trips.single().id, "naha")
        }
        val again = store()
        assertNull(again.visits.value["JPN-JPO"])
        again.geo = geo
        assertEquals(TravelState.Visited, again.visits.value["JPN-JPO"]?.state)
    }

    @Test fun aVersion2FileBecomesTripsStartingInEachCountry() {
        File(dir, "visits.json").writeText(
            """{"version":2,"countries":{"JPN":{"state":"visited","rating":4,"pins":{"pin-a":{"name":"Shrine","lat":34.5,"lon":131.8}},""" +
                """"trips":[{"id":"t1","date":"2019","cities":["tokyo","pin-a"]},{"id":"t2"}]}}}""",
        )
        val s = store()
        val v = s.visits.value["JPN"]!!
        assertEquals(4, v.rating)
        assertEquals(listOf("t1", "t2"), v.trips.map { it.id })
        assertEquals(listOf("JPN"), v.trips.first().lands)
        assertEquals("Shrine", v.pins["pin-a"]?.name)
        s.flush()
        assertTrue(File(dir, "visits.json").readText().contains("\"version\":3"))
    }

    @Test fun aGarbageFileIsSetAsideWithItsBytesAndFoundAgain() {
        val garbage = byteArrayOf(0x7b, 0x00, 0xff.toByte(), 0x13, 0x22, 0x0a)
        val f = File(dir, "visits.json")
        f.writeBytes(garbage)
        val s = store()
        assertTrue(s.visits.value.isEmpty())
        val aside = SetAside.find(f)
        assertEquals(1, aside.size)
        assertTrue(garbage.contentEquals(aside.single().readBytes()))
        // A save on the empty store never touches the set-aside copy.
        s.set("JPN", TravelState.Visited)
        assertTrue(garbage.contentEquals(aside.single().readBytes()))
        // The next launch still finds it, until it is marked handled.
        assertFalse(store().damaged)
        assertEquals(aside, SetAside.find(f))
        assertTrue(SetAside.handled(aside.single()))
        assertTrue(SetAside.find(f).isEmpty())
        assertTrue(garbage.contentEquals(File(dir, aside.single().name + ".handled").readBytes()))
    }

    @Test fun aFileFromANewerVersionIsSetAsideNotOverwritten() {
        val f = File(dir, "visits.json")
        val newer = "{\"version\":99,\"countries\":{\"PER\":{\"state\":\"visited\",\"future\":1}},\"trips\":[]}"
        f.writeText(newer)
        val s = store()
        assertTrue(s.damaged)
        assertTrue(s.visits.value.isEmpty())
        s.set("JPN", TravelState.Visited)
        val aside = SetAside.find(f).single()
        assertEquals(newer, aside.readText())
        assertTrue(SetAside.fromNewer(aside))
        // Nor is it restored as an older layout.
        assertNull(s.backupSize(newer))
        assertFalse(s.restore(newer))
        assertEquals(setOf("JPN"), s.visits.value.keys)
    }

    @Test fun setAsideFilesComeNewestFirst() {
        val f = File(dir, "visits.json")
        File(dir, "visits.json.unreadable-100").writeText("a")
        File(dir, "visits.json.unreadable-300").writeText("b")
        File(dir, "visits.json.unreadable-200.handled").writeText("c")
        File(dir, "visits.json.before-trips").writeText("d")
        assertEquals(listOf("visits.json.unreadable-300", "visits.json.unreadable-100"), SetAside.find(f).map { it.name })
        assertEquals(300L, SetAside.at(SetAside.find(f).first()))
    }
}
