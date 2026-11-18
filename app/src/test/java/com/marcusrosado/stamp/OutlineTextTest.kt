package com.marcusrosado.stamp

import com.marcusrosado.stamp.data.Ring
import com.marcusrosado.stamp.data.Shape
import com.marcusrosado.stamp.ui.TextCache
import com.marcusrosado.stamp.ui.outlineJson
import com.marcusrosado.stamp.ui.pointsIn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The tapped country's outline as text, and the cache that keeps the last few. */
class OutlineTextTest {
    private fun ring(vararg p: Double) = Ring(DoubleArray(p.size / 2) { p[2 * it] }, DoubleArray(p.size / 2) { p[2 * it + 1] })

    private val square = Shape(ring(0.0, 0.0, 4.0, 0.0, 4.0, 4.0, 0.0, 0.0), listOf(ring(1.0, 1.0, 2.0, 1.0, 1.0, 1.0)))
    private val island = Shape(ring(-10.5, 50.25, -10.0, 51.0, -10.5, 50.25), emptyList())

    @Test fun writesEveryRingAsLongitudeThenLatitude() {
        assertEquals(
            "{\"type\":\"Feature\",\"properties\":{},\"geometry\":{\"type\":\"MultiPolygon\",\"coordinates\":[" +
                "[[[0.0,0.0],[4.0,0.0],[4.0,4.0],[0.0,0.0]],[[1.0,1.0],[2.0,1.0],[1.0,1.0]]]," +
                "[[[-10.5,50.25],[-10.0,51.0],[-10.5,50.25]]]]}}",
            outlineJson(listOf(square, island)),
        )
    }

    @Test fun nothingToDrawIsAnEmptyMultiPolygon() {
        assertEquals("{\"type\":\"Feature\",\"properties\":{},\"geometry\":{\"type\":\"MultiPolygon\",\"coordinates\":[]}}", outlineJson(emptyList()))
    }

    @Test fun countsHolesAsPoints() {
        assertEquals(10, pointsIn(listOf(square, island)))
    }

    @Test fun dropsTheLeastRecentlyUsedPastItsSize() {
        val c = TextCache(10)
        c.put("a", "1234")
        c.put("b", "1234")
        c["a"]
        c.put("c", "1234")
        assertNull("b was used least lately", c["b"])
        assertEquals(setOf("a", "c"), c.keys)
        assertEquals(8, c.chars)
    }

    @Test fun keepsTheNewestHoweverLong() {
        val c = TextCache(10)
        c.put("a", "12")
        c.put("big", "x".repeat(50))
        assertEquals(setOf("big"), c.keys)
        assertEquals(50, c.chars)
    }

    @Test fun puttingAgainReplacesTheText() {
        val c = TextCache(100)
        c.put("a", "1234")
        c.put("a", "12")
        assertEquals("12", c["a"])
        assertEquals(2, c.chars)
    }
}
