package com.marcusrosado.stamp.ui

import com.marcusrosado.stamp.data.PartialDate
import com.marcusrosado.stamp.data.Trip

/** One year of trips in the Passport, or the undated ones when [year] is null. */
data class TripYear(val year: Int?, val trips: List<Trip>)

/**
 * The Passport's trips, newest first in one group per year, then the undated ones.
 *
 * A trip sits in the year it last happened: a stay in the year it ended, the stay the user
 * lives in now in [thisYear], ahead of the rest of that year. Within a year the later end comes
 * first, and a vaguer date sorts after the precise ones it could be ("2023" after "Apr 2023").
 * Trips that tie keep the newest added first, which is also the order of the undated group.
 * Trips with no date and no stop are counts on their country, not trips, and are left out.
 */
fun tripYears(trips: List<Trip>, thisYear: Int): List<TripYear> {
    val now = PartialDate(thisYear)
    return trips.asReversed()
        .filter { it.cities.isNotEmpty() || it.date != null || it.until != null }
        .map { it to (if (it.now) now else it.until ?: it.date) }
        .sortedWith(
            compareByDescending<Pair<Trip, PartialDate?>, Int?>(nullsFirst()) { it.second?.year }
                .thenByDescending { it.first.now }
                .thenByDescending(nullsFirst()) { it.second }
                .thenByDescending(nullsFirst()) { it.first.date },
        )
        .groupBy({ it.second?.year }, { it.first })
        .map { (year, list) -> TripYear(year, list) }
}
