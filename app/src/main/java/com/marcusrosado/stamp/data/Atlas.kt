package com.marcusrosado.stamp.data

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** One closed ring of a polygon, longitude in [xs] and latitude in [ys]. */
class Ring(val xs: DoubleArray, val ys: DoubleArray)

/** A polygon: its outline and any holes cut out of it, such as a lake or an enclave. */
class Shape(val outer: Ring, val holes: List<Ring>)

class Country(
    /** ISO 3166 alpha-3 where one exists, otherwise Natural Earth's own code. */
    val id: String,
    val name: String,
    val continent: String,
    /** True for the 193 UN members and the two observer states. */
    val un: Boolean,
    /** ISO alpha-2, used for the flag. Empty when the place has none. */
    val alpha2: String,
    val labelLon: Double,
    val labelLat: Double,
    val shapes: List<Shape>,
    /**
     * The country this land mass belongs to. Equal to [id] for a country's main land mass;
     * a separate one, such as Corsica or Hawaii, names its country here.
     */
    val country: String = id,
) {
    /** True for a separate land mass of a country, not its mainland. */
    val isPart: Boolean get() = country != id

    val minLon: Double
    val maxLon: Double
    val minLat: Double
    val maxLat: Double

    init {
        var x0 = 180.0; var x1 = -180.0; var y0 = 90.0; var y1 = -90.0
        for (s in shapes) {
            for (x in s.outer.xs) { x0 = min(x0, x); x1 = max(x1, x) }
            for (y in s.outer.ys) { y0 = min(y0, y); y1 = max(y1, y) }
        }
        minLon = x0; maxLon = x1; minLat = y0; maxLat = y1
    }

    /** Regional indicator pair for the flag emoji, or empty. */
    val flag: String
        get() = if (alpha2.length == 2) {
            alpha2.uppercase().map { String(Character.toChars(0x1F1E6 + (it - 'A'))) }.joinToString("")
        } else ""

    /**
     * True when the point is within [margin] degrees of latitude of the bounding box.
     * Longitude degrees shrink towards the poles, so the longitude margin grows to match
     * the same ground distance, the way [distanceTo] measures it.
     */
    fun near(lat: Double, lon: Double, margin: Double): Boolean {
        val lonMargin = margin / cos(Math.toRadians(lat)).coerceAtLeast(0.05)
        return lat >= minLat - margin && lat <= maxLat + margin &&
            lon >= minLon - lonMargin && lon <= maxLon + lonMargin
    }

    fun contains(lat: Double, lon: Double): Boolean =
        shapes.any { s -> inRing(s.outer, lon, lat) && s.holes.none { inRing(it, lon, lat) } }

    /** Shortest distance from the point to this country's outline, in degrees of latitude. */
    fun distanceTo(lat: Double, lon: Double): Double {
        val k = cos(Math.toRadians(lat))
        var best = Double.MAX_VALUE
        for (s in shapes) {
            val r = s.outer
            for (i in 0 until r.xs.size - 1) {
                val d = segmentDistance(
                    lon * k, lat, r.xs[i] * k, r.ys[i], r.xs[i + 1] * k, r.ys[i + 1],
                )
                if (d < best) best = d
            }
        }
        return best
    }
}

/** Even-odd ray cast. Points on an edge may land either way, which is fine at this scale. */
internal fun inRing(r: Ring, x: Double, y: Double): Boolean {
    var inside = false
    val xs = r.xs; val ys = r.ys
    var j = xs.size - 1
    for (i in xs.indices) {
        if ((ys[i] > y) != (ys[j] > y) &&
            x < (xs[j] - xs[i]) * (y - ys[i]) / (ys[j] - ys[i]) + xs[i]
        ) inside = !inside
        j = i
    }
    return inside
}

private fun segmentDistance(px: Double, py: Double, ax: Double, ay: Double, bx: Double, by: Double): Double {
    val dx = bx - ax; val dy = by - ay
    val len2 = dx * dx + dy * dy
    val t = if (len2 == 0.0) 0.0 else (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0.0, 1.0)
    val cx = ax + t * dx - px; val cy = ay + t * dy - py
    return sqrt(cx * cx + cy * cy)
}

/** Every country the app knows, plus lookups by id and by position. */
class Atlas(val countries: List<Country>) {
    val byId: Map<String, Country> = countries.associateBy { it.id }

    /** Countries without their separate land masses: one entry per country. */
    val mains: List<Country> = countries.filter { !it.isPart }

    private val partsByCountry: Map<String, List<Country>> = countries.filter { it.isPart }.groupBy { it.country }

