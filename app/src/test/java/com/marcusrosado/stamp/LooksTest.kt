package com.marcusrosado.stamp

import com.marcusrosado.stamp.ui.Looks
import com.marcusrosado.stamp.ui.MIN_APART
import com.marcusrosado.stamp.ui.MIN_SEA_LAND
import com.marcusrosado.stamp.ui.apart
import com.marcusrosado.stamp.ui.mustDiffer
import org.junit.Assert.assertTrue
import org.junit.Test

class LooksTest {
    /** Every look keeps sea, land and the three marks clearly apart. */
    @Test fun mapColoursNeverLookAlike() {
        val clashes = Looks.flatMap { look ->
            val marks = look.map.mustDiffer().mapNotNull { (what, a, b) ->
                val d = apart(a, b)
                if (d < MIN_APART) "${look.name} $what ${"%.1f".format(d)}" else null
            }
            val coast = apart(look.map.ocean, look.map.land)
            marks + listOfNotNull(if (coast < MIN_SEA_LAND) "${look.name} ocean/land ${"%.1f".format(coast)}" else null)
        }
        assertTrue("colours too close: $clashes", clashes.isEmpty())
    }
}
