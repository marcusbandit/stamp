package com.marcusrosado.stamp

import com.marcusrosado.stamp.data.City
import com.marcusrosado.stamp.ui.citiesJson
import org.junit.Assert.assertEquals
import org.junit.Test

/** The cities view's dots, written as GeoJSON text. */
class CitiesJsonTest {
    private fun city(id: String, name: String, lat: Double, lon: Double, pop: Int) = City(id, name, name, "XX", "01", lat, lon, pop)

    @Test fun writesEachCityAsAPointWithItsIdPopulationAndName() {
        assertEquals(
            "{\"type\":\"FeatureCollection\",\"features\":[" +
                "{\"type\":\"Feature\",\"geometry\":{\"type\":\"Point\",\"coordinates\":[10.5,-3.25]},\"properties\":{\"id\":\"1\",\"pop\":20000,\"name\":\"Sometown\"}}," +
                "{\"type\":\"Feature\",\"geometry\":{\"type\":\"Point\",\"coordinates\":[0.0,0.0]},\"properties\":{\"id\":\"2\",\"pop\":0,\"name\":\"Malmö\"}}]}",
            citiesJson(listOf(city("1", "Sometown", -3.25, 10.5, 20_000), city("2", "Malmö", 0.0, 0.0, 0))),
        )
    }

    @Test fun escapesQuotesBackslashesAndControlCharacters() {
        val json = citiesJson(listOf(city("1", "A \"B\" C\\D\tE", 1.0, 2.0, 1)))
        assertEquals(true, json.contains("\"name\":\"A \\\"B\\\" C\\\\D\\u0009E\""))
    }

    @Test fun noCitiesIsAnEmptyCollection() {
        assertEquals("{\"type\":\"FeatureCollection\",\"features\":[]}", citiesJson(emptyList()))
    }
}
