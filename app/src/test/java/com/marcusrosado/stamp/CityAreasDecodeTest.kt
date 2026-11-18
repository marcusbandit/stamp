package com.marcusrosado.stamp

import com.marcusrosado.stamp.data.CityAreas
import com.marcusrosado.stamp.data.Ring
import com.marcusrosado.stamp.data.Shape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Outlines decoded one city at a time, against the reader that decoded every city up front. */
class CityAreasDecodeTest {
    private val bytes = File("src/main/assets/cityareas.bin").readBytes()

    @Test fun everyCityDecodesAsBefore() {
        val before = decodeAll(bytes)
        val now = CityAreas.read(bytes.inputStream())
        var points = 0L
        for ((id, shapes) in before) {
            val got = now.of(id)!!
            assertEquals(id, shapes.size, got.size)
            for ((a, b) in shapes.zip(got)) {
                assertEquals(id, a.holes.size, b.holes.size)
                for ((ra, rb) in (listOf(a.outer) + a.holes).zip(listOf(b.outer) + b.holes)) {
                    assertTrue(id, ra.xs.contentEquals(rb.xs))
                    assertTrue(id, ra.ys.contentEquals(rb.ys))
                    points += ra.xs.size
                }
            }
        }
        // What the old reader held for the life of the app, against what is kept now.
        println("cityareas.bin: ${before.size} cities, $points points, %.1f MB of doubles before, %.1f MB of bytes kept now"
            .format(points * 16 / 1e6, bytes.size / 1e6))
    }

    @Test fun unknownAndOddIdsHaveNone() {
        val areas = CityAreas.read(bytes.inputStream())
        assertNull(areas.of("1849523"))
        assertNull(areas.of("01850147"))
        assertNull(areas.of("tokyo"))
        assertNull(areas.of(""))
        assertNull(CityAreas.EMPTY.of("1850147"))
    }

    @Test fun aCityAskedForAgainIsTheSameOutline() {
        val areas = CityAreas.read(bytes.inputStream())
        assertSame(areas.of("1850147"), areas.of("1850147"))
    }

    /** The reader this replaced, which decoded every city when the file was read. */
    private fun decodeAll(bytes: ByteArray): Map<String, List<Shape>> {
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
        return out
    }
}
