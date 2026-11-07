package com.lagradost.cloudstream4.core.designsystem.scroll

import kotlin.test.Test
import kotlin.test.assertEquals

class RowScrollTest {

    private fun distance(offset: Float, size: Float = ROW) =
        RowScroll.distance(offset, size, CONTAINER, START, END, ROW, PITCH)

    /**
     * What Compose does: asks, scrolls some of the way, asks again with the row where it is now, until the answer is 0.
     * Returns how far the list moved in all.
     */
    private fun scrollInSteps(from: Float, size: Float = ROW, fraction: Float = 0.25f): Float {
        var offset = from
        var moved = 0f
        repeat(MAX_STEPS) {
            val left = distance(offset, size)
            if (left == 0f) return moved
            val step = if (kotlin.math.abs(left) < 1f) left else left * fraction
            offset -= step
            moved += step
        }
        error("never settled, $moved so far")
    }

    @Test
    fun aRowThatFitsDoesNotMoveTheList() {
        for (row in 0..5) assertEquals(0f, distance(START + row * PITCH), "row $row")
    }

    @Test
    fun aRowBelowTheBandMovesTheListByOneWholeRow() {
        assertEquals(PITCH, distance(START + 6 * PITCH))
    }

    @Test
    fun aRowFarBelowMovesTheListByWholeRows() {
        assertEquals(3 * PITCH, distance(START + 8 * PITCH))
    }

    @Test
    fun aRowAboveTheBandMovesTheListBackByWholeRows() {
        assertEquals(-PITCH, distance(START - PITCH))
        assertEquals(-2 * PITCH, distance(START - 2 * PITCH))
    }

    /** A row's verb is shorter than its row, so its offset is not on a row's line. The list still moves by whole rows. */
    @Test
    fun aVerbInsideARowMovesTheListByWholeRows() {
        assertEquals(-PITCH, distance(START - PITCH + 4f, size = ROW - 8f))
        assertEquals(PITCH, distance(START + 6 * PITCH + 4f, size = ROW - 8f))
        assertEquals(0f, distance(START + 2 * PITCH + 4f, size = ROW - 8f))
    }

    /** The row fits long before the list has moved a whole row, and the list must carry on to the line. */
    @Test
    fun askedAgainPartWayTheListStillMovesAWholeRow() {
        assertEquals(PITCH, scrollInSteps(START + 6 * PITCH), 0.5f)
        assertEquals(-PITCH, scrollInSteps(START - PITCH), 0.5f)
        assertEquals(3 * PITCH, scrollInSteps(START + 8 * PITCH), 0.5f)
        assertEquals(PITCH, scrollInSteps(START + 6 * PITCH + 4f, size = ROW - 8f), 0.5f)
    }

    @Test
    fun anInterruptedScrollSettlesOnALine() {
        assertEquals(-20f, distance(START + 2 * PITCH - 20f))
        assertEquals(20f, distance(START + 2 * PITCH + 20f))
    }

    @Test
    fun theFurthestScrollIsAWholeNumberOfRows() {
        for (rows in 1..50) {
            val end = RowScroll.listEndPadding(VIEWPORT, rows, 32, 36, 80, 16)
            val content = 32 + rows * 80 - 16 + end
            assertEquals(0, (content - VIEWPORT).mod(80), "$rows rows")
            assertEquals(true, end >= 36, "$rows rows keep the ring's inset")
        }
    }

    private companion object {
        const val ROW = 64f
        const val PITCH = 80f
        const val START = 32f
        const val END = 36f
        const val CONTAINER = 539f
        const val VIEWPORT = 539
        const val MAX_STEPS = 200
    }
}
