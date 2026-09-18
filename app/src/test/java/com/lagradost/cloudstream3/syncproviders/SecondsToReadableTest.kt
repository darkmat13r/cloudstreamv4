package com.lagradost.cloudstream3.syncproviders

import com.lagradost.cloudstream3.syncproviders.AccountManager.Companion.secondsToReadable
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the rendering of [secondsToReadable] across the sub-minute boundary.
 *
 * The bug this guards: everything under a minute used to render as "0m", so the
 * resume-watching line said "0m remaining" for the last minute of every episode.
 */
class SecondsToReadableTest {

    @Test
    fun `sub-minute durations render in seconds`() {
        assertEquals("1s", secondsToReadable(1, "0 mins"))
        assertEquals("45s", secondsToReadable(45, "0 mins"))
        assertEquals("59s", secondsToReadable(59, "0 mins"))
    }

    @Test
    fun `zero still renders as 0m, meaning done`() {
        assertEquals("0m", secondsToReadable(0, "0 mins"))
    }

    @Test
    fun `minutes, hours and days are unchanged`() {
        assertEquals("1m", secondsToReadable(60, "0 mins"))
        assertEquals("1m", secondsToReadable(61, "0 mins"))
        assertEquals("45m", secondsToReadable(2700, "0 mins"))
        assertEquals("1h 0m", secondsToReadable(3600, "0 mins"))
        assertEquals("2h 22m", secondsToReadable(8520, "0 mins"))
        assertEquals("3d 4h 0m", secondsToReadable(273600, "0 mins"))
    }

    /**
     * The `minutes < 0` guard is a minutes-within-the-hour check, not a negativity
     * check, so it misses the first 59 seconds of every negative hour. That is
     * pre-existing behaviour; these cases exist to prove the seconds branch sits
     * after the guard and does not disturb it.
     */
    @Test
    fun `negative inputs are untouched by the seconds branch`() {
        assertEquals("0m", secondsToReadable(-45, "0 mins"))
        assertEquals("0m", secondsToReadable(-59, "0 mins"))
        assertEquals("0 mins", secondsToReadable(-60, "0 mins"))
        assertEquals("0 mins", secondsToReadable(-8520, "0 mins"))
        assertEquals("-1h 0m", secondsToReadable(-3600, "0 mins"))
    }

    @Test
    fun `the empty completedValue call site is honoured`() {
        assertEquals("", secondsToReadable(-60, ""))
    }
}
