package com.marcusrosado.stamp.data

/**
 * The 1:10m country shapes the map draws from mid zoom, kept as the file's bytes plus where
 * each feature sits in them. The selected outline hands MapLibre one country's JSON straight
 * from here, with no need to parse the other 285.
 */
class FineShapes private constructor(private val bytes: ByteArray, private val spans: Map<String, IntRange>) {
    val ids: Set<String> get() = spans.keys

    /** The feature's GeoJSON text, or null if the file has no such id. */
    fun feature(id: String): String? =
        spans[id]?.let { String(bytes, it.first, it.last - it.first + 1, Charsets.UTF_8) }

    companion object {
        val EMPTY = FineShapes(ByteArray(0), emptyMap())

        private val START = "{\"type\":\"Feature\",\"id\":\"".toByteArray()

        /**
         * Reads a FeatureCollection written the way tools/build_countries.py writes it: no
         * spaces, each feature starting with its type and id, one after another.
         */
        fun read(bytes: ByteArray): FineShapes {
            val starts = ArrayList<Int>()
            var i = indexOf(bytes, START, 0)
            while (i >= 0) {
                starts += i
                i = indexOf(bytes, START, i + START.size)
            }
            if (starts.isEmpty()) return EMPTY
            // The last feature ends just before the "]}" that closes the collection.
            val end = bytes.lastIndexOf(']'.code.toByte())
            val spans = HashMap<String, IntRange>(starts.size * 2)
            for ((k, s) in starts.withIndex()) {
                val idFrom = s + START.size
                var idTo = idFrom
                while (bytes[idTo] != '"'.code.toByte()) idTo++
                // Each feature ends before the comma that leads to the next one.
                val to = if (k + 1 < starts.size) starts[k + 1] - 1 else end
                spans[String(bytes, idFrom, idTo - idFrom, Charsets.UTF_8)] = s until to
            }
            return FineShapes(bytes, spans)
        }

        private fun indexOf(bytes: ByteArray, pattern: ByteArray, from: Int): Int {
            val first = pattern[0]
            var i = from
            val last = bytes.size - pattern.size
            outer@ while (i <= last) {
                if (bytes[i] != first) { i++; continue }
                for (j in 1 until pattern.size) if (bytes[i + j] != pattern[j]) { i++; continue@outer }
                return i
            }
            return -1
        }
    }
}
