package com.marcusrosado.stamp

import com.marcusrosado.stamp.data.Atlas
import com.marcusrosado.stamp.data.TravelState
import com.marcusrosado.stamp.data.Visit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

class AtlasTest {
    companion object {
        private lateinit var atlas: Atlas

        @BeforeClass
        @JvmStatic
        fun load() {
            atlas = Atlas.parse(File("src/main/assets/countries.geojson").readText())
        }
    }

    private fun at(lat: Double, lon: Double) = atlas.locate(lat, lon)?.id

    @Test fun counts195UnCountries() = assertEquals(195, atlas.unCount)

    @Test fun cities() {
        assertEquals("DNK", at(55.676, 12.568))
        assertEquals("JPN", at(35.676, 139.650))
        assertEquals("PRT", at(38.722, -9.139))
        assertEquals("MAR", at(31.629, -7.981))
        assertEquals("USA", at(40.713, -74.006))
        assertEquals("BRA", at(-22.907, -43.173))
        assertEquals("AUS", at(-33.869, 151.209))
        assertEquals("ZAF", at(-33.925, 18.424))
    }

    @Test fun beachJustOffSimplifiedCoastCountsAsLand() {
        // Praia da Rocha, Algarve: right on the waterline, outside the 50m outline.
        assertEquals("PRT", at(37.115, -8.535))
    }

    @Test fun openOceanIsNowhere() {
        assertNull(at(30.0, -40.0))
        assertNull(at(0.0, 0.0))
    }

    @Test fun holesAreRespected() {
        // Lesotho sits inside South Africa as a hole.
        assertEquals("LSO", at(-29.31, 27.48))
    }

    @Test fun separateLandMassesAreTheirOwnPlaces() {
        assertEquals("FRA-FXC", at(42.15, 9.10))      // Corsica
        assertEquals("FRA-REU", at(-21.13, 55.53))    // Réunion
        assertEquals("USA-USH", at(21.31, -157.86))   // Honolulu
        assertEquals("JPN", at(43.06, 141.35))        // Sapporo: Hokkaido is close enough to stay Japan
        assertEquals("FRA", atlas.countryOf("FRA-REU"))
    }

    @Test fun aPartCountsForItsCountryOnce() {
        val visits = mapOf(
            "FRA-REU" to Visit(TravelState.Visited),
            "FRA" to Visit(TravelState.Visited),
            "USA-USH" to Visit(TravelState.Visited),
            "PRT" to Visit(TravelState.Want),
        )
        assertEquals(setOf("FRA", "USA"), atlas.countedCountries(visits))
    }

    @Test fun wholeCountriesShowTheStrongestMarkOfTheirParts() {
        val visits = mapOf("USA-USH" to Visit(TravelState.Visited), "USA-USK" to Visit(TravelState.Want))
        assertEquals(TravelState.Visited, atlas.markOf("USA", visits, split = false))
        assertEquals(TravelState.Visited, atlas.markOf("USA-USK", visits, split = false))
        assertNull(atlas.markOf("USA", visits, split = true))
    }
}
