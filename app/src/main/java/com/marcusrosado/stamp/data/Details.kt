package com.marcusrosado.stamp.data

import java.time.Month
import java.time.format.TextStyle
import java.util.Locale

/**
 * A date known as precisely as the user remembers it: just the year, the year and month, or
 * the full day. [month] runs 1 to 12, and [day] is only set together with [month].
 */
data class PartialDate(val year: Int, val month: Int? = null, val day: Int? = null) : Comparable<PartialDate> {
    init {
        require(month == null || month in 1..12) { "month $month" }
        require(day == null || (month != null && java.time.YearMonth.of(year, month).isValidDay(day))) { "day $day" }
    }

    /** "2023", "2023-04" or "2023-04-02", which also sorts correctly as text. */
    val key: String
        get() = buildString {
            append("%04d".format(year))
            month?.let { append("-%02d".format(it)) }
            day?.let { append("-%02d".format(it)) }
        }

    /** "2023", "Apr 2023" or "2 Apr 2023". */
    val label: String
        get() {
            val m = month ?: return "$year"
            val name = Month.of(m).getDisplayName(TextStyle.SHORT, Locale.getDefault())
            return if (day == null) "$name $year" else "$day $name $year"
        }

    /**
     * True when a stay that ended on this date cannot have started on [start]. Missing parts
     * are generous: an end of "2019" fits a start of April 2019.
     */
    fun endsBefore(start: PartialDate): Boolean = when {
        year != start.year -> year < start.year
        month == null || start.month == null -> false
        month != start.month -> month < start.month
        day == null || start.day == null -> false
        else -> day < start.day
    }

    override fun compareTo(other: PartialDate): Int =
        compareValuesBy(this, other, { it.year }, { it.month ?: 0 }, { it.day ?: 0 })

    companion object {
        /** The inverse of [key]; null for anything else. */
        fun parse(s: String): PartialDate? = runCatching {
            val p = s.split("-").map { it.toInt() }
            when (p.size) {
                1 -> PartialDate(p[0])
                2 -> PartialDate(p[0], p[1])
                3 -> PartialDate(p[0], p[1], p[2])
                else -> null
            }
        }.getOrNull()
    }
}

/**
 * One visit to a country, or one stay in a country the user lived in. Every field is
 * optional: a visit with no date and no cities still counts, so the user can say "I've been
 * three times" and nothing more.
 */
