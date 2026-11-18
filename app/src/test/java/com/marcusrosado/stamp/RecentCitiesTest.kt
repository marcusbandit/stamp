package com.marcusrosado.stamp

import com.marcusrosado.stamp.data.City
import com.marcusrosado.stamp.data.RECENT_CITIES
import com.marcusrosado.stamp.data.bumpRecent
import com.marcusrosado.stamp.data.recentFromText
import com.marcusrosado.stamp.data.recentToText
import com.marcusrosado.stamp.data.yourCities
import org.junit.Assert.assertEquals
import org.junit.Test

class RecentCitiesTest {
    private fun city(id: String, country: String, people: Int) = City(id, "Town $id", "Town $id", country, "01", 0.0, 0.0, people)

    private val byId = listOf(
        city("1", "AA", 5_000),
        city("2", "AA", 900_000),
        city("3", "AA", 40_000),
        city("4", "BB", 2_000_000),
    ).associateBy { it.id }

    @Test fun aPickMovesToTheFrontOnce() {
        assertEquals(listOf("3", "1", "2"), bumpRecent(listOf("1", "2", "3"), "3"))
        assertEquals(listOf("9", "1"), bumpRecent(listOf("1"), "9"))
    }

    @Test fun theOldestPickFallsOff() {
        val full = (1..RECENT_CITIES).map { "$it" }
        val after = bumpRecent(full, "new")
        assertEquals(RECENT_CITIES, after.size)
        assertEquals("new", after.first())
        assertEquals(false, "$RECENT_CITIES" in after)
    }

    @Test fun theFileRoundTripsAndSkipsJunk() {
        assertEquals(listOf("3", "1"), recentFromText(recentToText(listOf("3", "1"))))
        assertEquals(listOf("3", "1"), recentFromText("3\n\n 1 \n3\n"))
        assertEquals(emptyList<String>(), recentFromText(null))
    }

    @Test fun recentComeFirstThenOwnCitiesBiggestFirst() {
        val got = yourCities(recent = listOf("1"), mine = listOf("1", "3", "2"), byId = byId)
        assertEquals(listOf("1", "2", "3"), got.map { it.id })
    }

    @Test fun pinsAndOtherCountriesAreLeftOut() {
        val got = yourCities(
            recent = listOf("4", "pin:x"),
            mine = listOf("pin:y", "3"),
            byId = byId,
            keep = { it.country == "AA" },
        )
        assertEquals(listOf("3"), got.map { it.id })
    }

    @Test fun theListStopsAtTheLimit() {
        assertEquals(2, yourCities(listOf("1", "2", "3"), emptyList(), byId, limit = 2).size)
    }
}
