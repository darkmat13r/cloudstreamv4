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
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
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
        dpad.center()
        rule.waitUntil(WAIT_MS) { !watched }
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

    private companion object {
        const val ROW = "row"
        const val TRAILER = "Trailer"
        const val WATCHLIST = "Watchlist"
        const val MORE = "More"
        const val WAIT_MS = 10_000L
    }
}
