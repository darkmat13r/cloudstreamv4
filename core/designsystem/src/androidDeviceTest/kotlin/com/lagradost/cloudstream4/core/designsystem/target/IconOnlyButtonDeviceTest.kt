package com.lagradost.cloudstream4.core.designsystem.target

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lagradost.cloudstream4.core.designsystem.icon.CloudStreamIcons
import com.lagradost.cloudstream4.core.devicetest.Dpad
import com.lagradost.cloudstream4.core.devicetest.DpadDriver
import com.lagradost.cloudstream4.core.devicetest.assertScreenshot
import com.lagradost.cloudstream4.core.devicetest.focusedLabel
import com.lagradost.cloudstream4.core.theme.CloudStreamDimens
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme
import com.lagradost.cloudstream4.core.theme.cloudStreamDarkColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What the JVM tests cannot prove about [IconOnlyToggle] and [IconOnlyButton] (`DEC-17`, 2026-11-05): on a TV's
 * density, real D-pad keys reach them from a text button, each is announced by its label, shows that label only while
 * focused, focusing moves nothing, and centre flips the toggle.
 */
@RunWith(AndroidJUnit4::class)
class IconOnlyButtonDeviceTest {

    @get:Rule
    val rule = createComposeRule()

    private val dpad = DpadDriver(readFocus = { rule.focusedLabel() })
    private var watched by mutableStateOf(false)

