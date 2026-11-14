package com.marcusrosado.stamp.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

enum class TravelState(val key: String, val label: String, val short: String) {
    Visited("visited", "Visited", "Visited"),
    Lived("lived", "Lived", "Lived"),
    Want("want", "Want to go", "Want"),
    ;

    /** Lived counts towards the passport the same way a visit does. */
    val counts: Boolean get() = this != Want

    companion object {
        fun of(key: String): TravelState? = entries.firstOrNull { it.key == key }
    }
}

/** The first and last time a country shows up in the user's photos. 0 means unknown. */
data class PhotoEvidence(val firstMs: Long, val lastMs: Long) {
    /** The wider span of the two, so a second scan only ever stretches the dates. */
    fun merge(other: PhotoEvidence?): PhotoEvidence {
        other ?: return this
        fun lo(a: Long, b: Long) = if (a <= 0) b else if (b <= 0) a else minOf(a, b)
        return PhotoEvidence(lo(firstMs, other.firstMs), maxOf(lastMs, other.lastMs))
    }
}

data class Visit(
    val state: TravelState,
    val evidence: PhotoEvidence? = null,
    val loved: Boolean = false,
    /** 0 to [MAX_RATING]; 0 is unrated. */
    val rating: Int = 0,
    /** Each visit to the country, oldest first when dated. Its size is the visit count. */
    val trips: List<Trip> = emptyList(),
    /** Region ids ("JP.15") the user added by hand. Regions of visited cities count on their own. */
    val regions: List<String> = emptyList(),
    /** Heart and stars per city id, for cities on at least one of [trips]. */
    val cityNotes: Map<String, Note> = emptyMap(),
    /** Places pinned by hand, by pin id. Each one is on at least one of [trips]. */
    val pins: Map<String, Pin> = emptyMap(),
    /** The map draws this country as its regions, each marked on its own. */
    val splitRegions: Boolean = false,
    /** Regions ("JP.15") the user wants to go to. */
    val wantRegions: List<String> = emptyList(),
    /** Cities, by id, the user wants to go to. */
    val wantCities: List<String> = emptyList(),
    /** Regions and cities, by id, the user lived in. */
    val livedPlaces: List<String> = emptyList(),
) {
    /** True when clearing the mark would throw away something the user entered. */
    val hasDetails: Boolean
        get() = loved || rating > 0 || trips.any { !it.isBlank } || regions.isNotEmpty() || wantRegions.isNotEmpty() || wantCities.isNotEmpty() || livedPlaces.isNotEmpty()

    /** Every city on any visit, each once, in the order first visited. */
    val cities: List<String> get() = trips.flatMap { it.places }.distinct()

    /** How many visits a city was on. */
    fun visitsTo(cityId: String): Int = trips.count { cityId in it.places }
}

/**
 * Where places are, for a store that only knows ids: which land mass a city or a point is on,
 * and which country a land mass belongs to. The app supplies one once its map data is loaded.
 */
interface Geo {
    fun landOfCity(cityId: String): String?
    fun landOf(lat: Double, lon: Double): String?
    fun countryOf(land: String): String
}

/**
 * What the user marked about one country or land mass, apart from its trips: the mark itself,
 * photo dates, heart, stars, regions and notes per city.
 */
private data class Mark(
    val state: TravelState,
    val evidence: PhotoEvidence? = null,
    val loved: Boolean = false,
    val rating: Int = 0,
    val regions: List<String> = emptyList(),
    val cityNotes: Map<String, Note> = emptyMap(),
    val split: Boolean = false,
    val wantRegions: List<String> = emptyList(),
    val wantCities: List<String> = emptyList(),
    val livedPlaces: List<String> = emptyList(),
)

/**
 * The user's marks and trips, kept in a JSON file in the app's private storage.
 *
 * Trips stand on their own: a trip lists the land masses it touched, so one trip can cross
 * borders (an Interrail) or reach an island of the same country (Tokyo, then Okinawa). Each
 * country's [Visit] is put together from its mark and the trips that touch it. Every land a
 * trip touches is marked visited, once [geo] says where its stops are.
 *
 * Every change updates what is shown at once, on the caller's thread, and the file is
 * rewritten whole on the save thread from the newest state ([LatestWriter]). [flush] waits
 * until the file holds everything; the app calls it when it goes to the background.
 */
class VisitStore(private val file: File) {
    private val writer = LatestWriter.of(file)

    private var marks: Map<String, Mark>
    private var trips: List<Trip>
    private var pins: Map<String, Pin>

    private val _visits = MutableStateFlow<Map<String, Visit>>(emptyMap())
    val visits: StateFlow<Map<String, Visit>> = _visits.asStateFlow()

    /** Every trip, each once, dated ones oldest first. */
    private val _trips = MutableStateFlow<List<Trip>>(emptyList())
    val allTrips: StateFlow<List<Trip>> = _trips.asStateFlow()

    /** Where the user lives now: the Lived land holding the stay marked now. */
    private val _home = MutableStateFlow<String?>(null)
    val home: StateFlow<String?> = _home.asStateFlow()

    /** Set once the map data has loaded; until then trips keep the lands they were saved with. */
    var geo: Geo? = null
        set(value) {
            synchronized(this) {
                field = value
                pinLands.clear()
                if (value != null) {
                    val drops = pendingDrops
                    pendingDrops = emptyMap()
                    // Only the trips that were there when the place was taken off them; the land may
                    // already be off their list, and its stops are what is left to go.
                    for ((land, tripIds) in drops) {
                        trips = trips.map { t -> if (t.id in tripIds) t.without(land) else t }
                    }
                }
                save()
            }
        }

    /**
     * Places taken off trips before the map data loaded, with the trips they came off, to
     * finish once it has. A place marked again, or a restore, cancels its entry.
     */
    private var pendingDrops: Map<String, Set<String>> = emptyMap()

