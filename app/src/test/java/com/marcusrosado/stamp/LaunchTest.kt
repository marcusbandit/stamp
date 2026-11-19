package com.marcusrosado.stamp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LaunchTest {
    @Test fun aPlainGeoLinkIsItsPosition() {
        assertEquals(Launch.Place(48.85, 2.35, null), Launch.geo("geo:48.85,2.35"))
        assertEquals(Launch.Place(-33.87, 151.21, null), Launch.geo("geo:-33.87,151.21,40"))
        assertEquals(Launch.Place(48.85, 2.35, null), Launch.geo("geo:48.85,2.35;u=35"))
        assertEquals(Launch.Place(48.85, 2.35, null), Launch.geo("GEO:48.85,2.35"))
    }

    @Test fun aGeoLinkMaySayHowClose() {
        assertEquals(Launch.Place(48.85, 2.35, 12.0), Launch.geo("geo:48.85,2.35?z=12"))
        assertEquals(Launch.Place(48.85, 2.35, null), Launch.geo("geo:48.85,2.35?z=far"))
    }

    @Test fun aPositionInTheQueryWins() {
        assertEquals(Launch.Place(48.85, 2.35, null), Launch.geo("geo:0,0?q=48.85,2.35(A%20tower)"))
        assertEquals(Launch.Place(48.85, 2.35, 9.0), Launch.geo("geo:0,0?q=48.85%2C2.35&z=9"))
    }

    @Test fun anAddressHasNoPosition() {
        assertEquals(Launch.Unreadable, Launch.geo("geo:0,0?q=Example+Street+12+Sometown"))
        assertEquals(Launch.Unreadable, Launch.geo("geo:0,0"))
        assertEquals(Launch.Unreadable, Launch.geo("geo:91,0"))
        // An address near a position still opens that position.
        assertEquals(Launch.Place(10.0, 20.0, null), Launch.geo("geo:10,20?q=Example+Street"))
    }

    @Test fun otherLinksAreNotGeo() {
        assertNull(Launch.geo("https://example.com/48.85,2.35"))
    }

    @Test fun shortcutsByName() {
        assertEquals(Launch.NewTrip, Launch.shortcut("trip"))
        assertEquals(Launch.FindCity, Launch.shortcut("find"))
        assertEquals(Launch.Scan, Launch.shortcut("scan"))
        assertEquals(Launch.Here, Launch.shortcut("here"))
        assertNull(Launch.shortcut("elsewhere"))
    }
}
