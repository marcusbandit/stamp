package com.marcusrosado.stamp.data

import java.io.InputStream

/**
 * The 1:10m country shapes the map draws from mid zoom, kept as where each feature sits in the
 * file, not the file itself. The selected outline hands MapLibre one country's JSON, read from
 * the file when asked for, with no need to parse the other 285. Keeping all 7.8 MB in memory
 * for that cost more than the rest of the map data put together.
 */
class FineShapes private constructor(private val open: () -> InputStream?, private val spans: Map<String, IntRange>) {
    val ids: Set<String> get() = spans.keys

    /**
     * The feature's GeoJSON text, or null if the file has no such id or cannot be read. Reads
     * the file up to the feature, so call it off the main thread.
     */
    fun feature(id: String): String? {
        val span = spans[id] ?: return null
        return runCatching {
            open()?.use { input ->
                var skip = span.first.toLong()
                while (skip > 0) {
                    val n = input.skip(skip)
                    if (n > 0) skip -= n else if (input.read() < 0) return null else skip--
                }
                val out = ByteArray(span.last - span.first + 1)
                var at = 0
                while (at < out.size) {
                    val n = input.read(out, at, out.size - at)
                    if (n < 0) return null
                    at += n
                }
                String(out, Charsets.UTF_8)
            }
        }.getOrNull()
    }

    /**
     * Several features in one read of the file, in file order, so a country with many parts
     * does not inflate the compressed asset again from its start for each of them.
     */
    fun features(ids: List<String>): List<String> {
        val wanted = ids.mapNotNull { id -> spans[id]?.let { id to it } }.sortedBy { it.second.first }
        if (wanted.isEmpty()) return emptyList()
        return runCatching {
            open()?.use { input ->
                var pos = 0L
                wanted.mapNotNull { (_, span) ->
                    var skip = span.first - pos
                    while (skip > 0) {
                        val n = input.skip(skip)
                        if (n > 0) skip -= n else if (input.read() < 0) return@use null else skip--
                    }
                    val out = ByteArray(span.last - span.first + 1)
                    var at = 0
                    while (at < out.size) {
                        val n = input.read(out, at, out.size - at)
                        if (n < 0) return@use null
                        at += n
                    }
                    pos = span.last + 1L
                    String(out, Charsets.UTF_8)
                }
            }
        }.getOrNull().orEmpty()
    }

    companion object {
        val EMPTY = FineShapes({ null }, emptyMap())

        private val START = "{\"type\":\"Feature\",\"id\":\"".toByteArray()

        /**
         * Finds the features of a FeatureCollection written the way tools/build_countries.py
         * writes it: no spaces, each feature starting with its type and id, one after another.
         * One pass over the file from [open], keeping nothing of it; [open] is called again for
         * each feature asked for.
         */
        fun index(open: () -> InputStream?): FineShapes {
            val starts = ArrayList<Int>()
            val names = ArrayList<String>()
            var lastBracket = -1
            // How much of START the bytes just read match. Its '{' shows up only at its start,
            // so a mismatch starts over, from that byte if it is a '{'.
            var matched = 0
            // The id being read after a full match, or null.
            var id: java.io.ByteArrayOutputStream? = null
            var pos = 0
            val buf = ByteArray(64 * 1024)
            val input = open() ?: return EMPTY
            input.use {
                while (true) {
                    val n = it.read(buf)
                    if (n < 0) break
                    for (k in 0 until n) {
                        val b = buf[k]
                        if (b == ']'.code.toByte()) lastBracket = pos
                        val reading = id
                        if (reading != null) {
                            if (b == '"'.code.toByte()) { names += reading.toString(Charsets.UTF_8.name()); id = null } else reading.write(b.toInt())
                        } else if (b == START[matched]) {
                            matched++
                            if (matched == START.size) {
                                starts += pos - START.size + 1
                                matched = 0
                                id = java.io.ByteArrayOutputStream(8)
                            }
                        } else {
                            matched = if (b == START[0]) 1 else 0
                        }
                        pos++
                    }
                }
            }
            if (starts.isEmpty() || names.size != starts.size) return EMPTY
            // The last feature ends just before the "]}" that closes the collection.
            val end = lastBracket
            val spans = HashMap<String, IntRange>(starts.size * 2)
            for ((k, s) in starts.withIndex()) {
                // Each feature ends before the comma that leads to the next one.
                val to = if (k + 1 < starts.size) starts[k + 1] - 1 else end
                spans[names[k]] = s until to
            }
            return FineShapes(open, spans)
        }
    }
}
