package com.lagradost.cloudstream4.core.designsystem.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream4.core.designsystem.card.ContinueCard
import com.lagradost.cloudstream4.core.designsystem.card.EpisodeCard
import com.lagradost.cloudstream4.core.designsystem.primitive.Avatar
import com.lagradost.cloudstream4.core.designsystem.primitive.AvatarDefaults
import com.lagradost.cloudstream4.core.designsystem.primitive.StatusDot
import com.lagradost.cloudstream4.core.designsystem.primitive.StatusDotState
import com.lagradost.cloudstream4.core.designsystem.primitive.StatusPill
import com.lagradost.cloudstream4.core.designsystem.primitive.StatusPillState
import com.lagradost.cloudstream4.core.designsystem.primitive.StorageMeter
import com.lagradost.cloudstream4.core.designsystem.primitive.StorageUsage
import com.lagradost.cloudstream4.core.designsystem.rows.DownloadProgressRow
import com.lagradost.cloudstream4.core.designsystem.rows.DownloadProgressRowState
import com.lagradost.cloudstream4.core.designsystem.target.SegmentedControl
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme
import com.lagradost.cloudstream4.core.theme.cloudStreamDarkColors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Bounds and states of the components `DS-08` added, drawn offscreen at density 1, so 1dp is 1px and a measured
 * size or a pixel coordinate reads as dp. Colours are read back from the render rather than from the source.
 */
class TvComponentsTest {

    private val colors = cloudStreamDarkColors()

    private class Rendered(val pixels: PixelMap, val size: IntSize) {
        operator fun get(x: Int, y: Int): Int = pixels[x, y].toArgb()

        fun has(color: Color): Boolean {
            val argb = color.toArgb()
            return (0 until pixels.width).any { x -> (0 until pixels.height).any { y -> pixels[x, y].toArgb() == argb } }
        }
    }

    /** Draws [content] on `bg` at +[MARGIN], with room for a ring, and reports the size it measured. */
    private fun render(content: @Composable (Modifier) -> Unit): Rendered {
        var measured = IntSize.Zero
        val scene = ImageComposeScene(CANVAS, CANVAS, Density(1f)) {
            CloudStreamTheme {
                Box(Modifier.fillMaxSize().background(CloudStreamTheme.colors.bg).padding(MARGIN.dp)) {
                    content(Modifier.onSizeChanged { measured = it })
                }
            }
        }
        return try {
            scene.render()
            Rendered(scene.render().toComposeImageBitmap().toPixelMap(), measured)
        } finally {
            scene.close()
        }
    }

    private fun Int.onStep() = assertEquals(0, this % STEP, "$this is not on the 4dp step")

    // ---- StatusDot and StatusPill ----------------------------------------------------------

    @Test
    fun `a failed dot is hollow and muted, and never live`() {
        val dot = render { StatusDot(StatusDotState.Failed, it) }
        assertEquals(colors.muted.toArgb(), dot[MARGIN, MARGIN], "the corner of the outline")
        assertEquals(colors.bg.toArgb(), dot[MARGIN + 4, MARGIN + 4], "the middle shows the ground")
        assertFalse(dot.has(colors.live))
    }

    @Test
    fun `ok and neutral dots stay filled`() {
        assertEquals(colors.ink.toArgb(), render { StatusDot(StatusDotState.Ok, it) }[MARGIN + 4, MARGIN + 4])
        assertEquals(colors.muted.toArgb(), render { StatusDot(StatusDotState.Neutral, it) }[MARGIN + 4, MARGIN + 4])
    }

    @Test
    fun `every pill state is the same height, on the step`() {
        StatusPillState.entries.forEach { state ->
            assertEquals(PILL_HEIGHT, render { StatusPill(state, "READY", it) }.size.height, "$state")
        }
        PILL_HEIGHT.onStep()
    }

    @Test
    fun `only a playing pill spends live`() {
        StatusPillState.entries.forEach { state ->
            val pill = render { StatusPill(state, "STATE", it) }
            assertEquals(state == StatusPillState.Playing, pill.has(colors.live), "$state")
        }
    }

    @Test
    fun `each pill marker reads differently`() {
        // Pill at +MARGIN, padding 8, an 8dp marker centred in 24: its middle is at x +12, y +12.
        val middle = MARGIN + 12
        assertEquals(colors.muted.toArgb(), render { StatusPill(StatusPillState.Probing, "P", it) }[middle, middle])
        assertEquals(colors.ink.toArgb(), render { StatusPill(StatusPillState.Ready, "R", it) }[middle, middle])
        assertEquals(colors.raised.toArgb(), render { StatusPill(StatusPillState.Failed, "F", it) }[middle, middle])
    }

