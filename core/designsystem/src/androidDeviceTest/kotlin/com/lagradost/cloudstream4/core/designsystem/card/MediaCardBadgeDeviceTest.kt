package com.lagradost.cloudstream4.core.designsystem.card

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
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
import com.lagradost.cloudstream4.core.designsystem.primitive.Badge
import com.lagradost.cloudstream4.core.designsystem.primitive.BadgeDefaults
import com.lagradost.cloudstream4.core.designsystem.primitive.BadgeGroup
import com.lagradost.cloudstream4.core.devicetest.assertScreenshot
import com.lagradost.cloudstream4.core.theme.CloudStreamDimens
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `BUG-62` (a): a card's badges in the longest translation the sweep found, on the device that measured them.
 *
 * Every string here is a real translation of a key the app already has, with the width `BUG-62` measured for it on
 * `Television_720p` against the 72dp a `PortraitMedium` card allows and the 88dp a `PortraitLarge` does. Before the fix
 * all three cases drew the same thing: a label cut mid-glyph by the card's clip, with nothing on screen to say it had
 * been cut, and in the pair's case a second badge measured at zero width and absent altogether.
 *
 * The badge's own box is intrinsic, so what is asserted is what the card allows it: a label that ellipsises, a pair
 * that stacks, and a leading badge that stops before the trailing one.
 */
@RunWith(AndroidJUnit4::class)
class MediaCardBadgeDeviceTest {

    @get:Rule
    val rule = createComposeRule()

    /**
     * The widest a badge's **label** can be on a card of this rung: the card, less
     * `MediaCardDefaults.ContentPadding` on both sides (which is what `MediaCard` gives the badge row), less
     * `BadgeDefaults.HorizontalPadding` on both sides (which is the badge's own inset around its label).
     *
     * The label is what these cases measure, because a `Badge`'s box is a `Box` and carries no semantics of its
     * own: `onNodeWithText` finds the `BasicText` inside it.
     */
    private fun labelBox(card: Dp): Dp =
        card - MediaCardDefaults.ContentPadding * SIDES - BadgeDefaults.HorizontalPadding * SIDES

