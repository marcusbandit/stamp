package com.marcusrosado.stamp.data

import java.io.BufferedReader
import java.text.Normalizer
import java.util.Locale

/** A city from GeoNames: every place with at least 1,000 people. */
class City(
    /** The GeoNames id, stable across GeoNames releases. */
    val id: String,
    val name: String,
    /** Plain ASCII spelling, for search without accents. */
    val ascii: String,
    /** ISO alpha-2 of the country it is in. */
    val country: String,
    /** First-level region code inside the country, as GeoNames numbers them. */
    val admin1: String,
    val lat: Double,
    val lon: Double,
    val population: Int,
) {
    /** [name] folded for search, worked out once when the list loads. */
    val key: String = Places.fold(name)

    /**
     * [ascii] in lower case, for search, worked out once instead of on every keystroke for
     * every city. Most are the same text as [key] and share it.
     */
    internal val asciiKey: String = ascii.lowercase(Locale.ROOT).let { if (it == key) key else it }
}

/** A first-level region: a US state, a Japanese prefecture, a Danish region. */
class Region(val country: String, val code: String, val name: String) {
    /** "JP.15", unique across countries. */
    val id: String = idOf(country, code)

    /** [name] folded for search and sorting. */
    val key: String = Places.fold(name)

    companion object {
        fun idOf(country: String, admin1: String) = "$country.$admin1"
    }
}

/**
 * The bundled cities and region names, read once from assets/cities.tsv and regions.tsv
 * (built by tools/build_places.py). Lookups are by country, since every question the app
 * asks is about the cities of one country.
 */
