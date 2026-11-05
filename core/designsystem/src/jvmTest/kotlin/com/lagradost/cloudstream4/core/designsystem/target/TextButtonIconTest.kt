package com.lagradost.cloudstream4.core.designsystem.target

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import com.lagradost.cloudstream4.core.designsystem.harness.Bounds
import com.lagradost.cloudstream4.core.designsystem.harness.Dpad
import com.lagradost.cloudstream4.core.designsystem.harness.Harness
import com.lagradost.cloudstream4.core.designsystem.icon.CloudStreamIconSize
import com.lagradost.cloudstream4.core.designsystem.icon.CloudStreamIcons
import com.lagradost.cloudstream4.core.designsystem.state.StateAction
import com.lagradost.cloudstream4.core.designsystem.state.StateActionButton
import com.lagradost.cloudstream4.core.theme.CloudStreamDimens
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme
import com.lagradost.cloudstream4.core.theme.cloudStreamDarkColors
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The owner, 2026-11-05 (`DEC-17`): *"use icons here if possible or other places which are common"*. A text button
 * for a common action leads with a glyph from `CloudStreamIcons`, and that adds the glyph's width and nothing else.
 *
 * Read at density 1, so a pixel is a dp. The glyph's box is found from where it must be, not from where it is drawn:
 * the padding, then [CloudStreamIconSize.Small], then [TextButtonDefaults.IconGap], then the label. A label long enough
 * to take the button past its 120dp minimum puts that run flush against the padding, so every column is known.
 */
class TextButtonIconTest {

    private val colors = cloudStreamDarkColors()
    private val padding = TextButtonDefaults.HorizontalPadding.value.toInt()
    private val glyph = CloudStreamIconSize.Small.dp.value.toInt()
    private val gap = TextButtonDefaults.IconGap.value.toInt()
    private val height = CloudStreamDimens.TextButtonHeight.value.toInt()

    private enum class Kind { Primary, Ghost, State }

    @Composable
    private fun Button(kind: Kind, label: String, icon: ImageVector?, modifier: Modifier) = when (kind) {
        Kind.Primary -> PrimaryTextButton(label = label, onClick = {}, modifier = modifier, icon = icon)
        Kind.Ghost -> GhostTextButton(label = label, onClick = {}, modifier = modifier, icon = icon)
        Kind.State -> StateActionButton(action = StateAction(label, icon) {}, modifier = modifier)
    }

    /** What the inside of the button is, where nothing is drawn on it. */
    private fun fillOf(kind: Kind): Color = when (kind) {
        Kind.Primary, Kind.State -> colors.ink
        Kind.Ghost -> colors.bg
    }

    /** What the label and the glyph are drawn in. */
    private fun inkOf(kind: Kind): Color = when (kind) {
        Kind.Primary, Kind.State -> colors.bg
        Kind.Ghost -> colors.ink
    }

    private fun bounds(kind: Kind, label: String, icon: ImageVector?): Rect {
        val bounds = Bounds()
        Harness { Ground { Button(kind, label, icon, Modifier.onGloballyPositioned(bounds.record(BUTTON))) } }
            .use { it.settle() }
        return bounds[BUTTON]
    }

    @Composable
    private fun Ground(content: @Composable () -> Unit) {
        Box(
            Modifier
                .fillMaxSize()
                .background(CloudStreamTheme.colors.bg)
                .padding(CloudStreamDimens.FocusBandInset),
        ) { content() }
    }

    private fun Color.near(other: Color, tolerance: Float = PIXEL_TOLERANCE): Boolean =
        abs(red - other.red) <= tolerance && abs(green - other.green) <= tolerance && abs(blue - other.blue) <= tolerance

    @Test
    fun `a glyph adds its width and the gap, and nothing else`() {
        Kind.entries.forEach { kind ->
            val plain = bounds(kind, LONG, icon = null)
            val withIcon = bounds(kind, LONG, CloudStreamIcons.Download)
            assertTrue(plain.width > CloudStreamDimens.TextButtonMinWidth.value, "$kind: the label is past the minimum")
            assertEquals((glyph + gap).toFloat(), withIcon.width - plain.width, "$kind: 16dp glyph plus 8dp gap")
            assertEquals(height.toFloat(), withIcon.height, "$kind: still 32dp tall")
            assertEquals(plain.topLeft, withIcon.topLeft, "$kind: the button did not move")
        }
    }

    @Test
    fun `a short label with a glyph still takes the 120dp minimum`() {
        Kind.entries.forEach { kind ->
            val withIcon = bounds(kind, SHORT, CloudStreamIcons.Retry)
            assertEquals(CloudStreamDimens.TextButtonMinWidth.value, withIcon.width, kind.name)
            assertEquals(height.toFloat(), withIcon.height, kind.name)
        }
    }

