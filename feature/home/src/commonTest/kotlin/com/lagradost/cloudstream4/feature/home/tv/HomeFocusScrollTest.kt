package com.lagradost.cloudstream4.feature.home.tv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HomeFocusScrollTest {

    private fun fits(top: Float) = HomeFocusScroll.railFits(top, size = 164f, container = 540f, band = 28f)

    private fun across(offset: Float) = HomeFocusScroll.across(offset, size = 80f, container = 880f, start = 24f, end = 28f)

    @Test
    fun aRailInsideTheBandFits() {
        assertTrue(fits(28f))
        assertTrue(fits(88f))
        assertTrue(fits(348f))
    }

    @Test
    fun aRailReachingPastTheFootOfTheBandDoesNot() {
        assertFalse(fits(349f))
        assertFalse(fits(600f))
    }

    @Test
    fun aRailStartingAboveTheBandDoesNot() {
        assertFalse(fits(27f))
        assertFalse(fits(-100f))
    }

    @Test
    fun aCardOnARailThatFitsDoesNotMove() {
        assertEquals(0f, across(24f))
        assertEquals(0f, across(772f))
    }

    @Test
    fun aCardPastAnEndMovesJustInsideIt() {
        assertEquals(30f, across(802f))
        assertEquals(-24f, across(0f))
    }
}
