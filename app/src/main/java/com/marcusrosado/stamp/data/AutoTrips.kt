package com.marcusrosado.stamp.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor

/** A town a found trip stopped at, with the first and last time a photo puts the user there. */
data class FoundStop(val city: City, val firstMs: Long, val lastMs: Long, val spots: Int)

/**
 * A trip a scan found in the photos: days away from home in a row, with the towns in the order
 * they were first seen.
 */
data class FoundTrip(
    val startMs: Long,
    val endMs: Long,
    val stops: List<FoundStop>,
    val photos: Int,
    /** The land it starts in, the way the review counts countries; empty until worked out. */
    val land: String = "",
    /** Every land its stops are in, [land] first, counted the same way. */
    val lands: List<String> = listOfNotNull(land.ifEmpty { null }),
) {
    /** Stays the same when the same photos are read again, so a tick in the review sticks to it. */
    val key: String get() = "trip-$startMs"
}

/**
 * The cities of the bundled list on a grid, so the town a photo was taken in is found by
 * looking at the few cells around it instead of at every city on Earth.
 */
class TownIndex(cities: Iterable<City>) {
    private val cells = HashMap<Long, MutableList<City>>()

    init {
        for (c in cities) cells.getOrPut(cell(row(c.lat), col(c.lon))) { ArrayList(4) } += c
    }

    /**
     * The town a photo at [lat], [lon] was taken in: the one that best weighs size against
     * distance, so a photo in a city's suburb names the city and one in a village far from
     * anywhere names the village. Null past [MAX_KM].
     */
    fun townOf(lat: Double, lon: Double): City? {
        val r = row(lat)
        val c = col(lon)
        // A cell is half a degree; a degree of longitude shrinks towards the poles.
        val dr = ceil(MAX_KM / 111.0 / STEP).toInt()
        val dc = ceil(MAX_KM / (111.0 * cos(Math.toRadians(abs(lat).coerceAtMost(85.0)))) / STEP).toInt()
        var best: City? = null
        var score = 0.0
        for (i in r - dr..r + dr) for (j in c - dc..c + dc) {
            val list = cells[cell(i, Math.floorMod(j, COLS))] ?: continue
            for (t in list) {
                val d = Distance.km(lat to lon, t.lat to t.lon)
                if (d > MAX_KM) continue
                val s = t.population.coerceAtLeast(1) / ((1 + d) * (1 + d))
                if (s > score) { score = s; best = t }
            }
        }
        return best
    }

    private fun row(lat: Double) = floor((lat + 90) / STEP).toInt()
    private fun col(lon: Double) = Math.floorMod(floor((lon + 180) / STEP).toInt(), COLS)
    private fun cell(r: Int, c: Int) = r.toLong() * COLS + c

    companion object {
        private const val STEP = 0.5
        private const val COLS = (360 / STEP).toInt()

        /** How far a town may be from a photo and still be where it was taken. */
        const val MAX_KM = 30.0
    }
}

/** A town a found trip adds to a trip the user has, as its stop number [at], counted from 0. */
data class NewStop(val city: City, val at: Int, val arrived: PartialDate, val left: PartialDate)

/**
 * What a found trip fills in on a trip the user has: towns that fit between its stops, dates of
 * stops the user left without one, and the trip's own date when it has none.
 */
data class TripUpdate(
    val tripId: String,
    val stops: List<NewStop>,
    /** Arrival and leaving days for stops that had none, by stop id. */
    val arrived: Map<String, PartialDate>,
    val left: Map<String, PartialDate>,
    val date: PartialDate?,
    /** The trip as it was, for the review to name it. */
    val trip: Trip,
    /** The lands the added towns are in, the way the review counts countries. */
    val lands: List<String> = emptyList(),
) {
    val key: String get() = "update-$tripId"
}

