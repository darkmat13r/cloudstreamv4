package com.lagradost.cloudstream4.core.designsystem.rows

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lagradost.cloudstream4.core.designsystem.panel.SidePanel
import com.lagradost.cloudstream4.core.devicetest.assertScreenshot
import com.lagradost.cloudstream4.core.theme.CloudStreamDimens
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `BUG-62` (c): a side panel's row titles in the longest translation the sweep found, on the device that measured them.
 *
 * A `CloudStreamDimens.SidePanelWidth` panel leaves a row about 212dp of title. `sort_release_date_old` measures
 * 309.5dp in Tamil against 157dp in English, and 8 of a panel's 21 row titles are over in some language
 * (`BUG-62`, measured on `Television_720p` in all 61 locales). On one line that read `Release Date (Old to ...`, with
 * the direction — the only thing that tells the row from the one above it — cut off.
 *
 * What is asserted is the table in `ListRowTextStack.kt`: a title that fits is a 48dp row and is **unchanged**, a title
 * that does not takes a second line and its row is a whole 16dp step taller, whether or not it has a subtitle.
 */
@RunWith(AndroidJUnit4::class)
class ListRowSecondLineDeviceTest {

    @get:Rule
    val rule = createComposeRule()

    private fun show(title: String, subtitle: String? = null, trailing: String? = null) = rule.setContent {
        CloudStreamTheme {
            Box(
                Modifier
                    .size(width = SHOT_WIDTH.dp, height = SHOT_HEIGHT.dp)
                    .background(CloudStreamTheme.colors.bg)
                    .testTag(SHOT),
            ) {
                SidePanel(
                    header = { PanelHeader() },
                    commit = {
                        ListRow(
                            state = ListRowState(title = DONE_TA),
                            onClick = {},
                            modifier = Modifier.fillMaxWidth(),
                        )
                    },
                ) {
                    ListRow(
                        state = ListRowState(title = title, subtitle = subtitle, selected = true),
                        onClick = {},
                        modifier = Modifier.fillMaxWidth().testTag(ROW),
                        trailing = trailing?.let {
                            { BasicText(text = it, style = CloudStreamTheme.typography.labelMono) }
                        },
                    )
                    ListRow(
                        state = ListRowState(title = SHORT_TITLE),
                        onClick = {},
                        modifier = Modifier.fillMaxWidth().testTag(NEXT_ROW),
                    )
                }
            }
        }
    }

    /** Stands in for a `RailHeader`, which lives in this module but is not what is being measured. */
    @Composable
    private fun PanelHeader() {
        BasicText(
            text = PANEL_TITLE,
            style = CloudStreamTheme.typography.railTitle,
        )
    }

    private fun tagBounds(tag: String): Rect = rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

