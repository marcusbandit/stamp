package com.marcusrosado.stamp.data

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

        /**
         * Reads countries.geojson as tools/build_countries.py writes it. One pass over the text,
         * numbers straight into each ring's arrays: org.json built a tree of boxed numbers first,
         * about 13 MB for this file, and the first screen waited for it.
         */
        fun parse(geojson: String): Atlas {
            val list = ArrayList<Country>(300)
            val r = GeoJsonReader(geojson)
            var found = false
            r.obj { key ->
                if (key != "features") { r.skip(); return@obj }
                found = true
                r.arr { list += feature(r) }
            }
            require(found) { "no features" }
            return Atlas(list)
        }

        private fun feature(r: GeoJsonReader): Country {
            var props: Map<String, Any?> = emptyMap()
            var type: String? = null
            var coords: Any? = null
            r.obj { key ->
                when (key) {
                    "properties" -> props = r.flatObject()
                    "geometry" -> r.obj { g ->
                        when (g) {
                            "type" -> type = r.string()
                            "coordinates" -> coords = r.coordinates()
                            else -> r.skip()
                        }
                    }
                    else -> r.skip()
                }
            }
            @Suppress("UNCHECKED_CAST")
            val shapes = when (type) {
                "Polygon" -> listOf(shape(coords as List<Ring>))
                "MultiPolygon" -> (coords as List<List<Ring>>).map { shape(it) }
                else -> emptyList()
            }
            fun string(k: String): String = props[k] as? String ?: throw IllegalArgumentException("no $k")
            fun number(k: String): Double = props[k] as? Double ?: throw IllegalArgumentException("no $k")
            fun optString(k: String): String = props[k]?.toString().orEmpty()
            val id = string("id")
            return Country(
                id = id,
                name = string("name"),
                continent = string("continent"),
                un = props["un"] as? Boolean ?: throw IllegalArgumentException("no un"),
                alpha2 = optString("a2"),
                labelLon = number("lx"),
                labelLat = number("ly"),
                shapes = shapes,
                country = optString("country").ifEmpty { id },
            )
        }

        private fun shape(rings: List<Ring>): Shape = Shape(rings.first(), rings.drop(1))
    }
}

/**
 * Just enough of a JSON reader for the country file: objects, arrays, strings, numbers, true,
 * false and null, read in place from the text. Coordinates go straight into primitive arrays.
 */
private class GeoJsonReader(private val s: String) {
    private var i = 0
    // Scratch space for one ring, grown as needed and copied out at its exact size.
    private var xs = DoubleArray(1024)
    private var ys = DoubleArray(1024)

    private fun bad(what: String): Nothing = throw IllegalArgumentException("$what at $i")

    private fun ws() {
        while (i < s.length) {
            val c = s[i]
            if (c == ' ' || c == '\n' || c == '\r' || c == '\t') i++ else return
        }
    }

    private fun peek(): Char {
        ws()
        if (i >= s.length) bad("end of text")
        return s[i]
    }

    private fun expect(c: Char) {
        if (peek() != c) bad("expected '$c'")
        i++
    }

    /** Reads an object, handing each key to [member], which must read its value. */
    fun obj(member: (String) -> Unit) {
        expect('{')
        if (peek() == '}') { i++; return }
        while (true) {
            val key = string()
            expect(':')
            member(key)
            when (peek()) {
                ',' -> i++
                '}' -> { i++; return }
                else -> bad("expected ',' or '}'")
            }
        }
    }

    /** Reads an array, calling [item] once per element, which must read it. */
    fun arr(item: () -> Unit) {
        expect('[')
        if (peek() == ']') { i++; return }
        while (true) {
            item()
            when (peek()) {
                ',' -> i++
                ']' -> { i++; return }
                else -> bad("expected ',' or ']'")
            }
        }
    }

    /** An object of plain values, such as a feature's properties: strings, numbers, booleans, null. */
    fun flatObject(): Map<String, Any?> {
        val out = HashMap<String, Any?>(16)
        obj { key ->
            out[key] = when (val c = peek()) {
                '"' -> string()
                't', 'f' -> bool()
                'n' -> { literal("null"); null }
                '{', '[' -> { skip(); null }
                else -> if (c == '-' || c in '0'..'9') number() else bad("unexpected '$c'")
            }
        }
        return out
    }

