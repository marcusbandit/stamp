package com.marcusrosado.stamp.ui

import com.marcusrosado.stamp.data.PartialDate
import com.marcusrosado.stamp.data.Trip

/** One year of trips in the Passport, or the undated ones when [year] is null. */
data class TripYear(val year: Int?, val trips: List<Trip>)

/**
 * The Passport's trips, newest first in one group per year, then the undated ones.
 *
 * A trip sits in the year it last happened: a stay in the year it ended, the stay the user
 * lives in now in [thisYear] and above everything else. Within a year the later end comes
 * first, and a vaguer date sorts after the precise ones it could be ("2023" after "Apr 2023").
 * Trips that tie keep the newest added first, which is also the order of the undated group.
 * Trips with neither a date nor a stop are counts on their country, not trips, and are left out.
 */
fun tripYears(trips: List<Trip>, thisYear: Int): List<TripYear> {
    val shown = trips.asReversed().filter { it.cities.isNotEmpty() || it.date != null }
    val (dated, undated) = shown.partition { lastDate(it, thisYear) != null }
    val years = dated
        .sortedWith(
            compareByDescending<Trip> { it.now }
                .thenByDescending { lastDate(it, thisYear) }
                .thenByDescending(nullsFirst()) { it.date },
        )
        .groupBy { lastDate(it, thisYear)!!.year }
        .map { (year, list) -> TripYear(year, list) }
    return if (undated.isEmpty()) years else years + TripYear(null, undated)
}

/** When [t] last happened, as far as the user said; a current stay is still going on. */
private fun lastDate(t: Trip, thisYear: Int): PartialDate? =
    if (t.now) PartialDate(thisYear) else t.until ?: t.date
