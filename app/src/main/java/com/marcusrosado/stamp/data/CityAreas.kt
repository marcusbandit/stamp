package com.marcusrosado.stamp.data

import java.io.InputStream

/**
 * The official outlines of big cities, from OpenStreetMap, keyed by GeoNames id. Built by
 * tools/build_cityareas.py into assets/cityareas.bin. A city without an outline stays a dot.
 *
 * The file is "CAR1", then per city: the GeoNames id, the number of polygons, and per polygon
 * the number of rings, each ring as a point count and zigzag varint deltas of longitude and
 * latitude in units of 1e-4 degrees. Rings are open: the closing point is implied.
 */
class CityAreas private constructor(private val byId: Map<String, List<Shape>>) {
    /** The outline of a city, or null when it has none. */
    fun of(cityId: String): List<Shape>? = byId[cityId]


    companion object {
        val EMPTY = CityAreas(emptyMap())

        fun read(input: InputStream): CityAreas {
            val bytes = input.readBytes()
            require(bytes.size >= 4 && String(bytes, 0, 4, Charsets.US_ASCII) == "CAR1") { "not a city area file" }
            var at = 4
            fun varint(): Long {
                var shift = 0
                var out = 0L
                while (true) {
                    val b = bytes[at++].toInt() and 0xFF
                    out = out or ((b and 0x7F).toLong() shl shift)
                    if (b and 0x80 == 0) return out
                    shift += 7
                }
            }
            fun zigzag(n: Long): Long = (n ushr 1) xor -(n and 1)
            val out = HashMap<String, List<Shape>>()
            while (at < bytes.size) {
                val id = varint().toString()
                val polys = varint().toInt()
                val shapes = ArrayList<Shape>(polys)
                repeat(polys) {
                    val rings = varint().toInt()
                    val list = ArrayList<Ring>(rings)
                    repeat(rings) {
                        val n = varint().toInt()
                        // One extra slot closes the ring, the way the country shapes are stored.
                        val xs = DoubleArray(n + 1)
                        val ys = DoubleArray(n + 1)
                        var x = 0L
                        var y = 0L
                        for (i in 0 until n) {
                            x += zigzag(varint())
                            y += zigzag(varint())
                            xs[i] = x / 1e4
                            ys[i] = y / 1e4
                        }
                        if (n > 0) { xs[n] = xs[0]; ys[n] = ys[0] }
                        list += Ring(xs, ys)
                    }
                    if (list.isNotEmpty()) shapes += Shape(list.first(), list.drop(1))
                }
                out[id] = shapes
            }
            return CityAreas(out)
        }
    }
}
