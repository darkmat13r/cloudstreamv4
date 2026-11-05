package com.lagradost.cloudstream4.feature.downloads.tv

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import kotlin.math.ceil

/**
 * How the list moves under a remote. 6c draws focus with no scroll and every row whole, so a row that fits stays where it
 * is, and a row that does not moves the list by whole rows: the default TV pivot cut the row against the chips.
 *
 * All sizes are pixels. Every scroll position is then a whole number of pitches, which [listEndPadding] keeps true at the
 * end of the list as well.
 */
internal object DownloadsRowScroll {

    /**
     * @param offset where the focused thing is now, from the start of the viewport. A row's verb is a little inside its row.
     * @param size its height.
     * @param container the viewport's height.
     * @param start the inset above the first row.
     * @param end the inset below the last, which holds a ring.
     * @param pitch one row and one gap.
     */
    fun distance(offset: Float, size: Float, container: Float, start: Float, end: Float, pitch: Float): Float {
        val above = start - offset
        val below = offset + size - (container - end)
        return when {
            above > TOLERANCE -> -wholeRows(above, pitch) * pitch
            below > TOLERANCE -> wholeRows(below, pitch) * pitch
            else -> 0f
        }
    }

    private fun wholeRows(distance: Float, pitch: Float): Float = ceil((distance - TOLERANCE) / pitch)

    /**
     * The padding below the last row that makes the furthest scroll a whole number of pitches too. Otherwise the list stops
     * wherever its content ends, with a part of a row against the chips.
     */
    fun listEndPadding(viewport: Int, rows: Int, start: Int, end: Int, pitch: Int, gap: Int): Int {
        val content = start + rows * pitch - gap + end
        return end + (viewport - content).mod(pitch)
    }

    private const val TOLERANCE = 0.5f
}

/** [DownloadsRowScroll.distance], for the list. */
@OptIn(ExperimentalFoundationApi::class)
internal class DownloadsRowScrollSpec(
    private val start: Float,
    private val end: Float,
    private val pitch: Float,
) : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float =
        DownloadsRowScroll.distance(offset, size, containerSize, start, end, pitch)
}
