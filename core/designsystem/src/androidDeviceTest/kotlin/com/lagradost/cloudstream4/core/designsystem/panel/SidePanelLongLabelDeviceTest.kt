package com.lagradost.cloudstream4.core.designsystem.panel

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lagradost.cloudstream4.core.designsystem.rows.ListRow
import com.lagradost.cloudstream4.core.designsystem.rows.ListRowDefaults
import com.lagradost.cloudstream4.core.designsystem.rows.ListRowState
import com.lagradost.cloudstream4.core.devicetest.Dpad
import com.lagradost.cloudstream4.core.devicetest.DpadDriver
import com.lagradost.cloudstream4.core.devicetest.focusedLabel
import com.lagradost.cloudstream4.core.theme.CloudStreamDimens
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What `BUG-68`'s review asked of the side panel's second line, on the device that measured `BUG-62`.
 *
 * A row's height follows what its title's layout reported, so it lands on the frame **after** the layout that
 * reported it. The review asked whether a focused row near the bottom of a scrolling panel survives that. Three
 * cases, in the order they matter:
 *
 * 1. **The real sort panel** — four grown rows in the 540dp panel the TV draws, with focus asked for the applied one
 *    as it opens. They fit with 36dp to spare, so the panel a person opens is unaffected.
 * 2. **Walking down** a panel too short for its rows, which is how a person reaches a row below the fold. After every
 *    press the focused row and its ring are inside the list.
 * 3. **The commit row's longest label** fits on one line, which is the premise behind leaving `commit` out of the
 *    second line. Every case logs its measured width under [LOG_TAG], so `SidePanel`'s KDoc cites this device.
 *
 * **What is deliberately not asserted here.** Asking focus for a row the first frame does not compose never reaches
 * `requestFocus`: a `LazyColumn` disposes the item and cancels its effects first. Measured on this device, a target
 * at index 7 of 8 is never focused and the list never scrolls — and **the same happens with one-line English rows**,
 * so it is the caller's open-the-panel pattern rather than this task's second line. It is `BUG-82`, with the control
 * pair as its evidence; a test here asserting today's behaviour would only freeze it.
 */
@RunWith(AndroidJUnit4::class)
class SidePanelLongLabelDeviceTest {

    @get:Rule
    val rule = createComposeRule()

    private val dpad = DpadDriver(readFocus = { rule.focusedLabel() })

    private fun tagBounds(tag: String): Rect = rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

    /** `BasicText` publishes its layout, which is the only honest way to ask what a title did. */
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

    private fun Float.dp(): Float = with(rule.density) { toDp().value }