    /** Which land each pin is on, worked out once per pin and place: a pin that moves is a new key. */
    private val pinLands = HashMap<Pin, String>()

    /**
     * The trips as the last settle left them, with the map data and pins it went by, and every
     * place each one goes to, sets out from or goes back to. A trip that is still the same object
     * needs nothing worked out again; any other change to the map data or the pins redoes all.
     */
    private var settled: Set<Trip> = emptySet()
    private var settledGeo: Geo? = null
    private var settledPins: Map<String, Pin>? = null
    private var placesOf = java.util.IdentityHashMap<Trip, List<String>>()

    /** True when the saved file could not be read and was set aside: the store started empty. */
    var damaged = false
        private set

    init {
        // A store opened again on the same file reads what the last one saved, all of it.
        writer.flush()
        val read = load()
        // Before an older layout is rewritten as trips, its file is kept beside, once.
        if (read.old) {
            val keep = File(file.parentFile, "${file.name}.before-trips")
            if (!keep.exists()) runCatching { file.copyTo(keep) }
        }
        marks = read.marks
        trips = read.trips
        pins = read.pins
        val before = Triple(marks, trips, pins)
        settle()
        publish()
        if (read.old || Triple(marks, trips, pins) != before) write()
        // Before stays, home was a country id in its own file. Move it onto a stay once.
        val oldHome = homeFile.takeIf { it.exists() }?.readText()?.trim()?.ifEmpty { null }
        if (oldHome != null) {
            if (_home.value == null) setHome(oldHome)
            homeFile.delete()
        }
    }

    // ------------------------------------------------------------------ marks

    @Synchronized
    fun set(id: String, state: TravelState?) {
        if (state == null) {
            clear(id)
        } else {
            val old = marks[id]
            marks = marks + (id to (old?.copy(state = state) ?: Mark(state)))
            // Marked again: an unfinished take-off from before the map loaded no longer applies.
            pendingDrops = pendingDrops - id
            // Only a stay in a Lived place can be where the user lives now.
            if (state != TravelState.Lived) trips = trips.map { if (it.now && it.home == id) it.copy(now = false) else it }
            // Want to go is the user's word against a trip that passed through: its stops there
            // come off trips that started elsewhere, or the next save would mark it visited again.
            if (state == TravelState.Want) dropStopsIn(id, alsoFromItsOwn = false)
        }
        save()
    }

    /** Clears several places in one step and one save: a country and its islands, say. */
    @Synchronized
    fun clearAll(ids: Collection<String>) {
        for (id in ids) clear(id)
        save()
    }

    /** Clearing a place drops it from every trip; a trip left with nothing goes too. */
    private fun clear(id: String) {
        marks = marks - id
        dropStopsIn(id, alsoFromItsOwn = true)
    }

    /**
     * Takes [id] and its stops off the trips that touch it, keeping each stop's memories with
     * the trip. Trips that started in [id] lose it only when [alsoFromItsOwn]; one left with no
     * land at all goes.
     */
    private fun dropStopsIn(id: String, alsoFromItsOwn: Boolean) {
        val g = geo
        // Before the map data loads, which stops lie in [id] is unknown: remember the place and
        // finish the job when it arrives, or the next settle would bring it back.
        val touched = trips.filter { t -> id in t.lands && (alsoFromItsOwn || t.home != id) && t.lands.size > 1 }.map { it.id }.toSet()
        if (g == null && touched.isNotEmpty()) pendingDrops = pendingDrops + (id to (pendingDrops[id].orEmpty() + touched))
        trips = trips.mapNotNull { t ->
            if (id !in t.lands || (!alsoFromItsOwn && t.home == id)) return@mapNotNull t
            if (t.lands.size == 1) return@mapNotNull null
            t.without(id)
        }
    }

    /**
     * Changes a marked place's own details: heart, stars, regions, notes. Its trips are changed
     * through the trip calls below; changes to [Visit.trips] made here are ignored.
     */
    @Synchronized
    fun edit(id: String, change: (Visit) -> Visit) {
        val old = _visits.value[id] ?: return
        val new = change(old)
        if (new == old) return
        val m = marks[id] ?: return
        marks = marks + (id to m.copy(
            state = new.state, evidence = new.evidence, loved = new.loved,
            rating = new.rating.coerceIn(0, MAX_RATING), regions = new.regions, cityNotes = new.cityNotes,
            split = new.splitRegions, wantRegions = new.wantRegions, wantCities = new.wantCities, livedPlaces = new.livedPlaces,
        ))
        save()
    }

    /**
     * Draws country [id] as its regions, or as one country again. Regions marked while split
     * stay with the country, so splitting it again brings them back. Only the drawing changes:
     * a country with no mark has nowhere to keep it, and is left as it is.
     */
    @Synchronized
    fun setSplitRegions(id: String, on: Boolean) {
        if (marks[id] == null) return
        edit(id) { it.copy(splitRegions = on) }
    }

    /**
     * A region or a city of country [id] marked [state] marks the country too, with the same
     * mark, when the country has none or only want to go. A country been to keeps its own.
     */
    private fun markCountryFor(id: String, state: TravelState) {
        val now = marks[id]?.state
        if (now == null || (state.counts && !now.counts)) set(id, state)
    }

    /**
     * Marks region [regionId] of country [id] visited, lived in, wanted, or neither. A region
     * marked in a country with no mark of its own, or only want to go, marks the country the
     * same way.
     */
    @Synchronized
    fun setRegion(id: String, regionId: String, state: TravelState?) {
        if (state == null) { if (marks[id] == null) return } else markCountryFor(id, state)
        edit(id) { x ->
            x.copy(
                regions = if (state == TravelState.Visited) (x.regions - regionId) + regionId else x.regions - regionId,
                wantRegions = if (state == TravelState.Want) (x.wantRegions - regionId) + regionId else x.wantRegions - regionId,
                livedPlaces = if (state == TravelState.Lived) (x.livedPlaces - regionId) + regionId else x.livedPlaces - regionId,
            )
        }
    }