    // ---- SegmentedControl ------------------------------------------------------------------

    private val twoOptions = listOf("Merged", "By source")

    @Test
    fun `the control measures its 48dp cell, with a 24dp visual centred on the step`() {
        val control = render { SegmentedControl(twoOptions, 0, {}, it) }
        assertEquals(CELL_HEIGHT, control.size.height)
        CELL_HEIGHT.onStep()
        VISUAL_TOP.onStep()
        val x = MARGIN + 12 // Past the 8dp corner, and before the label.
        assertEquals(colors.bg.toArgb(), control[x, MARGIN + VISUAL_TOP - 1], "above the visual")
        assertEquals(colors.line.toArgb(), control[x, MARGIN + VISUAL_TOP + 1], "the visual's top")
        assertEquals(colors.line.toArgb(), control[x, MARGIN + VISUAL_TOP + 22], "the visual's foot")
        assertEquals(colors.bg.toArgb(), control[x, MARGIN + VISUAL_TOP + 24], "below the visual")
    }

    @Test
    fun `only the chosen option is filled`() {
        val first = render { SegmentedControl(twoOptions, 0, {}, it) }
        val inFirst = MARGIN + 4
        val inLast = MARGIN + first.size.width - 4
        val y = MARGIN + CELL_HEIGHT / 2
        assertEquals(colors.line.toArgb(), first[inFirst, y], "chosen")
        assertEquals(colors.raised.toArgb(), first[inLast, y], "not chosen")
        val second = render { SegmentedControl(twoOptions, 1, {}, it) }
        assertEquals(colors.raised.toArgb(), second[inFirst, y], "not chosen")
        assertEquals(colors.line.toArgb(), second[inLast, y], "chosen")
    }

    @Test
    fun `choosing does not change what the control measures`() {
        assertEquals(
            render { SegmentedControl(twoOptions, 0, {}, it) }.size,
            render { SegmentedControl(twoOptions, 1, {}, it) }.size,
        )
    }

    @Test
    fun `it takes two to four options and a real index`() {
        assertFailsWith<IllegalArgumentException> { render { SegmentedControl(listOf("One"), 0, {}, it) } }
        assertFailsWith<IllegalArgumentException> { render { SegmentedControl(List(5) { "$it" }, 0, {}, it) } }
        assertFailsWith<IllegalArgumentException> { render { SegmentedControl(twoOptions, 2, {}, it) } }
    }

    @Test
    fun `a tap reports the option it landed on`() {
        val chosen = mutableListOf<Int>()
        var width = 0
        val scene = ImageComposeScene(CANVAS, CANVAS, Density(1f)) {
            CloudStreamTheme {
                SegmentedControl(twoOptions, 0, { chosen += it }, Modifier.onSizeChanged { width = it.width })
            }
        }
        try {
            scene.render()
            // Inside the visual, then in the cell above it: the whole 48dp is the target.
            listOf(Offset(width - 4f, CELL_HEIGHT / 2f), Offset(4f, 1f)).forEach { at ->
                scene.sendPointerEvent(PointerEventType.Press, at)
                scene.sendPointerEvent(PointerEventType.Release, at)
                scene.render()
            }
        } finally {
            scene.close()
        }
        assertEquals(listOf(1, 0), chosen)
    }

    @Test
    fun `each option is a focus stop and the focused one wears the ring above its neighbour`() {
        var manager: FocusManager? = null
        var width = 0
        val scene = ImageComposeScene(CANVAS, CANVAS, Density(1f)) {
            CloudStreamTheme {
                manager = LocalFocusManager.current
                Box(Modifier.fillMaxSize().background(CloudStreamTheme.colors.bg).padding(MARGIN.dp)) {
                    SegmentedControl(twoOptions, 1, {}, Modifier.onSizeChanged { width = it.width })
                }
            }
        }
        try {
            scene.render()
            assertTrue(manager!!.moveFocus(FocusDirection.Next), "first stop")
            scene.render()
            val focus = colors.focus.toArgb()
            val first = scene.render().toComposeImageBitmap().toPixelMap()
            // The ring is on the visual: 2dp offset and 2dp thick, so it covers 3dp above the 24dp, and not above the cell.
            assertEquals(focus, first[MARGIN + 8, MARGIN + VISUAL_TOP - 3].toArgb())
            assertFalse(first[MARGIN + 8, MARGIN - 3].toArgb() == focus, "nothing rings the 48dp cell")
            // Its right side crosses the chosen neighbour's fill, which would cover it if it did not draw above.
            // This line is inside the visual and above the labels, so only the ring can be this colour.
            val y = MARGIN + VISUAL_TOP + 2
            assertTrue(
                (MARGIN + 2 until MARGIN + width - 2).any { first[it, y].toArgb() == focus },
                "the ring's inner side is visible over the neighbour",
            )
            assertTrue(manager!!.moveFocus(FocusDirection.Next), "second stop")
            scene.render()
            val second = scene.render().toComposeImageBitmap().toPixelMap()
            assertFalse(second[MARGIN + 8, MARGIN + VISUAL_TOP - 3].toArgb() == focus, "the ring left the first option")
        } finally {
            scene.close()
        }
    }