/** Groups photo places into trips away from home. */
object AutoTrips {
    /**
     * The trips in [points]: runs of days with a photo away from home, broken by a day whose
     * photos are all at home or by more than [GAP_DAYS] days with no photo at all, or
     * [LONG_GAP_DAYS] when the photos on both sides are in one country. [homeAt]
     * gives where the user lived on a day, when they said; for other days it is the town their
     * photos show most often that year. A town seen in a single photo is no stop, and a trip
     * with no stop left is no trip.
     */
    fun find(
        points: List<SeenPoint>,
        towns: TownIndex,
        zone: ZoneId = ZoneId.systemDefault(),
        homeAt: (LocalDate) -> Pair<Double, Double>? = { null },
        /** Where the user lives now, for a year whose photos show no home. */
        fallback: Pair<Double, Double>? = null,
    ): List<FoundTrip> {
        if (points.isEmpty()) return emptyList()
        val sorted = points.filter { it.ms > 0 }.sortedBy { it.ms }
        // Photos cluster, hundreds from one spot: look each ~1 km cell up once.
        val cache = HashMap<Long, City?>()
        fun town(p: SeenPoint): City? {
            val k = Math.round(p.lat * 100) * 100_000 + Math.round(p.lon * 100)
            return if (cache.containsKey(k)) cache[k] else towns.townOf(p.lat, p.lon).also { cache[k] = it }
        }
        fun day(p: SeenPoint) = Instant.ofEpochMilli(p.ms).atZone(zone).toLocalDate()

        val guessed = guessHomes(sorted, zone, ::town)
        fun home(d: LocalDate): Pair<Double, Double>? = homeAt(d) ?: guessed[d.year] ?: fallback ?: guessed.nearest(d.year)

        class Run(val days: MutableList<LocalDate>, val away: MutableList<SeenPoint>)
        val runs = ArrayList<Run>()
        var current: Run? = null
        for ((d, list) in sorted.groupBy(::day).toSortedMap()) {
            val h = home(d)
            val away = list.filter { p -> h == null || Distance.km(h, p.lat to p.lon) > HOME_KM }
            if (away.isEmpty()) {
                // A day at home ends the trip.
                current = null
                continue
            }
            val last = current?.days?.last()
            // Days with no photo inside one country are still the same trip, up to a week of them.
            val sameCountry = current != null && town(current.away.last())?.country.let { it != null && it == town(away.first())?.country }
            val gap = if (sameCountry) LONG_GAP_DAYS else GAP_DAYS
            if (current == null || last == null || last.plusDays(gap + 1L) < d) {
                current = Run(ArrayList(), ArrayList()).also { runs += it }
            }
            current.days += d
            current.away += away
        }
        return runs.mapNotNull { run ->
            class Seen(val city: City, var first: Long, var last: Long, var spots: Int)
            val byTown = LinkedHashMap<String, Seen>()
            for (p in run.away) {
                val c = town(p) ?: continue
                val s = byTown.getOrPut(c.id) { Seen(c, p.ms, p.ms, 0) }
                s.last = p.ms
                s.spots++
            }
            val stops = byTown.values.filter { it.spots >= MIN_SPOTS }
                // A long road trip can pass through dozens: keep the towns with the most photos.
                .sortedByDescending { it.spots }.take(MAX_STOPS)
                .sortedBy { it.first }
                .map { FoundStop(it.city, it.first, it.last, it.spots) }
            // Long away is a stay, however few days of it have photos.
            if (stops.isEmpty() || java.time.temporal.ChronoUnit.DAYS.between(run.days.first(), run.days.last()) >= MAX_DAYS) null
            else FoundTrip(run.away.first().ms, run.away.last().ms, stops, run.away.size)
        }
    }

    /**
     * Where the user seems to have lived each year: the town their photos show on the most
     * days, when that is enough days to be more than a holiday.
     */
    private fun guessHomes(points: List<SeenPoint>, zone: ZoneId, town: (SeenPoint) -> City?): Map<Int, Pair<Double, Double>> {
        val days = HashMap<Int, HashMap<String, HashSet<Long>>>()
        val cities = HashMap<String, City>()
        for (p in points) {
            val c = town(p) ?: continue
            cities[c.id] = c
            val d = Instant.ofEpochMilli(p.ms).atZone(zone).toLocalDate()
            days.getOrPut(d.year) { HashMap() }.getOrPut(c.id) { HashSet() } += d.toEpochDay()
        }
        return days.mapNotNull { (year, towns) ->
            val (id, seen) = towns.maxByOrNull { it.value.size } ?: return@mapNotNull null
            if (seen.size < MIN_HOME_DAYS) null else year to cities.getValue(id).let { it.lat to it.lon }
        }.toMap()
    }

    private fun Map<Int, Pair<Double, Double>>.nearest(year: Int): Pair<Double, Double>? =
        entries.minByOrNull { abs(it.key - year) }?.value

    /**
     * True when [trip] is one the user already has: one of [existing] sharing a land with it
     * whose dates overlap it. A date given as a month or a year covers all of it, and a trip's
     * dates run from the earliest the user gave to the latest.
     */
    fun known(trip: FoundTrip, lands: Set<String>, existing: List<Trip>, zone: ZoneId = ZoneId.systemDefault()): Boolean {
        val from = day(trip.startMs, zone).minusDays(1)
        val to = day(trip.endMs, zone).plusDays(1)
        return existing.any { t ->
            if (t.lands.none { it in lands }) return@any false
            val (start, end) = span(t) ?: return@any false
            !start.isAfter(to) && !end.isBefore(from)
        }
    }

