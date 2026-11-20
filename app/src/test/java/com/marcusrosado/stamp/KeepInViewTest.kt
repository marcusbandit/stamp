package com.marcusrosado.stamp

import com.marcusrosado.stamp.ui.keepInView
import org.junit.Assert.assertEquals
import org.junit.Test

/** Which countries stay drawn as regions as the map pans. */
class KeepInViewTest {
    @Test fun aCountryPannedOutOfSightStaysDrawn() {
        assertEquals(listOf("B", "C", "A"), keepInView(listOf("A", "B"), listOf("B", "C")))
    }

    @Test fun panningBackChangesNothing() {
        val after = keepInView(listOf("A", "B"), listOf("B", "C"))
        assertEquals(setOf("A", "B", "C"), keepInView(after, listOf("A", "B")).toSet())
    }

    @Test fun zoomedOutDrawsNone() {
        assertEquals(emptyList<String>(), keepInView(listOf("A", "B"), emptyList()))
    }

    @Test fun theLongestOutOfSightGoFirst() {
        // Seen last: E, D; before that C, B, A.
        val kept = listOf("D", "E", "C", "B", "A")
        assertEquals(listOf("F", "D", "E", "C"), keepInView(kept, listOf("F"), most = 4))
    }

    @Test fun neverDropsWhatIsInSight() {
        assertEquals(listOf("A", "B", "C"), keepInView(listOf("X", "Y"), listOf("A", "B", "C"), most = 2))
    }
}
