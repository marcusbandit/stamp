package com.marcusrosado.stamp

import com.marcusrosado.stamp.data.BestPhotos
import com.marcusrosado.stamp.data.BestPhotos.Shot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BestPhotosTest {
    private val hour = 3_600_000L

    @Test fun everyStopGetsOneBeforeAnyGetsTwo() {
        // 600 photos at the first stop, 5 at each of the other three.
        val shots = (0 until 600).map { Shot("a$it", it * 10_000L, 0) } +
            (1..3).flatMap { s -> (0 until 5).map { Shot("s$s-$it", 100 * hour * s + it * hour, s) } }
        val picked = BestPhotos.pick(shots)
        assertEquals(4, picked.size)
        assertEquals(listOf(0, 1, 2, 3), picked.map { it.stop })
    }

    @Test fun aBurstCountsOnce() {
        // Twelve frames a second apart, then one more photo an hour later.
        val shots = (0 until 12).map { Shot("b$it", it * 1_000L, 0) } + Shot("later", hour, 0) + Shot("end", 5 * hour, 0)
        val picked = BestPhotos.pick(shots, 4)
        assertEquals(1, picked.count { it.id.startsWith("b") })
        assertTrue(picked.any { it.id == "later" } && picked.any { it.id == "end" })
    }

    @Test fun aFewPhotosAreAllKept() {
        val shots = listOf(Shot("x", 2L, 0), Shot("y", 1L, 1))
        assertEquals(listOf("y", "x"), BestPhotos.pick(shots).map { it.id })
    }

    @Test fun oneStopSpreadsAcrossItsDays() {
        val shots = (0 until 100).map { Shot("d$it", it * hour, 0) }
        val picked = BestPhotos.pick(shots, 4).map { it.takenMs / hour }
        // The middle first, then the two ends, then between.
        assertTrue(0L in picked && 99L in picked && 50L in picked)
    }
}
