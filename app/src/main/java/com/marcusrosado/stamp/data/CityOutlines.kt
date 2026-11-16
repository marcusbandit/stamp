package com.marcusrosado.stamp.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * City outlines the bundled set lacks, fetched from OpenStreetMap when a city is tapped. One the
 * user logged (been to, lived in or wants to go to) is kept on the phone, outside the backup,
 * since it is public map data and can be fetched again; any other stays in memory until the app
 * closes. Only the city's name, country code and the box around its centre are sent.
 */
class CityOutlines(private val dir: File, private val agent: String, private val scope: CoroutineScope) {
    private val lock = Mutex()
    private val session = HashMap<String, List<Shape>>()
    // Cities OpenStreetMap answered with no outline for: asked for once a launch. A request that
    // failed, offline or refused, is not remembered and is tried again on the next tap.
    private val none = HashSet<String>()
    // A fetch under way per city, shared by every tap on it, and finished even when the tap that
    // started it moved on to another city.
    private val running = HashMap<String, Deferred<List<Shape>?>>()

    private val _kept = MutableStateFlow<Map<String, List<Shape>>>(emptyMap())

    /** Every outline kept on the phone, by city id; read once at launch. */
    val kept: StateFlow<Map<String, List<Shape>>> = _kept.asStateFlow()

    /** Reads the kept outlines and clears what a write cut short. Called once, off the main thread. */
    suspend fun start() = lock.withLock {
        val out = HashMap<String, List<Shape>>()
        dir.listFiles()?.forEach { f ->
            if (!f.name.endsWith(".json")) { f.delete(); return@forEach }
            runCatching { parseGeometry(JSONObject(f.readText())) }.getOrNull()?.takeIf { it.isNotEmpty() }
                ?.let { out[f.name.removeSuffix(".json")] = it } ?: f.delete()
        }
        // Anything kept while the files were read stays.
        _kept.value = out + _kept.value
    }

    /** The outline already at hand, kept or fetched this session; null when it is not. */
    fun known(cityId: String): List<Shape>? = _kept.value[cityId] ?: synchronized(session) { session[cityId] }

    /**
     * The outline of [city], fetched when it is not at hand. [logged] keeps it on the phone. Null
     * when OpenStreetMap has none that holds the city's centre, or the fetch failed.
     */
    suspend fun fetch(city: City, logged: Boolean): List<Shape>? {
        val job = lock.withLock {
            known(city.id)?.let { if (logged) keep(city.id, it); return it }
            if (city.id in none) return null
            running.getOrPut(city.id) {
                scope.async(Dispatchers.IO) {
                    var failed = false
                    val shapes = try { ask(city) } catch (e: java.io.IOException) { failed = true; null }
                    lock.withLock {
                        running.remove(city.id)
                        when {
                            !shapes.isNullOrEmpty() -> synchronized(session) { session[city.id] = shapes }
                            !failed -> none += city.id
                        }
                    }
                    shapes
                }
            }
        }
        val shapes = job.await()
        if (logged && !shapes.isNullOrEmpty()) lock.withLock { keep(city.id, shapes) }
        return shapes
    }

    /**
     * Keeps on the phone every outline fetched this session for a city in [logged], and lets go
     * of the kept ones no longer logged: they stay in memory until the app closes.
     */
    suspend fun keepLogged(logged: Set<String>) = lock.withLock {
        val fetched = synchronized(session) { session.toMap() }
        for ((id, shapes) in fetched) if (id in logged && id !in _kept.value) keep(id, shapes)
        val gone = _kept.value.keys - logged
        if (gone.isEmpty()) return@withLock
        synchronized(session) { for (id in gone) _kept.value[id]?.let { session[id] = it } }
        withContext(Dispatchers.IO) { for (id in gone) File(dir, "$id.json").delete() }
        _kept.value = _kept.value - gone
    }