    // ---- Cards -----------------------------------------------------------------------------

    @Test
    fun `an episode card is 128 by 112, whatever it holds`() {
        val expected = IntSize(128, 112)
        assertEquals(expected, render { EpisodeCard("The Relay", "48M", it) { } }.size)
        assertEquals(expected, render { EpisodeCard("The Relay", null, it, focused = true, number = "E4", progress = .6f) { } }.size)
        expected.height.onStep()
    }

    @Test
    fun `a continue card is 192 by 152, whatever it holds`() {
        val expected = IntSize(192, 152)
        assertEquals(expected, render { ContinueCard("The Long Dark", "S2 E4", .6f, it) { } }.size)
        assertEquals(expected, render { ContinueCard("The Long Dark", null, 1f, it, focused = true) { } }.size)
        expected.height.onStep()
    }

    @Test
    fun `an unwatched episode still draws the track, with no fill`() {
        val card = render { EpisodeCard("Cold Start", "44M", it) { } }
        // The 4dp rule is on the art's foot, which ends at +72.
        val y = MARGIN + 70
        assertEquals(colors.line.toArgb(), card[MARGIN + 2, y])
        assertEquals(colors.line.toArgb(), card[MARGIN + 126, y])
        assertFalse(card.has(colors.live))
    }

    @Test
    fun `watched progress fills from the start in live`() {
        val card = render { EpisodeCard("Deck Four", "46M", it, progress = .5f) { } }
        val y = MARGIN + 70
        assertEquals(colors.live.toArgb(), card[MARGIN + 2, y])
        assertEquals(colors.line.toArgb(), card[MARGIN + 126, y])
    }

    @Test
    fun `a continue card keeps its rule below the art`() {
        val card = render { ContinueCard("Nine Lives", "S3 E2", 1f, it) { Box(Modifier.fillMaxSize().background(Color.Blue)) } }
        assertEquals(Color.Blue.toArgb(), card[MARGIN + 96, MARGIN + 107], "the art runs to +108")
        assertEquals(colors.bg.toArgb(), card[MARGIN + 96, MARGIN + 109], "gap")
        assertEquals(colors.live.toArgb(), card[MARGIN + 96, MARGIN + 113], "rule, +112..116")
        assertEquals(colors.bg.toArgb(), card[MARGIN + 96, MARGIN + 117], "gap")
    }

    @Test
    fun `progress out of range is held to the bar, not thrown`() {
        val over = render { ContinueCard("A", null, 7f, it) { } }
        assertEquals(colors.live.toArgb(), over[MARGIN + 190, MARGIN + 113])
        val nan = render { ContinueCard("A", null, Float.NaN, it) { } }
        assertFalse(nan.has(colors.live))
    }

    @Test
    fun `the ring goes around the art and only when focused`() {
        val focus = colors.focus.toArgb()
        val on = render { EpisodeCard("A", null, it, focused = true) { } }
        assertEquals(focus, on[MARGIN + 64, MARGIN - 3], "above the art")
        assertEquals(focus, on[MARGIN + 64, MARGIN + 74], "below the art, not below the text")
        val off = render { EpisodeCard("A", null, it) { } }
        assertFalse(off[MARGIN + 64, MARGIN - 3] == focus)
    }

    // ---- Avatar ----------------------------------------------------------------------------

    @Test
    fun `an avatar is 80dp, with or without a headshot, focused or not`() {
        val expected = IntSize(AvatarDefaults.Size.value.toInt(), AvatarDefaults.Size.value.toInt())
        assertEquals(expected, render { Avatar("BC", it) }.size)
        assertEquals(expected, render { Avatar("BC", it, focused = true) { Box(Modifier.size(200.dp)) } }.size)
        expected.height.onStep()
    }

    @Test
    fun `a headshot is cropped to the circle`() {
        val avatar = render { Avatar("BC", it) { Box(Modifier.fillMaxSize().background(Color.Blue)) } }
        assertEquals(Color.Blue.toArgb(), avatar[MARGIN + 40, MARGIN + 40], "the middle")
        assertEquals(colors.bg.toArgb(), avatar[MARGIN + 1, MARGIN + 1], "the corner")
    }