    @Test
    fun `the glyph is 16dp, centred on the height, in the label's colour, and 8dp before the label`() {
        Kind.entries.forEach { kind ->
            val bounds = Bounds()
            Harness {
                Ground { Button(kind, LONG, CloudStreamIcons.Download, Modifier.onGloballyPositioned(bounds.record(BUTTON))) }
            }.use { harness ->
                harness.settle()
                val frame = harness.pixels()
                val box = bounds[BUTTON]
                val left = box.left.toInt()
                val top = box.top.toInt()
                val glyphLeft = left + padding
                val glyphTop = top + (height - glyph) / 2
                val fill = fillOf(kind)

                assertEquals(height / 2, glyphTop - top + glyph / 2, "$kind: the glyph's centre is the button's")
                val glyphInk = inkIn(frame, glyphLeft until glyphLeft + glyph, glyphTop until glyphTop + glyph, fill)
                assertTrue(glyphInk.isNotEmpty(), "$kind: the glyph draws inside its 16dp box")
                assertTrue(
                    glyphInk.any { it.near(inkOf(kind), TINT_TOLERANCE) },
                    "$kind: the glyph is the label's colour, ${inkOf(kind)}; found ${glyphInk.take(4)}",
                )

                // Two pixels in from each edge, past the hairline and the corner curve.
                val rows = top + EDGE until top + height - EDGE
                assertEquals(
                    emptyList(),
                    // On the glyph's own rows: above and below them the shape's corners curve in.
                    inkIn(frame, left + EDGE until glyphLeft, glyphTop until glyphTop + glyph, fill),
                    "$kind: the padding before the glyph is empty",
                )
                assertEquals(
                    emptyList(),
                    inkIn(frame, glyphLeft until glyphLeft + glyph, rows.first until glyphTop, fill),
                    "$kind: nothing above the glyph's box",
                )
                assertEquals(
                    emptyList(),
                    inkIn(frame, glyphLeft until glyphLeft + glyph, glyphTop + glyph until rows.last + 1, fill),
                    "$kind: nothing below the glyph's box",
                )
                val labelLeft = glyphLeft + glyph + gap
                assertEquals(
                    emptyList(),
                    inkIn(frame, glyphLeft + glyph until labelLeft, rows, fill),
                    "$kind: the 8dp gap is empty",
                )
                val firstLabelInk = (labelLeft until labelLeft + LABEL_SEARCH).firstOrNull { x ->
                    inkIn(frame, x..x, rows, fill).isNotEmpty()
                }
                assertTrue(
                    firstLabelInk != null && firstLabelInk - labelLeft <= SIDE_BEARING,
                    "$kind: the label starts right after the gap, at $labelLeft; its ink starts at $firstLabelInk",
                )
            }
        }
    }

    @Test
    fun `the ring is where it was, with a glyph and without`() {
        listOf(Kind.Primary, Kind.Ghost, Kind.State).forEach { kind ->
            val plain = ringSides(kind, icon = null)
            val withIcon = ringSides(kind, CloudStreamIcons.Retry)
            val expected = Side(CloudStreamDimens.FocusRingOffset.value.toInt(), CloudStreamDimens.FocusRingStroke.value.toInt())
            assertEquals(List(4) { expected }, withIcon, "$kind: left, right, top, bottom")
            assertEquals(plain, withIcon, "$kind: the glyph changed the ring")
        }
    }

    private data class Side(val gap: Int, val stroke: Int)

    private fun ringSides(kind: Kind, icon: ImageVector?): List<Side> {
        val dpad = Dpad()
        val bounds = Bounds()
        return Harness {
            dpad.Install()
            Ground { Button(kind, SHORT, icon, Modifier.onGloballyPositioned(bounds.record(BUTTON))) }
        }.use { harness ->
            harness.settle()
            assertTrue(dpad.press(FocusDirection.Next, harness), "$kind takes focus")
            val frame = harness.pixels()
            val box = bounds[BUTTON]
            val midX = box.center.x.toInt()
            val midY = box.center.y.toInt()
            listOf(
                frame.side(box.left.toInt() - 1, midY, -1, 0),
                frame.side(box.right.toInt(), midY, 1, 0),
                frame.side(midX, box.top.toInt() - 1, 0, -1),
                frame.side(midX, box.bottom.toInt(), 0, 1),
            )
        }
    }

    private fun PixelMap.side(startX: Int, startY: Int, stepX: Int, stepY: Int): Side {
        fun ring(i: Int) = this[startX + stepX * i, startY + stepY * i].near(colors.focus)
        var clear = 0
        while (clear < WALK_LIMIT && !ring(clear)) clear++
        var drawn = 0
        while (drawn < WALK_LIMIT && ring(clear + drawn)) drawn++
        return Side(clear, drawn)
    }

    /** Every pixel in the area that is not [fill]. */
    private fun inkIn(frame: PixelMap, xs: IntRange, ys: IntRange, fill: Color): List<Color> =
        xs.flatMap { x -> ys.map { y -> frame[x, y] } }.filterNot { it.near(fill) }

    private companion object {
        const val BUTTON = "button"
        const val LONG = "Episodes of the whole season"
        const val SHORT = "Go"
        const val EDGE = 2
        const val LABEL_SEARCH = 8
        const val SIDE_BEARING = 3
        const val WALK_LIMIT = 12
        const val PIXEL_TOLERANCE = 0.02f
        const val TINT_TOLERANCE = 0.35f
    }
}
