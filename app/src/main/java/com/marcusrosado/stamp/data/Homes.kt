package com.marcusrosado.stamp.data

/** A home address the user lived at: a stop on a stay in a place they lived. */
data class Home(val place: String, val visit: Visit, val from: PartialDate?, val to: PartialDate?, val now: Boolean)

object Homes {
    /**
     * Every home the user has given: the ones lived in now first, and among those, and among
     * the rest, the one moved into last first, so where two seem to overlap the later move wins.
     */
    fun all(visits: Map<String, Visit>): List<Home> =
        visits.values.filter { it.state == TravelState.Lived }.flatMap { v ->
            v.trips.flatMap { stay ->
                stay.cities.map { id ->
                    val from = stay.arrived[id] ?: stay.date
                    val to = stay.left[id] ?: stay.until
                    Home(id, v, from, to, now = stay.now && stay.left[id] == null)
                }
            }
        }.sortedWith(compareByDescending<Home> { it.now }.thenByDescending(nullsFirst()) { it.from })

    /**
     * Where the user lived when a trip on [date] began: the home whose dates hold it, or the
     * home lived in now when none does or the trip has no date. Null when no address was given.
     */
    fun at(visits: Map<String, Visit>, date: PartialDate?): Home? {
        val homes = all(visits)
        if (date != null) {
            homes.sortedWith(compareByDescending(nullsFirst()) { it.from }).firstOrNull { h ->
                (h.from != null || h.to != null) &&
                    (h.from == null || !date.endsBefore(h.from)) &&
                    (h.to == null || !h.to.endsBefore(date))
            }?.let { return it }
        }
        return homes.firstOrNull { it.now } ?: homes.firstOrNull()
    }
}
