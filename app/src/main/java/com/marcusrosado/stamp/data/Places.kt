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
     */
    fun search(alpha2: String, query: String): List<City> {
        val all = citiesIn(alpha2)
        val q = fold(query.trim())
        if (q.isEmpty()) return all
        val starts = ArrayList<City>()
        val contains = ArrayList<City>()
        for (c in all) {
            val n = c.key
            val a = c.ascii.lowercase(Locale.ROOT)
            when {
                n.startsWith(q) || a.startsWith(q) -> starts += c
                n.contains(q) || a.contains(q) -> contains += c
            }
        }
        return starts + contains
    }

    private val all: List<City> by lazy { byCountry.values.flatten().sortedByDescending { it.population } }

    /**
     * Cities anywhere in the world matching [query], for a trip that crosses a border. Names that
     * start with the query come first, bigger cities first within each; at most [limit].
     */
    fun searchAll(query: String, limit: Int = 60): List<City> {
        val q = fold(query.trim())
        if (q.length < 2) return emptyList()
        val starts = ArrayList<City>()
        val contains = ArrayList<City>()
        for (c in all) {
            val a = c.ascii.lowercase(Locale.ROOT)
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
            val list = cities.lineSequence().mapNotNull { line ->
                val p = line.split('\t')
                if (p.size < 8) return@mapNotNull null
                val lat = p[5].toDoubleOrNull() ?: return@mapNotNull null
                val lon = p[6].toDoubleOrNull() ?: return@mapNotNull null
                val ascii = if (p[2] == p[1]) p[1] else p[2]
                City(
                    p[0], p[1], ascii, codes.getOrPut(p[3]) { p[3] }, codes.getOrPut(p[4]) { p[4] },
                    lat, lon, p[7].toIntOrNull() ?: 0,
                )
            }.toList()
            val names = regions.lineSequence().mapNotNull { line ->
                val p = line.split('\t')
                if (p.size < 3) null else Region(p[0], p[1], p[2])
            }.toList()
            return Places(list.groupBy { it.country }, names)
        }

        private val marks = Regex("\\p{M}+")

        /** Lower case with accents removed, so "malmo" finds Malmö. */
        fun fold(s: String): String =
            marks.replace(Normalizer.normalize(s, Normalizer.Form.NFD), "").lowercase(Locale.ROOT)
    }
}