    /** [trip] as a trip on the map: its stops in order, each with the days it was seen. */
    fun toTrip(trip: FoundTrip, id: String, land: String, zone: ZoneId = ZoneId.systemDefault()): Trip {
        fun day(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate().let { PartialDate(it.year, it.monthValue, it.dayOfMonth) }
        val stops = trip.stops.distinctBy { it.city.id }
        return Trip(
            id,
            date = day(trip.startMs),
            cities = stops.map { it.city.id },
            arrived = stops.associate { it.city.id to day(it.firstMs) },
            left = stops.associate { it.city.id to day(it.lastMs) },
            lands = listOf(land),
            pickPhotos = true,
        )
    }

    /** The trip of [existing] that [found] overlaps most, sharing one of [lands]; null for none. */
    fun overlapping(found: FoundTrip, lands: Set<String>, existing: List<Trip>, zone: ZoneId = ZoneId.systemDefault()): Trip? {
        val from = day(found.startMs, zone)
        val to = day(found.endMs, zone)
        return existing.filter { known(found, lands, listOf(it), zone) }.maxByOrNull { t ->
            val (a, b) = span(t) ?: return@maxByOrNull 0L
            val start = maxOf(a, from)
            val end = minOf(b, to)
            java.time.temporal.ChronoUnit.DAYS.between(start, end)
        }
    }

    /**
     * What [found] adds to [trip], or null when it adds nothing. A town goes in only where its
     * days clearly fit, by [slot]. A stop the user gave no days gets the photos' days, as far as
     * they agree with the days the user did give and with the stops on either side; a trip with
     * no date gets the first day it covers. Days the user gave never change, and a town they said
     * was no stop, or one the trip already has, is left out.
     */
    fun update(trip: Trip, found: FoundTrip, zone: ZoneId = ZoneId.systemDefault()): TripUpdate? {
        var work = trip
        val fillArrived = LinkedHashMap<String, PartialDate>()
        val fillLeft = LinkedHashMap<String, PartialDate>()
        val added = ArrayList<NewStop>()
        val have = trip.places.map(::placeIdOf).toSet()
        for (f in found.stops.sortedBy { it.firstMs }) {
            val a = day(f.firstMs, zone)
            val b = day(f.lastMs, zone)
            val same = work.cities.filter { placeIdOf(it) == f.city.id }
            if (same.size == 1) {
                // A stop the user has: its days, where they left them out and the photos agree.
                val s = same[0]
                val k = work.cities.indexOf(s)
                val prevEnd = (0 until k).mapNotNull { endOf(work, it) }.maxOrNull()
                val nextStart = (k + 1 until work.cities.size).mapNotNull { startOf(work, it) }.minOrNull()
                val myStart = dayOf(work.arrived[s])
                val myEnd = dayOf(work.left[s])
                if (work.arrived[s] == null && (prevEnd == null || !a.isBefore(prevEnd)) && (myEnd == null || !a.isAfter(myEnd))) {
                    fillArrived[s] = pd(a)
                    work = work.copy(arrived = work.arrived + (s to pd(a)))
                }
                if (work.left[s] == null && (nextStart == null || !b.isAfter(nextStart)) && (myStart == null || !b.isBefore(myStart))) {
                    fillLeft[s] = pd(b)
                    work = work.copy(left = work.left + (s to pd(b)))
                }
                continue
            }
            if (same.isNotEmpty() || f.city.id in have || f.city.id in trip.notStops) continue
            val at = slot(work, pd(a), pd(b)) ?: continue
            work = work.copy(
                cities = work.cities.take(at) + f.city.id + work.cities.drop(at),
                arrived = work.arrived + (f.city.id to pd(a)),
                left = work.left + (f.city.id to pd(b)),
            )
            added += NewStop(f.city, at, pd(a), pd(b))
        }
        // The trip began on the first day anything puts the user there, the photos or a stop.
        val date = if (trip.date == null) {
            listOfNotNull(day(found.startMs, zone), span(work)?.first).min().let(::pd)
        } else null
        if (added.isEmpty() && fillArrived.isEmpty() && fillLeft.isEmpty() && date == null) return null
        return TripUpdate(trip.id, added, fillArrived, fillLeft, date, trip)
    }

    /**
     * Where a town seen from [arrived] to [left] clearly goes among [trip]'s stops, counted from
     * 0: after every stop that had ended by its first day and before every stop that began after
     * its last. A stop with no arrival day, or no leaving day and no next stop to bound it, leaves
     * the gaps next to it unclear: null.
     */
    fun slot(trip: Trip, arrived: PartialDate, left: PartialDate): Int? {
        val a = dayOf(arrived) ?: return null
        val b = dayOf(left) ?: a
        val n = trip.cities.size
        return (0..n).firstOrNull { i ->
            (0 until i).all { k -> endOf(trip, k)?.let { !it.isAfter(a) } == true } &&
                (i until n).all { k -> startOf(trip, k)?.let { !it.isBefore(b) } == true }
        }
    }

    private fun pd(d: LocalDate) = PartialDate(d.year, d.monthValue, d.dayOfMonth)

    /** A date given to the day, as a day; null for a month, a year or none. */
    private fun dayOf(d: PartialDate?): LocalDate? =
        d?.takeIf { it.month != null && it.day != null }?.let { LocalDate.of(it.year, it.month!!, it.day!!) }

    private fun startOf(t: Trip, k: Int): LocalDate? = dayOf(t.arrived[t.cities[k]])

    /** When stop [k] ended: the day the user left, or else the next stop's arrival. */
    private fun endOf(t: Trip, k: Int): LocalDate? =
        dayOf(t.left[t.cities[k]]) ?: t.cities.getOrNull(k + 1)?.let { dayOf(t.arrived[it]) }

    private fun day(ms: Long, zone: ZoneId) = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()

    /** The first and last day a trip's dates cover; null when it has none. */
    private fun span(t: Trip): Pair<LocalDate, LocalDate>? {
        val dates = listOfNotNull(t.date, t.until) + t.arrived.values + t.left.values
        if (dates.isEmpty()) return null
        return dates.minOf(::firstDay) to dates.maxOf(::lastDay)
    }

    private fun firstDay(d: PartialDate) = LocalDate.of(d.year, d.month ?: 1, d.day ?: 1)

    private fun lastDay(d: PartialDate): LocalDate = when {
        d.day != null -> firstDay(d)
        d.month != null -> firstDay(d).withDayOfMonth(firstDay(d).lengthOfMonth())
        else -> LocalDate.of(d.year, 12, 31)
    }

    /** Further than this from home is away. */
    const val HOME_KM = 40.0

    /** Days with no photo that a trip may have in it and still be one trip. */
    const val GAP_DAYS = 2

    /** The same, when the photos on either side are in one country. */
    const val LONG_GAP_DAYS = 7

    /** A home has to show on this many days of a year to be guessed as one. */
    private const val MIN_HOME_DAYS = 20

    private const val MIN_SPOTS = 2

    private const val MAX_STOPS = 20

    /** Longer than this away is a stay, not a trip. */
    private const val MAX_DAYS = 120
}

/** Photo places kept for finding trips, packed: a big library has tens of thousands. */
class PhotoPoints {
    private var ms = LongArray(1024)
    private var lat = FloatArray(1024)
    private var lon = FloatArray(1024)
    var size = 0
        private set

