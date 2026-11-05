package com.lagradost.cloudstream4.core.designsystem.target

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.layout.onGloballyPositioned
import com.lagradost.cloudstream4.core.designsystem.harness.Bounds
import com.lagradost.cloudstream4.core.designsystem.harness.Dpad
import com.lagradost.cloudstream4.core.designsystem.harness.Harness
import com.lagradost.cloudstream4.core.theme.CloudStreamDimens
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme
import com.lagradost.cloudstream4.core.theme.cloudStreamDarkColors
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `DEC-17`, 2026-11-05, the owner: *"make chips even like buttons"*. The ring sits the same distance from the visible
 * control on all four sides, and the 48dp cell it used to surround keeps every position and every measured size.
 *
 * Read from rendered pixels at density 1, so a pixel is a dp. Each gap is counted outward from the visual's edge until
 * the ring starts, and the visual's edges are themselves read from a frame with nothing focused.
 */
class FocusRingGapTest {

    private val colors = cloudStreamDarkColors()
    private val gap = CloudStreamDimens.FocusRingOffset.value.toInt()
    private val stroke = CloudStreamDimens.FocusRingStroke.value.toInt()
    private val inset = CloudStreamDimens.FocusBandInset.value.toInt()
    private val visualHeight = CloudStreamDimens.ChipVisualHeight.value.toInt()
    private val cellHeight = CloudStreamDimens.ChipFocusCell.value.toInt()
    private val visualTop = inset + (cellHeight - visualHeight) / 2
    private val visualBottom = visualTop + visualHeight

    /** The box a person sees, as whole pixels. [right] and [bottom] are the first pixels past it. */
    private data class Box4(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val centreX get() = (left + right) / 2
        val centreY get() = (top + bottom) / 2
    }

    /** One side of a ring: the clear pixels between the visual and the stroke, then the stroke. */
    private data class Side(val gap: Int, val stroke: Int)

    private fun Color.isRing(): Boolean {
        val focus = colors.focus
        return abs(red - focus.red) < RING_TOLERANCE &&
            abs(green - focus.green) < RING_TOLERANCE &&
            abs(blue - focus.blue) < RING_TOLERANCE
    }

    private fun PixelMap.side(startX: Int, startY: Int, stepX: Int, stepY: Int): Side {
        var clear = 0
        while (clear < WALK_LIMIT && !this[startX + stepX * clear, startY + stepY * clear].isRing()) clear++
        var drawn = 0
        while (
            drawn < WALK_LIMIT &&
            this[startX + stepX * (clear + drawn), startY + stepY * (clear + drawn)].isRing()
        ) {
            drawn++
        }
        return Side(clear, drawn)
    }

    private fun PixelMap.assertEvenRing(visual: Box4, name: String) {
        val expected = Side(gap, stroke)
        assertEquals(expected, side(visual.left - 1, visual.centreY, -1, 0), "$name: left")
        assertEquals(expected, side(visual.right, visual.centreY, 1, 0), "$name: right")
        assertEquals(expected, side(visual.centreX, visual.top - 1, 0, -1), "$name: top")
        assertEquals(expected, side(visual.centreX, visual.bottom, 0, 1), "$name: bottom")
    }

    /** A flat ground, with the room for a ring the chassis gives every focusable. */
    @Composable
    private fun Ground(content: @Composable () -> Unit) {
        Box(
            Modifier
                .fillMaxSize()
                .background(CloudStreamTheme.colors.bg)
                .padding(CloudStreamDimens.FocusBandInset),
        ) { content() }
    }

    /** The chip's visible pill, found by walking in from its cell until the ground stops. */
    private fun PixelMap.chipVisual(cell: Rect): Box4 {
        val x = cell.center.x.toInt()
        val y = cell.center.y.toInt()
        val ground = colors.bg
        val top = (cell.top.toInt() until cell.bottom.toInt()).first { this[x, it] != ground }
        val bottom = (cell.bottom.toInt() - 1 downTo cell.top.toInt()).first { this[x, it] != ground } + 1
        val left = (cell.left.toInt() until cell.right.toInt()).first { this[it, y] != ground }
        val right = (cell.right.toInt() - 1 downTo cell.left.toInt()).first { this[it, y] != ground } + 1
        return Box4(left, top, right, bottom)
    }

