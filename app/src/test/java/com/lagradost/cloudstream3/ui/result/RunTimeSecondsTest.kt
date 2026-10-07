package com.lagradost.cloudstream3.ui.result

import com.lagradost.cloudstream3.syncproviders.AccountManager.Companion.secondsToReadable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards BUG-01: a provider-supplied runtime, in minutes, multiplied by 60 and narrowed to Int
 * used to wrap, so 40_000_000 minutes rendered as a negative duration.
 */
class RunTimeSecondsTest {

    @Test
    fun `ordinary runtimes convert exactly`() {
        assertEquals(0, runTimeMinutesToSeconds(0))
        assertEquals(45 * 60, runTimeMinutesToSeconds(45))
        assertEquals("1h 30m", secondsToReadable(runTimeMinutesToSeconds(90)!!, ""))
    }

    @Test
    fun `null stays null`() {
        assertNull(runTimeMinutesToSeconds(null))
    }

    @Test
    fun `the largest runtime that fits is not altered`() {
        val max = Int.MAX_VALUE / 60
        assertEquals(max * 60, runTimeMinutesToSeconds(max))
    }

    @Test
    fun `values that used to wrap saturate instead`() {
        // The two measured in the defect entry: both wrapped negative.
        assertEquals(Int.MAX_VALUE, runTimeMinutesToSeconds(35_791_395))
        assertEquals(Int.MAX_VALUE, runTimeMinutesToSeconds(40_000_000))
        assertEquals(Int.MAX_VALUE, runTimeMinutesToSeconds(Int.MAX_VALUE))
    }

    @Test
    fun `a saturated runtime renders as a long duration, not as completed`() {
        val text = secondsToReadable(runTimeMinutesToSeconds(40_000_000)!!, "")
        assertTrue(text, text.isNotEmpty())
    }

    @Test
    fun `negative input never becomes positive`() {
        assertTrue(runTimeMinutesToSeconds(-40_000_000)!! < 0)
        assertTrue(runTimeMinutesToSeconds(Int.MIN_VALUE)!! < 0)
    }
}