    fun string(): String {
        expect('"')
        val start = i
        // Most strings have no escapes and come straight out of the text.
        while (i < s.length) {
            val c = s[i]
            if (c == '"') return s.substring(start, i++)
            if (c == '\\') break
            i++
        }
        val b = StringBuilder().append(s, start, i)
        while (true) {
            if (i >= s.length) bad("unclosed string")
            val c = s[i++]
            when (c) {
                '"' -> return b.toString()
                '\\' -> {
                    if (i >= s.length) bad("unclosed string")
                    when (val e = s[i++]) {
                        '"', '\\', '/' -> b.append(e)
                        'b' -> b.append('\b')
                        'f' -> b.append('\u000C')
                        'n' -> b.append('\n')
                        'r' -> b.append('\r')
                        't' -> b.append('\t')
                        'u' -> {
                            if (i + 4 > s.length) bad("short escape")
                            b.append(s.substring(i, i + 4).toInt(16).toChar())
                            i += 4
                        }
                        else -> bad("bad escape")
                    }
                }
                else -> b.append(c)
            }
        }
    }

    private fun literal(word: String) {
        ws()
        if (!s.startsWith(word, i)) bad("expected $word")
        i += word.length
    }

    private fun bool(): Boolean =
        if (peek() == 't') { literal("true"); true } else { literal("false"); false }

    /**
     * A number. Up to 15 digits with up to 22 after the point is one exact long divided by an
     * exact power of ten, which rounds the same as Double.parseDouble; anything else goes to it.
     */
    fun number(): Double {
        ws()
        val start = i
        val neg = i < s.length && s[i] == '-'
        if (neg) i++
        var mant = 0L
        var digits = 0
        var scale = 0
        while (i < s.length && s[i] in '0'..'9') { mant = mant * 10 + (s[i] - '0'); digits++; i++ }
        if (i < s.length && s[i] == '.') {
            i++
            while (i < s.length && s[i] in '0'..'9') { mant = mant * 10 + (s[i] - '0'); digits++; scale++; i++ }
        }
        val exp = i < s.length && (s[i] == 'e' || s[i] == 'E')
        if (exp || digits > 15 || scale >= POW10.size) {
            if (exp) {
                i++
                if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
                while (i < s.length && s[i] in '0'..'9') i++
            }
            return s.substring(start, i).toDouble()
        }
        if (digits == 0) bad("expected a number")
        val v = if (scale == 0) mant.toDouble() else mant.toDouble() / POW10[scale]
        return if (neg) -v else v
    }

    /** Skips one value of any kind. */
    fun skip() {
        when (val c = peek()) {
            '{' -> obj { skip() }
            '[' -> arr { skip() }
            '"' -> string()
            't', 'f' -> bool()
            'n' -> literal("null")
            else -> if (c == '-' || c in '0'..'9') number() else bad("unexpected '$c'")
        }
    }

    /**
     * A geometry's coordinates as nested lists, with each innermost list of points read as a
     * [Ring]: a list of rings for a Polygon, a list of those for a MultiPolygon.
     */
    fun coordinates(): Any {
        expect('[')
        // An array whose first element is an array of numbers is a ring of points.
        var k = i
        while (k < s.length && s[k].isWhitespace()) k++
        if (k < s.length && s[k] == '[') {
            k++
            while (k < s.length && s[k].isWhitespace()) k++
            if (k < s.length && (s[k] == '-' || s[k] in '0'..'9')) return ring()
        }
        val out = ArrayList<Any>()
        if (peek() == ']') { i++; return out }
        while (true) {
            if (peek() == '[') out += coordinates() else skip()
            when (peek()) {
                ',' -> i++
                ']' -> { i++; return out }
                else -> bad("expected ',' or ']'")
            }
        }
    }

    /** The points of one ring, its opening bracket already read. */
    private fun ring(): Ring {
        var n = 0
        while (true) {
            if (n == xs.size) { xs = xs.copyOf(n * 2); ys = ys.copyOf(n * 2) }
            // A point: longitude and latitude, and any further numbers, such as height, ignored.
            expect('[')
            xs[n] = number()
            expect(',')
            ys[n] = number()
            while (peek() == ',') { i++; number() }
            expect(']')
            n++
            when (peek()) {
                ',' -> i++
                ']' -> { i++; return Ring(xs.copyOf(n), ys.copyOf(n)) }
                else -> bad("expected ',' or ']'")
            }
        }
    }

    private companion object {
        val POW10 = DoubleArray(23).also { p -> p[0] = 1.0; for (k in 1 until p.size) p[k] = p[k - 1] * 10 }
    }
}