    @Composable
    private fun ThreeChips(dpad: Dpad, bounds: Bounds) {
        dpad.Install()
        Ground {
            ChipRow {
                Chip("Movies", false, {}, Modifier.onGloballyPositioned(bounds.record(FIRST)))
                Chip("Series", true, {}, Modifier.onGloballyPositioned(bounds.record(SECOND)))
                Chip("Live", false, {}, Modifier.onGloballyPositioned(bounds.record(THIRD)), count = "12")
            }
        }
    }

    @Test
    fun `a focused chip's ring is the same distance from the pill on all four sides, across the D-pad`() {
        val dpad = Dpad()
        val bounds = Bounds()
        Harness { ThreeChips(dpad, bounds) }.use { harness ->
            harness.settle()
            val rest = harness.pixels()
            val names = listOf(FIRST, SECOND, THIRD)
            val visuals = names.associateWith { rest.chipVisual(bounds[it]) }
            names.forEach { name ->
                assertEquals(visualHeight, visuals.getValue(name).bottom - visuals.getValue(name).top, name)
                assertEquals(visualTop, visuals.getValue(name).top, "$name stays on the step")
            }

            names.forEach { name ->
                assertTrue(dpad.press(FocusDirection.Next, harness), "focus reaches $name")
                harness.pixels().assertEvenRing(visuals.getValue(name), name)
            }
        }
    }

    @Test
    fun `an icon cell's ring is on its 24dp tile, the same distance on all four sides`() {
        val dpad = Dpad()
        val bounds = Bounds()
        Harness {
            dpad.Install()
            Ground {
                Row(horizontalArrangement = Arrangement.spacedBy(CloudStreamDimens.MinFocusGap)) {
                    IconCell({}, Modifier.onGloballyPositioned(bounds.record(FIRST))) {}
                    IconCell({}, Modifier.onGloballyPositioned(bounds.record(SECOND))) {}
                }
            }
        }.use { harness ->
            harness.settle()
            val rest = harness.pixels()
            val tile = IconCellDefaults.TileSize.value.toInt()
            val visuals = listOf(FIRST, SECOND).associateWith { rest.chipVisual(bounds[it]) }
            visuals.forEach { (name, visual) ->
                assertEquals(tile, visual.right - visual.left, "$name: the tile is drawn at rest")
                assertEquals(tile, visual.bottom - visual.top, "$name: the tile is drawn at rest")
            }
            listOf(FIRST, SECOND).forEach { name ->
                assertTrue(dpad.press(FocusDirection.Next, harness), "focus reaches $name")
                assertEquals(CloudStreamDimens.IconTargetCell.value, bounds[name].width, "$name: the cell is still 48dp")
                harness.pixels().assertEvenRing(visuals.getValue(name), name)
            }
        }
    }

    @Test
    fun `focusing a chip moves and resizes nothing`() {
        val dpad = Dpad()
        val bounds = Bounds()
        Harness { ThreeChips(dpad, bounds) }.use { harness ->
            harness.settle()
            val names = listOf(FIRST, SECOND, THIRD)
            val before = names.map { bounds[it] }
            before.forEach { assertEquals(cellHeight.toFloat(), it.height, "the focus cell is still 48dp") }
            val rest = harness.pixels()
            val visualsBefore = before.map { rest.chipVisual(it) }

            dpad.press(FocusDirection.Next, harness)
            assertEquals(before, names.map { bounds[it] }, "no cell moved")
            val frame = harness.pixels()
            assertEquals(visualsBefore[1], frame.chipVisual(before[1]), "the unfocused pill beside it did not move")
            assertEquals(visualsBefore[2], frame.chipVisual(before[2]), "nor the next")
        }
    }

    @Test
    fun `the ring takes the pill's corners, not a box's`() {
        val dpad = Dpad()
        val bounds = Bounds()
        Harness { ThreeChips(dpad, bounds) }.use { harness ->
            harness.settle()
            val visual = harness.pixels().chipVisual(bounds[FIRST])
            dpad.press(FocusDirection.Next, harness)
            val frame = harness.pixels()
            val out = gap + stroke
            assertFalse(frame[visual.left - out, visual.top - out].isRing(), "the corner of the ring's box stays empty")
            // On the stadium's 45-degree line: 15dp from the end cap's centre, in the middle of the stroke.
            assertTrue(frame[visual.left + 1, visual.top + 1].isRing(), "the ring follows the curve")
        }
    }

