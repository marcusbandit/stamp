package com.marcusrosado.stamp.data

import java.io.InputStream

/**
 * The official outlines of big cities, from OpenStreetMap, keyed by GeoNames id. Built by
 * tools/build_cityareas.py into assets/cityareas.bin. A city without an outline stays a dot.
 *
 * The file is "CAR1", then per city: the GeoNames id, the number of polygons, and per polygon
 * the number of rings, each ring as a point count and zigzag varint deltas of longitude and
 * latitude in units of 1e-4 degrees. Rings are open: the closing point is implied.
 *
 * Only the file's bytes and where each city starts in them are kept. A city's outline is
 * decoded when asked for: decoding all 1.4 million points up front held about 22 MB of
 * doubles for the whole life of the app, for the handful of cities ever drawn.
 */
class CityAreas private constructor(
    private val bytes: ByteArray,
    /** Each city as its id in the high 32 bits and its offset in the low ones, sorted by id. */
    private val index: LongArray,
) {
    private val decoded = object : LinkedHashMap<Long, List<Shape>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, List<Shape>>?) = size > KEEP
    }

    /** The outline of a city, or null when it has none. */
    fun of(cityId: String): List<Shape>? {
        val id = cityId.toLongOrNull()?.takeIf { it in 0..Int.MAX_VALUE && it.toString() == cityId } ?: return null
        val at = find(id)
        if (at < 0) return null
        synchronized(decoded) { decoded[id]?.let { return it } }
        val shapes = decode(at)
        synchronized(decoded) { decoded[id] = shapes }
        return shapes
    }

    /** The offset of city [id] in the file, or -1. */
    private fun find(id: Long): Int {
        var lo = 0
        var hi = index.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val k = index[mid] ushr 32
            when {
                k < id -> lo = mid + 1
                k > id -> hi = mid - 1
                else -> return (index[mid] and 0xFFFFFFFFL).toInt()
            }
        }
        return -1
    }

    private fun decode(from: Int): List<Shape> {
        val r = Varints(bytes, from)
        r.next() // the id
        val polys = r.next().toInt()
        val shapes = ArrayList<Shape>(polys)
        repeat(polys) {
            val rings = r.next().toInt()
            val list = ArrayList<Ring>(rings)
            repeat(rings) {
                val n = r.next().toInt()
                // One extra slot closes the ring, the way the country shapes are stored.
                val xs = DoubleArray(n + 1)
                val ys = DoubleArray(n + 1)
                var x = 0L
                var y = 0L
                for (i in 0 until n) {
                    x += zigzag(r.next())
                    y += zigzag(r.next())
                    xs[i] = x / 1e4
                    ys[i] = y / 1e4
                }
                if (n > 0) { xs[n] = xs[0]; ys[n] = ys[0] }
                list += Ring(xs, ys)
            }
            if (list.isNotEmpty()) shapes += Shape(list.first(), list.drop(1))
        }
        return shapes
    }

    private class Varints(private val bytes: ByteArray, var at: Int) {
        fun next(): Long {
            var shift = 0
            var out = 0L
            while (true) {
                val b = bytes[at++].toInt() and 0xFF
                out = out or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return out
                shift += 7
            }
        }

        /** Steps over [count] varints without working out their values. */
        fun skip(count: Long) {
            var left = count
            while (left > 0) {
                if (bytes[at++].toInt() and 0x80 == 0) left--
            }
        }
    }

    companion object {
        val EMPTY = CityAreas(ByteArray(0), LongArray(0))

        /** Decoded outlines kept for the cities asked for most recently, such as those on the map. */
        // Enough for every city a well-travelled user has logged: the map asks for all of them
        // again on every edit, and an LRU smaller than that misses on every one.
        private const val KEEP = 2048

        private fun zigzag(n: Long): Long = (n ushr 1) xor -(n and 1)

        fun read(input: InputStream): CityAreas {
            val bytes = input.readBytes()
            require(bytes.size >= 4 && String(bytes, 0, 4, Charsets.US_ASCII) == "CAR1") { "not a city area file" }
            // One pass to find where each city starts, skipping over its points.
            var index = LongArray(1024)
            var count = 0
            val r = Varints(bytes, 4)
            while (r.at < bytes.size) {
                val start = r.at
                val id = r.next()
                require(id in 0..Int.MAX_VALUE) { "city id $id out of range" }
                repeat(r.next().toInt()) {
                    repeat(r.next().toInt()) { r.skip(2 * r.next()) }
                }
                if (count == index.size) index = index.copyOf(count * 2)
                index[count++] = (id shl 32) or start.toLong()
            }
            index = index.copyOf(count)
            // Sorted by id, then by offset: a city listed twice keeps its last outline, as a
            // map filled in file order would.
            index.sort()
            var kept = 0
            for (k in 0 until count) {
                if (kept > 0 && index[kept - 1] ushr 32 == index[k] ushr 32) kept--
                index[kept++] = index[k]
            }
            return CityAreas(bytes, index.copyOf(kept))
        }
    }
}
