package com.lagradost.cloudstream4.core.designsystem.target

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.layout.onGloballyPositioned
import com.lagradost.cloudstream4.core.designsystem.harness.Bounds
import com.lagradost.cloudstream4.core.designsystem.harness.Dpad
import com.lagradost.cloudstream4.core.designsystem.harness.Harness
import com.lagradost.cloudstream4.core.designsystem.icon.CloudStreamIcons
import com.lagradost.cloudstream4.core.theme.CloudStreamDimens
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme
import com.lagradost.cloudstream4.core.theme.cloudStreamDarkColors
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `DEC-17`, 2026-11-05: icon-only is for toggles, overflow and search, and such a button shows its text label when
 * focused. Read at density 1, so a pixel is a dp.
 */
class IconOnlyButtonTest {

    private val colors = cloudStreamDarkColors()
    private val cell = CloudStreamDimens.IconTargetCell.value.toInt()
    private val tile = IconOnlyButtonDefaults.TileSize.value.toInt()
    private val inset = (cell - tile) / 2
    private val labelBox = IconOnlyButtonDefaults.LabelReserve.value.toInt()

    private var watched by mutableStateOf(false)

    @Composable
    private fun Scene(dpad: Dpad, bounds: Bounds) {
        dpad.Install()
        Box(
            Modifier
                .fillMaxSize()
                .background(CloudStreamTheme.colors.bg)
                .padding(CloudStreamDimens.FocusBandInset),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(CloudStreamDimens.MinFocusGap)) {
                IconOnlyToggle(
                    icon = CloudStreamIcons.Bookmark,
                    label = WATCHLIST,
                    checked = watched,
                    onCheckedChange = { watched = it },
                    modifier = Modifier.onGloballyPositioned(bounds.record(TOGGLE)),
                )
                IconOnlyButton(
                    icon = CloudStreamIcons.More,
                    label = MORE,
                    onClick = {},
                    modifier = Modifier.onGloballyPositioned(bounds.record(BUTTON)),
                )
            }
        }
    }

    private fun Color.near(other: Color): Boolean =
        abs(red - other.red) <= TOLERANCE && abs(green - other.green) <= TOLERANCE && abs(blue - other.blue) <= TOLERANCE

    /** Every pixel under the cell, in the label's line box and a little wider than the cell, that is not ground. */
    private fun PixelMap.labelInk(box: Rect): Int {
        val left = box.left.toInt() - LABEL_OVERHANG
        val right = box.right.toInt() + LABEL_OVERHANG
        val top = box.bottom.toInt()
        return (left until right).sumOf { x -> (top until top + labelBox).count { y -> !this[x, y].near(colors.bg) } }
    }

    private data class Side(val gap: Int, val stroke: Int)

    private fun PixelMap.side(startX: Int, startY: Int, stepX: Int, stepY: Int): Side {
        fun ring(i: Int) = this[startX + stepX * i, startY + stepY * i].near(colors.focus)
        var clear = 0
        while (clear < WALK_LIMIT && !ring(clear)) clear++
        var drawn = 0
        while (drawn < WALK_LIMIT && ring(clear + drawn)) drawn++
        return Side(clear, drawn)
    }

    @Test
    fun `the cell is 48dp and focusing either button moves and resizes nothing`() {
        val dpad = Dpad()
        val bounds = Bounds()
        Harness { Scene(dpad, bounds) }.use { harness ->
            harness.settle()
            val before = listOf(bounds[TOGGLE], bounds[BUTTON])
            before.forEach {
                assertEquals(cell.toFloat(), it.width, "the cell is the target")
                assertEquals(cell.toFloat(), it.height, "the label adds no height")
            }
            assertTrue(dpad.press(FocusDirection.Next, harness))
            assertEquals(before, listOf(bounds[TOGGLE], bounds[BUTTON]), "focusing the toggle moved nothing")
            assertTrue(dpad.press(FocusDirection.Next, harness))
            assertEquals(before, listOf(bounds[TOGGLE], bounds[BUTTON]), "focusing the button moved nothing")
        }
    }

    @Test
    fun `the label shows under the cell only while focused`() {
        val dpad = Dpad()
        val bounds = Bounds()
        Harness { Scene(dpad, bounds) }.use { harness ->
            harness.settle()
            val rest = harness.pixels()
            assertEquals(0, rest.labelInk(bounds[TOGGLE]), "no label at rest")
            assertEquals(0, rest.labelInk(bounds[BUTTON]), "no label at rest")

            dpad.press(FocusDirection.Next, harness)
            val onToggle = harness.pixels()
            assertTrue(onToggle.labelInk(bounds[TOGGLE]) > 0, "the focused toggle names itself")
            assertEquals(0, onToggle.labelInk(bounds[BUTTON]), "the other one does not")

            dpad.press(FocusDirection.Next, harness)
            val onButton = harness.pixels()
            assertEquals(0, onButton.labelInk(bounds[TOGGLE]), "the label goes with focus")
            assertTrue(onButton.labelInk(bounds[BUTTON]) > 0, "the focused button names itself")
        }
    }

    @Test
    fun `the ring sits evenly on the 32dp tile, not the cell`() {
        val dpad = Dpad()
        val bounds = Bounds()
        Harness { Scene(dpad, bounds) }.use { harness ->
            harness.settle()
            dpad.press(FocusDirection.Next, harness)
            val frame = harness.pixels()
            val box = bounds[TOGGLE]
            val left = box.left.toInt() + inset
            val top = box.top.toInt() + inset
            val midX = left + tile / 2
            val midY = top + tile / 2
            val expected = Side(CloudStreamDimens.FocusRingOffset.value.toInt(), CloudStreamDimens.FocusRingStroke.value.toInt())
            assertEquals(expected, frame.side(left - 1, midY, -1, 0), "left")
            assertEquals(expected, frame.side(left + tile, midY, 1, 0), "right")
            assertEquals(expected, frame.side(midX, top - 1, 0, -1), "top")
            assertEquals(expected, frame.side(midX, top + tile, 0, 1), "bottom")
        }
    }

    @Test
    fun `centre flips the toggle, and checked is the raised fill`() {
        val dpad = Dpad()
        val bounds = Bounds()
        watched = false
        Harness { Scene(dpad, bounds) }.use { harness ->
            harness.settle()
            dpad.press(FocusDirection.Next, harness)
            val box = bounds[TOGGLE]
            // Inside the tile, clear of the hairline, the corner curve and the centred glyph.
            val probeX = box.left.toInt() + inset + FILL_PROBE
            val probeY = box.top.toInt() + inset + tile / 2
            assertTrue(harness.pixels()[probeX, probeY].near(colors.bg), "unchecked has no fill")

            harness.press(Key.Enter)
            assertTrue(watched, "centre checks it")
            assertTrue(harness.pixels()[probeX, probeY].near(colors.raised), "checked is raised")

            harness.press(Key.Enter)
            assertTrue(!watched, "and unchecks it")
        }
    }

    private companion object {
        const val TOGGLE = "toggle"
        const val BUTTON = "button"
        const val WATCHLIST = "Watchlist"
        const val MORE = "More"
        const val LABEL_OVERHANG = 4
        const val WALK_LIMIT = 12
        const val FILL_PROBE = 4
        const val TOLERANCE = 0.02f
    }
}
