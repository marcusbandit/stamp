package com.marcusrosado.stamp

import com.marcusrosado.stamp.data.CityAreas
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CityAreasTest {
    private val areas = File("src/main/assets/cityareas.bin").inputStream().use { CityAreas.read(it) }

    @Test fun tokyoHasAnOutlineAroundItsCentre() {
        val tokyo = areas.of("1850147").also { assertNotNull(it) }!!
        assertTrue(tokyo.isNotEmpty())
        val xs = tokyo.flatMap { it.outer.xs.toList() }
        val ys = tokyo.flatMap { it.outer.ys.toList() }
        // The wards and Tama, without the far islands: roughly 138.9 to 139.95 E, 35.5 to 35.9 N.
        assertTrue(xs.min() > 138.8 && xs.max() < 140.0)
        assertTrue(ys.min() > 35.4 && ys.max() < 36.0)
        assertTrue("rings close", tokyo.all { it.outer.xs.first() == it.outer.xs.last() })
    }

    @Test fun aCityWithoutAnOutlineHasNone() = assertNull(areas.of("1849523"))
}
