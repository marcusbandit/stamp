package com.marcusrosado.stamp

import com.marcusrosado.stamp.data.Coordinates
import com.marcusrosado.stamp.data.LatLon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CoordinatesTest {
    private fun near(expected: LatLon, input: String) {
        val got = Coordinates.parse(input)
        assertNotNull("could not read \"$input\"", got)
        assertEquals("lat of \"$input\"", expected.lat, got!!.lat, 1e-4)
        assertEquals("lon of \"$input\"", expected.lon, got.lon, 1e-4)
    }

    private val tsuwano = LatLon(34.4667, 131.7667)

    @Test fun decimalAsGoogleMapsCopiesIt() {
        near(tsuwano, "34.4667, 131.7667")
        near(tsuwano, "34.4667,131.7667")
        near(tsuwano, "  34.4667 131.7667 ")
        near(LatLon(-33.8568, 151.2153), "-33.8568, 151.2153")
        near(LatLon(40.7128, -74.006), "40.7128 -74.006")
    }

    @Test fun decimalWithHemispheres() {
        near(tsuwano, "34.4667° N, 131.7667° E")
        near(tsuwano, "34.4667N 131.7667E")
        near(tsuwano, "N 34.4667 E 131.7667")
        near(LatLon(-33.8568, 151.2153), "33.8568° S, 151.2153° E")
        near(LatLon(40.7128, -74.006), "40.7128 n, 74.006 w")
    }

    @Test fun degreesMinutesSeconds() {
        near(LatLon(34.466694, 131.766694), "34°28'00.1\"N 131°46'00.1\"E")
        near(LatLon(34.466694, 131.766694), "34°28′00.1″N 131°46′00.1″E")
        near(LatLon(34.466694, 131.766694), "34 28 00.1 N, 131 46 00.1 E")
        near(LatLon(-22.951944, -43.210556), "22°57'07\"S 43°12'38\"W")
    }

    @Test fun degreesAndDecimalMinutes() {
        near(LatLon(34.4667, 131.7667), "34°28.002'N 131°46.002'E")
    }

    @Test fun longitudeFirstWithLetters() {
        near(tsuwano, "131.7667 E, 34.4667 N")
    }

    @Test fun googleMapsLinks() {
        near(tsuwano, "https://www.google.com/maps/@34.4667,131.7667,15z")
        near(tsuwano, "https://www.google.com/maps/place/Somewhere/@34.40,131.70,12z/data=!3m1!4b1!4m6!3m5!1s0x0:0x0!8m2!3d34.4667!4d131.7667")
        near(tsuwano, "https://maps.google.com/?q=34.4667,131.7667")
        near(tsuwano, "https://www.google.com/maps/@34.40,131.70,12z?q=34.4667,131.7667")
        near(tsuwano, "https://www.google.com/maps/search/?api=1&query=34.4667%2C131.7667")
    }

    @Test fun nonsenseIsRejected() {
        assertNull(Coordinates.parse(""))
        assertNull(Coordinates.parse("Tsuwano"))
        assertNull(Coordinates.parse("34.4667"))
        assertNull(Coordinates.parse("95.0, 10.0"))
        assertNull(Coordinates.parse("10.0, 190.0"))
        assertNull(Coordinates.parse("34°75'N 131°46'E"))
        assertNull(Coordinates.parse("34 N, 35 S"))
        assertNull(Coordinates.parse("https://maps.app.goo.gl/AbCdEf"))
    }
}
