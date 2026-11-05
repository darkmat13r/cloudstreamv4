package com.lagradost.cloudstream4.feature.detail.tv

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec

/**
 * Where the page sits when each band takes focus. Pure, so the stops are tested without a device.
 *
 * ## Why the page needs this at all (`BUG-38`)
 *
 * Compose's default spec moves a focused element just far enough to satisfy its own pivot, which on
 * this page landed Resume's top and a focused poster's top both at 0.3 of the viewport. The page
 * therefore **arrived already scrolled**: the chrome row was laid out at -76..-12px in a 720px root
 * with none of it surviving the column's clip, so the viewer reached the title with no visible way
 * back. The boards do not work that way — they draw three fixed stops, which is what [stopFor] is.
 *
 * `HomeFocusScroll` is the precedent, with one difference that matters. Home's page refuses to scroll
 * at all (`HomeNoScrollSpec`) because every band there is a rail that handles itself. This page cannot:
 * its episode list is part of the same column, is arbitrarily long, and has no board. So the page's
 * spec is the band rule in [rowScrollDistance] — which is a **no-op for anything already inside the
 * band**, and therefore leaves the head block and the cast row exactly where their boards put them —
 * and the three drawn stops are applied on top of it by [stopFor] when a band takes focus.
 *
 * Refusing to scroll outright was tried and is what `DOWN never reached Chapter 40 in 60 presses,
 * stopped on Chapter 03` measured: with no rule at all the episode list never moved.
 */
internal object DetailFocusScroll {

    /** 7a and 7b: the head block and the cast row are both drawn with the page at its origin. */
    const val PAGE_TOP = 0

    /**
     * The stop a band takes focus at: which lazy item goes to the top, and by how much past it.
     *
     * The head block and the cast row are the same stop because both boards draw a focused cast item
     * with the page unmoved: 7a's focus strip sends DOWN from the action row into "the cast item whose
     * x overlaps it", and 7b draws exactly that at the page's origin. Anything below the cast is
     * 7a-ii, which is this page scrolled until the cast row is whole at the top of the band — so the
     * cast item goes to the top and then [CAST_LEAD] of its own leading spacers is scrolled away,
     * which is what lands the avatars on the board's y72 rather than y76.
     *
     * The episode list has no board — it is this page's documented departure, because the boards put
     * episodes on a panel of their own — so it takes the only stop that can be derived rather than
     * read: its own item at the top of the band.
     *
     * @param index the lazy item that is taking focus.
     * @param castIndex the cast row's item index, or null when the title has no cast.
     * @param relatedIndex the recommendations row's item index, or null when there are none.
     */
    fun stopFor(index: Int, castIndex: Int?, relatedIndex: Int?): Stop = when (index) {
        HEAD, castIndex -> Stop(PAGE_TOP, 0)
        // 7a-ii is the cast row at the top of the band. With no cast row there is no 7a-ii to show,
        // and the rail is already on screen, so the page stays where it opened.
        relatedIndex -> if (castIndex == null) Stop(PAGE_TOP, 0) else Stop(castIndex, CAST_LEAD)
        else -> Stop(index, 0)
    }

    /** The head block is always item 0. */
    const val HEAD = 0

    /**
     * Down the episode list: the least scroll that brings a row fully inside the band, and none at all
     * for a row already inside it.
     *
     * The three stops above are fixed because their boards draw them. The episode list has no board, is
     * arbitrarily long, and is an ordinary vertical list, so "scroll just enough" is right for it — and
     * it has to come from somewhere, because [DetailNoScrollSpec] turns off the scrolling Compose would
     * otherwise do. Without this, DOWN walked to the last row that happened to be on screen and stopped:
     * measured as `Down never reached Chapter 40 in 60 presses, stopped on Chapter 03`.
     *
     * This is `HomeFocusScroll.across`'s rule turned through ninety degrees, with one inset because the
     * band's top and foot are the same.
     *
     * @param offset where the row starts, from the top of the viewport.
     * @param size the row's height.
     * @param containerSize the viewport's height.
     * @param inset the focus band's inset, which the row must clear at either edge.
     */
    fun rowScrollDistance(offset: Float, size: Float, containerSize: Float, inset: Float): Float = when {
        offset < inset -> offset - inset
        offset + size > containerSize - inset -> offset + size - (containerSize - inset)
        else -> 0f
    }

    /**
     * How much of the cast item's leading spacers is scrolled past at the 7a-ii stop, in dp.
     *
     * The item opens with `SectionGap` (32) + the rail header (28) + `LiftedRowClearance` (16) = 76
     * before its avatars, and 7a-ii draws them at 72.
     */
    const val CAST_LEAD = 4

    /** A `scrollToItem` argument pair. [offsetDp] is in dp; the caller converts it. */
    data class Stop(val index: Int, val offsetDp: Int)
}

/**
 * The page's rule: [DetailFocusScroll.rowScrollDistance], the least scroll that keeps a focused thing
 * inside the band.
 *
 * It replaces the pivot Compose would pick, which is what opened the page part-scrolled in `BUG-38`.
 * Because it does nothing for anything already inside the band, the head block and the cast row sit
 * where their boards draw them and the explicit stops in [DetailFocusScroll.stopFor] do the rest.
 *
 * One spec per scrollable: `LocalBringIntoViewSpec` is read where the **scrollable** is composed, not
 * where the focusable is, so providing a second one around the episode items does nothing — measured,
 * and it is why this is one rule for the whole column rather than two.
 */
@OptIn(ExperimentalFoundationApi::class)
internal class DetailBandScrollSpec(private val inset: Float) : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float =
        DetailFocusScroll.rowScrollDistance(offset, size, containerSize, inset)
}
