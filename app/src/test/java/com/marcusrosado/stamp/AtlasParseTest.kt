package com.marcusrosado.stamp

import com.marcusrosado.stamp.data.Atlas
import com.marcusrosado.stamp.data.Country
import com.marcusrosado.stamp.data.Ring
import com.marcusrosado.stamp.data.Shape
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The country file's own reader against the org.json one it replaced, on the real asset. */
class AtlasParseTest {
    private val text = File("src/main/assets/countries.geojson").readText()

    @Test fun readsTheSameAsOrgJson() {
        val now = Atlas.parse(text).countries
        val before = orgJson(text)
        assertEquals(before.size, now.size)
        for ((a, b) in before.zip(now)) {
            assertEquals(a.id, b.id)
            assertEquals(a.name, b.name)
            assertEquals(a.continent, b.continent)
            assertEquals(a.un, b.un)
            assertEquals(a.alpha2, b.alpha2)
            assertEquals(a.country, b.country)
            assertEquals(a.labelLon, b.labelLon, 0.0)
            assertEquals(a.labelLat, b.labelLat, 0.0)
            assertEquals(a.id, a.shapes.size, b.shapes.size)
            for ((sa, sb) in a.shapes.zip(b.shapes)) {
                assertEquals(a.id, sa.holes.size, sb.holes.size)
                for ((ra, rb) in (listOf(sa.outer) + sa.holes).zip(listOf(sb.outer) + sb.holes)) {
                    assertTrue(a.id, ra.xs.contentEquals(rb.xs))
                    assertTrue(a.id, ra.ys.contentEquals(rb.ys))
                }
            }
        }
    }

    @Test fun escapesNumbersAndOrder() {
        // Geometry before properties, an escaped name, an exponent and a height per point.
        val a = Atlas.parse(
            """{"features":[{"geometry":{"coordinates":[[[1e1,-2.5,7],[3,4],[0.000001,1E-2],[1e1,-2.5]]],"type":"Polygon"},
               "type":"Feature","properties":{"id":"X","name":"Côte \"d\"","continent":"Africa","un":true,"a2":null,"lx":1,"ly":-0.0}}],"type":"FeatureCollection"}""",
        )
        val c = a.countries.single()
        assertEquals("Côte \"d\"", c.name)
        assertEquals("", c.alpha2)
        assertEquals("X", c.country)
        assertEquals(1.0, c.labelLon, 0.0)
        assertTrue(c.shapes.single().outer.xs.contentEquals(doubleArrayOf(10.0, 3.0, 0.000001, 10.0)))
        assertTrue(c.shapes.single().outer.ys.contentEquals(doubleArrayOf(-2.5, 4.0, 0.01, -2.5)))
    }

    @Test fun fasterThanOrgJson() {
        // Warm both up, then time a few rounds of each. Printed for the record, not asserted
        // tightly: a loaded build machine makes timings noisy.
        repeat(3) { orgJson(text); Atlas.parse(text) }
        val rounds = 10
        val t0 = System.nanoTime()
        repeat(rounds) { orgJson(text) }
        val t1 = System.nanoTime()
        repeat(rounds) { Atlas.parse(text) }
        val t2 = System.nanoTime()
        val before = (t1 - t0) / rounds / 1e6
        val now = (t2 - t1) / rounds / 1e6
        println("countries.geojson: org.json %.1f ms, own reader %.1f ms".format(before, now))
        assertTrue("own reader $now ms, org.json $before ms", now < before)
    }

    private fun orgJson(geojson: String): List<Country> {
        val features = JSONObject(geojson).getJSONArray("features")
        return List(features.length()) { i ->
            val f = features.getJSONObject(i)
            val p = f.getJSONObject("properties")
            val g = f.getJSONObject("geometry")
            val coords = g.getJSONArray("coordinates")
            val shapes = when (g.getString("type")) {
                "Polygon" -> listOf(shape(coords))
                "MultiPolygon" -> List(coords.length()) { shape(coords.getJSONArray(it)) }
                else -> emptyList()
            }
            Country(
                id = p.getString("id"), name = p.getString("name"), continent = p.getString("continent"),
                un = p.getBoolean("un"), alpha2 = p.optString("a2"), labelLon = p.getDouble("lx"),
                labelLat = p.getDouble("ly"), shapes = shapes,
                country = p.optString("country").ifEmpty { p.getString("id") },
            )
        }
    }

    private fun shape(rings: JSONArray): Shape {
        val all = List(rings.length()) { ring(rings.getJSONArray(it)) }
        return Shape(all.first(), all.drop(1))
    }

    private fun ring(points: JSONArray): Ring {
        val n = points.length()
        val xs = DoubleArray(n); val ys = DoubleArray(n)
        for (i in 0 until n) {
            val pt = points.getJSONArray(i)
            xs[i] = pt.getDouble(0); ys[i] = pt.getDouble(1)
        }
        return Ring(xs, ys)
    }
}
