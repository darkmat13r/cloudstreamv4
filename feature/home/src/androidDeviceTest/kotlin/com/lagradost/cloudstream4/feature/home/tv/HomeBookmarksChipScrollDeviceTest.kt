package com.lagradost.cloudstream4.feature.home.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lagradost.cloudstream4.core.devicetest.Dpad
import com.lagradost.cloudstream4.core.devicetest.DpadDriver
import com.lagradost.cloudstream4.core.devicetest.assertScreenshot
import com.lagradost.cloudstream4.core.devicetest.focusedLabel
import com.lagradost.cloudstream4.core.strings.generated.resources.Res
import com.lagradost.cloudstream4.core.strings.generated.resources.error_bookmarks_text
import com.lagradost.cloudstream4.core.strings.generated.resources.type_completed
import com.lagradost.cloudstream4.core.strings.generated.resources.type_dropped
import com.lagradost.cloudstream4.core.strings.generated.resources.type_on_hold
import com.lagradost.cloudstream4.core.strings.generated.resources.type_plan_to_watch
import com.lagradost.cloudstream4.core.strings.generated.resources.type_watching
import com.lagradost.cloudstream4.core.theme.CloudStreamDimens
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme
import com.lagradost.cloudstream4.feature.home.HomeBookmark
import com.lagradost.cloudstream4.feature.home.HomeBookmarks
import com.lagradost.cloudstream4.feature.home.HomeItem
import com.lagradost.cloudstream4.feature.home.HomeWatchStatus
import org.jetbrains.compose.resources.stringResource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

/**
 * `BUG-62` (d): Home's bookmarks chip row scrolls, so the last status is reachable in every language.
 *
 * The header is the rail's title and one chip per status that holds a title, on one 48dp row from
 * `CloudStreamDimens.ContentOriginX` to `ContentEndX` — 832dp of content. In English the title and five chips measure
 * 592dp of it; in Tamil they measure 908dp (`BUG-62`, measured on `Television_720p` in all 61 locales). A `Row` takes
 * that overflow out of whatever is left when it reaches its last child, so the fifth chip was drawn as a sliver
 * against the screen's edge with its word ellipsised away — a focus stop nobody could read. Library's tab row already
 * scrolls for the same reason, and this is the same three modifiers.
 *
 * **The screen is drawn in Tamil**, not handed long strings: the chips' words come from `:core:strings` inside the
 * composable. compose-resources resolves through `Locale.current`, which on Android is the default locale, so this case
 * sets it and puts it back. [theChipsAreReallyInTamil] guards the rest: if the locale ever stops reaching the
 * resources, the chips fall back to English, they all fit, and the cases below would pass saying nothing.
 */
@RunWith(AndroidJUnit4::class)
class HomeBookmarksChipScrollDeviceTest {

    @get:Rule
    val rule = createComposeRule()

    private val dpad = DpadDriver(readFocus = { rule.focusedLabel() })
    private val before: Locale = Locale.getDefault()

    /** What the header resolved, read from the composition so the case never second-guesses the resources. */
    private var words: List<String>? = null
    private var railTitle: String? = null

    @Before
    fun drawInTamil() = Locale.setDefault(Locale.forLanguageTag(TAMIL))

    @After
    fun putTheLocaleBack() = Locale.setDefault(before)