    private fun show() = rule.setContent {
        CloudStreamTheme {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(CloudStreamTheme.colors.bg)
                    .padding(CloudStreamDimens.FocusBandInset),
            ) {
                // The row's own box, with the label's reserve below it, is what the screenshots crop to.
                Box(Modifier.testTag(ROW).padding(bottom = IconOnlyButtonDefaults.LabelReserve)) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(CloudStreamDimens.MinFocusGap),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        GhostTextButton(label = TRAILER, onClick = {}, icon = CloudStreamIcons.Trailer)
                        IconOnlyToggle(
                            icon = CloudStreamIcons.Bookmark,
                            label = WATCHLIST,
                            checked = watched,
                            onCheckedChange = { watched = it },
                        )
                        IconOnlyButton(icon = CloudStreamIcons.More, label = MORE, onClick = {})
                    }
                }
            }
        }
    }

    private fun bounds(description: String): Rect {
        rule.waitForIdle()
        return rule.onNodeWithContentDescription(description).fetchSemanticsNode().boundsInRoot
    }

    /** Pixels in the label's line box under [box] that are not the ground. */
    private fun labelInk(box: Rect): Int {
        val frame = rule.onRoot().captureToImage().toPixelMap()
        val reserve = with(rule.density) { IconOnlyButtonDefaults.LabelReserve.toPx() }.toInt()
        val bg = cloudStreamDarkColors().bg
        return (box.left.toInt() until box.right.toInt()).sumOf { x ->
            (box.bottom.toInt() until box.bottom.toInt() + reserve).count { y -> frame[x, y] != bg }
        }
    }

    @Test
    fun dpadReachesBothAndEachNamesItselfOnlyWhileFocused() {
        show()
        val cells = listOf(WATCHLIST, MORE).map(::bounds)
        val cell = with(rule.density) { CloudStreamDimens.IconTargetCell.toPx() }
        cells.forEach { assertEquals("a 48dp target", cell, it.height, 1f) }
        assertEquals(0, labelInk(cells[0]))
        assertEquals(0, labelInk(cells[1]))

        assertEquals(TRAILER, dpad.enter())
        assertEquals("announced by its label", WATCHLIST, dpad.press(Dpad.Right))
        assertTrue("the focused toggle shows its label", labelInk(cells[0]) > 0)
        assertEquals("the other does not", 0, labelInk(cells[1]))
        assertEquals("focus moved nothing", cells, listOf(WATCHLIST, MORE).map(::bounds))

        assertEquals(MORE, dpad.press(Dpad.Right))
        assertEquals("the label goes with focus", 0, labelInk(cells[0]))
        assertTrue(labelInk(cells[1]) > 0)
        assertEquals("focus moved nothing", cells, listOf(WATCHLIST, MORE).map(::bounds))
    }

    @Test
    fun centreFlipsTheToggle() {
        show()
        dpad.enter()
        dpad.press(Dpad.Right)
        dpad.center()
        rule.waitUntil(WAIT_MS) { watched }
        // The toggle reads its value when it is clicked, so the next press waits for the recomposition.
        rule.waitForIdle()
        rule.onNodeWithContentDescription(WATCHLIST).assertIsOn()
        dpad.center()
        rule.waitUntil(WAIT_MS) { !watched }
        rule.waitForIdle()
        rule.onNodeWithContentDescription(WATCHLIST).assertIsOff()
    }

    @Test
    fun screenshotToggleFocusedShowsItsLabel() {
        show()
        dpad.enter()
        assertEquals(WATCHLIST, dpad.press(Dpad.Right))
        rule.assertScreenshot("icon-only-toggle-focus-label", rule.onNodeWithTag(ROW))
    }

    @Test
    fun screenshotToggleCheckedOverflowFocused() {
        watched = true
        show()
        dpad.enter()
        dpad.press(Dpad.Right)
        assertEquals(MORE, dpad.press(Dpad.Right))
        rule.assertScreenshot("icon-only-checked-overflow-focus-label", rule.onNodeWithTag(ROW))
    }

    /** On and off side by side, with focus on the text button so neither shows its ring or label. */
    @Test
    fun screenshotToggleOnAndOffSideBySide() {
        rule.setContent {
            CloudStreamTheme {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(CloudStreamTheme.colors.bg)
                        .padding(CloudStreamDimens.FocusBandInset),
                ) {
                    Box(Modifier.testTag(ROW).padding(bottom = IconOnlyButtonDefaults.LabelReserve)) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(CloudStreamDimens.MinFocusGap),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            GhostTextButton(label = TRAILER, onClick = {}, icon = CloudStreamIcons.Trailer)
                            IconOnlyToggle(CloudStreamIcons.Bookmark, WATCHLIST, checked = true, onCheckedChange = {})
                            IconOnlyToggle(CloudStreamIcons.Favorite, FAVOURITE, checked = false, onCheckedChange = {})
                        }
                    }
                }
            }
        }
        assertEquals(TRAILER, dpad.enter())
        rule.assertScreenshot("icon-only-toggle-on-off", rule.onNodeWithTag(ROW))
    }

    /**
     * `BUG-60`: the longest `switch_account` in the 62 locales, Macedonian's 25 letters, under a cell flush with the
     * screen's right edge, as the TV Home chrome's last cell nearly is. Its label is as wide there as under a cell in the
     * middle of the screen, so none of it is cut, and it ends on screen.
     */
    @Test
    fun aLongLabelAtTheScreensEdgeStaysOnScreen() {
        rule.setContent {
            CloudStreamTheme {
                Box(Modifier.fillMaxSize().background(CloudStreamTheme.colors.bg)) {
                    IconOnlyButton(CloudStreamIcons.Profile, LONGEST, onClick = {}, modifier = Modifier.align(Alignment.Center).testTag(MIDDLE))
                    IconOnlyButton(CloudStreamIcons.Profile, LONGEST, onClick = {}, modifier = Modifier.align(Alignment.TopEnd).testTag(EDGE))
                }
            }
        }
        val middle = labelExtent(MIDDLE)
        val edge = labelExtent(EDGE)
        val width = rule.onRoot().fetchSemanticsNode().size.width
        assertTrue("the label under the edge cell ends at ${edge.last}, past the screen's $width px", edge.last < width)
        assertEquals(
            "the label under the edge cell is ${edge.last - edge.first + 1} px wide, under the middle one " +
                "${middle.last - middle.first + 1} px: it was cut",
            (middle.last - middle.first).toFloat(),
            (edge.last - edge.first).toFloat(),
            1f,
        )
    }

    /** Focuses the cell tagged [tag] and returns the columns its label's ink covers, in the line box under the cell. */
    private fun labelExtent(tag: String): IntRange {
        rule.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.RequestFocus)
        rule.waitForIdle()
        val cell = rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        val frame = rule.onRoot().captureToImage().toPixelMap()
        val reserve = with(rule.density) { IconOnlyButtonDefaults.LabelReserve.toPx() }.toInt()
        val bg = cloudStreamDarkColors().bg
        val rows = cell.bottom.toInt() until minOf(cell.bottom.toInt() + reserve, frame.height)
        val inked = (0 until frame.width).filter { x -> rows.any { y -> frame[x, y] != bg } }
        assertTrue("no label drawn under $tag", inked.isNotEmpty())
        return inked.first()..inked.last()
    }

    private companion object {
        const val LONGEST = "Промени корисничка сметка"
        const val MIDDLE = "middle"
        const val EDGE = "edge"
        const val ROW = "row"
        const val TRAILER = "Trailer"
        const val WATCHLIST = "Watchlist"
        const val MORE = "More"
        const val FAVOURITE = "Favourite"
        const val WAIT_MS = 10_000L
    }
}
