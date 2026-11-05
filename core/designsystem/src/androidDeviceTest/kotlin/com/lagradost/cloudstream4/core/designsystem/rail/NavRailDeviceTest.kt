package com.lagradost.cloudstream4.core.designsystem.rail

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What the JVM tests cannot prove about [NavRail]: real D-pad keys through the input dispatcher open it onto the
 * current page, travel inside it and leave it, with a focusable screen beside it, and the screen shows where focus is.
 */
@RunWith(AndroidJUnit4::class)
class NavRailDeviceTest {

    @get:Rule
    val rule = createComposeRule()

    private val dpad = DpadDriver(readFocus = { rule.focusedLabel() })
    private val colors = cloudStreamDarkColors()
    private val content = FocusRequester()
    private var current by mutableIntStateOf(LIBRARY_INDEX)

    /** The rail with the five `DEC-17` items and a screen beside it, with focus starting on the screen. */
    private fun show() {
        rule.setContent {
            CloudStreamTheme {
                Row(Modifier.fillMaxSize().background(CloudStreamTheme.colors.bg)) {
                    NavRail(Items, current, { current = it })
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .semantics { contentDescription = CONTENT }
                            .focusRequester(content)
                            .focusable(),
                    )
                }
            }
        }
        rule.runOnIdle { content.requestFocus() }
        assertEquals(CONTENT, dpad.focused())
    }

    /** The colour at the far end of an expanded item, past its label. */
    private fun fillOf(label: String): Color {
        rule.waitForIdle()
        val bounds = rule.onNodeWithText(label).fetchSemanticsNode().boundsInRoot
        val inset = with(rule.density) { FILL_PROBE.toPx() }
        return rule.onRoot().captureToImage().toPixelMap()[(bounds.right - inset).toInt(), bounds.center.y.toInt()]
    }

    private fun widthOf(node: SemanticsNodeInteraction): Float =
        node.fetchSemanticsNode().boundsInRoot.width

    @Test
    fun leftFromTheScreenOpensTheRailOnTheCurrentPageAndTheScreenDoesNotMove() {
        show()
        val screenLeft = rule.onNodeWithContentDescription(CONTENT).fetchSemanticsNode().boundsInRoot.left
        assertEquals(with(rule.density) { CloudStreamDimens.NavRailWidth.toPx() }, screenLeft, PIXEL)

        assertEquals("the current page, not the first item", LIBRARY, dpad.press(Dpad.Left))
        assertEquals("focus is the ink fill", colors.ink, fillOf(LIBRARY))
        assertEquals(
            with(rule.density) { NavRailDefaults.ExpandedItemWidth.toPx() },
            widthOf(rule.onNodeWithText(LIBRARY)),
            PIXEL,
        )
        assertEquals(
            "the screen never reflows",
            screenLeft,
            rule.onNodeWithContentDescription(CONTENT).fetchSemanticsNode().boundsInRoot.left,
            PIXEL,
        )
    }

    @Test
    fun travellingKeepsTheCurrentPageMarkedAndCommitsNothing() {
        show()
        dpad.press(Dpad.Left)

        assertEquals(SEARCH, dpad.press(Dpad.Up))
        assertEquals("focus moved", colors.ink, fillOf(SEARCH))
        assertEquals("the current page stays raised", colors.raised, fillOf(LIBRARY))
        assertEquals("travelling commits nothing", LIBRARY_INDEX, current)
        rule.onNodeWithText(LIBRARY).assertIsSelected()
        rule.onNodeWithText(SEARCH).assertIsNotSelected()
    }

    @Test
    fun centreCommitsAndTheNextEntryLandsOnTheNewPage() {
        show()
        dpad.press(Dpad.Left)
        dpad.press(Dpad.Up)

        dpad.center()
        rule.waitUntil(WAIT_MS) { current == SEARCH_INDEX }
        rule.onNodeWithText(SEARCH).assertIsSelected()
        assertEquals("choosing keeps focus", SEARCH, dpad.focused())

        assertEquals("right leaves the rail", CONTENT, dpad.press(Dpad.Right))
        assertEquals(SEARCH, dpad.press(Dpad.Left))
    }

    @Test
    fun leavingTheRailCollapsesIt() {
        show()
        dpad.press(Dpad.Left)

        assertEquals(CONTENT, dpad.press(Dpad.Right))
        rule.waitForIdle()
        assertEquals(
            with(rule.density) { CloudStreamDimens.NavRailIconCell.toPx() },
            widthOf(rule.onNodeWithContentDescription(LIBRARY)),
            PIXEL,
        )
        rule.onNodeWithText(LIBRARY).assertDoesNotExist()
    }

    @Test
    fun everyItemIsReachableAndNothingTrapsFocus() {
        show()
        val graph = dpad.walk()
        assertEquals(graph.toString(), emptyList<String>(), graph.unreachable(Items.map { it.label } + CONTENT))
        assertEquals(graph.toString(), emptyList<String>(), graph.traps())
    }

    @Test
    fun screenshotCollapsedFocusOnTheScreen() {
        show()
        rule.assertScreenshot("nav-rail-collapsed")
    }

    @Test
    fun screenshotExpandedFocusOnTheCurrentPage() {
        show()
        assertEquals(LIBRARY, dpad.press(Dpad.Left))
        rule.assertScreenshot("nav-rail-expanded-focus-current")
    }

    private companion object {
        const val CONTENT = "Screen"
        const val SEARCH = "Search"
        const val SEARCH_INDEX = 1
        const val LIBRARY = "Library"
        const val LIBRARY_INDEX = 2
        const val WAIT_MS = 10_000L
        const val PIXEL = 1f
        val FILL_PROBE = 8.dp

        val Items = listOf(
            NavRailItem("Home", CloudStreamIcons.Home),
            NavRailItem(SEARCH, CloudStreamIcons.Search),
            NavRailItem(LIBRARY, CloudStreamIcons.Library),
            NavRailItem("Downloads", CloudStreamIcons.Download),
            NavRailItem("Settings", CloudStreamIcons.Settings),
        )
    }
}