    private fun show() {
        rule.setContent {
            CloudStreamTheme {
                ReadWords()
                // The shell's nav rail, which is what leaves the chips' row the 832dp of content the board gives it:
                // x100 to x932 on a 960dp screen. Without it this case would measure a row 76dp wider than the screen has.
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(CloudStreamTheme.colors.bg)
                        .padding(start = CloudStreamDimens.NavRailWidth),
                ) {
                    HomeBookmarksTv(
                        bookmarks = BOARD,
                        focusTarget = null,
                        initialCard = 0,
                        initialCardOffset = 0,
                        onFocusTaken = {},
                        onOpen = { _, _ -> },
                        onFilter = {},
                        stops = HomeStops(),
                        artwork = { Box(Modifier.fillMaxSize().background(ART)) },
                        progress = emptyMap(),
                        dimmed = false,
                    )
                }
            }
        }
        rule.waitForIdle()
    }

    /**
     * Reads the words the header will draw, in chip order.
     *
     * It writes to plain fields rather than to state, which is safe exactly because nothing observes them: no
     * recomposition can be triggered by them, and the test reads them after the composition has settled.
     */
    @Composable
    private fun ReadWords() {
        railTitle = stringResource(Res.string.error_bookmarks_text)
        words = listOf(
            stringResource(Res.string.type_watching),
            stringResource(Res.string.type_completed),
            stringResource(Res.string.type_on_hold),
            stringResource(Res.string.type_dropped),
            stringResource(Res.string.type_plan_to_watch),
        )
    }

    /** The chips, in the order `HomeBookmarks.statuses` puts them, which is `HomeWatchStatus`'s own. */
    private fun chips(): List<String> {
        val resolved = checkNotNull(words) { "the header has not been composed" }
        return BOARD.statuses.map { status ->
            when (status) {
                HomeWatchStatus.Watching -> resolved[0]
                HomeWatchStatus.Completed -> resolved[1]
                HomeWatchStatus.OnHold -> resolved[2]
                HomeWatchStatus.Dropped -> resolved[3]
                HomeWatchStatus.PlanToWatch -> resolved[4]
            }
        }
    }

    /** The node's own box, which is not cut by what is scrolled out of view. */
    private fun unclipped(text: String): Rect {
        val node = rule.onNodeWithText(text).fetchSemanticsNode()
        return Rect(
            node.positionInRoot.x,
            node.positionInRoot.y,
            node.positionInRoot.x + node.size.width,
            node.positionInRoot.y + node.size.height,
        )
    }

    /** The same box as the screen actually shows it: cut by every ancestor, the scroll's viewport included. */
    private fun onScreen(text: String): Rect = rule.onNodeWithText(text).fetchSemanticsNode().boundsInRoot

    private fun focusedChip(): String = checkNotNull(rule.focusedLabel()) { "nothing holds focus" }

    /**
     * Whether a chip's label is cut.
     *
     * This is the assertion that catches the row that does not scroll, and the first version of this case missed it:
     * a `Row` measures its children in order and the ones past its end are measured at **what is left**, which for
     * the fourth and fifth chips is nothing. So they stay inside the window and inside their own boxes — and say
     * nothing, because the label inside each is ellipsised away to a pill the width of its own padding. Asking
     * whether a chip is on screen is not the same as asking whether it can be read.
     */
    private fun labelIsCut(text: String): Boolean {
        val results = mutableListOf<TextLayoutResult>()
        val node = rule.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode()
        val action = checkNotNull(node.config[SemanticsActions.GetTextLayoutResult].action) {
            "the chip's label does not publish a layout"
        }
        action(results)
        return results.first().isLineEllipsized(FIRST_LINE)
    }

    /** The guard: without it the case below could pass on English chips that always fitted. */
    @Test
    fun theChipsAreReallyInTamil() {
        show()
        assertNotEquals("the forced locale did not reach the resources", WATCHING_EN, chips().first())
        assertNotEquals("the forced locale did not reach the resources", BOOKMARKS_EN, railTitle)
    }

    /**
     * Every chip, walked to with the D-pad, says its whole word and is wholly on screen when it holds focus — and
     * walking to the last one has scrolled the row, which is the whole of the fix.
     */
    @Test
    fun theDpadReachesEveryChipAndEachIsWhollyOnScreenWhenItHoldsFocus() {
        show()
        val chips = chips()
        assertEquals("one chip per status that holds a title", 5, chips.size)

        // Entering lands on whatever stop lies under the window's middle, which may be a card: UP reaches the chip
        // row above it, and LEFT walks to the chip the row starts at.
        dpad.enter()
        repeat(UP_ATTEMPTS) { if (rule.focusedLabel() !in chips) dpad.press(Dpad.Up) }
        repeat(chips.size) { if (rule.focusedLabel() != chips.first()) dpad.press(Dpad.Left) }
        assertEquals("the row starts at the first chip", chips.first(), focusedChip())
        val firstChipAtRest = unclipped(chips.first()).left
        val window = rule.onRoot().fetchSemanticsNode().size
        for ((index, chip) in chips.withIndex()) {
            if (index > 0) assertEquals("RIGHT reaches chip $index", chip, dpad.press(Dpad.Right))
            assertEquals("the chip that holds focus", chip, focusedChip())
            rule.waitForIdle()
            val box = unclipped(chip)
            val shown = onScreen(chip)
            assertFalse("chip $index says its whole word: $chip", labelIsCut(chip))
            assertTrue("chip $index ($chip) starts on screen: $box", box.left >= -TOLERANCE)
            assertTrue(
                "chip $index ($chip) ends on screen: $box in a ${window.width}px window",
                box.right <= window.width + TOLERANCE,
            )
            assertEquals("chip $index ($chip) is not cut: $shown of $box", box.width, shown.width, TOLERANCE)
        }
        rule.assertScreenshot("home-bookmarks-chips-scrolled-to-last")

        // The row scrolled to get here: the first chip has moved off its resting x, which is the one thing a row
        // that merely squeezed its overflowing chips could not do.
        assertTrue(
            "the row scrolled: the first chip was at $firstChipAtRest and is now ${unclipped(chips.first()).left}",
            unclipped(chips.first()).left < firstChipAtRest - TOLERANCE,
        )

        // And the title did not travel with them: it is the row's heading, not part of the scroll.
        val title = unclipped(checkNotNull(railTitle))
        assertEquals("the title is where it was", onScreen(checkNotNull(railTitle)).width, title.width, TOLERANCE)
    }

    /** The row at rest, before a key is pressed: the first chips are shown and the title is beside them. */
    @Test
    fun screenshotTheHeaderAtRest() {
        show()
        rule.assertScreenshot("home-bookmarks-chips-at-rest")
    }

    private companion object {
        const val TAMIL = "ta"

        /** Enough UP presses to climb out of the card rail into the chip row, whichever card focus entered on. */
        const val UP_ATTEMPTS = 2

        const val FIRST_LINE = 0

        /** A pixel of rounding: a dp is not a whole number of pixels on every density. */
        const val TOLERANCE = 1f

        /** The English the forced locale must not be showing. */
        const val WATCHING_EN = "Watching"
        const val BOOKMARKS_EN = "Bookmarks"

        val ART = Color(0xFF5A6B7C)

        /** One title in each of the five statuses, so each gets a chip, with Watching applied as 3e draws it. */
        val BOARD = HomeBookmarks(
            all = HomeWatchStatus.entries.mapIndexed { index, status ->
                HomeBookmark(HomeItem("bm/$index", "Saved $index"), status, updatedAt = index.toLong())
            },
            chosen = HomeWatchStatus.Watching,
        )
    }
}
