package com.marcusrosado.stamp

import com.marcusrosado.stamp.ui.PaneRules
import com.marcusrosado.stamp.ui.PaneRules.Cover
import com.marcusrosado.stamp.ui.PaneRules.Side
import com.marcusrosado.stamp.ui.PaneRules.Tab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PaneRulesTest {
    @Test
    fun everyTabShowsItsOwnScreenInEveryPosture() {
        // Half folded used to leave the Passport tab showing the map: each tab covers the map.
        assertEquals(Cover.None, PaneRules.cover(Tab.Map))
        assertEquals(Cover.Passport, PaneRules.cover(Tab.Passport))
        assertEquals(Cover.Settings, PaneRules.cover(Tab.Settings))
    }

    @Test
    fun bentWithNothingChosenShowsThePassportBesideTheMap() {
        assertEquals(Side.Passport, PaneRules.side(bent = true, wideFlat = false, selected = false))
        assertEquals(Side.Passport, PaneRules.side(bent = true, wideFlat = true, selected = false))
    }

    @Test
    fun aChosenCountryOpensBesideTheMapOnABigScreen() {
        assertEquals(Side.Card, PaneRules.side(bent = true, wideFlat = false, selected = true))
        assertEquals(Side.Card, PaneRules.side(bent = false, wideFlat = true, selected = true))
    }

    @Test
    fun aPhoneKeepsTheMapWhole() {
        assertEquals(Side.None, PaneRules.side(bent = false, wideFlat = false, selected = true))
        assertEquals(Side.None, PaneRules.side(bent = false, wideFlat = false, selected = false))
        assertEquals(Side.None, PaneRules.side(bent = false, wideFlat = true, selected = false))
    }

    @Test
    fun backReturnsToWhereAJumpCameFrom() {
        // "Show on map" from Settings: back goes to Settings, not out of the app.
        assertEquals(Tab.Settings, PaneRules.back(Tab.Map, cameFrom = Tab.Settings))
        assertEquals(Tab.Passport, PaneRules.back(Tab.Map, cameFrom = Tab.Passport))
    }

    @Test
    fun backFromATabGoesToTheMapAndOnlyTheMapLeaves() {
        assertEquals(Tab.Map, PaneRules.back(Tab.Settings, cameFrom = null))
        assertEquals(Tab.Map, PaneRules.back(Tab.Passport, cameFrom = null))
        assertNull(PaneRules.back(Tab.Map, cameFrom = null))
    }
}