data class Trip(
    val id: String,
    /** When the visit was, or when the stay began. */
    val date: PartialDate? = null,
    /** City ids, in the order they were added. */
    val cities: List<String> = emptyList(),
    /** When a stay ended. Unused for visits. */
    val until: PartialDate? = null,
    /** True for the one stay the user is living in now. Its country is home. */
    val now: Boolean = false,
    /** When the user got to each place on [cities], by place id, where they said. */
    val arrived: Map<String, PartialDate> = emptyMap(),
    /** When the user left a place, where they said. Otherwise it is the next arrival. */
    val left: Map<String, PartialDate> = emptyMap(),
    /** Photos, videos and written moments, oldest first. */
    val memories: List<Memory> = emptyList(),
    /**
     * The land masses the trip touched, the one it started from first: a country, or a separate
     * part of one such as the Ryukyu Islands. A trip that crosses borders lists several.
     */
    val lands: List<String> = emptyList(),
    /** How the user got from each stop to the next, where they said. */
    val legs: List<Leg> = emptyList(),
    /**
     * The place the trip set out from, a city or pin id; null is home at the trip's dates. Its
     * way to the first stop is the leg from [Leg.START].
     */
    val start: String? = null,
    /** Where the trip went back to; null is home. The way there is the leg to [Leg.END]. */
    val end: String? = null,
    /**
     * Places the user went to while staying at a stop, by stop id: day trips that share the
     * stop's dates instead of following it on the route. They count as visited like any stop.
     */
    val dayPlaces: Map<String, List<String>> = emptyMap(),
    /**
     * Where the user slept at a stop, by stop id, when it was one of its day places and not the
     * stop's own place: the hotel just over the border from the town the trip was about.
     */
    val slept: Map<String, String> = emptyMap(),
) {
    /** Every place the trip went, each once: its stops' places and the day places from them. */
    val places: List<String>
        get() = cities.flatMap { placesAt(it) }.distinct()

    /** Where the user slept at [stop]: its own place unless they said otherwise. */
    fun bedOf(stop: String): String = slept[stop] ?: placeIdOf(stop)

    /** A stay's homes in the order they were moved into, undated ones last. */
    val homesInOrder: List<String>
        get() = cities.sortedWith(compareBy(nullsLast()) { arrived[it] })

    /** Every place of [stop]: its own, then the ones gone to from it. */
    fun placesAt(stop: String): List<String> = listOf(placeIdOf(stop)) + dayPlaces[stop].orEmpty()

    /** Where the trip started, the country or land mass it was first added to. */
    val home: String? get() = lands.firstOrNull()

    /**
     * True when the trip is only a visit to city [cityId], with no dates or memories and not the
     * stay going on now: one that can be taken back without losing anything the user told.
     */
    fun isBareVisitTo(cityId: String): Boolean =
        cities == listOf(cityId) && date == null && until == null && memories.isEmpty() && !now

    /** The ways the user went from [from] to [to], or none when they did not say. */
    fun waysBetween(from: String, to: String): List<Travel> = legBetween(from, to)?.ways.orEmpty()

    fun legBetween(from: String, to: String): Leg? = legs.firstOrNull { it.from == from && it.to == to }

    /**
     * Every leg in order, as the stops it runs between: the way there from [Leg.START] to the
     * first stop, between each stop and the next, and home from the last to [Leg.END].
     */
    val legEnds: List<Pair<String, String>>
        get() = if (cities.isEmpty()) emptyList()
        else (listOf(Leg.START) + cities + Leg.END).zipWithNext()

    /** The legs between stops that follow each other; the rest were left behind by a reorder. */
    val currentLegs: List<Leg>
        get() = legEnds.mapNotNull { (a, b) -> legBetween(a, b) }

    /**
     * The legs worth keeping: both ends still on the trip. One left behind by a reorder stays,
     * unseen, so it is there again when the stops are put back.
     */
    val keptLegs: List<Leg>
        get() = legs.filter { (it.from == Leg.START || it.from in cities) && (it.to == Leg.END || it.to in cities) }

    val isBlank: Boolean
        get() = date == null && until == null && cities.isEmpty() && !now && memories.isEmpty()

    /**
     * When the user was at [place], as far as anything says: their own dates first, then the
     * visit's start for the first stop, the next stop's arrival for a departure, and the
     * visit's end for the last stop. The second value of each pair is true when it was guessed.
     */
    fun stopDates(place: String): Pair<Pair<PartialDate?, Boolean>, Pair<PartialDate?, Boolean>> {
        val i = cities.indexOf(place)
        val from = arrived[place]?.let { it to false }
            ?: (if (i == 0) date else cities.getOrNull(i - 1)?.let { left[it] })?.let { it to true }
            ?: (null to false)
        val to = left[place]?.let { it to false }
            ?: (cities.getOrNull(i + 1)?.let { arrived[it] } ?: if (i == cities.size - 1) until else null)?.let { it to true }
            ?: (null to false)
        return from to to
    }
}

/**
 * Something the user keeps from a visit: a photo or a video from their gallery, or a few
 * written lines. [place] ties it to a stop; null means the visit as a whole.
 */
data class Memory(
    val id: String,
    val place: String? = null,
    /** A content uri the app holds a lasting grant for, or null for a written moment. */
    val media: String? = null,
    val video: Boolean = false,
    val text: String = "",
    /** When the photo was taken or the moment written, in epoch ms. 0 is unknown. */
    val takenMs: Long = 0,
)

/** How the user feels about a city: a heart and a star rating. */
data class Note(
    val loved: Boolean = false,
    val rating: Int = 0,
    /** The user's own name for the place, such as "Mum's place"; empty for none. */
    val name: String = "",
) {
    val isEmpty: Boolean get() = !loved && rating == 0 && name.isBlank()
}

/**
 * A place the user put on the map by hand because the city list does not have it. It lives
 * on visits next to cities, under an id that starts with [PIN_PREFIX].
 */
data class Pin(val name: String, val lat: Double, val lon: Double)

const val PIN_PREFIX = "pin-"

/** Joins a place id to its count when a trip stops at the same place again: "1850147~2". */
const val STOP_SEP = '~'

/** The place a stop is at; a second stop at the same place on one trip carries a count. */
fun placeIdOf(stop: String): String = stop.substringBefore(STOP_SEP)

fun isPin(id: String) = id.startsWith(PIN_PREFIX)

/** Stars run 1 to 5; 0 means not rated. */
const val MAX_RATING = 5