    /**
     * Marks city [cityId] of country [id] the way a country is marked: visited gives it a visit
     * when it has none, lived and wanted are said of the city itself, and none takes back what
     * was said of it. Its visits stay; taking those back is one at a time. The country is
     * marked the way [setRegion] marks it, so a city been to never hides in a wanted country.
     */
    @Synchronized
    fun setCity(id: String, cityId: String, state: TravelState?) {
        if (state == null && marks[id] == null) return
        val visited = _visits.value[id]?.trips?.any { cityId in it.places } == true
        // The visit first: a country marked visited with no visit at all gets an empty one.
        if (state == TravelState.Visited && !visited) newTripAt(cityId)
        if (state != null) markCountryFor(id, state)
        edit(id) { x ->
            x.copy(
                wantCities = if (state == TravelState.Want) (x.wantCities - cityId) + cityId else x.wantCities - cityId,
                livedPlaces = if (state == TravelState.Lived) (x.livedPlaces - cityId) + cityId else x.livedPlaces - cityId,
            )
        }
    }

    /** Says the user wants to go to city [cityId] of country [id], or no longer does. */
    @Synchronized
    fun setCityWant(id: String, cityId: String, on: Boolean) {
        if (_visits.value[id] == null) { if (!on) return; set(id, TravelState.Want) }
        edit(id) { x -> x.copy(wantCities = if (on) (x.wantCities - cityId) + cityId else x.wantCities - cityId) }
    }

    fun setLoved(id: String, loved: Boolean) = edit(id) { it.copy(loved = loved) }

    fun setRating(id: String, rating: Int) = edit(id) { it.copy(rating = rating.coerceIn(0, MAX_RATING)) }

    fun addRegion(id: String, regionId: String) = edit(id) { v ->
        if (regionId in v.regions) v else v.copy(regions = v.regions + regionId)
    }

    fun removeRegion(id: String, regionId: String) = edit(id) { v -> v.copy(regions = v.regions - regionId) }

    fun setCityNote(id: String, cityId: String, note: Note) = edit(id) { v ->
        // A city on a visit, a city lived in or wanted, or a region ("JP.15") takes a note.
        if (cityId !in v.cities && cityId !in v.livedPlaces && cityId !in v.wantCities && '.' !in cityId) return@edit v
        v.copy(cityNotes = if (note.isEmpty) v.cityNotes - cityId else v.cityNotes + (cityId to note.copy(rating = note.rating.coerceIn(0, MAX_RATING))))
    }

    // ------------------------------------------------------------------ trips

    @Synchronized
    private fun changeTrip(tripId: String, change: (Trip) -> Trip) {
        val next = trips.map { if (it.id == tripId) change(it) else it }
        if (next == trips) return
        trips = next
        save()
    }

    /**
     * A new trip starting at a city, wherever it is: the way to log a trip without picking a
     * country first. Returns the trip's id, or null while the map data is still loading.
     */
    @Synchronized
    fun newTripAt(cityId: String): String? {
        val land = geo?.landOfCity(cityId) ?: return null
        val t = Trip(newTripId(), cities = listOf(cityId), lands = listOf(land))
        trips = trips + t
        save()
        return t.id
    }

    /**
     * Takes one visit to city [cityId] back: the newest visit that is only that city, with no
     * dates or memories. It goes, unless it is its country's only visit, which keeps its place
     * as a visit with no city. False when no such visit exists, so nothing the user told is lost.
     */
    @Synchronized
    fun forgetCityVisit(cityId: String): Boolean {
        val t = trips.lastOrNull { it.isBareVisitTo(cityId) } ?: return false
        val home = t.home
        val others = trips.count { it.id != t.id && home != null && home in it.lands }
        trips = if (others > 0) trips.filterNot { it.id == t.id }
        else trips.map { if (it.id == t.id) it.copy(cities = emptyList(), dayPlaces = emptyMap(), slept = emptyMap(), legs = emptyList()) else it }
        save()
        return true
    }

    /** One more visit to [id], starting there, with nothing filled in yet. */
    @Synchronized
    fun addTrip(id: String) {
        trips = trips + Trip(newTripId(), lands = listOf(id))
        save()
    }

    @Synchronized
    fun updateTrip(trip: Trip) {
        val stay = if (trip.now) trip.copy(until = null) else trip
        // Where the trip started stays put; its other lands follow its stops when it settles.
        trips = trips.map { if (it.id == trip.id) stay.copy(lands = it.lands) else it }
        // One home at a time: living here now ends "now" everywhere else.
        if (stay.now) trips = trips.map { if (it.id != stay.id && it.now) it.copy(now = false) else it }
        save()
    }

    /** Removes a trip everywhere. A Visited or Lived place always keeps at least one. */
    @Synchronized
    fun removeTrip(id: String, tripId: String) {
        val v = _visits.value[id]
        if (v != null && v.state.counts && v.trips.size <= 1) return
        val t = trips.firstOrNull { it.id == tripId } ?: return
        // A trip that started elsewhere only leaves this place: its stops here come off, and the
        // rest of it, with its dates and memories, stays where it began.
        val beganHere = t.home == id || geo?.countryOf(t.home ?: "") == id
        trips = if (!beganHere && t.lands.size > 1) {
            if (geo == null) pendingDrops = pendingDrops + (id to (pendingDrops[id].orEmpty() + tripId))
            trips.map { if (it.id == tripId) it.without(id) else it }
        } else trips.filterNot { it.id == tripId }
        save()
    }

