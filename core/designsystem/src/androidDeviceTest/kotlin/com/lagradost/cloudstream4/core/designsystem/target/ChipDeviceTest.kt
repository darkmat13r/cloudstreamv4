package com.lagradost.cloudstream4.core.designsystem.target

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lagradost.cloudstream4.core.devicetest.Dpad
import com.lagradost.cloudstream4.core.devicetest.DpadDriver
import com.lagradost.cloudstream4.core.devicetest.TouchModeReset
import com.lagradost.cloudstream4.core.devicetest.focusedLabel
import com.lagradost.cloudstream4.core.theme.CloudStreamDimens
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme
import com.lagradost.cloudstream4.core.theme.cloudStreamDarkColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What the JVM tests cannot prove about [Chip]: real D-pad keys through the input dispatcher move along a [ChipRow] on a
 * TV's density, and on every stop the ring sits evenly around the 24dp visual and nothing moves (`DEC-17`, 2026-11-05).
 */
@RunWith(AndroidJUnit4::class)
class ChipDeviceTest {

    /** `BUG-59`: starts and ends out of touch mode, whatever ran before it. */
    @get:Rule
    val touchMode = TouchModeReset()

    @get:Rule
    val rule = createComposeRule()

    private val dpad = DpadDriver(readFocus = { rule.focusedLabel() })

