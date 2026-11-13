package com.marcusrosado.stamp.data

import java.io.InputStream

/** One region's outline: its polygons, each a list of rings, each ring flat as lat, lon, lat, lon. */
class RegionShape(
    /** The region's GeoNames id, such as "JP.15". */
    val id: String,
    val polygons: List<List<DoubleArray>>,
)

/**
 * The outlines of each country's regions, as tools/build_regions.py writes them: one small file
 * per country, read only when a country is drawn as its regions and kept for the few countries
 * seen last, so the whole world's regions never sit in memory at once.
 */
class RegionShapes(private val open: (alpha2: String) -> InputStream?) {
    private val kept = object : LinkedHashMap<String, List<RegionShape>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<RegionShape>>?) = size > KEEP
    }

    /** The regions of country [alpha2], or an empty list when there are none. */
    fun of(alpha2: String): List<RegionShape> {
        val cc = alpha2.uppercase()
        synchronized(kept) { kept[cc]?.let { return it } }
        val read = runCatching { open(cc)?.use { read(cc, it.readBytes()) } }.getOrNull().orEmpty()
        synchronized(kept) { kept[cc] = read }
        return read
    }

    companion object {
        /** How many countries' regions stay in memory. */
        const val KEEP = 24

        fun read(alpha2: String, b: ByteArray): List<RegionShape> {
            if (b.size < 4 || String(b, 0, 4, Charsets.US_ASCII) != "SRG1") return emptyList()
            var i = 4
            fun varint(): Long {
                var v = 0L
                var shift = 0
                while (true) {
                    val x = b[i++].toInt() and 0xFF
                    v = v or ((x and 0x7F).toLong() shl shift)
                    if (x < 0x80) return v
                    shift += 7
                }
            }
            fun zigzag(): Long { val n = varint(); return (n ushr 1) xor -(n and 1) }
            val out = ArrayList<RegionShape>()
            repeat(varint().toInt()) {
                val len = varint().toInt()
                val code = String(b, i, len, Charsets.UTF_8)
                i += len
                val polys = ArrayList<List<DoubleArray>>()
                repeat(varint().toInt()) {
                    val rings = ArrayList<DoubleArray>()
                    repeat(varint().toInt()) {
                        val n = varint().toInt()
                        val ring = DoubleArray(n * 2)
                        var y = 0L
                        var x = 0L
                        for (k in 0 until n) {
                            y += zigzag(); x += zigzag()
                            ring[2 * k] = y / 1e4
                            ring[2 * k + 1] = x / 1e4
                        }
                        rings += ring
                    }
                    polys += rings
                }
                out += RegionShape("$alpha2.$code", polys)
            }
            return out
        }
    }
}