class Places private constructor(
    private val byCountry: Map<String, List<City>>,
    regionList: List<Region>,
) {
    private val regions: Map<String, Region> = regionList.associateBy { it.id }
    private val regionsByCountry: Map<String, List<Region>> =
        regionList.groupBy { it.country }.mapValues { (_, list) -> list.sortedBy { it.key } }

    /** The regions of a country, by name. */
    fun regionsIn(alpha2: String): List<Region> = regionsByCountry[alpha2.uppercase()].orEmpty()

    /** A region's name from its id, such as "Ishikawa" for "JP.15". */
    fun regionName(id: String): String? = regions[id]?.name

    /** Sort key for a region id; unknown ids sort by the id itself. */
    fun regionKey(id: String): String = regions[id]?.key ?: id

    /**
     * Regions the user has been to in one country: the ones added by hand, plus the region
     * of every city on a visit. Values say which city put a region there, or null for a hand
     * added one. A region both added by hand and reached by a city keeps the city, since the
     * city keeps counting it whatever happens to the hand-added entry.
     */
    fun regionsVisited(v: Visit): Map<String, City?> {
        val out = LinkedHashMap<String, City?>()
        for (cid in v.cities) {
            val c = byId[cid] ?: continue
            regionIdOf(c)?.let { out.putIfAbsent(it, c) }
        }
        for (r in v.regions) out.putIfAbsent(r, null)
        return out
    }

    /** The region id a city is in, such as "JP.15", or null when GeoNames has none. */
    fun regionIdOf(city: City): String? = Region.idOf(city.country, city.admin1).takeIf { it in regions }

    val byId: Map<String, City> = byCountry.values.flatten().associateBy { it.id }

    /** Cities in a country, biggest first. [alpha2] is the country's ISO alpha-2. */
    fun citiesIn(alpha2: String): List<City> = byCountry[alpha2.uppercase()].orEmpty()

    /** The region a city is in, such as "Ishikawa" for Kanazawa, or null when unknown. */
    fun regionOf(city: City): String? = regionIdOf(city)?.let { regions[it]?.name }

    /**
     * Cities in a country matching [query], ignoring case and accents. Names starting with
     * the query come before names that only contain it; within each, bigger cities first.
     * [check] is called every few thousand cities, so a search no longer wanted can throw out of
     * the loop instead of finishing it.
     */
    fun search(alpha2: String, query: String, check: () -> Unit = {}): List<City> {
        val all = citiesIn(alpha2)
        val q = fold(query.trim())
        if (q.isEmpty()) return all
        val starts = ArrayList<City>()
        val contains = ArrayList<City>()
        var n = 0
        for (c in all) {
            if (++n and CHECK_EVERY == 0) check()
            val n = c.key
            val a = c.asciiKey
            when {
                n.startsWith(q) || a.startsWith(q) -> starts += c
                n.contains(q) || a.contains(q) -> contains += c
            }
        }
        return starts + contains
    }

    // Sorted once, the first time the world is searched, then kept: 162,000 cities take a while.
    private val all: List<City> by lazy { byCountry.values.flatten().sortedByDescending { it.population } }

    /**
     * Sorts the world's cities for [searchAll] ahead of the first search, so the first letters
     * typed do not wait on it. Slow: call it off the main thread.
     */
    fun prepareWorldSearch() { all }

    /**
     * Cities anywhere in the world matching [query], for a trip that crosses a border. Names that
     * start with the query come first, bigger cities first within each; at most [limit].
     * [check] is called every few thousand cities, as in [search].
     */
    fun searchAll(query: String, limit: Int = 60, check: () -> Unit = {}): List<City> {
        val q = fold(query.trim())
        if (q.length < 2) return emptyList()
        val starts = ArrayList<City>()
        val contains = ArrayList<City>()
        var n = 0
        for (c in all) {
            if (++n and CHECK_EVERY == 0) check()
            val a = c.asciiKey
            when {
                c.key.startsWith(q) || a.startsWith(q) -> if (starts.size < limit) starts += c
                contains.size < limit && (c.key.contains(q) || a.contains(q)) -> contains += c
            }
            if (starts.size >= limit) break
        }
        return (starts + contains).take(limit)
    }

    companion object {
        fun read(cities: BufferedReader, regions: BufferedReader): Places {
            // 160,000 cities share a few thousand country and region codes, and most ASCII
            // names equal the real name, so share those strings instead of keeping copies.
            val codes = HashMap<String, String>()
            val list = ArrayList<City>(170_000)
            // Fields are cut out by hand: split() made a list and eight strings per line, for
            // 160,000 lines, where only the names and the id are kept.
            val tabs = IntArray(8)
            while (true) {
                val line = cities.readLine() ?: break
                var n = 0
                var at = line.indexOf('\t')
                while (at >= 0 && n < tabs.size) { tabs[n++] = at; at = line.indexOf('\t', at + 1) }
                if (n < 7) continue
                val found = n
                fun from(k: Int) = if (k == 0) 0 else tabs[k - 1] + 1
                fun to(k: Int) = if (k < found) tabs[k] else line.length
                fun field(k: Int) = line.substring(from(k), to(k))
                val lat = decimal(line, from(5), to(5)) ?: continue
                val lon = decimal(line, from(6), to(6)) ?: continue
                val name = field(1)
                val asciiLen = to(2) - from(2)
                val ascii = if (asciiLen == name.length && line.regionMatches(from(2), name, 0, asciiLen)) name else field(2)
                val country = field(3)
                val admin1 = field(4)
                list += City(
                    field(0), name, ascii, codes.getOrPut(country) { country }, codes.getOrPut(admin1) { admin1 },
                    lat, lon, field(7).toIntOrNull() ?: 0,
                )
            }
            val names = regions.lineSequence().mapNotNull { line ->
                val p = line.split('\t')
                if (p.size < 3) null else Region(p[0], p[1], p[2])
            }.toList()
            return Places(list.groupBy { it.country }, names)
        }

        /**
         * A plain decimal such as "42.5078" read in place, without cutting out a string. Up to 15
         * digits is one exact long over an exact power of ten, which rounds the same as
         * toDouble(); anything else goes to toDoubleOrNull().
         */
        internal fun decimal(s: String, from: Int, to: Int): Double? {
            var i = from
            val neg = i < to && s[i] == '-'
            if (neg) i++
            var mant = 0L
            var digits = 0
            var scale = 0
            var point = false
            while (i < to) {
                val c = s[i++]
                when {
                    c in '0'..'9' -> { mant = mant * 10 + (c - '0'); digits++; if (point) scale++ }
                    c == '.' && !point -> point = true
                    else -> return s.substring(from, to).toDoubleOrNull()
                }
                if (digits > 15) return s.substring(from, to).toDoubleOrNull()
            }
            if (digits == 0) return s.substring(from, to).toDoubleOrNull()
            val v = mant.toDouble() / POW10[scale]
            return if (neg) -v else v
        }

        /** One less than how many cities a search goes through between two calls of its check. */
        private const val CHECK_EVERY = 4095

        private val POW10 = DoubleArray(16).also { p -> p[0] = 1.0; for (k in 1 until p.size) p[k] = p[k - 1] * 10 }

        private val marks = Regex("\\p{M}+")

        /** Lower case with accents removed, so "malmo" finds Malmö. */
        fun fold(s: String): String {
            // Plain ASCII has no accents to take off: most names skip the normalizing.
            for (c in s) if (c >= '\u0080') return marks.replace(Normalizer.normalize(s, Normalizer.Form.NFD), "").lowercase(Locale.ROOT)
            return s.lowercase(Locale.ROOT)
        }
    }
}