    fun add(t: Long, la: Double, lo: Double) {
        if (size == ms.size) {
            ms = ms.copyOf(size * 2)
            lat = lat.copyOf(size * 2)
            lon = lon.copyOf(size * 2)
        }
        ms[size] = t
        lat[size] = la.toFloat()
        lon[size] = lo.toFloat()
        size++
    }

    fun addAll(o: PhotoPoints) {
        for (i in 0 until o.size) add(o.ms[i], o.lat[i].toDouble(), o.lon[i].toDouble())
    }

    fun toList(): List<SeenPoint> = List(size) { SeenPoint(ms[it], lat[it].toDouble(), lon[it].toDouble(), SeenFrom.Photo) }

    fun write(file: java.io.File) {
        val tmp = java.io.File(file.path + ".part")
        java.io.DataOutputStream(tmp.outputStream().buffered()).use { out ->
            out.writeInt(size)
            for (i in 0 until size) { out.writeLong(ms[i]); out.writeFloat(lat[i]); out.writeFloat(lon[i]) }
        }
        if (!tmp.renameTo(file)) tmp.delete()
    }

    companion object {
        /** What [write] saved; empty when the file is missing or damaged. */
        fun read(file: java.io.File): PhotoPoints = runCatching {
            java.io.DataInputStream(file.inputStream().buffered()).use { inp ->
                val p = PhotoPoints()
                repeat(inp.readInt()) { p.add(inp.readLong(), inp.readFloat().toDouble(), inp.readFloat().toDouble()) }
                p
            }
        }.getOrDefault(PhotoPoints())
    }
}