    /** The trip with [land] and its stops taken off; memories of those stops stay with the trip. */
    private fun Trip.without(land: String): Trip {
        val g = geo
        val gone = if (g == null) emptySet() else cities.filter { landOfStop(it, g) == land }.toSet()
        // Day places in that land go too, wherever the stop they hang off is.
        fun there(place: String) = g != null && landOfStop(place, g) == land
        val days = (dayPlaces - gone).mapValues { (_, l) -> l.filterNot(::there) }.filterValues { it.isNotEmpty() }
        return copy(
            // A trip always keeps the land it started from.
            lands = (lands - land).ifEmpty { lands },
            cities = cities - gone,
            arrived = arrived - gone,
            left = left - gone,
            memories = memories.map { m -> if (m.place in gone) m.copy(place = null) else m },
            dayPlaces = days,
            slept = (slept - gone).filter { (stop, bed) -> bed in days[stop].orEmpty() },
        )
    }

    /** Puts a city on a trip. With no trip given, it goes on a new one starting at [id]. */
    @Synchronized
    fun addCity(id: String, tripId: String?, cityId: String) {
        trips = if (tripId == null) {
            trips + Trip(newTripId(), cities = listOf(cityId), lands = listOf(id))
        } else {
            // Back to a place the trip already stopped at: a new stop of its own, with a count.
            trips.map { if (it.id == tripId) it.copy(cities = it.cities + nextStopId(it, cityId)) else it }
        }
        save()
    }

    /** Puts a hand-pinned place on a trip, the way [addCity] puts a city there. */
    @Synchronized
    fun addPin(id: String, tripId: String?, pin: Pin) {
        if (tripId != null && trips.none { it.id == tripId }) return
        val pinId = PIN_PREFIX + newTripId()
        pins = pins + (pinId to pin)
        trips = if (tripId == null) {
            trips + Trip(newTripId(), cities = listOf(pinId), lands = listOf(id))
        } else {
            trips.map { if (it.id == tripId) it.copy(cities = it.cities + pinId) else it }
        }
        save()
    }

    private fun nextStopId(t: Trip, place: String): String {
        if (t.cities.none { placeIdOf(it) == place }) return place
        var n = 2
        while ("$place$STOP_SEP$n" in t.cities) n++
        return "$place$STOP_SEP$n"
    }

    /** Adds a place the user went to while staying at [stop], sharing its dates. */
    fun addDayPlace(tripId: String, stop: String, place: String) = changeTrip(tripId) { t ->
        if (stop !in t.cities || place == placeIdOf(stop) || place in t.dayPlaces[stop].orEmpty()) return@changeTrip t
        t.copy(dayPlaces = t.dayPlaces + (stop to t.dayPlaces[stop].orEmpty() + place))
    }

    /** Puts a pinned place on a stop as a day place: a spot by the town, say. */
    @Synchronized
    fun addDayPin(tripId: String, stop: String, pin: Pin) {
        if (trips.none { it.id == tripId && stop in it.cities }) return
        val pinId = PIN_PREFIX + newTripId()
        pins = pins + (pinId to pin)
        trips = trips.map { t ->
            if (t.id != tripId || stop !in t.cities) t
            else t.copy(dayPlaces = t.dayPlaces + (stop to t.dayPlaces[stop].orEmpty() + pinId))
        }
        save()
    }

    fun removeDayPlace(tripId: String, stop: String, place: String) = changeTrip(tripId) { t ->
        val left = t.dayPlaces[stop].orEmpty() - place
        t.copy(
            dayPlaces = if (left.isEmpty()) t.dayPlaces - stop else t.dayPlaces + (stop to left),
            slept = if (t.slept[stop] == place) t.slept - stop else t.slept,
        )
    }

    /** Says where the user slept at [stop]: its own place, or one of its day places. */
    fun setSlept(tripId: String, stop: String, place: String) = changeTrip(tripId) { t ->
        when {
            stop !in t.cities -> t
            place == placeIdOf(stop) -> t.copy(slept = t.slept - stop)
            place in t.dayPlaces[stop].orEmpty() -> t.copy(slept = t.slept + (stop to place))
            else -> t
        }
    }

    /**
     * Puts a hand-pinned place where a stop was: a street address for a town, say. It keeps the
     * stop's place in the order, its dates and its memories.
     */
    @Synchronized
    fun replaceStopWithPin(tripId: String, place: String, pin: Pin) {
        val t = trips.firstOrNull { it.id == tripId } ?: return
        if (place !in t.cities) return
        val pinId = PIN_PREFIX + newTripId()
        pins = pins + (pinId to pin)
        fun <V> Map<String, V>.moved() = mapKeys { (k, _) -> if (k == place) pinId else k }
        // Its heart and stars go with it, unless another stop on the trip is at the same place.
        val notesKey = placeIdOf(place)
        if (t.cities.none { it != place && placeIdOf(it) == notesKey }) {
            marks = marks.mapValues { (_, m) ->
                if (notesKey in m.cityNotes) m.copy(cityNotes = m.cityNotes.mapKeys { (k, _) -> if (k == notesKey) pinId else k }) else m
            }
        }
        trips = trips.map {
            if (it.id != tripId) it else it.copy(
                cities = it.cities.map { c -> if (c == place) pinId else c },
                arrived = it.arrived.moved(),
                left = it.left.moved(),
                legs = it.legs.map { l -> l.copy(from = if (l.from == place) pinId else l.from, to = if (l.to == place) pinId else l.to) },
                memories = it.memories.map { m -> if (m.place == place) m.copy(place = pinId) else m },
                dayPlaces = it.dayPlaces.moved(),
                slept = it.slept.moved(),
            )
        }
        save()
    }

    /** Moves a hand-pinned place, say an address found by its real street. */
    @Synchronized
    fun movePin(pinId: String, lat: Double, lon: Double) {
        val pin = pins[pinId] ?: return
        pins = pins + (pinId to pin.copy(lat = lat, lon = lon))
        save()
    }

    @Synchronized
    fun renamePin(pinId: String, name: String) {
        val pin = pins[pinId] ?: return
        if (name.isBlank()) return
        pins = pins + (pinId to pin.copy(name = name.trim()))
        save()
    }

