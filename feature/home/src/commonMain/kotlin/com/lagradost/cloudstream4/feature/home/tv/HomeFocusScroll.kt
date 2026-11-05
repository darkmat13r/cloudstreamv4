package com.lagradost.cloudstream4.feature.home.tv

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec

/**
 * Where a focused card sits on screen. Pure, so its rules are tested without a device.
 *
 * Compose's own rule moves a focused card just far enough to be visible. That is wrong here both ways. Going up it leaves
 * the card at the top edge with its rail's title cut off and its lift and ring clipped, and going across it leaves the
 * card against the edge of the screen, outside the focus band (`TV Home` 3a).
 */
internal object HomeFocusScroll {

    /**
     * Down the page: whether a rail already sits inside the band. One that does stays put, and one that does not is
     * scrolled to, which lands its title at the top of the band where 3d draws the first rail.
     *
     * This is decided once when the rail takes focus and not in a `BringIntoViewSpec`, which is asked again on every frame
     * of the scroll: measured, a spec that answered "pin it" stopped the scroll as soon as the rail came inside the band,
     * 269dp short of the top.
     *
     * @param top where the rail starts, from the top of the viewport.
     * @param size the rail's height, title included.
     * @param container the viewport's height.
     * @param band the focus band's inset, at the top and at the foot.
     */
    fun railFits(top: Float, size: Float, container: Float, band: Float): Boolean =
        top >= band && top + size <= container - band

    /**
     * Across a rail. The least movement that keeps the card between the insets, which is what the board's cards do: a
     * focused card on a rail that fits does not move at all.
     *
     * @param offset where the card is now, from the start of the viewport.
     * @param size the card's width.
     * @param container the viewport's width.
     * @param start the inset before the card.
     * @param end the inset after the card.
     */
    fun across(offset: Float, size: Float, container: Float, start: Float, end: Float): Float = when {
        offset < start -> offset - start
        offset + size > container - end -> offset + size - (container - end)
        else -> 0f
    }
}

/** The page's list leaves it to the rail taking focus: see [HomeFocusScroll.railFits]. */
@OptIn(ExperimentalFoundationApi::class)
internal object HomeNoScrollSpec : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = 0f
}

/** [HomeFocusScroll.across], for one rail. */
@OptIn(ExperimentalFoundationApi::class)
internal class HomeAcrossSpec(private val start: Float, private val end: Float) : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float =
        HomeFocusScroll.across(offset, size, containerSize, start, end)
}
