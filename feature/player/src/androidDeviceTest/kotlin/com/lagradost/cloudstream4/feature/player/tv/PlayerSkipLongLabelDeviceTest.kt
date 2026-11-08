package com.lagradost.cloudstream4.feature.player.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lagradost.cloudstream4.core.designsystem.target.TextButtonDefaults
import com.lagradost.cloudstream4.core.devicetest.assertScreenshot
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme
import com.lagradost.cloudstream4.feature.player.HudSkip
import com.lagradost.cloudstream4.feature.player.PlayerHudState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.max

/**
 * `BUG-62` (b): the skip button grows to its label, with the board's width as its floor.
 *
 * `PlayerHudDefaults.SkipWidth` is 140dp, which holds 108dp of label, and **none** of the nine skip labels fits that in
 * every language: `skip_type_format` with `skip_type_recap` measures 262.2dp in Tamil, 165dp in German and 144dp
 * in Greek against 55.6dp in English (`BUG-62`, measured on `Television_720p` in all 61 locales). At a fixed 140dp the
 * one control the board allows on screen while the HUD is hidden said something other than what pressing it does.
 *
 * The label is passed in, as the real screen passes it: `HudSkip` carries an already-formatted string.
 */
@RunWith(AndroidJUnit4::class)
class PlayerSkipLongLabelDeviceTest {

    @get:Rule
    val rule = createComposeRule()

    private fun show(label: String) {
        rule.setContent {
            CloudStreamTheme {
                // The HUD hidden, which is the state 7d draws the skip control in, over a flat stand-in for the video.
                Box(Modifier.fillMaxSize().background(FRAME)) {
                    PlayerHudTv(
                        state = PlayerHudState(title = TITLE, playing = true, skip = HudSkip(label)),
                        shown = false,
                        readout = null,
                        clock = CLOCK,
                        shownSeekMs = SEEK_MS,
                        onAction = {},
                    )
                }
            }
        }
        rule.waitForIdle()
    }

    private fun bounds(text: String): Rect = rule.onNodeWithText(text).fetchSemanticsNode().boundsInRoot

    /** `BasicText` publishes its layout, which is the only honest way to ask whether a label was cut. */
    private fun SemanticsNodeInteraction.layout(): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        val action = checkNotNull(fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action) {
            "the label does not publish a layout"
        }
        action(results)
        return results.first()
    }

    private fun Dp.px(): Float = with(rule.density) { toPx() }

    /**
     * The rule, which does not depend on any one translation staying long: the button is its label plus its padding, or
     * the board's width, whichever is more — and the label is never cut.
     */
    private fun assertGrowsToItsLabel(label: String) {
        val button = bounds(label)
        val text = rule.onNodeWithText(label, useUnmergedTree = true).layout()
        assertFalse("the label is not cut: $label", text.isLineEllipsized(FIRST_LINE))
        assertEquals(
            "the button is its label plus padding, floored at the board's width",
            max(
                PlayerHudDefaults.SkipWidth.px(),
                text.size.width + TextButtonDefaults.HorizontalPadding.px() * SIDES,
            ),
            button.width,
            TOLERANCE,
        )
        val screen = rule.onRoot().fetchSemanticsNode().boundsInRoot
        assertTrue(
            "and it is still inside the content's end: button ${button.right}, screen ${screen.right}",
            button.right <= screen.right - PlayerHudDefaults.EndInset.px() + TOLERANCE,
        )
    }

    /** 262.2dp of Tamil in a 140dp button. */
    @Test
    fun theLongestSkipLabelFitsBecauseTheButtonGrows() {
        show(RECAP_TA)
        rule.assertScreenshot("player-skip-long-label")
        assertGrowsToItsLabel(RECAP_TA)
    }

    /** And English, which fits twice over, still draws the board's 140dp. */
    @Test
    fun aShortSkipLabelStillDrawsTheBoardsWidth() {
        show(RECAP_EN)
        rule.assertScreenshot("player-skip-short-label")
        assertGrowsToItsLabel(RECAP_EN)
        assertEquals(
            "a label that fits leaves the button exactly as the board draws it",
            PlayerHudDefaults.SkipWidth.px(),
            bounds(RECAP_EN).width,
            TOLERANCE,
        )
    }

    private companion object {
        const val TITLE = "Arcane"
        const val CLOCK = "21:47"
        const val SEEK_MS = 10_000L
        const val FIRST_LINE = 0
        const val SIDES = 2

        /** A pixel of rounding: a dp is not a whole number of pixels on every density. */
        const val TOLERANCE = 1f

        val FRAME = Color(0xFF3A3A3A)

        /** `skip_type_format` with `skip_type_recap` in `ta`: 262.2dp against English's 55.6dp. */
        const val RECAP_TA = "மறுபரிசீலனை செய்யுங்கள் ஐத் தவிர்க்கவும்"

        /** The same label in English. */
        const val RECAP_EN = "Skip Recap"
    }
}
