package com.lagradost.cloudstream4.feature.downloads.tv

import kotlin.test.Test
import kotlin.test.assertEquals

class DownloadsRowScrollTest {

    private fun distance(offset: Float, size: Float = ROW) =
        DownloadsRowScroll.distance(offset, size, CONTAINER, START, END, PITCH)

    @Test
    fun aRowThatFitsDoesNotMoveTheList() {
        assertEquals(0f, distance(START))
        assertEquals(0f, distance(START + 5 * PITCH))
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
    }

    @Test
    fun theFurthestScrollIsAWholeNumberOfRows() {
        for (rows in 1..50) {
            val end = DownloadsRowScroll.listEndPadding(VIEWPORT, rows, 32, 36, 80, 16)
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
    }
}
