package com.marcusrosado.stamp

import com.marcusrosado.stamp.data.PartialDate
import com.marcusrosado.stamp.data.Trip
import com.marcusrosado.stamp.ui.tripYears
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TripYearsTest {
    private fun trip(id: String, date: PartialDate? = null, until: PartialDate? = null, now: Boolean = false, city: Boolean = true) =
        Trip(id, date = date, until = until, now = now, cities = if (city) listOf("c-$id") else emptyList())

    private fun shape(trips: List<Trip>) = tripYears(trips, thisYear = 2026).map { it.year to it.trips.map { t -> t.id } }

    @Test
    fun datedTripsGoNewestFirstByYearAndUndatedLast() {
        // As the store keeps them: ascending, undated last.
        val trips = listOf(
            trip("y2019", PartialDate(2019)),
            trip("d2021", PartialDate(2021, 3, 14)),
            trip("y2023", PartialDate(2023)),
            trip("m2023", PartialDate(2023, 4)),
            trip("d2023", PartialDate(2023, 4, 20)),
            trip("undatedA"),
            trip("undatedB"),
        )
        assertEquals(
            listOf(
                2023 to listOf("d2023", "m2023", "y2023"),
                2021 to listOf("d2021"),
                2019 to listOf("y2019"),
                null to listOf("undatedB", "undatedA"),
            ),
            shape(trips),
        )
    }

    @Test
    fun theCurrentStayLeadsThisYearAndAFinishedStayGoesWhereItEnded() {
        val trips = listOf(
            trip("old stay", PartialDate(2010), until = PartialDate(2024, 6)),
            trip("home", PartialDate(2018, 9), now = true),
            trip("visit", PartialDate(2026, 2, 1)),
            trip("spring", PartialDate(2024, 3)),
        )
        assertEquals(
            listOf(
                2026 to listOf("home", "visit"),
                2024 to listOf("old stay", "spring"),
            ),
            shape(trips),
        )
    }

    @Test
    fun anUndatedCurrentStayStillCountsAsNow() {
        val trips = listOf(trip("visit", PartialDate(2025)), trip("home", now = true))
        assertEquals(listOf(2026 to listOf("home"), 2025 to listOf("visit")), shape(trips))
    }

    @Test
    fun countsWithNoDateAndNoStopAreNotTrips() {
        val trips = listOf(trip("bare", city = false), trip("dated", PartialDate(2022), city = false))
        assertEquals(listOf(2022 to listOf("dated")), shape(trips))
        assertTrue(tripYears(emptyList(), 2026).isEmpty())
    }

    @Test
    fun sameDatesKeepTheNewestAddedFirst() {
        val trips = listOf(trip("first", PartialDate(2022, 5)), trip("second", PartialDate(2022, 5)))
        assertEquals(listOf(2022 to listOf("second", "first")), shape(trips))
    }
}