    /** `BasicText` publishes its layout, which is the only honest way to ask how many lines a title took. */
    private fun SemanticsNodeInteraction.layout(): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        val action = checkNotNull(fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action) {
            "the title does not publish a layout"
        }
        action(results)
        return results.first()
    }

    private fun title(text: String): TextLayoutResult = rule.onNodeWithText(text, useUnmergedTree = true).layout()

    private fun Dp.px(): Float = with(rule.density) { toPx() }

    /** The row every panel has drawn since the design: one line, 48dp, and this fix must not move it. */
    @Test
    fun aTitleThatFitsIsStillAFortyEightDpRow() {
        show(SHORT_TITLE_EN)
        rule.waitForIdle()
        rule.assertScreenshot("list-row-one-line-unchanged", rule.onNodeWithTag(SHOT))

        assertEquals("one line", 1, title(SHORT_TITLE_EN).lineCount)
        assertEquals(
            "the row is one `ListRowHeight`",
            CloudStreamDimens.ListRowHeight.px(),
            tagBounds(ROW).height,
            TOLERANCE,
        )
    }

    /**
     * A row with a readout in its trailing slot is **still 48dp**, which is the case a density where a dp is not a
     * whole number of pixels breaks.
     *
     * On this device the row's 8dp padding rounds to 11px a side and its 32dp content box to 43px, so such a row
     * measures 65px of content against a 64px row. The first version of `steppedRowHeight` read that 1px as "the
     * content needs another step" and made every row with a trailing slot 64dp: a downloads list moved 12dp, a
     * suggestion column lost its last row, and four device tests in two other modules failed. The JVM harness runs at
     * density 1.0 and cannot see it.
     */
    @Test
    fun aRowWithATrailingReadoutIsStillAFortyEightDpRow() {
        show(SHORT_TITLE_EN, trailing = READOUT)
        rule.waitForIdle()
        assertEquals("one line", 1, title(SHORT_TITLE_EN).lineCount)
        assertEquals(
            "a readout does not make the row a step taller",
            CloudStreamDimens.ListRowHeight.px(),
            tagBounds(ROW).height,
            TOLERANCE,
        )
    }

    /** 309.5dp of Tamil in 212dp of row: two lines, and the row is four 16dp steps instead of three. */
    @Test
    fun aTitleTooWideForThePanelTakesASecondLineAndTheRowGrowsOneStep() {
        show(RELEASE_TA)
        rule.waitForIdle()
        rule.assertScreenshot("list-row-two-line-title", rule.onNodeWithTag(SHOT))

        val layout = title(RELEASE_TA)
        assertEquals("the title takes its second line", 2, layout.lineCount)
        assertFalse("and does not need a third", layout.isLineEllipsized(SECOND_LINE))

        // 64dp: `ListRowHeight` plus one whole `ListRowDefaults.HeightStep`, which is also four steps in all.
        val row = tagBounds(ROW)
        assertEquals("the row is one step taller", GROWN_ROW.px(), row.height, TOLERANCE)
        val next = tagBounds(NEXT_ROW)
        assertEquals(
            "the row after it follows at the panel's own gap",
            row.bottom + SIDE_PANEL_ROW_GAP.px(),
            next.top,
            TOLERANCE,
        )
        assertEquals(
            "and is itself unchanged",
            CloudStreamDimens.ListRowHeight.px(),
            next.height,
            TOLERANCE,
        )
    }

    /**
     * The disabled criterion of `TV Library` 5d: the same long title with the reason under it. Also 64dp.
     *
     * A row with a subtitle draws its title in `label`, which is smaller than the `body` a lone title gets, so the
     * string has to be the longest one to be over at that size too: `sort_release_date_old` is 257.7dp in
     * Tamil as a `label` against the roughly 220dp a panel row leaves. `sort_updated_new` is 216.3dp, which **fits**,
     * and this case was written with it and passed for the wrong reason until the device said so.
     */
    @Test
    fun aTwoLineTitleOverASubtitleIsTheSameGrownRow() {
        show(RELEASE_TA, NOT_KEPT_TA)
        rule.waitForIdle()
        rule.assertScreenshot("list-row-two-line-title-with-subtitle", rule.onNodeWithTag(SHOT))

        assertEquals("the title takes its second line", 2, title(RELEASE_TA).lineCount)
        assertEquals("the subtitle keeps to one", 1, title(NOT_KEPT_TA).lineCount)
        assertEquals("the row is the same grown row", GROWN_ROW.px(), tagBounds(ROW).height, TOLERANCE)
        assertTrue(
            "the subtitle is still below the title",
            rule.onNodeWithText(NOT_KEPT_TA, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.top >
                rule.onNodeWithText(RELEASE_TA, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.top,
        )
    }

    private companion object {
        const val SHOT = "shot"
        const val ROW = "row"
        const val NEXT_ROW = "next-row"
        const val SHOT_WIDTH = 280
        const val SHOT_HEIGHT = 360
        const val SECOND_LINE = 1

        /** A pixel of rounding: a dp is not a whole number of pixels on every density. */
        const val TOLERANCE = 1f

        /** One `ListRowDefaults.HeightStep` above `CloudStreamDimens.ListRowHeight`. */
        val GROWN_ROW: Dp = CloudStreamDimens.ListRowHeight + ListRowDefaults.HeightStep

        /** `SidePanelDefaults.RowGap`, which is not public to this test's eye but is this subtraction. */
        val SIDE_PANEL_ROW_GAP: Dp = CloudStreamDimens.SidePanelRowPitch - CloudStreamDimens.ListRowHeight

        /**
         * `sort_release_date_old` in `ta`. 309.5dp in the `body` a lone title is drawn in and 257.7dp in the `label`
         * a title over a subtitle gets, against English's 157.0dp and 132.2dp.
         */
        const val RELEASE_TA = "வெளியீட்டு தேதி (பழையதிலிருந்து புதியது)"

        /** `library_sort_not_kept` in `ta`, the reason 5d puts under a criterion it cannot sort by. */
        const val NOT_KEPT_TA = "Simkl இதை வைத்திருக்காது"

        /** `sort_by` in `ta`, the panel's own header. */
        const val PANEL_TITLE = "வரிசைப்படுத்தவும்"

        /** `setup_done` in `ta`, the commit row every panel with a bottom row has. */
        const val DONE_TA = "முடிந்தது"

        /** `sort_alphabetical_a`'s English, which fits one line and must stay a 48dp row. */
        const val SHORT_TITLE_EN = "Alphabetical (A to Z)"

        /** A second row, so the pitch after a grown row is measured rather than argued. */
        const val SHORT_TITLE = "Rating"

        /** What `TV Episodes Downloads` 6c puts in a row's trailing slot, and what it is 43px of at this density. */
        const val READOUT = "4.2 MB/S"
    }
}
