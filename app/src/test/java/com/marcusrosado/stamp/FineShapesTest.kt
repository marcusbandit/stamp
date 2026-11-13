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
        private lateinit var atlas: Atlas
        private lateinit var fine: FineShapes

        @BeforeClass
        @JvmStatic
        fun load() {
            atlas = Atlas.parse(File("src/main/assets/countries.geojson").readText())
            fine = FineShapes.read(File("src/main/assets/countries_10m.geojson").readBytes())
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
}