    @Test
    fun `no headshot gives a raised disc`() {
        val avatar = render { Avatar("", it) }
        assertEquals(colors.raised.toArgb(), avatar[MARGIN + 40, MARGIN + 40])
        assertEquals(colors.bg.toArgb(), avatar[MARGIN + 1, MARGIN + 1])
        assertFalse(avatar.has(colors.ink2))
        assertTrue(render { Avatar("BC", it) }.has(colors.ink2), "initials draw")
    }

    // ---- StorageMeter ----------------------------------------------------------------------

    @Test
    fun `the meter is three parts in board order, and 24dp tall in every state`() {
        val meter = render {
            StorageMeter(StorageUsage(other = .5f, downloads = .25f), "384 GB OF 512 GB USED", it.width(METER_WIDTH.dp))
        }
        assertEquals(METER_HEIGHT, meter.size.height)
        METER_HEIGHT.onStep()
        val y = MARGIN + 22
        assertEquals(colors.muted.toArgb(), meter[MARGIN + 199, y], "other, to 200")
        assertEquals(colors.ink.toArgb(), meter[MARGIN + 201, y], "downloads, 200 to 300")
        assertEquals(colors.ink.toArgb(), meter[MARGIN + 299, y], "downloads, 200 to 300")
        assertEquals(colors.line.toArgb(), meter[MARGIN + 301, y], "free")
        assertTrue(meter.has(colors.ink2), "the legend draws, so its absence below means something")
        assertFalse(meter.has(colors.live))
    }

    @Test
    fun `loading is an undivided bar with no legend, at the same height`() {
        val meter = render { StorageMeter(null, "IGNORED", it.width(METER_WIDTH.dp)) }
        assertEquals(METER_HEIGHT, meter.size.height)
        val y = MARGIN + 22
        listOf(100, 201, 301).forEach { assertEquals(colors.line.toArgb(), meter[MARGIN + it, y], "x $it") }
        assertFalse(meter.has(colors.ink2), "the legend is suppressed")
    }

    @Test
    fun `usage can never run past the bar`() {
        val usage = StorageUsage(other = .75f, downloads = .5f)
        assertEquals(.75f, usage.other)
        assertEquals(.25f, usage.downloads)
        assertEquals(1f, StorageUsage(other = 3f, downloads = 1f).other)
        assertEquals(0f, StorageUsage(other = 3f, downloads = 1f).downloads)
        assertEquals(0f, StorageUsage(other = -1f, downloads = -1f).other)
    }

    // ---- DownloadProgressRow ---------------------------------------------------------------

    private val rowWidth = Modifier.width(ROW_WIDTH.dp)

    @Test
    fun `a download row is a list row tall, with or without a bar`() {
        val height = render { DownloadProgressRow(DownloadProgressRowState("A"), {}, it.then(rowWidth)) }.size.height
        assertEquals(ROW_HEIGHT, height)
        val barred = DownloadProgressRowState("A", "B", "25%", progress = .25f)
        assertEquals(ROW_HEIGHT, render { DownloadProgressRow(barred, {}, it.then(rowWidth)) }.size.height)
    }

    @Test
    fun `transfer is ink on a line track, inset 8dp, with its top edge on the step`() {
        val row = render {
            DownloadProgressRow(DownloadProgressRowState("A", progress = .5f), {}, it.then(rowWidth))
        }
        val y = MARGIN + 44
        assertEquals(colors.raised.toArgb(), row[MARGIN + 100, MARGIN + 43], "above the bar")
        assertEquals(colors.ink.toArgb(), row[MARGIN + 100, y], "filled half")
        assertEquals(colors.line.toArgb(), row[MARGIN + ROW_WIDTH - 12, y], "the track")
        assertEquals(colors.raised.toArgb(), row[MARGIN + ROW_WIDTH - 4, y], "the inset")
        assertFalse(row.has(colors.live))
    }

    @Test
    fun `a failed download stalls in muted and spends no live`() {
        val state = DownloadProgressRowState("A", "storage full", "19%", progress = .5f, failed = true)
        val row = render { DownloadProgressRow(state, {}, it.then(rowWidth)) }
        assertEquals(colors.muted.toArgb(), row[MARGIN + 100, MARGIN + 44])
        assertFalse(row.has(colors.live))
    }

    private companion object {
        const val CANVAS = 800
        const val MARGIN = 16
        const val STEP = 4
        const val PILL_HEIGHT = 24
        const val CELL_HEIGHT = 48
        const val VISUAL_TOP = 12
        const val METER_WIDTH = 400
        const val METER_HEIGHT = 24
        const val ROW_WIDTH = 400
        const val ROW_HEIGHT = 48
    }
}
