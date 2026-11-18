package com.marcusrosado.stamp

import com.marcusrosado.stamp.data.Atlas
import com.marcusrosado.stamp.data.CountrySearch
import com.marcusrosado.stamp.data.Places.Companion.fold
import com.marcusrosado.stamp.data.TravelState
import com.marcusrosado.stamp.ui.ListFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

class CountrySearchTest {
    companion object {
        private lateinit var atlas: Atlas

        @BeforeClass
        @JvmStatic
        fun load() {
            atlas = Atlas.parse(File("src/main/assets/countries.geojson").readText())
        }
    }

    private fun find(q: String) = atlas.mains.filter { CountrySearch.matches(it, fold(q)) }.map { it.id }.toSet()

    @Test fun shortNamesAndCodesFindTheirCountry() {
        assertTrue("GBR" in find("uk"))
        assertTrue("GBR" in find("Britain"))
        assertTrue("USA" in find("usa"))
        assertTrue("USA" in find("us"))
        assertTrue("NLD" in find("Holland"))
        assertTrue("CZE" in find("Czechia"))
        assertTrue("CIV" in find("Côte"))
        assertTrue("DNK" in find("dk"))
        assertTrue("ARE" in find("UAE"))
    }

    @Test fun namesStillMatchAnywhereInside() {
        assertTrue("ZAF" in find("africa"))
        assertEquals(setOf("DNK"), find("denmark"))
    }

    @Test fun oneLetterNeverMatchesAnOtherName() {
        // "Germany" has no d; only its other name "Deutschland" starts with one.
        assertFalse("DEU" in find("d"))
        assertTrue("DEU" in find("de"))
    }

    @Test fun filtersKeepWhatTheyCount() {
        assertTrue(ListFilter().keeps(null, "Europe"))
        assertFalse(ListFilter(ListFilter.MARKED).keeps(null, "Europe"))
        assertTrue(ListFilter(ListFilter.BEEN).keeps(TravelState.Lived, "Asia"))
        assertFalse(ListFilter(ListFilter.BEEN).keeps(TravelState.Want, "Asia"))
        assertTrue(ListFilter.of(TravelState.Want).keeps(TravelState.Want, "Asia"))
        assertFalse(ListFilter(continent = "Europe").keeps(TravelState.Visited, "Asia"))
        assertTrue(ListFilter(ListFilter.ALL, "Asia").keeps(null, "Asia"))
    }
}
