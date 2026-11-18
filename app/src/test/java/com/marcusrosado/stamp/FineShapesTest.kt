package com.marcusrosado.stamp

import com.marcusrosado.stamp.data.Atlas
import com.marcusrosado.stamp.data.FineShapes
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

class FineShapesTest {
    companion object {
        private const val FILE = "src/main/assets/countries_10m.geojson"
        private lateinit var atlas: Atlas
        private lateinit var fine: FineShapes

        @BeforeClass
        @JvmStatic
        fun load() {
            atlas = Atlas.parse(File("src/main/assets/countries.geojson").readText())
            fine = FineShapes.index { File(FILE).inputStream() }
        }
    }

    // The map colours and taps both files by id, so they must hold exactly the same ones.
    @Test fun sameIdsAsTheCoarseShapes() = assertEquals(atlas.byId.keys, fine.ids)

    @Test fun eachFeatureIsItsOwnGeoJson() {
        for (id in listOf("AFG", "JPN-JPI", "USA-USH", "ZWE")) {
            val f = JSONObject(fine.feature(id)!!)
            assertEquals(id, f.getString("id"))
            assertEquals(atlas.countryOf(id), f.getJSONObject("properties").getString("country"))
        }
    }

    @Test fun unknownIdHasNoFeature() = assertNull(fine.feature("XXX"))

    @Test fun aMissingFileHasNoFeatures() {
        val none = FineShapes.index { null }
        assertEquals(emptySet<String>(), none.ids)
        assertNull(none.feature("AFG"))
    }

    // Each feature read from the file matches what cutting it out of the whole file in memory
    // gave, the way this class worked before it stopped keeping the file.
    @Test fun everyFeatureMatchesTheWholeFileCut() {
        val bytes = File(FILE).readBytes()
        val start = "{\"type\":\"Feature\",\"id\":\"".toByteArray()
        val text = String(bytes, Charsets.ISO_8859_1)
        val pattern = String(start, Charsets.ISO_8859_1)
        val starts = generateSequence(text.indexOf(pattern).takeIf { it >= 0 }) { i -> text.indexOf(pattern, i + start.size).takeIf { it >= 0 } }.toList()
        val end = bytes.lastIndexOf(']'.code.toByte())
        assertEquals(starts.size, fine.ids.size)
        for ((k, s) in starts.withIndex()) {
            val idFrom = s + start.size
            val id = String(bytes, idFrom, text.indexOf('"', idFrom) - idFrom, Charsets.UTF_8)
            val to = if (k + 1 < starts.size) starts[k + 1] - 1 else end
            assertEquals(id, String(bytes, s, to - s, Charsets.UTF_8), fine.feature(id))
        }
    }
}
