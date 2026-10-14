package com.lagradost.cloudstream3.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Guards BUG-09: the picture-in-picture buttons write `CSPlayerEvent.value` into an Intent and
 * the receiver in `PlayerView` used to read it back by ordinal. The receiver now looks the case
 * up by `value`, so `value` must be unique and must round-trip whether or not it equals the
 * ordinal, and an unknown code must resolve to nothing instead of throwing.
 */
class CSPlayerEventWireValueTest {

    // Same lookup as the PiP receiver in PlayerView.
    private fun byValue(code: Int) = CSPlayerEvent.entries.firstOrNull { it.value == code }

    @Test
    fun `every wire value is unique`() {
        val values = CSPlayerEvent.entries.map { it.value }
        assertEquals(values, values.distinct())
    }

    @Test
    fun `each case is found again by its own wire value`() {
        for (event in CSPlayerEvent.entries) {
            assertEquals(event, byValue(event.value))
        }
    }

    @Test
    fun `an unknown wire value resolves to nothing instead of throwing`() {
        assertNull(byValue(999))
        assertNull(byValue(-1))
        assertNull(byValue(Int.MIN_VALUE))
    }
}
