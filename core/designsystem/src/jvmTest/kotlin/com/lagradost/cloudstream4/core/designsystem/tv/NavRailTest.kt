package com.lagradost.cloudstream4.core.designsystem.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathData
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream4.core.designsystem.rail.NavRail
import com.lagradost.cloudstream4.core.designsystem.rail.NavRailItem
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme
import com.lagradost.cloudstream4.core.theme.cloudStreamDarkColors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * [NavRail] drawn offscreen at density 1, so a pixel coordinate reads as dp. Everything is read back from the render
 * or from layout callbacks. The glyph is a square that fills its viewport, so its bounds in the render are its box.
 */
class NavRailTest {

    private val colors = cloudStreamDarkColors()
    private val ink = colors.ink.toArgb()
    private val ink2 = colors.ink2.toArgb()
    private val raised = colors.raised.toArgb()
    private val ground = colors.bg.toArgb()
    private val muted = colors.muted.toArgb()
    private val behind = Color.Blue.toArgb()

    private val square = ImageVector.Builder(
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).addPath(
        pathData = PathData {
            moveTo(0f, 0f)
            lineTo(24f, 0f)
            lineTo(24f, 24f)
            lineTo(0f, 24f)
            close()
        },
        pathFillType = PathFillType.NonZero,
        fill = SolidColor(Color.White),
    ).build()

    private val items = List(ITEM_COUNT) { NavRailItem("Item $it", square) }