    /**
     * A sort panel's rows in `ta`, the worst locale the sweep found, with focus asked for [focusIndex] as the panel
     * opens — which is what `LibrarySortPanelTv` does for the applied criterion.
     *
     * [focusIndex] must be a row the **first** frame composes. A `LazyColumn` cancels the effects of an item it
     * disposes, so asking focus for a row below the fold never reaches `requestFocus` at all: measured on this
     * device, a target at index 7 of 8 is never focused and the list never scrolls — **with one-line English rows
     * too**, so it is the caller's pattern and not this task's second line. See `BUG-82`.
     */
    private fun showPanel(titles: List<String>, focusIndex: Int, panelHeight: Int) = rule.setContent {
        CloudStreamTheme {
            Box(
                Modifier
                    .size(width = PANEL_WIDTH.dp, height = panelHeight.dp)
                    .background(CloudStreamTheme.colors.bg)
                    .testTag(SHOT),
            ) {
                SidePanel(
                    header = { BasicText(text = SORT_BY_TA, style = CloudStreamTheme.typography.railTitle) },
                    commit = { CommitRow(DONE_TA) },
                ) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().testTag(LIST),
                        verticalArrangement = Arrangement.spacedBy(SidePanelDefaults.RowGap),
                        // What `LibrarySortPanelTv` gives it: a focused row's ring needs this much room.
                        contentPadding = PaddingValues(CloudStreamDimens.FocusRingBleed),
                    ) {
                        itemsIndexed(items = titles, key = { _, rowTitle -> rowTitle }) { index, rowTitle ->
                            val requester = remember { FocusRequester() }
                            val target = index == focusIndex
                            LaunchedEffect(Unit) {
                                if (target) {
                                    withFrameNanos { }
                                    requester.requestFocus()
                                }
                            }
                            ListRow(
                                state = ListRowState(title = rowTitle),
                                onClick = {},
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .focusRequester(requester)
                                    .testTag(if (target) FOCUSED_ROW else "$ROW$index"),
                            )
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun CommitRow(label: String) {
        ListRow(
            state = ListRowState(title = label),
            onClick = {},
            modifier = Modifier.fillMaxWidth().testTag(COMMIT_ROW),
        )
    }

    /**
     * **The real sort panel, in the worst locale.** `LibrarySortPanelTv` draws one row per
     * `LibrarySortCriterion` — four — in a panel the TV gives 540dp, and asks focus for the applied one as it opens.
     * Each row here is the longest `ta` label of its criterion, so all four are grown rows.
     *
     * Four 64dp rows at a 12dp gap plus the list's own 8dp of ring padding is 300dp inside the 336dp the region
     * leaves (540 − `SidePanelFirstRowY` 128 − `CommitRegionHeight` 76). So the second line costs this panel nothing
     * it has: every row stays composed, the applied row takes focus, and its ring is inside the list. That is the
     * case that has to keep working, and it is the one a person meets.
     */
    @Test
    fun theRealSortPanelsFourGrownRowsAllFitAndTheAppliedOneTakesFocus() {
        showPanel(titles = REAL_CRITERIA_TA, focusIndex = REAL_CRITERIA_TA.lastIndex, panelHeight = TV_PANEL_HEIGHT)
        rule.waitForIdle()

        rule.onNodeWithTag(FOCUSED_ROW).assertIsFocused()
        assertEquals(
            "the applied row took its second line",
            2,
            title(REAL_CRITERIA_TA.last()).lineCount,
        )
        val row = tagBounds(FOCUSED_ROW)
        assertEquals(
            "and is a grown row",
            (CloudStreamDimens.ListRowHeight + ListRowDefaults.HeightStep).px(),
            row.height,
            TOLERANCE,
        )
        assertRingInsideTheList(row, "the applied row")
    }

    /**
     * **Walking down a panel of grown rows.** Focus starts on the first row and the D-pad goes down, which is how a
     * person reaches a row below the fold: Compose composes the next focusable past the list's bounds to find it, so
     * the rows arrive as focus does and the list scrolls under them.
     *
     * After every press the focused row and its [CloudStreamDimens.FocusRingBleed] are inside the list's own box.
     * This is the invariant the row's height owes a scrolling panel, and it is asserted on the settled frame, which
     * is the one a viewer sees.
     */
    @Test
    fun walkingDownAPanelOfGrownRowsKeepsEveryFocusedRowInside() {
        showPanel(titles = SORT_TITLES_TA, focusIndex = 0, panelHeight = PANEL_HEIGHT)
        rule.waitForIdle()
        rule.onNodeWithTag(FOCUSED_ROW).assertIsFocused()

        for (step in 1..STEPS_DOWN) {
            val landed = dpad.press(Dpad.Down)
            rule.waitForIdle()
            assertEquals("DOWN $step moved focus to the next row", SORT_TITLES_TA[step], landed)
            val focused = rule.onNode(isFocused()).fetchSemanticsNode().boundsInRoot
            assertRingInsideTheList(focused, "row $step after DOWN $step")
        }
    }

    /** The one geometric claim both cases make, written once. */
    private fun assertRingInsideTheList(row: Rect, what: String) {
        val list = tagBounds(LIST)
        val bleed = CloudStreamDimens.FocusRingBleed.px()
        Log.i(
            LOG_TAG,
            "$what row=${row.top.dp()}..${row.bottom.dp()} list=${list.top.dp()}..${list.bottom.dp()} " +
                "bleed=${CloudStreamDimens.FocusRingBleed}",
        )
        assertTrue(
            "$what is cut by the panel's scroll viewport: the row is ${row.top.dp()}..${row.bottom.dp()}dp and the " +
                "list is ${list.top.dp()}..${list.bottom.dp()}dp, with ${CloudStreamDimens.FocusRingBleed} of ring " +
                "to fit inside it",
            row.top >= list.top + bleed - TOLERANCE && row.bottom <= list.bottom - bleed + TOLERANCE,
        )
    }

    /**
     * Every label a panel's commit row can hold, in the locales that make it longest, on one line and not ellipsised.
     *
     * This is the measurement behind [SidePanel]'s `commit` paragraph. If one of these were over the row's width the
     * call would be the owner's — two lines there eat 16dp of the bottom focus band — so the test states the premise
     * rather than leaving it to be read.
     */
    @Test
    fun everyCommitLabelFitsOnOneLine() {
        var label by mutableStateOf(COMMIT_LABELS.first().second)
        rule.setContent {
            CloudStreamTheme {
                Box(
                    Modifier
                        .size(width = PANEL_WIDTH.dp, height = PANEL_HEIGHT.dp)
                        .background(CloudStreamTheme.colors.bg),
                ) {
                    SidePanel(
                        header = { BasicText(text = SORT_BY_TA, style = CloudStreamTheme.typography.railTitle) },
                        commit = { CommitRow(label) },
                    ) {}
                }
            }
        }

        COMMIT_LABELS.forEach { (key, text) ->
            rule.runOnUiThread { label = text }
            rule.waitForIdle()
            val layout = title(text)
            val width = (layout.getLineRight(FIRST_LINE) - layout.getLineLeft(FIRST_LINE)).dp()
            val allowed = layout.layoutInput.constraints.maxWidth.toFloat().dp()
            Log.i(LOG_TAG, "commit $key width=${width}dp allowed=${allowed}dp lines=${layout.lineCount}")
            assertEquals("$key: $text is one line in ${allowed}dp (it measures ${width}dp)", 1, layout.lineCount)
            assertFalse(
                "$key: $text is not ellipsised in ${allowed}dp (it measures ${width}dp)",
                layout.isLineEllipsized(FIRST_LINE),
            )
            assertTrue("$key: $text fits the row it is drawn in", width <= allowed + TOLERANCE)
        }
    }

    private companion object {
        const val SHOT = "shot"
        const val LIST = "list"
        const val ROW = "row"
        const val FOCUSED_ROW = "focused-row"
        const val COMMIT_ROW = "commit-row"
        const val FIRST_LINE = 0

        /** Presses of DOWN the walking case makes, which reaches row 4 of 8 and scrolls the list under it. */
        const val STEPS_DOWN = 4

        const val LOG_TAG = "BUG68"

        /** `CloudStreamDimens.SidePanelWidth`, written out because the `Box` around the panel is the screen here. */
        const val PANEL_WIDTH = 280

        /**
         * Short enough that eight grown rows outrun the region, so the walking case really does scroll: 480 − 128 of
         * header region − 76 of commit region leaves 276dp for rows at a 76dp pitch, which is three and a half.
         */
        const val PANEL_HEIGHT = 480

        /**
         * What the TV gives a panel: `Television_720p` is 540dp tall at this density, and every board draws the panel
         * full height. The real-panel case uses it so its arithmetic is the shipped arithmetic.
         */
        const val TV_PANEL_HEIGHT = 540

        /** A pixel of rounding: a dp is not a whole number of pixels on every density. */
        const val TOLERANCE = 1f

        /** `sort_by` in `ta`, the panel's own header. */
        const val SORT_BY_TA = "வரிசைப்படுத்தவும்"

        /** `setup_done` in `ta`. */
        const val DONE_TA = "முடிந்தது"

        /**
         * One row per `LibrarySortCriterion`, in the enum's own order — Updated, Title, Released, Rating — each in
         * the direction whose `ta` label is longest, which is the worst the real panel can be asked to draw:
         * 303.5dp, 215.6dp, 309.5dp and 260.7dp against the roughly 212dp a row leaves.
         */
        val REAL_CRITERIA_TA = listOf(
            "புதுப்பிக்கப்பட்டது (பழையது முதல் புதியது)",
            "அகரவரிசை (A முதல் சட் வரை)",
            "வெளியீட்டு தேதி (பழையதிலிருந்து புதியது)",
            "மதிப்பீடு (உயர் முதல் குறைந்த வரை)",
        )

        /**
         * The eight `sort_*` labels in `ta`, which is what a criterion list with both directions draws, ordered so
         * that the first five are all certainly two-line rows. `BUG-62`'s sweep measured them at the `body` style a
         * lone title is drawn in, against the roughly 212dp a panel row leaves: `sort_release_date_old` 309.5dp,
         * `sort_updated_old` 303.5, `sort_rating_desc` and `sort_rating_asc` and `sort_updated_new` 260.7,
         * `sort_release_date_new` 252.4, `sort_alphabetical_a` 215.6 (barely over) and `sort_alphabetical_z` 203.6,
         * which fits. The last two are therefore kept out of the rows that have to grow.
         */
        val SORT_TITLES_TA = listOf(
            "புதுப்பிக்கப்பட்டது (பழையது முதல் புதியது)",
            "வெளியீட்டு தேதி (பழையது புதியது)",
            "புதுப்பிக்கப்பட்டது (பழையது புதியது)",
            "மதிப்பீடு (உயர் முதல் குறைந்த வரை)",
            "வெளியீட்டு தேதி (பழையதிலிருந்து புதியது)",
            "மதிப்பீடு (குறைந்த முதல் உயர் வரை)",
            "அகரவரிசை (A முதல் சட் வரை)",
            "அகரவரிசை (z முதல் A வரை)",
        )

        /**
         * Every label a `commit` row is given in the app, each in the locale that makes it longest. The review named
         * `search_quick_open_full` in `my`, `fr`, `hr` and `mk` (28 to 29 characters) and `go_back` in `ta` (22).
         * `search_state_retry` is English-only so far, and it is the one label with a leading glyph beside it.
         */
        val COMMIT_LABELS = listOf(
            "setup_done ta" to "முடிந்தது",
            "go_back ta" to "திரும்பிச் செல்லுங்கள்",
            "search_quick_open_full my" to "အပြည့်အစုံ ရှာဖွေမှု ဖွင့်ရန်",
            "search_quick_open_full fr" to "Ouvrir la recherche complète",
            "search_quick_open_full hr" to "Otvori potpuno pretraživanje",
            "search_quick_open_full mk" to "Отвори го целото пребарување",
            "search_quick_open_full el" to "Άνοιγμα πλήρους αναζήτησης",
            "cancel my" to "ပယ်ဖျက်ရန်",
            "search_state_retry en" to "Try again",
        )
    }
}
