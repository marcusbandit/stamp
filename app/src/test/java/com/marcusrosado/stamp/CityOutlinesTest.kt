package com.marcusrosado.stamp

import com.marcusrosado.stamp.data.CityOutlines
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CityOutlinesTest {
    private fun square(x0: Double, y0: Double, d: Double) =
        """{"type":"Polygon","coordinates":[[[$x0,$y0],[${x0 + d},$y0],[${x0 + d},${y0 + d}],[$x0,${y0 + d}],[$x0,$y0]]]}"""

    @Test fun picksTheSmallestPlaceHoldingTheCentre() {
        val found = JSONObject(
            """{"type":"FeatureCollection","features":[
              {"type":"Feature","geometry":${square(9.0, 55.0, 2.0)}},
              {"type":"Feature","geometry":${square(10.0, 56.0, 0.2)}},
              {"type":"Feature","geometry":${square(20.0, 40.0, 0.1)}},
              {"type":"Feature","geometry":{"type":"Point","coordinates":[10.1,56.1]}}]}""",
        )
        val shapes = CityOutlines.pick(found, 56.1, 10.1)
        assertNotNull(shapes)
        assertEquals(10.2, shapes!!.single().outer.xs.max(), 1e-9)
        assertNull("nothing holds a point at sea", CityOutlines.pick(found, 0.0, 0.0))
    }

    @Test fun theBoundaryNamedForTheTownWins() {
        val found = JSONObject(
            """{"elements":[
              {"type":"relation","id":1,"tags":{"admin_level":"5","name":"Region Sample"}},
              {"type":"relation","id":2,"tags":{"admin_level":"7","name":"Sampleby Kommune"}},
              {"type":"relation","id":3,"tags":{"admin_level":"6","name":"Sampleby"}},
              {"type":"relation","id":4,"tags":{"admin_level":"9","name":"Other Parish"}}]}""",
        )
        assertEquals(3L, CityOutlines.boundaryFor(found, listOf("Sampleby")))
        val onlyKommune = JSONObject("""{"elements":[{"id":2,"tags":{"admin_level":"7","name":"Sampleby Kommune"}},{"id":1,"tags":{"admin_level":"5","name":"Region Sample"}}]}""")
        assertEquals("accents do not matter", 2L, CityOutlines.boundaryFor(onlyKommune, listOf("Sámpleby")))
        assertNull("a town is never given a boundary without its name", CityOutlines.boundaryFor(found, listOf("Elsewhere")))
    }

    @Test fun keptOutlinesReadBack() {
        val shapes = CityOutlines.parseGeometry(JSONObject(square(1.0, 2.0, 0.5)))
        val again = CityOutlines.parseGeometry(CityOutlines.geometryJson(shapes))
        assertEquals(1, again.size)
        assertEquals(shapes[0].outer.xs.toList(), again[0].outer.xs.toList())
        assertEquals(shapes[0].outer.ys.toList(), again[0].outer.ys.toList())
    }
}