    private fun show() = rule.setContent {
        CloudStreamTheme {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(CloudStreamTheme.colors.bg)
                    .padding(CloudStreamDimens.FocusBandInset),
            ) {
                ChipRow {
                    Chip(MOVIES, selected = false, onClick = {})
                    Chip(SERIES, selected = true, onClick = {})
                    Chip(LIVE, selected = false, onClick = {})
                }
            }
        }
    }

    /** What takes focus: the pill's width, the cell's height. The ring's room is outside it (`BUG-65`). */
    private fun target(label: String): Rect {
        rule.waitForIdle()
        return rule.onNodeWithText(label).fetchSemanticsNode().boundsInRoot
    }

    /** The chip's cell: its focus target plus [CloudStreamDimens.FocusRingBleed] each side, the ring's room. */
    private fun cell(label: String): Rect {
        val target = target(label)
        val room = CloudStreamDimens.FocusRingBleed.px()
        return Rect(target.left - room, target.top, target.right + room, target.bottom)
    }

    private fun Dp.px(): Float = with(rule.density) { toPx() }

    private fun PixelMap.ringAt(x: Float, y: Float): Boolean = this[x.toInt(), y.toInt()] == cloudStreamDarkColors().focus

    /** The pill inside a chip's cell, as wide as its focus target (`BUG-65`). */
    private fun visual(label: String): Rect = target(label)

    /** Clear at 1dp out, the stroke's middle at 3dp, and clear again at 5dp, on each side of the visual. */
    private fun assertEvenRing(label: String) {
        val cell = visual(label)
        val frame = rule.onRoot().captureToImage().toPixelMap()
        val top = cell.center.y - (CloudStreamDimens.ChipVisualHeight / 2).px()
        val bottom = cell.center.y + (CloudStreamDimens.ChipVisualHeight / 2).px()
        val midX = cell.center.x
        val midY = cell.center.y
        listOf(
            "left" to { out: Float -> frame.ringAt(cell.left - out, midY) },
            "right" to { out: Float -> frame.ringAt(cell.right + out, midY) },
            "top" to { out: Float -> frame.ringAt(midX, top - out) },
            "bottom" to { out: Float -> frame.ringAt(midX, bottom + out) },
        ).forEach { (side, ring) ->
            assertFalse("$label $side: the gap is clear", ring(GAP_MIDDLE.px()))
            assertTrue("$label $side: the stroke is here", ring(STROKE_MIDDLE.px()))
            assertFalse("$label $side: nothing past the stroke", ring(PAST_STROKE.px()))
        }
        assertFalse("$label: nothing at the cell's old ring", frame.ringAt(midX, cell.top - STROKE_MIDDLE.px()))
    }

    @Test
    fun dpadMovesAcrossChipsAndTheRingStaysEvenWithNothingMoving() {
        show()
        val labels = listOf(MOVIES, SERIES, LIVE)
        val cells = labels.map(::cell)

        assertEquals(MOVIES, dpad.enter())
        assertEvenRing(MOVIES)
        assertEquals("focus moved nothing", cells, labels.map(::cell))

        assertEquals(SERIES, dpad.press(Dpad.Right))
        assertEvenRing(SERIES)
        assertEquals("focus moved nothing", cells, labels.map(::cell))

        assertEquals(LIVE, dpad.press(Dpad.Right))
        assertEvenRing(LIVE)
        assertEquals("focus moved nothing", cells, labels.map(::cell))

        assertEquals("the last chip holds focus", LIVE, dpad.press(Dpad.Right))
        assertEquals(SERIES, dpad.press(Dpad.Left))
        assertEvenRing(SERIES)
    }

    /**
     * `BUG-65`: the gap a person sees between two chips is [CloudStreamDimens.MinFocusGap], measured in pixels along the
     * pills' middle: every run of ground between two pills.
     */
    @Test
    fun theVisibleGapBetweenChipsIsMinFocusGap() {
        show()
        rule.waitForIdle()
        val frame = rule.onRoot().captureToImage().toPixelMap()
        val y = cell(MOVIES).center.y.toInt()
        val ground = cloudStreamDarkColors().bg
        val from = cell(MOVIES).left.toInt()
        val to = cell(LIVE).right.toInt()
        // Runs of ground with a pill on both sides.
        val gaps = mutableListOf<Int>()
        var seenPill = false
        var run = 0
        for (x in from..to) {
            if (frame[x, y] == ground) {
                if (seenPill) run++
            } else {
                if (run > 0) gaps += run
                run = 0
                seenPill = true
            }
        }
        val pillGaps = gaps
        assertEquals("two gaps between three pills: $gaps", 2, pillGaps.size)
        pillGaps.forEach { assertEquals("the visible gap, px", CloudStreamDimens.MinFocusGap.px(), it.toFloat(), 1f) }
    }

    /**
     * `BUG-65`: each focused chip's ring stays inside its own cell, clear of the next chip's pill, and every key press puts
     * focus on exactly one chip, the next one. The ring is what turns the focus colour against a capture before any
     * chip had focus: `ink` is the same white, and a selected chip's label is `ink` whether focused or not.
     */
    @Test
    fun eachRingStaysInsideItsOwnCellAndOneKeyFocusesOneChip() {
        show()
        val labels = listOf(MOVIES, SERIES, LIVE)
        val focus = cloudStreamDarkColors().focus
        rule.waitForIdle()
        val atRest = rule.onRoot().captureToImage().toPixelMap()
        labels.forEachIndexed { i, label ->
            val moved = if (i == 0) dpad.enter() else dpad.press(Dpad.Right)
            assertEquals("the key focused the next chip", label, moved)
            assertEquals("exactly one chip holds focus", 1, rule.onAllNodes(isFocused()).fetchSemanticsNodes().size)
            val cell = cell(label)
            val frame = rule.onRoot().captureToImage().toPixelMap()
            var left = Int.MAX_VALUE; var right = -1; var top = Int.MAX_VALUE; var bottom = -1
            for (y in 0 until frame.height) for (x in 0 until frame.width) {
                if (frame[x, y] == focus && atRest[x, y] != focus) {
                    left = minOf(left, x); right = maxOf(right, x); top = minOf(top, y); bottom = maxOf(bottom, y)
                }
            }
            assertTrue("no ring around $label", right >= 0)
            val ring = "$label's ring x $left..$right y $top..$bottom"
            val inCell = "its cell x ${cell.left}..${cell.right} y ${cell.top}..${cell.bottom}"
            assertTrue("$ring passes $inCell on the left", left >= cell.left.toInt())
            assertTrue("$ring passes $inCell on the right", right < cell.right.toInt())
            assertTrue("$ring passes $inCell at the top", top >= cell.top.toInt())
            assertTrue("$ring passes $inCell at the foot", bottom < cell.bottom.toInt())
            labels.getOrNull(i + 1)?.let { next ->
                assertTrue("$ring reaches $next's pill at ${visual(next).left}", right < visual(next).left.toInt())
            }
        }
    }

    /**
     * `BUG-65`: Left from content lined up under a [ChipRow] reaches the rail beside it, not the row's first chip. The
     * chips' ring room is outside what takes focus, so the first chip's focus target starts where the content does, and
     * focus search does not count it as being to the left. With the room inside the focus target it started 4dp further
     * left, and Left from the Downloads screen's first row went to "Download queue" (`DownloadsTvNavigationTest`).
     */
    @Test
    fun leftFromTheContentUnderAChipRowReachesTheRail() {
        val below = FocusRequester()
        rule.setContent {
            CloudStreamTheme {
                Row(
                    Modifier
                        .fillMaxSize()
                        .background(CloudStreamTheme.colors.bg)
                        .padding(CloudStreamDimens.FocusBandInset),
                ) {
                    Box(Modifier.width(CloudStreamDimens.IconTargetCell).fillMaxHeight().stop(RAIL))
                    Column(Modifier.padding(start = CloudStreamDimens.FocusBandInset)) {
                        ChipRow {
                            Chip(MOVIES, selected = false, onClick = {})
                            Chip(SERIES, selected = false, onClick = {})
                        }
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(CloudStreamDimens.ListRowHeight)
                                .focusRequester(below)
                                .stop(BELOW),
                        )
                    }
                }
            }
        }
        rule.runOnIdle { below.requestFocus() }
        assertEquals(BELOW, dpad.focused())
        assertEquals("Left from the content", RAIL, dpad.press(Dpad.Left))
        assertEquals("the first chip's focus target starts with the content", target(MOVIES).left, belowLeft(), 1f)
    }

    private fun belowLeft(): Float = rule.onNodeWithContentDescription(BELOW).fetchSemanticsNode().boundsInRoot.left

    private fun Modifier.stop(name: String): Modifier = semantics { contentDescription = name }.focusable()

    private companion object {
        const val MOVIES = "Movies"
        const val SERIES = "Series"
        const val LIVE = "Live"
        const val RAIL = "Rail"
        const val BELOW = "Below"
        val GAP_MIDDLE = 1.dp
        val STROKE_MIDDLE = 3.dp
        val PAST_STROKE = 5.dp
    }
}
