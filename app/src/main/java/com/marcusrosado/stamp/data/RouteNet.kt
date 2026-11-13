package com.marcusrosado.stamp.data

import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * The main railways and big roads of the countries a trip went through, from OpenStreetMap,
 * as a network a leg can follow: a train from one city to the next runs along the rails, a car
 * or a bus along the motorways. Read from the packs tools/build_routes.py writes, and merged
 * across borders where two packs share a junction.
 */
class RouteNet private constructor(
    /** Junctions, in 1e-5 degrees. */
    private val nodeLat: IntArray,
    private val nodeLon: IntArray,
    private val segKind: ByteArray,
    private val segA: IntArray,
    private val segB: IntArray,
    /** The points between a segment's two junctions, lat and lon interleaved, in 1e-5 degrees. */
    private val segPts: Array<IntArray>,
) {
    private val segKm = FloatArray(segA.size) { s -> length(s) }

    // Each junction's segments, as one flat list with an offset per junction.
    private val adjStart: IntArray
    private val adj: IntArray

    init {
        val count = IntArray(nodeLat.size + 1)
        for (s in segA.indices) { count[segA[s] + 1]++; count[segB[s] + 1]++ }
        for (i in 1 until count.size) count[i] += count[i - 1]
        adjStart = count
        adj = IntArray(segA.size * 2)
        val fill = count.copyOf()
        for (s in segA.indices) { adj[fill[segA[s]]++] = s; adj[fill[segB[s]]++] = s }
    }

    val isEmpty: Boolean get() = segA.isEmpty()

    private val cache = object : LinkedHashMap<String, List<Pair<Double, Double>>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<Pair<Double, Double>>>) = size > 400
    }

    /**
     * The line a leg taken by [way] follows from [from] to [to], as latitude and longitude, or
     * null when [way] keeps to no network here, either end is too far from it, or the network
     * does not join them up: then the leg stays a straight line.
     */
    @Synchronized
    fun follow(way: Travel, from: Pair<Double, Double>, to: Pair<Double, Double>): List<Pair<Double, Double>>? {
        val kind = kindOf(way) ?: return null
        val key = "$kind:${from.first},${from.second}:${to.first},${to.second}"
        cache[key]?.let { return it.ifEmpty { null } }
        // A way far longer than the stops are apart is a detour to some distant line, not the
        // line the user took: the leg stays straight.
        // A network too big for the memory left, or anything else gone wrong, leaves the leg
        // straight rather than taking the app down.
        val found = runCatching { route(kind, from, to) }.getOrNull()?.takeIf { line ->
            val crow = Distance.km(from, to)
            var along = 0.0
            for (i in 1 until line.size) along += Distance.km(line[i - 1], line[i])
            along <= crow * 2.2 + 15
        }
        cache[key] = found.orEmpty()
        return found
    }

    private fun route(kind: Byte, from: Pair<Double, Double>, to: Pair<Double, Double>): List<Pair<Double, Double>>? {
        val reach = if (kind == RAIL) 25.0 else 40.0
        val a = nearest(kind, from, reach) ?: return null
        val b = nearest(kind, to, reach) ?: return null
        if (a == b) return null
        val parts = parts(kind)
        if (parts[a] != parts[b]) {
            // Two pieces of network the map data does not join, such as the two ends of a sea
            // tunnel: cross the narrowest gap between them in a straight line, if it is short.
            val (x, y) = gap(kind, parts, parts[a], parts[b], a, b) ?: return null
            val first = path(kind, a, x) ?: return null
            val second = path(kind, y, b) ?: return null
            return listOf(from) + first + second + listOf(to)
        }
        val line = path(kind, a, b) ?: return null
        return listOf(from) + line + listOf(to)
    }

    /** Each junction's piece of connected network for one kind of line, worked out once. */
    private val pieces = HashMap<Byte, IntArray>()

    private fun parts(kind: Byte): IntArray = pieces.getOrPut(kind) {
        val up = IntArray(nodeLat.size) { it }
        fun find(i: Int): Int {
            var r = i
            while (up[r] != r) { up[r] = up[up[r]]; r = up[r] }
            return r
        }
        for (s in segA.indices) {
            if (segKind[s] != kind) continue
            val ra = find(segA[s]); val rb = find(segB[s])
            if (ra != rb) up[ra] = rb
        }
        val (start, to, _) = transfers(kind)
        for (u in nodeLat.indices) for (k in start[u] until start[u + 1]) {
            val ra = find(u); val rb = find(to[k])
            if (ra != rb) up[ra] = rb
        }
        IntArray(nodeLat.size) { find(it) }
    }

    /**
     * Changes between lines the map data does not join, such as a high-speed line and the old
     * line beside it at the same station: junctions under 800 m apart are a short walk between
     * them, a little dearer than riding. As flat lists with an offset per junction.
     */
    private val transferCache = HashMap<Byte, Triple<IntArray, IntArray, FloatArray>>()

    private fun transfers(kind: Byte): Triple<IntArray, IntArray, FloatArray> = transferCache.getOrPut(kind) {
        // Roads join up through their own slip roads; only rail needs changes of line.
        if (kind != RAIL) return@getOrPut Triple(IntArray(nodeLat.size + 1), IntArray(0), FloatArray(0))
        // Only between pieces of track the data leaves apart, and to the nearest junction of
        // each other piece: a station, not every pair of points along two parallel lines.
        val base = lines(kind)
        val cell = 1_000 // 0.01 degrees, about a kilometre
        fun key(y: Int, x: Int) = (y.toLong() shl 32) or (x.toLong() and 0xFFFFFFFFL)
        val grid = HashMap<Long, IntArray>()
        run {
            val lists = HashMap<Long, MutableList<Int>>()
            for (i in nodeLat.indices) if (on(kind, i)) lists.getOrPut(key(nodeLat[i] / cell, nodeLon[i] / cell)) { ArrayList() } += i
            for ((k, v) in lists) grid[k] = v.toIntArray()
        }
        var from = IntArray(1024); var to = IntArray(1024); var cost = FloatArray(1024); var count = 0
        val nearestOf = HashMap<Int, Int>()
        for (i in nodeLat.indices) {
            if (!on(kind, i)) continue
            nearestOf.clear()
            val cy = nodeLat[i] / cell
            val cx = nodeLon[i] / cell
            val wide = lonCells(1, nodeLat[i])
            for (dy in -1..1) for (dx in -wide..wide) {
                val list = grid[key(cy + dy, cx + dx)] ?: continue
                for (j in list) {
                    if (base[j] == base[i]) continue
                    val d = km(nodeLat[i], nodeLon[i], nodeLat[j], nodeLon[j])
                    if (d >= 0.8) continue
                    val had = nearestOf[base[j]]
                    if (had == null || d < km(nodeLat[i], nodeLon[i], nodeLat[had], nodeLon[had])) nearestOf[base[j]] = j
                }
            }
            for (j in nearestOf.values) {
                if (count == from.size) { from = from.copyOf(count * 2); to = to.copyOf(count * 2); cost = cost.copyOf(count * 2) }
                from[count] = i; to[count] = j
                cost[count] = (km(nodeLat[i], nodeLon[i], nodeLat[j], nodeLon[j]) * 2 + 0.5).toFloat()
                count++
            }
        }
        val start = IntArray(nodeLat.size + 1)
        for (k in 0 until count) start[from[k] + 1]++
        for (i in 1 until start.size) start[i] += start[i - 1]
        val outTo = IntArray(count)
        val outCost = FloatArray(count)
        val fill = start.copyOf()
        for (k in 0 until count) {
            val at = fill[from[k]]++
            outTo[at] = to[k]; outCost[at] = cost[k]
        }
        Triple(start, outTo, outCost)
    }

    /** Each junction's piece of track joined by segments alone, before any change of line. */
    private val lineCache = HashMap<Byte, IntArray>()

    private fun lines(kind: Byte): IntArray = lineCache.getOrPut(kind) {
        val up = IntArray(nodeLat.size) { it }
        fun find(i: Int): Int {
            var r = i
            while (up[r] != r) { up[r] = up[up[r]]; r = up[r] }
            return r
        }
        for (s in segA.indices) {
            if (segKind[s] != kind) continue
            val ra = find(segA[s]); val rb = find(segB[s])
            if (ra != rb) up[ra] = rb
        }
        IntArray(nodeLat.size) { find(it) }
    }

    /**
     * Where to cross between two pieces of network: the pair of junctions under 80 km apart, one
     * in each, that keeps the whole way from [a] to [b] shortest, the straight crossing counted
     * three times over so the line keeps to the rails wherever it can.
     */
    private fun gap(kind: Byte, parts: IntArray, pa: Int, pb: Int, a: Int, b: Int): Pair<Int, Int>? {
        val cell = 20_000 // 0.2 degrees, in 1e-5 degrees
        fun key(y: Int, x: Int) = (y.toLong() shl 32) or (x.toLong() and 0xFFFFFFFFL)
        val grid = HashMap<Long, MutableList<Int>>()
        for (i in nodeLat.indices) {
            if (parts[i] == pb && on(kind, i)) grid.getOrPut(key(nodeLat[i] / cell, nodeLon[i] / cell)) { ArrayList() } += i
        }
        var best: Pair<Int, Int>? = null
        var bestCost = Double.MAX_VALUE
        val span = 4 // cells either way: about 80 km of latitude
        for (i in nodeLat.indices) {
            if (parts[i] != pa || !on(kind, i)) continue
            val cy = nodeLat[i] / cell
            val cx = nodeLon[i] / cell
            val wide = lonCells(span, nodeLat[i])
            for (dy in -span..span) for (dx in -wide..wide) {
                val list = grid[key(cy + dy, cx + dx)] ?: continue
                for (j in list) {
                    val d = km(nodeLat[i], nodeLon[i], nodeLat[j], nodeLon[j])
                    if (d > 80) continue
                    val cost = km(nodeLat[a], nodeLon[a], nodeLat[i], nodeLon[i]) + 3 * d +
                        km(nodeLat[j], nodeLon[j], nodeLat[b], nodeLon[b])
                    if (cost < bestCost) { bestCost = cost; best = i to j }
                }
            }
        }
        return best
    }

    private fun on(kind: Byte, i: Int): Boolean {
        for (k in adjStart[i] until adjStart[i + 1]) if (segKind[adj[k]] == kind) return true
        return false
    }

    private var dist = FloatArray(0)
    private var via = IntArray(0)
    private val touched = ArrayList<Int>()

    /** Grid cells of longitude that cover as far as [cells] cells of latitude do at [lat]. */
    private fun lonCells(cells: Int, lat: Int): Int =
        kotlin.math.ceil(cells / cos(Math.toRadians(lat / 1e5)).coerceAtLeast(0.1)).toInt()

    /** The line along the network from junction [a] to junction [b], which share a piece. */
    private fun path(kind: Byte, a: Int, b: Int): List<Pair<Double, Double>>? {
        if (a == b) return listOf(point(nodeLat[a], nodeLon[a]))
        val straight = km(nodeLat[a], nodeLon[a], nodeLat[b], nodeLon[b])
        // A* over the junctions, by length along the line.
        val n = nodeLat.size
        // Kept between searches and reset only where the last one wrote.
        if (dist.size != n) { dist = FloatArray(n) { Float.MAX_VALUE }; via = IntArray(n) { -1 } }
        for (i in touched) { dist[i] = Float.MAX_VALUE; via[i] = -1 }
        touched.clear()
        val dist = dist
        val via = via
        val heap = Heap(n)
        val (tStart, tTo, tCost) = transfers(kind)
        // Through a change of line there is no segment to walk back along: -2 - the junction.
        dist[a] = 0f
        touched += a
        heap.push(a, h(a, b))
        while (heap.size > 0) {
            val u = heap.pop()
            if (u == b) break
            val du = dist[u]
            // A junction already reached a shorter way was pushed again; skip the old entry.
            if (heap.lastKey > du + h(u, b) + 1e-3f) continue
            // Far longer than the way as the crow flies: not a sensible route, give up.
            if (du > straight * 3 + 50) return null
            for (k in adjStart[u] until adjStart[u + 1]) {
                val s = adj[k]
                if (segKind[s] != kind) continue
                val v = if (segA[s] == u) segB[s] else segA[s]
                val dv = du + segKm[s]
                if (dv < dist[v]) {
                    if (dist[v] == Float.MAX_VALUE) touched += v
                    dist[v] = dv
                    via[v] = s
                    heap.push(v, dv + h(v, b))
                }
            }
            for (k in tStart[u] until tStart[u + 1]) {
                val v = tTo[k]
                val dv = du + tCost[k]
                if (dv < dist[v]) {
                    if (dist[v] == Float.MAX_VALUE) touched += v
                    dist[v] = dv
                    via[v] = -2 - u
                    heap.push(v, dv + h(v, b))
                }
            }
        }
        if (via[b] == -1) return null
        // Walk back from the end, laying each segment's points the right way round.
        val out = ArrayList<Pair<Double, Double>>()
        var v = b
        out += point(nodeLat[v], nodeLon[v])
        while (v != a) {
            val s = via[v]
            if (s <= -2) {
                v = -2 - s
                out += point(nodeLat[v], nodeLon[v])
                continue
            }
            val pts = segPts[s]
            val forward = segB[s] == v
            val m = pts.size / 2
            for (i in 0 until m) {
                val j = if (forward) m - 1 - i else i
                out += point(pts[2 * j], pts[2 * j + 1])
            }
            v = if (forward) segA[s] else segB[s]
            out += point(nodeLat[v], nodeLon[v])
        }
        out.reverse()
        return out
    }

    /**
     * The junction on [kind] of line nearest [p] within [reachKm], on a real stretch of network
     * where there is one: a scrap of track in a station yard is no place to start a journey.
     */
    private fun nearest(kind: Byte, p: Pair<Double, Double>, reachKm: Double): Int? {
        val lat = (p.first * 1e5).toInt()
        val lon = (p.second * 1e5).toInt()
        val parts = parts(kind)
        val size = sizes(kind)
        var best = -1
        var bestKm = reachKm
        var small = -1
        var smallKm = reachKm
        for (i in nodeLat.indices) {
            // A cheap box test before the distance, then only junctions on this kind of line.
            if (kotlin.math.abs(nodeLat[i] - lat) > 50_000) continue
            if (!on(kind, i)) continue
            val d = km(lat, lon, nodeLat[i], nodeLon[i])
            if (size[parts[i]] >= 40) { if (d < bestKm) { bestKm = d; best = i } }
            else if (d < smallKm) { smallKm = d; small = i }
        }
        return best.takeIf { it >= 0 } ?: small.takeIf { it >= 0 }
    }

    /** How many junctions each piece of network has, by its piece id. */
    private val sizeCache = HashMap<Byte, IntArray>()

    private fun sizes(kind: Byte): IntArray = sizeCache.getOrPut(kind) {
        val out = IntArray(nodeLat.size)
        for (r in parts(kind)) out[r]++
        out
    }

    private fun h(u: Int, b: Int) = km(nodeLat[u], nodeLon[u], nodeLat[b], nodeLon[b]).toFloat()

    private fun length(s: Int): Float {
        var la = nodeLat[segA[s]]
        var lo = nodeLon[segA[s]]
        var sum = 0.0
        val pts = segPts[s]
        for (i in 0 until pts.size / 2) {
            sum += km(la, lo, pts[2 * i], pts[2 * i + 1]); la = pts[2 * i]; lo = pts[2 * i + 1]
        }
        sum += km(la, lo, nodeLat[segB[s]], nodeLon[segB[s]])
        return sum.toFloat()
    }

    /** A small binary heap of junctions by priority; a junction can sit in it more than once. */
    private class Heap(cap: Int) {
        private var ids = IntArray(maxOf(16, cap / 8))
        private var keys = FloatArray(ids.size)
        var size = 0

        /** The priority of the junction [pop] returned last. */
        var lastKey = 0f

        fun push(id: Int, key: Float) {
            if (size == ids.size) { ids = ids.copyOf(size * 2); keys = keys.copyOf(size * 2) }
            var i = size++
            while (i > 0) {
                val p = (i - 1) / 2
                if (keys[p] <= key) break
                ids[i] = ids[p]; keys[i] = keys[p]; i = p
            }
            ids[i] = id; keys[i] = key
        }

        fun pop(): Int {
            val top = ids[0]
            lastKey = keys[0]
            val lastId = ids[--size]
            val lastKey = keys[size]
            var i = 0
            while (true) {
                var c = 2 * i + 1
                if (c >= size) break
                if (c + 1 < size && keys[c + 1] < keys[c]) c++
                if (keys[c] >= lastKey) break
                ids[i] = ids[c]; keys[i] = keys[c]; i = c
            }
            ids[i] = lastId; keys[i] = lastKey
            return top
        }
    }

    companion object {
        const val RAIL: Byte = 0
        const val ROAD: Byte = 1

        /** The network a way of travel keeps to, if any. */
        fun kindOf(way: Travel): Byte? = when (way) {
            Travel.Train, Travel.Transit -> RAIL
            Travel.Car, Travel.Bus -> ROAD
            else -> null
        }

        val EMPTY = RouteNet(IntArray(0), IntArray(0), ByteArray(0), IntArray(0), IntArray(0), emptyArray())

        private fun point(lat: Int, lon: Int) = lat / 1e5 to lon / 1e5

        /**
         * [Distance.km] for junctions in whole 1e-5 degrees, kept apart so the searches over
         * hundreds of thousands of junctions make no pairs. The short way round the date line.
         */
        private fun km(la1: Int, lo1: Int, la2: Int, lo2: Int): Double {
            val k = cos(Math.toRadians((la1 + la2) / 2e5))
            val dLon = Math.floorMod(lo2 - lo1 + 18_000_000, 36_000_000) - 18_000_000
            val dx = dLon / 1e5 * k * 111.0
            val dy = (la2 - la1) / 1e5 * 111.0
            return sqrt(dx * dx + dy * dy)
        }

        /** Reads one country's pack, gzipped as tools/build_routes.py writes it. */
        fun read(gz: ByteArray): RouteNet {
            val b = GZIPInputStream(ByteArrayInputStream(gz)).readBytes()
            var at = 0
            fun varint(): Long {
                var shift = 0
                var r = 0L
                while (true) {
                    val x = b[at++].toInt() and 0xFF
                    r = r or ((x and 0x7F).toLong() shl shift)
                    if (x and 0x80 == 0) return r
                    shift += 7
                }
            }
            fun zz(): Int { val v = varint(); return ((v ushr 1) xor -(v and 1)).toInt() }
            require(b.size >= 4 && String(b, 0, 4) == "SRT1") { "not a route pack" }
            at = 4
            val n = varint().toInt()
            val lat = IntArray(n)
            val lon = IntArray(n)
            var y = 0; var x = 0
            for (i in 0 until n) { y += zz(); x += zz(); lat[i] = y; lon[i] = x }
            val m = varint().toInt()
            val kind = ByteArray(m)
            val sa = IntArray(m)
            val sb = IntArray(m)
            val pts = arrayOfNulls<IntArray>(m)
            for (s in 0 until m) {
                kind[s] = b[at++]
                sa[s] = varint().toInt(); sb[s] = varint().toInt()
                val c = varint().toInt()
                val arr = IntArray(c * 2)
                var py = lat[sa[s]]; var px = lon[sa[s]]
                for (i in 0 until c) { py += zz(); px += zz(); arr[2 * i] = py; arr[2 * i + 1] = px }
                pts[s] = arr
            }
            @Suppress("UNCHECKED_CAST")
            return RouteNet(lat, lon, kind, sa, sb, pts as Array<IntArray>)
        }

        /** One network from several countries' packs, joined where they share a junction. */
        fun merge(nets: List<RouteNet>): RouteNet {
            if (nets.isEmpty()) return EMPTY
            if (nets.size == 1) return nets[0]
            val index = HashMap<Long, Int>()
            val lat = ArrayList<Int>(); val lon = ArrayList<Int>()
            val kind = ArrayList<Byte>(); val sa = ArrayList<Int>(); val sb = ArrayList<Int>()
            val pts = ArrayList<IntArray>()
            for (net in nets) {
                val remap = IntArray(net.nodeLat.size) { i ->
                    val key = (net.nodeLat[i].toLong() shl 32) or (net.nodeLon[i].toLong() and 0xFFFFFFFFL)
                    index.getOrPut(key) { lat += net.nodeLat[i]; lon += net.nodeLon[i]; lat.size - 1 }
                }
                for (s in net.segA.indices) {
                    kind += net.segKind[s]; sa += remap[net.segA[s]]; sb += remap[net.segB[s]]; pts += net.segPts[s]
                }
            }
            return RouteNet(lat.toIntArray(), lon.toIntArray(), kind.toByteArray(), sa.toIntArray(), sb.toIntArray(), pts.toTypedArray())
        }
    }
}