    private fun show(card: Dp, slots: MediaCardSlots) = rule.setContent {
        CloudStreamTheme {
            Box(
                Modifier
                    .size(width = SHOT_WIDTH.dp, height = SHOT_HEIGHT.dp)
                    .background(CloudStreamTheme.colors.bg)
                    .testTag(SHOT)
                    .padding(CloudStreamDimens.FocusBandInset),
                contentAlignment = Alignment.CenterStart,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(CloudStreamDimens.ChipRowSpacing)) {
                    PosterCard(
                        rung = if (card == MEDIUM) PosterRung.Medium else PosterRung.Large,
                        state = MediaCardState(focused = false),
                        modifier = Modifier.testTag(CARD),
                        slots = slots,
                    ) { Box(Modifier.fillMaxSize().background(ART)) }
                }
            }
        }
    }

    private fun bounds(text: String): Rect = rule.onNodeWithText(text).fetchSemanticsNode().boundsInRoot

    private fun cardBounds(): Rect = rule.onNodeWithTag(CARD).fetchSemanticsNode().boundsInRoot

    /** `BasicText` publishes its layout, which is the only honest way to ask whether a label was ellipsised. */
    private fun SemanticsNodeInteraction.layout(): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        val action = checkNotNull(fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action) {
            "the badge's text does not publish a layout"
        }
        action(results)
        return results.first()
    }

    private fun Dp.px(): Float = with(rule.density) { toPx() }

    /**
     * One badge, wider than the card: `quality_sdr` is `SDR` in English and `RANG DINAMIC SIMPLE (SDR)` — 154dp — in
     * Catalan, in a 72dp box.
     */
    @Test
    fun aBadgeTooWideForItsCardEndsInAnEllipsisInsideIt() {
        show(MEDIUM, MediaCardSlots(leadingBadge = { Badge(text = SDR_CA) }))
        rule.waitForIdle()
        rule.assertScreenshot("badge-too-wide-ellipsises", rule.onNodeWithTag(SHOT))

        val layout = rule.onNodeWithText(SDR_CA).layout()
        assertTrue("the label is ellipsised, not cut: $SDR_CA", layout.isLineEllipsized(FIRST_LINE))
        assertEquals("one line, as a badge always is", 1, layout.lineCount)

        val badge = bounds(SDR_CA)
        val card = cardBounds()
        assertEquals(
            "the label takes the box the card allows it",
            labelBox(MEDIUM).px(),
            badge.width,
            ROUNDING,
        )
        assertTrue(
            "the badge stays inside the card: badge ${badge.right}, card ${card.right}",
            badge.right <= card.right,
        )
    }

    /**
     * Two badges on one edge, as `TV Search` 4a draws the quality and `DUB`: 154dp and 141dp of a 72dp box, so they
     * cannot share a line. `app_dubbed_text` is `DUB` in English and the Tamil here.
     */
    @Test
    fun twoBadgesThatCannotShareOneEdgeStack() {
        show(MEDIUM, MediaCardSlots(leadingBadge = { BadgeGroup { Badge(SDR_CA); Badge(DUB_TA) } }))
        rule.waitForIdle()
        rule.assertScreenshot("badge-pair-stacks", rule.onNodeWithTag(SHOT))

        val quality = bounds(SDR_CA)
        val dub = bounds(DUB_TA)
        val card = cardBounds()
        assertTrue("both badges are on screen", quality.width > 0f && dub.width > 0f)
        assertTrue(
            "the second badge is below the first, not beside it: $quality then $dub",
            dub.top >= quality.bottom - TOLERANCE,
        )
        assertTrue(
            "and no further below than one gap and one badge: $quality then $dub",
            dub.top <= quality.bottom + (BADGE_GAP + BadgeDefaults.Height).px(),
        )
        assertEquals("they share the leading edge", quality.left, dub.left, TOLERANCE)
        for ((name, badge) in listOf("quality" to quality, "dub" to dub)) {
            assertTrue("$name stays inside the card: $badge in $card", badge.right <= card.right)
            assertTrue("$name stays inside the card: $badge in $card", badge.bottom <= card.bottom)
        }
    }

    /**
     * A badge on each edge, as `TV Library` 5b draws the type and the rating: `asian_drama_singular` is `ASIAN DRAMA`
     * in English and `DARAAMADA EESHIYAANKA` — 147dp — in Somali, on a card whose box is 88dp.
     */
    @Test
    fun aLongLeadingBadgeStopsBeforeTheTrailingOne() {
        show(
            LARGE,
            MediaCardSlots(
                leadingBadge = { Badge(text = ASIAN_DRAMA_SO) },
                trailingBadge = { Badge(text = RATING) },
            ),
        )
        rule.waitForIdle()
        rule.assertScreenshot("badge-two-edges-do-not-overlap", rule.onNodeWithTag(SHOT))

        val type = bounds(ASIAN_DRAMA_SO)
        val rating = bounds(RATING)
        val card = cardBounds()
        assertTrue("the type is ellipsised", rule.onNodeWithText(ASIAN_DRAMA_SO).layout().isLineEllipsized(FIRST_LINE))
        assertTrue(
            "the type stops a gap before the rating: type ${type.right}, rating ${rating.left}",
            type.right + BADGE_GAP.px() <= rating.left + TOLERANCE,
        )
        assertEquals(
            "the rating is still flush with the card's trailing inset",
            card.right - (MediaCardDefaults.ContentPadding + BadgeDefaults.HorizontalPadding).px(),
            rating.right,
            ROUNDING,
        )
        assertEquals("both badges are on the card's own top band", type.top, rating.top, TOLERANCE)
    }

    private companion object {
        const val SHOT = "shot"
        const val CARD = "card"
        const val SHOT_WIDTH = 240
        const val SHOT_HEIGHT = 200
        const val FIRST_LINE = 0
        const val SIDES = 2

        /** A pixel of rounding: a dp is not a whole number of pixels on every density. */
        const val TOLERANCE = 1f

        /**
         * What four insets of rounding come to. A badge's label sits inside two 4dp insets a side — the card's
         * `ContentPadding` and the badge's own `HorizontalPadding` — and at this density each of the four rounds
         * 5.33px down to 5, so the label is up to 2px wider than the dp arithmetic says.
         */
        const val ROUNDING = 2f

        val ART = Color(0xFF5A6B7C)
        val MEDIUM: Dp = 80.dp
        val LARGE: Dp = 96.dp
        /** `BadgeDefaults.Gap`, named here so the expected geometry is written out rather than read back. */
        val BADGE_GAP: Dp = 4.dp

        /** `quality_sdr` in `ca`, upper-cased as a badge draws it: 154.0dp against English's 21.0dp. */
        const val SDR_CA = "RANG DINAMIC SIMPLE (SDR)"

        /** `app_dubbed_text` in `ta`: 141.2dp against English's 21.8dp. */
        const val DUB_TA = "மொழிபெயர்க்கப்பட்டது"

        /** `asian_drama_singular` in `so`: 147.2dp against English's 75.9dp. */
        const val ASIAN_DRAMA_SO = "DARAAMADA EESHIYAANKA"

        /** What `TV Library` 5b puts on the trailing edge. */
        const val RATING = "8.4"
    }
}
