package com.marcusrosado.stamp

import androidx.compose.ui.unit.dp
import com.marcusrosado.stamp.ui.sheetStop
import org.junit.Assert.assertEquals
import org.junit.Test

class SheetStopTest {
    // Closed, the opening height, full height. There is no smaller resting height.
    private val stops = listOf(0.dp, 400.dp, 820.dp)

    @Test
    fun aQuarterUpFromTheOpeningHeightFillsTheScreen() {
        assertEquals(820.dp, sheetStop(510.dp, 0.dp, restedAt = 400.dp, stops))
    }

    @Test
    fun lessThanAQuarterUpFallsBack() {
        assertEquals(400.dp, sheetStop(480.dp, 0.dp, restedAt = 400.dp, stops))
    }

    @Test
    fun aQuarterDownFromFullGoesBackToTheOpeningHeight() {
        assertEquals(400.dp, sheetStop(700.dp, 0.dp, restedAt = 820.dp, stops))
    }

    @Test
    fun aHardSwipeDownFromFullCloses() {
        assertEquals(0.dp, sheetStop(780.dp, 1500.dp, restedAt = 820.dp, stops))
    }

    @Test
    fun aHardSwipeDownFromTheOpeningHeightCloses() {
        assertEquals(0.dp, sheetStop(390.dp, 1500.dp, restedAt = 400.dp, stops))
    }

    @Test
    fun aQuarterDownFromTheOpeningHeightCloses() {
        assertEquals(0.dp, sheetStop(290.dp, 0.dp, restedAt = 400.dp, stops))
    }

    @Test
    fun aFlickUpGoesOneStopUp() {
        assertEquals(820.dp, sheetStop(410.dp, (-1500).dp, restedAt = 400.dp, stops))
    }
}
