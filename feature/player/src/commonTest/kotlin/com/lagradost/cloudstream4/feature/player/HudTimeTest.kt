package com.lagradost.cloudstream4.feature.player

import kotlin.test.Test
import kotlin.test.assertEquals

/** 7d's figures: `42:13`, `-19:31`, `1:01:44`, `+00:30`, `1.0×`. */
class HudTimeTest {

    @Test
    fun theBoardsTimes() {
        val position = (42 * 60 + 13) * 1000L
        val duration = (61 * 60 + 44) * 1000L
        assertEquals("42:13", HudTime.clock(position))
        assertEquals("1:01:44", HudTime.clock(duration))
        assertEquals("-19:31", HudTime.remaining(position, duration))
    }

    @Test
    fun shortTimesHaveNoLeadingZeroAndNothingGoesBelowZero() {
        assertEquals("0:00", HudTime.clock(0))
        assertEquals("5:03", HudTime.clock((5 * 60 + 3) * 1000L))
        assertEquals("0:00", HudTime.clock(-4000))
        assertEquals("-0:00", HudTime.remaining(10_000, 5_000))
    }

    @Test
    fun theReadoutsStepAlwaysHasMinutesAndSeconds() {
        assertEquals("+00:30", HudTime.delta(30_000))
        assertEquals("-01:00", HudTime.delta(-60_000))
        assertEquals("+1:00:00", HudTime.delta(3_600_000))
    }

    @Test
    fun speedsKeepOneDecimalAndShowTwoWhenTheyHaveThem() {
        assertEquals("1.0×", HudTime.speed(1f))
        assertEquals("1.5×", HudTime.speed(1.5f))
        assertEquals("1.25×", HudTime.speed(1.25f))
        assertEquals("0.75×", HudTime.speed(0.75f))
        assertEquals("2.0×", HudTime.speed(2f))
    }
}