    /** The separate land masses of a country, not counting its mainland. */
    fun partsOf(countryId: String): List<Country> = partsByCountry[countryId].orEmpty()

    /** The country a land mass belongs to; a country is its own. */
    fun countryOf(id: String): String = byId[id]?.country ?: id

    /**
     * How a land mass shows on the map. Split, each part has its own mark. Whole, every part
     * shows the strongest mark among the country and all its parts, the same rule the
     * passport uses when it counts the country as been.
     */
    fun markOf(id: String, visits: Map<String, Visit>, split: Boolean): TravelState? {
        if (split) return visits[id]?.state
        val c = countryOf(id)
        return (listOf(c) + partsOf(c).map { it.id }).mapNotNull { visits[it]?.state }.maxByOrNull { strength(it) }
    }

    /**
     * The visit that stands for a whole country: its own, or else that of its most strongly
     * marked part. Null when nothing of the country is marked.
     */
    fun wholeVisit(countryId: String, visits: Map<String, Visit>): Visit? =
        visits[countryId] ?: partsOf(countryId).mapNotNull { visits[it.id] }.maxByOrNull { strength(it.state) }

    private fun strength(s: TravelState) = when (s) {
        TravelState.Lived -> 2
        TravelState.Visited -> 1
        TravelState.Want -> 0
    }

    /**
     * The UN countries the user has been to, each once: a visit to Réunion or Hawaii counts
     * for France or the United States.
     */
    fun countedCountries(visits: Map<String, Visit>): Set<String> =
        visits.filter { it.value.state.counts }.keys.map { countryOf(it) }.filter { byId[it]?.un == true }.toSet()
    val unCount: Int = countries.count { it.un }

    /** UN countries per continent, the denominator for continent progress. */
    val unPerContinent: Map<String, Int> =
        countries.filter { it.un }.groupingBy { it.continent }.eachCount()

    /**
     * The country a point falls in. The shapes are simplified, so a photo taken on a beach
     * or a pier can land just offshore; within [NEAR_DEG] of a coast counts as that country.
     */
    /**
     * The land a place in country [alpha2] is on. The place knows its country; the shapes only
     * choose among that country's own lands, say Okinawa over Honshu, since simplified borders
     * would put Strasbourg in Germany.
     */
    fun landOf(lat: Double, lon: Double, alpha2: String): Country? {
        val here = locate(lat, lon)
        val own = mains.firstOrNull { it.alpha2.equals(alpha2, ignoreCase = true) } ?: return here
        return if (here != null && countryOf(here.id) == own.id) here else own
    }

    fun locate(lat: Double, lon: Double): Country? {
        for (c in countries) if (c.near(lat, lon, 0.0) && c.contains(lat, lon)) return c
        var best: Country? = null
        var bestD = NEAR_DEG
        for (c in countries) {
            if (!c.near(lat, lon, NEAR_DEG)) continue
            val d = c.distanceTo(lat, lon)
            if (d < bestD) { bestD = d; best = c }
        }
        return best
    }

    companion object {
        /** About 20 km. */
        const val NEAR_DEG = 0.18

        fun parse(geojson: String): Atlas {
            val features = JSONObject(geojson).getJSONArray("features")
            val list = ArrayList<Country>(features.length())
            for (i in 0 until features.length()) {
                val f = features.getJSONObject(i)
                val p = f.getJSONObject("properties")
                val g = f.getJSONObject("geometry")
                val coords = g.getJSONArray("coordinates")
                val shapes = when (g.getString("type")) {
                    "Polygon" -> listOf(shape(coords))
                    "MultiPolygon" -> List(coords.length()) { shape(coords.getJSONArray(it)) }
                    else -> emptyList()
                }
                list += Country(
                    id = p.getString("id"),
                    name = p.getString("name"),
                    continent = p.getString("continent"),
                    un = p.getBoolean("un"),
                    alpha2 = p.optString("a2"),
                    labelLon = p.getDouble("lx"),
                    labelLat = p.getDouble("ly"),
                    shapes = shapes,
                    country = p.optString("country").ifEmpty { p.getString("id") },
                )
            }
            return Atlas(list)
        }

        private fun shape(rings: JSONArray): Shape {
            val all = List(rings.length()) { ring(rings.getJSONArray(it)) }
            return Shape(all.first(), all.drop(1))
        }

        private fun ring(points: JSONArray): Ring {
            val n = points.length()
            val xs = DoubleArray(n); val ys = DoubleArray(n)
            for (i in 0 until n) {
                val pt = points.getJSONArray(i)
                xs[i] = pt.getDouble(0); ys[i] = pt.getDouble(1)
            }
            return Ring(xs, ys)
        }
    }
}
