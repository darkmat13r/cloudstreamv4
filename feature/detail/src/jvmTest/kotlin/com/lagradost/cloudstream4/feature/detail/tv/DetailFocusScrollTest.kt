package com.lagradost.cloudstream4.feature.detail.tv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The three stops boards 7a and 7a-ii draw, which is what `BUG-38` cost when Compose picked its own.
 *
 * Pure, so the rules are pinned without an emulator — the same reason `HomeFocusScrollTest` exists.
 * The layout itself is a device test; this is the arithmetic under it.
 */
class DetailFocusScrollTest {

    /** A show with everything: head 0, cast 1, related 2, episodes header 3. */
    private fun full(index: Int) = DetailFocusScroll.stopFor(index, castIndex = 1, relatedIndex = 2)

    @Test
    fun theHeadBlockOpensThePageAtItsOrigin() {
        // The whole of BUG-38: arriving on this page must not scroll it.
        assertEquals(DetailFocusScroll.Stop(0, 0), full(0))
    }

    @Test
    fun theCastRowDoesNotMoveThePage() {
        // 7b draws a focused cast item with the page unmoved, and 7a sends DOWN from the action row
        // straight into it.
        assertEquals(DetailFocusScroll.Stop(0, 0), full(1))
    }

    @Test
    fun theRecommendationsRowIsBoard7aIi() {
        // The cast item goes to the top, less the 4dp of its own leading spacers that lands the
        // avatars on the board's y72 rather than y76.
        assertEquals(DetailFocusScroll.Stop(1, DetailFocusScroll.CAST_LEAD), full(2))
    }

    @Test
    fun theEpisodeListTakesItsOwnItemBecauseNoBoardDrawsIt() {
        assertEquals(DetailFocusScroll.Stop(3, 0), full(3))
        assertEquals(DetailFocusScroll.Stop(4, 0), full(4))
    }

    @Test
    fun withNoCastTheRecommendationsRowBecomesTheFirstBandAndStaysAtTheOrigin() {
        // There is no cast row to park at the top of the band, so 7a-ii has nothing to show: the
        // page stays where it opened rather than scrolling to a row that is already on screen.
        val stop = DetailFocusScroll.stopFor(1, castIndex = null, relatedIndex = 1)
        assertEquals(DetailFocusScroll.Stop(0, 0), stop)
    }

    @Test
    fun withNeitherRailTheEpisodeListStillTakesItsOwnItem() {
        val stop = DetailFocusScroll.stopFor(2, castIndex = null, relatedIndex = null)
        assertEquals(DetailFocusScroll.Stop(2, 0), stop)
    }

    /** 540dp tall, the band's 28dp inset, a 48dp episode row. */
    private fun rowScroll(offset: Float) =
        DetailFocusScroll.rowScrollDistance(offset, size = 48f, containerSize = 540f, inset = 28f)

    @Test
    fun aRowAlreadyInsideTheBandDoesNotScroll() {
        assertEquals(0f, rowScroll(28f))
        assertEquals(0f, rowScroll(300f))
        assertEquals(0f, rowScroll(464f))
    }

    @Test
    fun aRowPastTheFootOfTheBandScrollsJustInside() {
        // 465 + 48 = 513, which is 1 past the band's foot at 512.
        assertEquals(1f, rowScroll(465f))
        assertEquals(48f, rowScroll(512f))
    }

    @Test
    fun aRowAboveTheBandScrollsBackDown() {
        assertEquals(-28f, rowScroll(0f))
        assertEquals(-8f, rowScroll(20f))
    }

    @Test
    fun aRowFarBelowTheFoldStillScrolls() {
        // The regression this rule exists for: refusing to scroll at all left DOWN walking to the last
        // row that happened to be on screen — measured as "Down never reached Chapter 40 in 60
        // presses, stopped on Chapter 03".
        assertTrue(rowScroll(900f) > 0f)
    }

    @Test
    fun thePrimaryActionIsInsideTheBandSoArrivingNeverScrolls() {
        // BUG-38's property, as the page's own spec sees it: Resume sits at 250..282dp of a 540dp
        // viewport, so the rule that replaced Compose's pivot returns zero and the page opens unmoved.
        assertEquals(0f, DetailFocusScroll.rowScrollDistance(250f, 32f, 540f, 28f))
    }

    @Test
    fun theCastLeadIsTheLadderItClaimsToBe() {
        // SectionGap 32 + the rail header 28 + LiftedRowClearance 16 = 76 before the avatars, and
        // 7a-ii draws them at 72. If any of those three move, this number is wrong.
        val beforeAvatars = 32 + 28 + 16
        val boardAvatarTop = 72
        assertEquals(beforeAvatars - boardAvatarTop, DetailFocusScroll.CAST_LEAD)
    }
}