    @Test
    fun `a focused chip's ring draws over a neighbour that touches it`() {
        val dpad = Dpad()
        val bounds = Bounds()
        Harness {
            dpad.Install()
            Ground {
                Row {
                    Chip("Movies", false, {}, Modifier.onGloballyPositioned(bounds.record(FIRST)))
                    Chip("Series", false, {}, Modifier.onGloballyPositioned(bounds.record(SECOND)))
                }
            }
        }.use { harness ->
            harness.settle()
            dpad.press(FocusDirection.Next, harness)
            val frame = harness.pixels()
            val right = bounds[FIRST].right.toInt()
            val y = bounds[FIRST].center.y.toInt()
            assertEquals(right, bounds[SECOND].left.toInt(), "the two touch")
            assertTrue(frame[right + gap, y].isRing(), "the next chip's fill must not paint over the ring")
            assertTrue(frame[right + gap + stroke - 1, y].isRing(), "the next chip's fill must not paint over the ring")
        }
    }

    /**
     * An option's edges, from a frame where it is the chosen one and so the only one filled `line`. The row is below the
     * band's top edge and above the label. The control's outer edges are curved there, so those come from its bounds.
     */
    private fun PixelMap.optionVisual(control: Rect, index: Int, last: Int): Box4 {
        val y = visualTop + OPTION_SCAN_ROW
        val xs = control.left.toInt() until control.right.toInt()
        val fill = xs.filter { this[it, y] == colors.line }
        return Box4(
            left = if (index == 0) control.left.toInt() else fill.first(),
            top = visualTop,
            right = if (index == last) control.right.toInt() else fill.last() + 1,
            bottom = visualBottom,
        )
    }

    private fun segmentedHarness(selected: Int, dpad: Dpad, bounds: Bounds) = Harness {
        dpad.Install()
        Ground {
            SegmentedControl(
                listOf("Merged", "By source", "Latest"),
                selected,
                {},
                Modifier.onGloballyPositioned(bounds.record(CONTROL)),
            )
        }
    }

    @Test
    fun `a focused option's ring is the same distance from its visual on all four sides`() {
        repeat(OPTIONS) { index ->
            val dpad = Dpad()
            val bounds = Bounds()
            segmentedHarness(index, dpad, bounds).use { harness ->
                harness.settle()
                val control = bounds[CONTROL]
                assertEquals(cellHeight.toFloat(), control.height, "the control still measures its cell")
                val visual = harness.pixels().optionVisual(control, index, OPTIONS - 1)
                repeat(index + 1) { dpad.press(FocusDirection.Next, harness) }
                assertEquals(control, bounds[CONTROL], "focus moved nothing")
                harness.pixels().assertEvenRing(visual, "option $index")
            }
        }
    }

    @Test
    fun `a focused option's ring takes its own corners, rounded outside and square at a neighbour`() {
        val dpad = Dpad()
        val bounds = Bounds()
        segmentedHarness(0, dpad, bounds).use { harness ->
            harness.settle()
            val visual = harness.pixels().optionVisual(bounds[CONTROL], 0, OPTIONS - 1)
            dpad.press(FocusDirection.Next, harness)
            val frame = harness.pixels()
            // 8dp corner, so the stroke runs 10 to 12dp from a centre 8dp in on each axis.
            assertTrue(frame[visual.left, visual.top].isRing(), "the outer corner is rounded like the visual")
            // Square corner, so the stroke runs 2 to 4dp from the corner itself.
            assertTrue(frame[visual.right + gap, visual.top - gap].isRing(), "the inner corner is square like the visual")
        }
    }

    @Test
    fun `a focused option's ring draws over the chosen option beside it`() {
        val dpad = Dpad()
        val bounds = Bounds()
        segmentedHarness(1, dpad, bounds).use { harness ->
            harness.settle()
            val rest = harness.pixels()
            val first = rest.optionVisual(bounds[CONTROL], 1, OPTIONS - 1).left
            dpad.press(FocusDirection.Next, harness)
            val frame = harness.pixels()
            val y = visualTop + visualHeight / 2
            assertTrue(frame[first + gap, y].isRing(), "the chosen option's fill must not paint over the ring")
            assertTrue(frame[first + gap + stroke - 1, y].isRing(), "the chosen option's fill must not paint over the ring")
        }
    }

    private companion object {
        const val RING_TOLERANCE = 0.25f
        const val WALK_LIMIT = 24
        const val OPTION_SCAN_ROW = 4
        const val OPTIONS = 3
        const val FIRST = "first"
        const val SECOND = "second"
        const val THIRD = "third"
        const val CONTROL = "control"
    }
}
