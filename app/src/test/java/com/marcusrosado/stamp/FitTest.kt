package com.marcusrosado.stamp

import com.marcusrosado.stamp.ui.fitBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FitTest {
    // The map left above a card on a folded phone: about 411 by 420 dp.
    private val w = 411f
    private val h = 420f

    @Test
    fun germanyFillsMostOfTheMapLeftShowing() {
        val (lat, lon, zoom) = fitBox(listOf(47.3, 5.9, 55.1, 15.0), w, h, maxZoom = 6.0)
        assertTrue("zoom $zoom", zoom in 3.5..5.0)
        assertTrue(lat in 47.3..55.1 && lon in 5.9..15.0)
    }

    @Test
    fun aTinyCountryStopsAtTheCap() {
        // Liechtenstein would fill the screen at zoom 9 or so; it stops at the cap.
        val (_, _, zoom) = fitBox(listOf(47.05, 9.47, 47.27, 9.64), w, h, maxZoom = 6.0)
        assertEquals(6.0, zoom, 1e-9)
    }

    @Test
    fun aBiggerMapZoomsCloser() {
        val small = fitBox(listOf(47.3, 5.9, 55.1, 15.0), w, h, 6.0).third
        val big = fitBox(listOf(47.3, 5.9, 55.1, 15.0), w * 2, h * 2, 6.0).third
        assertEquals(1.0, big - small, 1e-6)
    }
}
