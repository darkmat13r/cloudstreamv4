package com.lagradost.cloudstream4.feature.downloads.tv

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * How the list moves under a remote. 6c draws focus with no scroll and every row whole, so a row that fits stays where it
 * is, and a row that does not moves the list by whole rows: the default TV pivot cut the row against the chips.
 *
 * All sizes are pixels. Every scroll position is then a whole number of pitches, which [listEndPadding] keeps true at the
 * end of the list as well.
 */
internal object DownloadsRowScroll {

    /**
     * Compose asks again on every step of the scroll and takes the answer as what is left to go, so this names a fixed line
     * rather than a distance from here. A distance rounded up to a whole row answers 0 as soon as the row first fits, and
     * the list stops part of the way.
     *
     * The focused thing is measured at its centre, because a row's verb is shorter than its row and centred on it.
     *
     * @param offset where the focused thing is now, from the start of the viewport.
     * @param size its height.
     * @param container the viewport's height.
     * @param start the inset above the first row.
     * @param end the inset below the last, which holds a ring.
     * @param row one row's height.
     * @param pitch one row and one gap.
     */
    fun distance(offset: Float, size: Float, container: Float, start: Float, end: Float, row: Float, pitch: Float): Float {
        val centre = offset + size / 2
        val first = start + row / 2
        val last = first + floor((container - end - start - row) / pitch).coerceAtLeast(0f) * pitch
        val line = when {
            centre < first -> first
            centre > last -> last
            // Only a scroll that was interrupted leaves a row between the lines.
            else -> first + ((centre - first) / pitch).roundToInt() * pitch
        }
        val distance = centre - line
        return if (abs(distance) <= TOLERANCE) 0f else distance
    }

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
    private val row: Float,
    private val pitch: Float,
) : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float =
        DownloadsRowScroll.distance(offset, size, containerSize, start, end, row, pitch)
}