    /**
     * Takes a stop off a trip, with its day places. A place that is only a day place comes off
     * the stop it hangs from instead.
     */
    fun removeCity(tripId: String, cityId: String) = changeTrip(tripId) {
        if (cityId !in it.cities) {
            val days = it.dayPlaces.mapValues { (_, l) -> l - cityId }.filterValues { l -> l.isNotEmpty() }
            return@changeTrip it.copy(dayPlaces = days, slept = it.slept.filterValues { bed -> bed != cityId })
        }
        it.copy(
            cities = it.cities - cityId,
            arrived = it.arrived - cityId,
            left = it.left - cityId,
            dayPlaces = it.dayPlaces - cityId,
            slept = it.slept - cityId,
            // Memories of a stop that is gone stay with the trip as a whole.
            memories = it.memories.map { m -> if (m.place == cityId) m.copy(place = null) else m },
        )
    }

    /** Sets or clears when the user got to and left one stop of a trip. */
    fun setStopDates(tripId: String, place: String, arrived: PartialDate?, left: PartialDate?) =
        changeTrip(tripId) { t ->
            if (place !in t.cities) return@changeTrip t
            t.copy(
                arrived = if (arrived == null) t.arrived - place else t.arrived + (place to arrived),
                left = if (left == null) t.left - place else t.left + (place to left),
            )
        }

    /**
     * Sets how the user got from stop [from] to stop [to], [Leg.START] and [Leg.END] included,
     * as the steps it took; no step with a way or a stop clears it.
     */
    fun setLeg(tripId: String, from: String, to: String, steps: List<Step>, around: Boolean = false) = changeTrip(tripId) { t ->
        if ((from to to) !in t.legEnds) return@changeTrip t
        val rest = t.legs.filterNot { it.from == from && it.to == to }
        val kept = Leg.tidy(steps)
        t.copy(legs = if (kept.isEmpty()) rest else rest + Leg(from, to, kept, around && kept.any { it.way == Travel.Plane }))
    }

    /** Sets where a trip set out from, a city or pin id; null is home. */
    fun setTripStart(tripId: String, place: String?) = changeTrip(tripId) { it.copy(start = place) }

    /** Sets where a trip went back to, a city or pin id; null is home. */
    fun setTripEnd(tripId: String, place: String?) = changeTrip(tripId) { it.copy(end = place) }

    /**
     * Puts a trip's stops in the order [cities], which holds the same stops rearranged; any
     * other list is ignored, so a stop added or removed meanwhile is never lost.
     */
    fun setStopOrder(tripId: String, cities: List<String>) = changeTrip(tripId) { t ->
        if (cities.size != t.cities.size || cities.toSet() != t.cities.toSet() || cities == t.cities) t
        else t.copy(cities = cities)
    }

    fun addMemories(tripId: String, add: List<Memory>) = changeTrip(tripId) { t ->
        val known = t.memories.mapNotNull { it.media }.toSet()
        val fresh = add.filter { it.media == null || it.media !in known }.map { if (it.id.isEmpty()) it.copy(id = newTripId()) else it }
        t.copy(memories = (t.memories + fresh).sortedBy { if (it.takenMs > 0) it.takenMs else Long.MAX_VALUE })
    }

    fun updateMemory(tripId: String, memory: Memory) = changeTrip(tripId) { t ->
        t.copy(memories = t.memories.map { if (it.id == memory.id) memory else it })
    }

    fun removeMemory(tripId: String, memoryId: String) = changeTrip(tripId) { t ->
        t.copy(memories = t.memories.filterNot { it.id == memoryId })
    }

    // ------------------------------------------------------------------ photos and home

    /**
     * Widens the dates of every place in [found] that has or gets a mark. Places in [mark]
     * become Visited, unless they are already Visited or Lived: photos from a place marked
     * Want to go are proof of a visit.
     */
    @Synchronized
    fun addFromPhotos(found: Map<String, PhotoEvidence>, mark: Set<String>) {
        val next = marks.toMutableMap()
        for ((id, ev) in found) {
            val old = next[id]
            val state = when {
                old != null && old.state.counts -> old.state
                id in mark -> TravelState.Visited
                else -> old?.state ?: continue
            }
            next[id] = old?.copy(state = state, evidence = ev.merge(old.evidence)) ?: Mark(state, ev)
        }
        marks = next
        save()
    }

    /**
     * Sets where the user lives now: marks the place Lived and its latest open stay as now, and
     * ends "now" everywhere else. Null means the user lives nowhere they have marked.
     */
    @Synchronized
    fun setHome(id: String?) {
        trips = trips.map { if (it.now) it.copy(now = false) else it }
        if (id != null) {
            val wasLived = marks[id]?.state == TravelState.Lived
            marks = marks + (id to (marks[id]?.copy(state = TravelState.Lived) ?: Mark(TravelState.Lived)))
            // The stay that is still open is the one going on now. A place whose stays all
            // ended gets a new one, so no end date is ever thrown away. A place only visited
            // so far gets a new stay too: a holiday there never turns into living there.
            val open = if (!wasLived) null
            else trips.filter { it.home == id && it.until == null }.maxWithOrNull(compareBy(nullsFirst()) { it.date })
            trips = if (open != null) trips.map { if (it.id == open.id) it.copy(now = true) else it }
            else trips + Trip(newTripId(), now = true, lands = listOf(id))
        }
        save()
    }

    // ------------------------------------------------------------------ working it out

    private fun landOfStop(stop: String, g: Geo): String? {
        val place = placeIdOf(stop)
        return pins[place]?.let { landOfPin(it, g) } ?: if (isPin(place)) null else g.landOfCity(place)
    }

    private fun landOfPin(pin: Pin, g: Geo): String? =
        pinLands[pin] ?: g.landOf(pin.lat, pin.lon)?.also { pinLands[pin] = it }