    /** The rail, then a focusable sibling filling the rest of a `Row`, on a [behind] ground that shows the paint. */
    private inner class Rig(selected: Int) {
        var selectedIndex by mutableStateOf(selected)
        val selections = mutableListOf<Int>()
        val railWidths = mutableSetOf<Int>()
        val siblingStarts = mutableSetOf<Float>()
        val railFocusLog = mutableListOf<Boolean>()
        val requester = FocusRequester()
        private var manager: FocusManager? = null

        val scene = ImageComposeScene(CANVAS_WIDTH, CANVAS_HEIGHT, Density(1f)) {
            CloudStreamTheme {
                manager = LocalFocusManager.current
                Box(Modifier.fillMaxSize().background(Color.Blue)) {
                    Row(Modifier.fillMaxSize()) {
                        NavRail(
                            items = items,
                            selectedIndex = selectedIndex,
                            onSelect = { selections += it },
                            modifier = Modifier
                                .focusRequester(requester)
                                .onSizeChanged { railWidths += it.width }
                                .onFocusChanged { railFocusLog += it.hasFocus },
                        )
                        Box(
                            Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .onGloballyPositioned { siblingStarts += it.positionInRoot().x }
                                .focusable(),
                        )
                    }
                }
            }
        }

        fun move(direction: FocusDirection): Boolean = requireNotNull(manager).moveFocus(direction)

        fun frame(): PixelMap {
            scene.render()
            return scene.render().toComposeImageBitmap().toPixelMap()
        }

        fun singleFrame(): PixelMap = scene.render().toComposeImageBitmap().toPixelMap()

        fun tap(x: Float, y: Float) {
            val at = Offset(x, y)
            scene.sendPointerEvent(PointerEventType.Press, at)
            scene.sendPointerEvent(PointerEventType.Release, at)
            scene.render()
        }

        // The only way to build a key event without a window. Test scope, so an upgrade that moves it fails here alone.
        @OptIn(InternalComposeUiApi::class)
        fun press(key: Key) {
            scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyDown))
            scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyUp))
            scene.render()
        }
    }

    private fun rig(selected: Int = 0, block: Rig.() -> Unit) {
        val rig = Rig(selected)
        try {
            rig.scene.render()
            rig.block()
        } finally {
            rig.scene.close()
        }
    }

    private fun PixelMap.argb(x: Int, y: Int) = this[x, y].toArgb()

    private fun PixelMap.expectCell(index: Int, x: Int, expected: Int, what: String) =
        assertEquals(expected, argb(x, itemTop(index) + FILL_PROBE), "item $index at x $x: $what")

    /** Inclusive-exclusive bounds of [argb] within a box of the render, as (left, top, right, bottom). */
    private fun PixelMap.bounds(argb: Int, xs: IntRange, ys: IntRange): List<Int> {
        val hits = xs.flatMap { x -> ys.filter { y -> this.argb(x, y) == argb }.map { y -> x to y } }
        assertTrue(hits.isNotEmpty(), "no pixel of that colour in $xs, $ys")
        return listOf(
            hits.minOf { it.first },
            hits.minOf { it.second },
            hits.maxOf { it.first } + 1,
            hits.maxOf { it.second } + 1,
        )
    }

    // ---- Measuring -------------------------------------------------------------------------

    @Test
    fun `it measures 76 collapsed and expanded, and the sibling never moves`() = rig {
        frame()
        assertEquals(setOf(RAIL_WIDTH), railWidths)
        assertEquals(setOf(RAIL_WIDTH.toFloat()), siblingStarts)
        assertTrue(move(FocusDirection.Next))
        val expanded = frame()
        expanded.expectCell(0, EXPANDED_PROBE_X, ink, "proves it did expand")
        assertEquals(
            setOf(RAIL_WIDTH) to setOf(RAIL_WIDTH.toFloat()),
            railWidths to siblingStarts,
            "what the rail measured, and where the sibling started",
        )
    }

    @Test
    fun `collapsed, the band ends at 76 and the sibling shows at once`() = rig {
        val rest = frame()
        assertEquals(behind, rest.argb(RAIL_WIDTH, BELOW_ITEMS))
        assertEquals(behind, rest.argb(PAST_COLLAPSED_X, BELOW_ITEMS))
        assertNotEquals(behind, rest.argb(0, BELOW_ITEMS), "the band is there at all")
    }

    @Test
    fun `expanded, it paints past 76 over the sibling and fades out by 188`() = rig {
        assertTrue(move(FocusDirection.Next))
        val open = frame()
        assertEquals(ground, open.argb(0, BELOW_ITEMS), "solid at the start")
        assertEquals(ground, open.argb(PAST_COLLAPSED_X, BELOW_ITEMS), "solid over the sibling, 72% of 188 is 135")
        val nearEdge = open.argb(EXPANDED_WIDTH - 1, BELOW_ITEMS)
        assertNotEquals(ground, nearEdge, "faded by the far edge")
        assertEquals(behind, open.argb(EXPANDED_WIDTH, BELOW_ITEMS), "nothing past 188")
    }

    // ---- Geometry --------------------------------------------------------------------------

    @Test
    fun `the first item is at 72, the pitch is 52 and the cell is 48`() {
        (0 until ITEM_COUNT).forEach { index ->
            rig(selected = index) {
                val rest = frame()
                val top = itemTop(index)
                val cell = rest.bounds(raised, CELL_LEFT until RAIL_WIDTH + 8, 0 until CANVAS_HEIGHT)
                assertEquals(listOf(CELL_LEFT, top, CELL_LEFT + CELL, top + CELL), cell, "item $index")
            }
        }
    }

    @Test
    fun `the glyph is 16dp at 28 + 16 collapsed`() = rig {
        val rest = frame()
        val top = itemTop(0)
        assertEquals(
            listOf(CELL_LEFT + 16, top + 16, CELL_LEFT + 32, top + 32),
            rest.bounds(ink, CELL_LEFT until RAIL_WIDTH, top until top + CELL),
        )
    }

    @Test
    fun `the glyph is 16dp at 28 + 12 expanded, and the items do not move vertically`() = rig {
        assertTrue(move(FocusDirection.Next))
        val open = frame()
        val top = itemTop(0)
        assertEquals(
            listOf(CELL_LEFT + 12, top + 16, CELL_LEFT + 28, top + 32),
            open.bounds(
                ground,
                CELL_LEFT + FILL_PROBE until LABEL_LEFT - 1,
                top + FILL_PROBE until top + CELL - FILL_PROBE,
            ),
        )
        assertEquals(
            listOf(CELL_LEFT, top, EXPANDED_ITEM_RIGHT, top + CELL),
            open.bounds(ink, 0 until CANVAS_WIDTH, top until top + CELL),
        )
    }

    @Test
    fun `an item's corners are the medium token, 8dp`() = rig {
        val rest = frame()
        val top = itemTop(0)
        assertNotEquals(raised, rest.argb(CELL_LEFT + 1, top + 1), "a 4dp arc is past here; an 8dp one is not")
        assertEquals(raised, rest.argb(CELL_LEFT + 4, top + 4), "the 8dp arc is past here")
    }

    // ---- States ----------------------------------------------------------------------------

    @Test
    fun `at rest the current page is raised with an ink glyph, the others are muted on the band`() = rig(selected = 1) {
        val rest = frame()
        rest.expectCell(1, CELL_LEFT + 8, raised, "current")
        assertEquals(ink, rest.argb(CELL_LEFT + 24, itemTop(1) + 24), "current glyph")
        assertNotEquals(raised, rest.argb(CELL_LEFT + 8, itemTop(0) + FILL_PROBE), "not current")
        assertEquals(muted, rest.argb(CELL_LEFT + 24, itemTop(0) + 24), "other glyph")
    }

    @Test
    fun `the focused item is ink with a bg glyph, and the current page stays raised while focus travels`() =
        rig(selected = 2) {
            assertTrue(move(FocusDirection.Next))
            val onFirst = frame()
            onFirst.expectCell(0, CELL_LEFT + 8, ink, "focused")
            assertEquals(ground, onFirst.argb(CELL_LEFT + 20, itemTop(0) + 24), "focused glyph")
            onFirst.expectCell(1, CELL_LEFT + 8, ground, "neither")
            assertEquals(muted, onFirst.argb(CELL_LEFT + 20, itemTop(1) + 24), "neither glyph")
            onFirst.expectCell(2, CELL_LEFT + 8, raised, "current")
            assertEquals(ink2, onFirst.argb(CELL_LEFT + 20, itemTop(2) + 24), "current glyph, expanded")

            assertTrue(move(FocusDirection.Down))
            assertTrue(move(FocusDirection.Down))
            val onCurrent = frame()
            onCurrent.expectCell(2, CELL_LEFT + 8, ink, "focus beats current")
            onCurrent.expectCell(0, CELL_LEFT + 8, ground, "left behind")

            assertTrue(move(FocusDirection.Down))
            val past = frame()
            past.expectCell(3, CELL_LEFT + 8, ink, "focused")
            past.expectCell(2, CELL_LEFT + 8, raised, "current again")
            assertEquals(emptyList(), selections, "travelling commits nothing")
        }

    @Test
    fun `labels show only when expanded`() = rig(selected = 1) {
        val labelBox = LABEL_LEFT until EXPANDED_ITEM_RIGHT
        val rest = frame()
        val y = itemTop(1) until itemTop(1) + CELL
        assertFalse(labelBox.any { x -> y.any { rest.argb(x, it) == ink2 || rest.argb(x, it) == ink } }, "collapsed")
        assertTrue(move(FocusDirection.Next))
        val open = frame()
        assertTrue(labelBox.any { x -> y.any { open.argb(x, it) == ink2 } }, "the current item's label")
        val focusedY = itemTop(0) until itemTop(0) + CELL
        assertTrue(labelBox.any { x -> focusedY.any { open.argb(x, it) == ground } }, "the focused item's label")
    }

    // ---- Focus -----------------------------------------------------------------------------

    @Test
    fun `focus survives the expansion and moves item to item`() = rig(selected = ITEM_COUNT - 1) {
        frame()
        railFocusLog.clear()
        assertTrue(move(FocusDirection.Next), "into the rail")
        repeat(SETTLE_FRAMES) { frame() }
        assertEquals(listOf(true), railFocusLog, "the rail gained focus once and never lost it")
        var pixels = frame()
        pixels.expectCell(0, EXPANDED_PROBE_X, ink, "still focused once wide")
        (1 until ITEM_COUNT).forEach { index ->
            assertTrue(move(FocusDirection.Down), "to item $index")
            pixels = frame()
            pixels.expectCell(index, EXPANDED_PROBE_X, ink, "focused")
            pixels.expectCell(index - 1, CELL_LEFT + 8, ground, "the last one let go")
        }
        assertEquals(listOf(true), railFocusLog, "travelling stayed inside")
        assertFalse(move(FocusDirection.Down), "nothing below the last item; focus stays")
        frame().expectCell(ITEM_COUNT - 1, EXPANDED_PROBE_X, ink, "stayed")
    }

    @Test
    fun `leaving the rail collapses it, and LEFT from the content opens it again`() = rig(selected = 1) {
        assertTrue(move(FocusDirection.Next))
        frame().expectCell(0, EXPANDED_PROBE_X, ink, "open")
        assertTrue(move(FocusDirection.Right), "onto the sibling")
        val closed = frame()
        assertEquals(behind, closed.argb(PAST_COLLAPSED_X, BELOW_ITEMS), "no band past 76")
        closed.expectCell(0, EXPANDED_PROBE_X, behind, "no item past 76")
        closed.expectCell(1, CELL_LEFT + 8, raised, "the current page is still marked")
        assertEquals(false, railFocusLog.last())
        assertTrue(move(FocusDirection.Left), "back")
        val reopened = frame()
        assertEquals(ground, reopened.argb(PAST_COLLAPSED_X, BELOW_ITEMS), "open again")
        assertEquals(true, railFocusLog.last())
    }

    @Test
    fun `a requester on the modifier puts focus in the rail, which is how a screen opens it`() = rig {
        requester.requestFocus()
        frame().expectCell(0, EXPANDED_PROBE_X, ink, "the first item took it")
    }

    /**
     * The ring the boards draw on a collapsed cell (`.nic.foc`) cannot occur: the first frame after focus arrives is
     * already wide, so there is no frame of a focused cell at 48dp to put it on.
     */
    @Test
    fun `no frame ever shows a focused collapsed cell, and no ring is drawn`() = rig {
        frame()
        assertTrue(move(FocusDirection.Next))
        val first = singleFrame()
        first.expectCell(0, EXPANDED_PROBE_X, ink, "the very first frame is already wide and filled")
        val top = itemTop(0)
        assertNotEquals(ink, first.argb(CELL_LEFT + CELL / 2, top - RING_OUTSET), "no ring above the cell")
        assertNotEquals(ink, first.argb(CELL_LEFT - RING_OUTSET, top + CELL / 2), "no ring beside the cell")
    }

    // ---- Commit ----------------------------------------------------------------------------

    @Test
    fun `a tap on a collapsed rail reports the index it landed on`() = rig {
        (ITEM_COUNT - 1 downTo 0).forEach { tap(CELL_LEFT + 24f, itemTop(it) + 24f) }
        assertEquals((ITEM_COUNT - 1 downTo 0).toList(), selections)
    }

    @Test
    fun `an expanded item is hit past 76, where the rail measured nothing`() = rig {
        assertTrue(move(FocusDirection.Next))
        frame()
        tap(EXPANDED_PROBE_X.toFloat(), itemTop(3) + 24f)
        assertEquals(listOf(3), selections)
    }

    @Test
    fun `centre and Enter commit the focused item, and selectedIndex is the caller's`() = rig {
        assertTrue(move(FocusDirection.Next))
        assertTrue(move(FocusDirection.Down))
        frame()
        press(Key.DirectionCenter)
        press(Key.Enter)
        assertEquals(listOf(1, 1), selections)
        frame().expectCell(0, CELL_LEFT + 8, raised, "the rail does not move the mark itself")
        selectedIndex = 1
        val moved = frame()
        moved.expectCell(1, CELL_LEFT + 8, ink, "focused beats current")
        moved.expectCell(0, CELL_LEFT + 8, ground, "the mark left")
    }

    // ---- Contract --------------------------------------------------------------------------

    @Test
    fun `a selectedIndex outside the items throws`() {
        listOf(-1, ITEM_COUNT).forEach { bad ->
            assertFailsWith<IllegalArgumentException>("$bad") {
                val scene = ImageComposeScene(CANVAS_WIDTH, CANVAS_HEIGHT, Density(1f)) {
                    CloudStreamTheme { NavRail(items, bad, {}) }
                }
                scene.close()
            }
        }
        assertFailsWith<IllegalArgumentException>("no items") {
            val scene = ImageComposeScene(CANVAS_WIDTH, CANVAS_HEIGHT, Density(1f)) {
                CloudStreamTheme { NavRail(emptyList(), 0, {}) }
            }
            scene.close()
        }
    }

    private companion object {
        const val CANVAS_WIDTH = 400
        const val CANVAS_HEIGHT = 500
        const val ITEM_COUNT = 5
        const val RAIL_WIDTH = 76
        const val EXPANDED_WIDTH = 188
        const val CELL_LEFT = 28
        const val CELL = 48
        const val FIRST_ITEM_TOP = 72
        const val PITCH = 52
        const val EXPANDED_ITEM_RIGHT = 180
        const val LABEL_LEFT = 64
        const val FILL_PROBE = 4
        const val EXPANDED_PROBE_X = 170
        const val PAST_COLLAPSED_X = 100
        const val BELOW_ITEMS = 400
        const val RING_OUTSET = 3
        const val SETTLE_FRAMES = 3

        fun itemTop(index: Int) = FIRST_ITEM_TOP + index * PITCH
    }
}