    private suspend fun keep(id: String, shapes: List<Shape>) {
        if (id in _kept.value) return
        withContext(Dispatchers.IO) {
            runCatching {
                dir.mkdirs()
                val part = File(dir, "$id.part")
                part.writeText(geometryJson(shapes).toString())
                part.renameTo(File(dir, "$id.json"))
            }
        }
        _kept.value = _kept.value + (id to shapes)
    }

    private fun ask(city: City): List<Shape>? {
        // A box around the centre keeps the search to the right town when the name is common.
        val box = listOf(city.lon - 0.6, city.lat + 0.6, city.lon + 0.6, city.lat - 0.6).joinToString(",")
        val q = "city=" + enc(city.name) + "&countrycodes=" + city.country.lowercase() + "&viewbox=$box&bounded=1" +
            "&format=geojson&polygon_geojson=1&polygon_threshold=$SIMPLIFY&limit=8"
        pick(JSONObject(get("$BASE/search?$q")), city.lat, city.lon)?.let { return it }
        // Many towns are only a point in OpenStreetMap's search. Their outline is then the
        // boundary that holds the centre and carries the town's name, such as "Sampleby Kommune".
        val find = "[out:json][timeout:25];is_in(${city.lat},${city.lon})->.a;" +
            "rel(pivot.a)[\"boundary\"=\"administrative\"][\"admin_level\"~\"^[5-9]$\"];out tags;"
        // The second server only when the first could not be reached.
        val found = OVERPASS.indices.firstNotNullOf { i ->
            try { get(OVERPASS[i] + "?data=" + enc(find)) } catch (e: java.io.IOException) { if (i == OVERPASS.lastIndex) throw e else null }
        }
        val rel = boundaryFor(JSONObject(found), listOf(city.name, city.ascii)) ?: return null
        val shape = get("$BASE/lookup?osm_ids=R$rel&format=geojson&polygon_geojson=1&polygon_threshold=$SIMPLIFY")
        return pick(JSONObject(shape), city.lat, city.lon)
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    // OpenStreetMap's servers ask for at most one request a second.
    private var last = 0L
    private fun pace() {
        val wait = last + 1_100 - System.currentTimeMillis()
        if (wait > 0) Thread.sleep(wait)
        last = System.currentTimeMillis()
    }

    /** The body of a good answer. A failed request, offline or refused, throws, so it is tried again later. */
    private fun get(url: String): String {
        pace()
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        // OpenStreetMap's search asks every app to say who it is.
        c.setRequestProperty("User-Agent", agent)
        return try {
            if (c.responseCode !in 200..299) throw java.io.IOException("HTTP ${c.responseCode}")
            c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    companion object {
        const val BASE = "https://nominatim.openstreetmap.org"
        private val OVERPASS = listOf("https://overpass-api.de/api/interpreter", "https://overpass.private.coffee/api/interpreter")
        private const val SIMPLIFY = "0.0003"

        /**
         * The boundary relation, of those that hold a city's centre, that is the city: its name
         * the same as one of [names], else holding one at municipal level; then the smallest unit.
         * Null when none carries the name, so a town never gets its county.
         */
        fun boundaryFor(found: JSONObject, names: List<String>): Long? {
            fun norm(s: String?) = java.text.Normalizer.normalize(s.orEmpty(), java.text.Normalizer.Form.NFD)
                .replace(Regex("\\p{M}+"), "").lowercase().trim()
            val want = names.map(::norm).filter { it.isNotEmpty() }.toSet()
            val elements = found.optJSONArray("elements") ?: return null
            return (0 until elements.length()).mapNotNull { i ->
                val e = elements.optJSONObject(i) ?: return@mapNotNull null
                val t = e.optJSONObject("tags") ?: return@mapNotNull null
                val have = listOf("name", "name:en", "int_name", "official_name").map { norm(t.optString(it)) }.filter { it.isNotEmpty() }.toSet()
                val exact = (want intersect have).isNotEmpty()
                val loose = want.any { n -> have.any { h -> h.split(' ', '-').contains(n) || h.startsWith("$n ") } }
                val level = t.optString("admin_level").toIntOrNull() ?: 0
                // A name that only holds the town's is a municipality at most, never a county
                // or a region named after its main town.
                if (!exact && !(loose && level >= 7)) return@mapNotNull null
                Triple(exact, level, e.optLong("id"))
            }.maxWithOrNull(compareBy({ it.first }, { it.second }))?.third
        }

        /**
         * Of the places a search found, the outline of the smallest one that holds the point,
         * so a town inside its county gets the town.
         */
        fun pick(found: JSONObject, lat: Double, lon: Double): List<Shape>? {
            val features = found.optJSONArray("features") ?: return null
            return (0 until features.length()).mapNotNull { i ->
                val g = features.optJSONObject(i)?.optJSONObject("geometry") ?: return@mapNotNull null
                parseGeometry(g).takeIf { s -> s.isNotEmpty() && s.any { it.holds(lat, lon) } && townSized(s) }
            }.minByOrNull { s -> s.sumOf { area(it.outer) } }
        }

        /** A GeoJSON Polygon or MultiPolygon as shapes; anything else is none. */
        fun parseGeometry(g: JSONObject): List<Shape> {
            val coords = g.optJSONArray("coordinates") ?: return emptyList()
            val polys = when (g.optString("type")) {
                "Polygon" -> listOf(coords)
                "MultiPolygon" -> (0 until coords.length()).mapNotNull { coords.optJSONArray(it) }
                else -> return emptyList()
            }
            return polys.mapNotNull { p ->
                val rings = (0 until p.length()).mapNotNull { r -> p.optJSONArray(r)?.let(::ring) }.filter { it.xs.size >= 4 }
                rings.firstOrNull()?.let { Shape(it, rings.drop(1)) }
            }
        }

        fun geometryJson(shapes: List<Shape>): JSONObject = JSONObject().put("type", "MultiPolygon").put(
            "coordinates",
            JSONArray(shapes.map { s -> JSONArray((listOf(s.outer) + s.holes).map { r -> JSONArray(r.xs.indices.map { i -> JSONArray(listOf(r.xs[i], r.ys[i])) }) }) }),
        )

        private fun ring(a: JSONArray): Ring {
            val xs = DoubleArray(a.length())
            val ys = DoubleArray(a.length())
            for (i in 0 until a.length()) {
                val p = a.getJSONArray(i)
                xs[i] = p.getDouble(0)
                ys[i] = p.getDouble(1)
            }
            return Ring(xs, ys)
        }

        /** No town spans more than about two degrees; anything wider is a county or a region. */
        private fun townSized(shapes: List<Shape>): Boolean {
            val xs = shapes.flatMap { it.outer.xs.asList() }
            val ys = shapes.flatMap { it.outer.ys.asList() }
            return xs.max() - xs.min() <= 2.0 && ys.max() - ys.min() <= 2.0
        }

        private fun Shape.holds(lat: Double, lon: Double) = inside(outer, lat, lon) && holes.none { inside(it, lat, lon) }

        private fun inside(r: Ring, lat: Double, lon: Double): Boolean {
            var odd = false
            var j = r.xs.size - 1
            for (i in r.xs.indices) {
                if ((r.ys[i] > lat) != (r.ys[j] > lat) &&
                    lon < (r.xs[j] - r.xs[i]) * (lat - r.ys[i]) / (r.ys[j] - r.ys[i]) + r.xs[i]
                ) odd = !odd
                j = i
            }
            return odd
        }

        private fun area(r: Ring): Double {
            var a = 0.0
            var j = r.xs.size - 1
            for (i in r.xs.indices) { a += (r.xs[j] + r.xs[i]) * (r.ys[j] - r.ys[i]); j = i }
            return kotlin.math.abs(a / 2)
        }
    }
}