    /**
     * Brings everything in line after a change: each trip's lands follow its stops, every land
     * a trip touches is marked visited, a visited place without a trip gets one, and pins and
     * notes nobody uses any more are dropped.
     */
    private fun settle() {
        // A pin id whose pin was lost, say to a damaged file, would show as a nameless city forever.
        fun lost(id: String) = placeIdOf(id).let { isPin(it) && it !in pins }
        val g = geo
        val known = if (g === settledGeo && pins === settledPins) settled else emptySet()
        trips = trips.map { trip ->
            if (trip in known) return@map trip
            var t = trip
            if (t.cities.any(::lost) || t.dayPlaces.values.any { l -> l.any(::lost) }) t = t.copy(
                cities = t.cities.filterNot(::lost),
                dayPlaces = t.dayPlaces.mapValues { (_, l) -> l.filterNot(::lost) }.filterValues { it.isNotEmpty() },
                slept = t.slept.filterValues { !lost(it) },
            )
            // A leg goes with a stop it ran to or from. One whose stops were only reordered apart
            // stays, unseen, and is back when the stops line up again.
            t.keptLegs.let { keep -> if (keep.size != t.legs.size) t = t.copy(legs = keep) }
            if (g != null) {
                val stops = t.places.mapNotNull { landOfStop(it, g) }
                val lands = (t.lands.take(1) + stops).distinct()
                if (lands != t.lands && lands.isNotEmpty()) t = t.copy(lands = lands)
            }
            t
        }
        // Somewhere a trip went is somewhere the user has been.
        val touched = trips.filter { t -> t.lands.firstOrNull()?.let { marks[it]?.state?.counts } != false }.flatMap { it.lands }.toSet()
        for (land in touched) {
            val m = marks[land]
            if (m == null || !m.state.counts) marks = marks + (land to (m?.copy(state = TravelState.Visited) ?: Mark(TravelState.Visited)))
        }
        // A Visited or Lived place has been visited at least once, so it holds at least one trip.
        val reached = trips.flatMapTo(HashSet()) { it.lands }
        for ((id, m) in marks) {
            if (m.state.counts && id !in reached) trips = trips + Trip(newTripId(), lands = listOf(id))
        }
        // A pin a trip set out from or went back to is in use too, though it is no stop.
        val before = placesOf
        placesOf = java.util.IdentityHashMap(trips.size * 2)
        for (t in trips) placesOf[t] = before[t] ?: (t.places + listOfNotNull(t.start, t.end))
        val used = placesOf.values.flatMapTo(HashSet()) { it }
        if (pins.keys.any { it !in used }) pins = pins.filterKeys { it in used }
        // Notes go with their place: a region's stays, as does a city's that is lived in or wanted.
        fun kept(m: Mark, k: String) = k in used || '.' in k || k in m.livedPlaces || k in m.wantCities
        marks = marks.mapValues { (_, m) -> if (m.cityNotes.keys.all { kept(m, it) }) m else m.copy(cityNotes = m.cityNotes.filterKeys { kept(m, it) }) }
        settled = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Trip, Boolean>(trips.size * 2)).apply { addAll(trips) }
        settledGeo = g
        settledPins = pins
    }

    /**
     * The trips that count for each place, dated ones oldest first, in one pass over [sorted]:
     * those that touch it, and for a country, its parts too.
     */
    private fun tripsByPlace(sorted: List<Trip>): Map<String, List<Trip>> {
        val g = geo
        val by = HashMap<String, MutableList<Trip>>()
        val keys = LinkedHashSet<String>()
        for (t in sorted) {
            keys.clear()
            for (land in t.lands) {
                keys += land
                if (g != null) keys += g.countryOf(land)
            }
            for (k in keys) by.getOrPut(k) { ArrayList() } += t
        }
        return by
    }

    private fun publish() {
        val sorted = trips.sortedWith(compareBy(nullsLast()) { it.date })
        val byPlace = tripsByPlace(sorted)
        // A place's pins are those its trips go to, set out from or go back to. Each trip's are
        // found once, and come out in the order of the whole list.
        val pinOrder = HashMap<String, Int>(pins.size * 2).apply { pins.keys.forEachIndexed { i, k -> put(k, i) } }
        val pinsOfTrip = java.util.IdentityHashMap<Trip, List<String>>()
        fun pinsOn(ts: List<Trip>): Map<String, Pin> {
            if (pinOrder.isEmpty()) return emptyMap()
            val here = ts.flatMapTo(HashSet()) { t ->
                pinsOfTrip.getOrPut(t) { (placesOf[t] ?: (t.places + listOfNotNull(t.start, t.end))).filter { it in pinOrder } }
            }
            if (here.isEmpty()) return emptyMap()
            return here.sortedBy { pinOrder[it] }.associateWith { pins.getValue(it) }
        }
        val view = marks.mapValues { (id, m) ->
            val ts = byPlace[id].orEmpty()
            Visit(
                state = m.state, evidence = m.evidence, loved = m.loved, rating = m.rating,
                trips = ts, regions = m.regions, cityNotes = m.cityNotes,
                pins = pinsOn(ts),
                splitRegions = m.split, wantRegions = m.wantRegions, wantCities = m.wantCities, livedPlaces = m.livedPlaces,
            )
        }
        _visits.value = view
        _trips.value = sorted
        _home.value = trips.firstOrNull { it.now }?.home?.takeIf { marks[it]?.state == TravelState.Lived }
    }

    private fun save() {
        settle()
        publish()
        write()
    }

    /**
     * Waits until the file holds every change made so far. Called when the app goes to the
     * background, and before a backup is read or a restore reports back. False when the write
     * failed; the changes stay in memory and are tried again on the next save.
     */
    fun flush(): Boolean = writer.flush()

    private fun newTripId() = java.util.UUID.randomUUID().toString().take(8)

    private val homeFile get() = File(file.parentFile, "home.txt")

    // ------------------------------------------------------------------ the file

    private class Read(val marks: Map<String, Mark>, val trips: List<Trip>, val pins: Map<String, Pin>, val old: Boolean)

    // The state as it is now, encoded and written on the save thread. The maps and lists are
    // never changed in place, only replaced, so the snapshot stays as it was taken.
    private fun write() {
        val m = marks
        val t = trips
        val p = pins
        writer.submit { encode(m, t, p) }
    }

    private fun encode(marks: Map<String, Mark>, trips: List<Trip>, pins: Map<String, Pin>): String {
        val root = JSONObject().put("version", 3)
        root.put("countries", JSONObject().apply {
            for ((id, m) in marks) {
                put(id, JSONObject().put("state", m.state.key).apply {
                    m.evidence?.let { put("first", it.firstMs).put("last", it.lastMs) }
                    if (m.loved) put("loved", true)
                    if (m.rating > 0) put("rating", m.rating)
                    if (m.regions.isNotEmpty()) put("regions", JSONArray(m.regions))
                    if (m.split) put("split", true)
                    if (m.wantRegions.isNotEmpty()) put("wantRegions", JSONArray(m.wantRegions))
                    if (m.wantCities.isNotEmpty()) put("wantCities", JSONArray(m.wantCities))
                    if (m.livedPlaces.isNotEmpty()) put("livedPlaces", JSONArray(m.livedPlaces))
                    if (m.cityNotes.isNotEmpty()) put("cityNotes", JSONObject().apply {
                        for ((cid, n) in m.cityNotes) put(cid, JSONObject().apply { if (n.loved) put("loved", true); if (n.rating > 0) put("rating", n.rating); if (n.name.isNotBlank()) put("name", n.name) })
                    })
                })
            }
        })
        root.put("trips", JSONArray(trips.map { tripJson(it) }))
        if (pins.isNotEmpty()) root.put("pins", JSONObject().apply {
            for ((pid, p) in pins) put(pid, JSONObject().put("name", p.name).put("lat", p.lat).put("lon", p.lon))
        })
        return root.toString()
    }

    private fun tripJson(t: Trip) = JSONObject().put("id", t.id).apply {
        if (t.lands.isNotEmpty()) put("lands", JSONArray(t.lands))
        t.date?.let { put("date", it.key) }
        t.until?.let { put("until", it.key) }
        if (t.now) put("now", true)
        if (t.cities.isNotEmpty()) put("cities", JSONArray(t.cities))
        if (t.arrived.isNotEmpty()) put("arrived", JSONObject(t.arrived.mapValues { it.value.key }))
        if (t.left.isNotEmpty()) put("left", JSONObject(t.left.mapValues { it.value.key }))
        // Only legs whose stops are both still on the trip are kept.
        t.keptLegs.takeIf { it.isNotEmpty() }?.let { legs ->
            put("legs", JSONArray(legs.map { l ->
                JSONObject().put("from", l.from).put("to", l.to)
                    .put("steps", JSONArray(l.steps.map { st -> JSONObject().apply { st.way?.let { put("way", it.key) }; st.to?.let { put("to", it) } } }))
                    .apply { if (l.around) put("around", true) }
            }))
        }
        if (t.dayPlaces.isNotEmpty()) put("days", JSONObject(t.dayPlaces.filterKeys { it in t.cities }.mapValues { JSONArray(it.value) }))
        if (t.slept.isNotEmpty()) put("slept", JSONObject(t.slept.filterKeys { it in t.cities }))
        t.start?.let { put("start", it) }
        t.end?.let { put("end", it) }
        if (t.memories.isNotEmpty()) put("memories", JSONArray(t.memories.map { m ->
            JSONObject().put("id", m.id).apply {
                m.place?.let { put("place", it) }
                m.media?.let { put("media", it) }
                if (m.video) put("video", true)
                if (m.text.isNotEmpty()) put("text", m.text)
                if (m.takenMs > 0) put("taken", m.takenMs)
            }
        }))
    }

    private fun load(): Read {
        if (!file.exists()) return Read(emptyMap(), emptyList(), emptyMap(), false)
        return runCatching { parse(file.readText()) }.getOrElse {
            // Keep the unreadable file aside instead of letting the next save overwrite it.
            file.renameTo(File(file.parentFile, "${file.name}.unreadable-${System.currentTimeMillis()}"))
            damaged = true
            Read(emptyMap(), emptyList(), emptyMap(), false)
        }
    }

    private fun dates(o: JSONObject?): Map<String, PartialDate> =
        o?.keys()?.asSequence()?.mapNotNull { k -> PartialDate.parse(o.optString(k))?.let { k to it } }?.toMap().orEmpty()

    private fun strings(a: JSONArray?): List<String> =
        a?.let { (0 until it.length()).mapNotNull { i -> it.optString(i).ifEmpty { null } } }.orEmpty()

    private fun parseTrip(t: JSONObject, lands: List<String>) = Trip(
        id = t.optString("id").ifEmpty { newTripId() },
        date = t.optString("date").takeIf { it.isNotEmpty() }?.let { PartialDate.parse(it) },
        until = t.optString("until").takeIf { it.isNotEmpty() }?.let { PartialDate.parse(it) },
        now = t.optBoolean("now"),
        cities = strings(t.optJSONArray("cities")),
        arrived = dates(t.optJSONObject("arrived")),
        left = dates(t.optJSONObject("left")),
        legs = t.optJSONArray("legs")?.let { a ->
            (0 until a.length()).mapNotNull { k ->
                val l = a.optJSONObject(k) ?: return@mapNotNull null
                val from = l.optString("from"); val to = l.optString("to")
                val around = l.optBoolean("around")
                val steps = l.optJSONArray("steps")
                when {
                    from.isEmpty() || to.isEmpty() -> null
                    steps != null -> Leg.tidy((0 until steps.length()).mapNotNull { i ->
                        steps.optJSONObject(i)?.let { st -> Step(Travel.of(st.optString("way")), st.optString("to").ifEmpty { null }) }
                    }).takeIf { it.isNotEmpty() }?.let { Leg(from, to, it, around) }
                    // Saved before legs had steps: its ways and its one flight's airports.
                    else -> Leg.fromWays(
                        from, to, strings(l.optJSONArray("ways")).mapNotNull { Travel.of(it) },
                        strings(l.optJSONArray("via")).map { it.uppercase() }, around,
                    )
                }
            }
        }.orEmpty(),
        start = t.optString("start").ifEmpty { null },
        dayPlaces = t.optJSONObject("days")?.let { o ->
            o.keys().asSequence().associateWith { k -> strings(o.optJSONArray(k)) }.filterValues { it.isNotEmpty() }
        }.orEmpty(),
        slept = t.optJSONObject("slept")?.let { o -> o.keys().asSequence().associateWith { o.optString(it) }.filterValues { it.isNotEmpty() } }.orEmpty(),
        end = t.optString("end").ifEmpty { null },
        memories = t.optJSONArray("memories")?.let { a ->
            (0 until a.length()).mapNotNull { k ->
                val m = a.optJSONObject(k) ?: return@mapNotNull null
                val media = m.optString("media").ifEmpty { null }
                val text = m.optString("text")
                if (media == null && text.isBlank()) return@mapNotNull null
                Memory(
                    id = m.optString("id").ifEmpty { newTripId() },
                    place = m.optString("place").ifEmpty { null },
                    media = media,
                    video = m.optBoolean("video"),
                    text = text,
                    takenMs = m.optLong("taken"),
                )
            }
        }.orEmpty(),
        lands = lands,
    )

    private fun parsePins(o: JSONObject?): Map<String, Pin> =
        o?.keys()?.asSequence()?.mapNotNull { k ->
            val x = o.optJSONObject(k) ?: return@mapNotNull null
            val name = x.optString("name").ifEmpty { return@mapNotNull null }
            val lat = x.optDouble("lat")
            val lon = x.optDouble("lon")
            // A damaged pin is dropped on its own, like a damaged trip.
            if (lat.isNaN() || lon.isNaN()) return@mapNotNull null
            k to Pin(name, lat, lon)
        }?.toMap().orEmpty()

    /**
     * Reads either layout: version 3 keeps trips and pins at the top, version 2 and older kept
     * them inside each country, and those become trips starting in that country.
     */
    private fun parse(text: String): Read {
        val root = JSONObject(text)
        val countries = root.getJSONObject("countries")
        val marks = LinkedHashMap<String, Mark>()
        val trips = ArrayList<Trip>()
        val pins = LinkedHashMap<String, Pin>()
        val v3 = root.optInt("version") >= 3
        for (id in countries.keys()) {
            // A damaged country is skipped on its own; it never takes the others with it.
            val o = countries.optJSONObject(id) ?: continue
            val state = TravelState.of(o.optString("state")) ?: continue
            marks[id] = Mark(
                state,
                evidence = if (o.has("first")) PhotoEvidence(o.optLong("first"), o.optLong("last")) else null,
                loved = o.optBoolean("loved"),
                rating = o.optInt("rating").coerceIn(0, MAX_RATING),
                regions = strings(o.optJSONArray("regions")),
                split = o.optBoolean("split"),
                wantRegions = strings(o.optJSONArray("wantRegions")),
                wantCities = strings(o.optJSONArray("wantCities")),
                livedPlaces = strings(o.optJSONArray("livedPlaces")),
                cityNotes = o.optJSONObject("cityNotes")?.let { n ->
                    n.keys().asSequence().mapNotNull { k ->
                        val x = n.optJSONObject(k) ?: return@mapNotNull null
                        k to Note(x.optBoolean("loved"), x.optInt("rating").coerceIn(0, MAX_RATING), x.optString("name").trim())
                    }.toMap()
                }.orEmpty(),
            )
            if (!v3) {
                o.optJSONArray("trips")?.let { a ->
                    for (i in 0 until a.length()) a.optJSONObject(i)?.let { trips += parseTrip(it, listOf(id)) }
                }
                pins += parsePins(o.optJSONObject("pins"))
            }
        }
        if (v3) {
            root.optJSONArray("trips")?.let { a ->
                for (i in 0 until a.length()) {
                    val t = a.optJSONObject(i) ?: continue
                    val lands = strings(t.optJSONArray("lands"))
                    if (lands.isNotEmpty()) trips += parseTrip(t, lands)
                }
            }
            pins += parsePins(root.optJSONObject("pins"))
        }
        return Read(marks, trips, pins, old = !v3)
    }

    /** Everything the store holds, as the text of a backup file. */
    @Synchronized
    fun backup(): String {
        flush()
        return encode(marks, trips, pins)
    }

    /** How many places a backup holds, or null when the text is not a Stamp backup. */
    fun backupSize(text: String): Int? = runCatching { parse(text).marks.size }.getOrNull()

    /**
     * Replaces everything with what [text] holds. Returns false and changes nothing when the
     * text cannot be read, so a damaged file never wipes what is on the phone.
     */
    @Synchronized
    fun restore(text: String): Boolean {
        val read = runCatching { parse(text) }.getOrNull() ?: return false
        marks = read.marks
        trips = read.trips
        pins = read.pins
        pendingDrops = emptyMap()
        save()
        // On disk before the user is told it worked.
        flush()
        return true
    }
}

/** One-line flags in the app's private storage, such as whether onboarding has run. */
class Flags(private val dir: File) {
    fun get(name: String): Boolean = File(dir, "$name.flag").exists()
    fun set(name: String, on: Boolean) {
        val f = File(dir, "$name.flag")
        if (on) f.writeText("1") else f.delete()
    }
}
