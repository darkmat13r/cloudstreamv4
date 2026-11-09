package com.lagradost.cloudstream4.core.designsystem.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream4.core.designsystem.card.MediaCardDefaults
import com.lagradost.cloudstream4.core.designsystem.card.MediaCardSlots
import com.lagradost.cloudstream4.core.designsystem.card.MediaCardState
import com.lagradost.cloudstream4.core.designsystem.card.PosterCard
import com.lagradost.cloudstream4.core.designsystem.card.PosterRung
import com.lagradost.cloudstream4.core.designsystem.card.SeeAllCard
import com.lagradost.cloudstream4.core.designsystem.harness.Bounds
import com.lagradost.cloudstream4.core.designsystem.harness.Harness
import com.lagradost.cloudstream4.core.designsystem.panel.SidePanel
import com.lagradost.cloudstream4.core.designsystem.panel.SidePanelHost
import com.lagradost.cloudstream4.core.designsystem.panel.SidePanelOutsideDim
import com.lagradost.cloudstream4.core.designsystem.panel.sidePanelOutsideDim
import com.lagradost.cloudstream4.core.designsystem.primitive.Badge
import com.lagradost.cloudstream4.core.designsystem.primitive.BadgeDefaults
import com.lagradost.cloudstream4.core.designsystem.rows.ListRow
import com.lagradost.cloudstream4.core.designsystem.rows.ListRowState
import com.lagradost.cloudstream4.core.designsystem.rows.RailHeader
import com.lagradost.cloudstream4.core.designsystem.state.FailureBanner
import com.lagradost.cloudstream4.core.designsystem.state.FailureNotice
import com.lagradost.cloudstream4.core.designsystem.state.StateAction
import com.lagradost.cloudstream4.core.designsystem.state.StatePrompt
import com.lagradost.cloudstream4.core.designsystem.state.StateView
import com.lagradost.cloudstream4.core.designsystem.target.Chip
import com.lagradost.cloudstream4.core.theme.CloudStreamCardSizes
import com.lagradost.cloudstream4.core.theme.CloudStreamDimens
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme
import com.lagradost.cloudstream4.core.theme.cloudStreamDarkColors
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The shared-component findings from the design review of the TV screens, each held by what is drawn.
 *
 * Every case reads rendered pixels at density 1, so a pixel is a dp, and each one fails on the drawing it replaced.
 */
class DesignReviewFixesTest {

    private val colors = cloudStreamDarkColors()

    private fun PixelMap.argb(x: Int, y: Int) = this[x, y].toArgb()

    private fun PixelMap.close(x: Int, y: Int, expected: Color): Boolean {
        val actual = this[x, y]
        val step = 1.5f / 255f
        return abs(actual.red - expected.red) <= step &&
            abs(actual.green - expected.green) <= step &&
            abs(actual.blue - expected.blue) <= step
    }

    /** The brightest channel anywhere in [rows] x [columns], 0 to 255. */
    private fun PixelMap.brightest(columns: IntRange, rows: IntRange): Int =
        rows.maxOf { y -> columns.maxOf { x -> (maxOf(this[x, y].red, this[x, y].green, this[x, y].blue) * 255).roundToInt() } }

    @Composable
    private fun Ground(content: @Composable () -> Unit) {
        Box(Modifier.fillMaxSize().background(CloudStreamTheme.colors.bg).padding(MARGIN.dp)) { content() }
    }

    @Composable
    private fun FlatArt() {
        Box(Modifier.fillMaxSize().background(ART))
    }

    // ---- 1. Badge ---------------------------------------------------------------------------

    @Test
    fun `a badge sits 4dp in, on a small corner and a 78 percent ground`() {
        Harness {
            Ground {
                PosterCard(
                    rung = PosterRung.Medium,
                    state = MediaCardState(),
                    slots = MediaCardSlots(leadingBadge = { Badge(text = "    ") }),
                ) { FlatArt() }
            }
        }.use { harness ->
            harness.settle()
            val frame = harness.pixels()
            val ground = colors.bg.copy(alpha = BadgeDefaults.GroundAlpha).compositeOver(ART)
            val inset = MediaCardDefaults.ContentPadding.value.toInt()
            val midY = MARGIN + inset + BadgeDefaults.Height.value.toInt() / 2
            val left = (MARGIN until MARGIN + 40).first { !frame.close(it, midY, ART) }
            assertEquals(MARGIN + 4, left, "the badge's left edge, 4dp in")
            assertTrue(frame.close(left + 8, midY, ground), "the ground is bg at 0.78 over the art")
            val midX = left + 8
            val top = (MARGIN until MARGIN + 40).first { !frame.close(midX, it, ART) }
            assertEquals(MARGIN + 4, top, "the badge's top edge, 4dp in")
            // A 4dp corner covers the pixel one in from the corner; a pill's 8dp radius does not.
            assertFalse(frame.close(left + 1, top + 1, ART), "the corner is shapes.small, not a pill")
        }
    }

    // ---- 2. Card reveal ---------------------------------------------------------------------

    /** The first row, from the card's top along its inset column, that is not the art. */
    private fun revealTop(title: String): Pair<Int, Int> {
        var line = 0f
        Harness {
            line = CloudStreamTheme.typography.body.lineHeight.value
            Ground {
                PosterCard(rung = PosterRung.Medium, state = MediaCardState(focused = true, title = title)) { FlatArt() }
            }
        }.use { harness ->
            harness.settle()
            val frame = harness.pixels()
            // A focused card is lifted, so its top is above the margin.
            val cardTop = MARGIN - CloudStreamDimens.FocusLift.value.toInt()
            val x = MARGIN + 1
            val top = (cardTop until cardTop + CARD_HEIGHT).first { !frame.close(x, it, ART) }
            val solid = colors.bg.copy(alpha = MediaCardDefaults.SCRIM_END_ALPHA).compositeOver(ART)
            assertTrue(frame.close(x, cardTop + CARD_HEIGHT - 2, solid), "the bottom of the block is solid at 0.92")
            return top - cardTop to line.roundToInt()
        }
    }

    @Test
    fun `the reveal scrim is a block behind the text, not a fixed share of the card`() {
        val (oneLine, line) = revealTop("Up")
        val (twoLines, _) = revealTop("Salt Road Crossing the Bay")
        val lead = MediaCardDefaults.RevealScrimLead.value.toInt()
        val inset = MediaCardDefaults.ContentPadding.value.toInt()
        val block = lead + line + inset
        // The fade starts at zero, so the first visibly changed row is a few rows below the block's top.
        assertTrue(oneLine in CARD_HEIGHT - block..CARD_HEIGHT - block + FADE_IN, "one line: $oneLine, block $block")
        assertTrue(
            twoLines in CARD_HEIGHT - block - line..CARD_HEIGHT - block - line + FADE_IN,
            "two lines: $twoLines, one line higher than $oneLine",
        )
    }

    // ---- 3. See all -------------------------------------------------------------------------

    @Test
    fun `see all is a dark ground in a dashed line edge, with no glyph`() {
        Harness {
            Ground { SeeAllCard(size = CloudStreamCardSizes.PortraitMedium, label = " ", focused = false) }
        }.use { harness ->
            harness.settle()
            val frame = harness.pixels()
            val width = CloudStreamCardSizes.PortraitMedium.width.value.toInt()
            val height = CloudStreamCardSizes.PortraitMedium.height.value.toInt()
            val ground = colors.surface.copy(alpha = MediaCardDefaults.SEE_ALL_GROUND_ALPHA).compositeOver(colors.bg)
            assertEquals(Color(0xFF060A0D).toArgb(), ground.toArgb(), "the board's #060A0D, from the tokens")
            val inside = MARGIN + 2 until MARGIN + width - 2
            assertTrue(inside.all { frame.close(it, MARGIN + height / 2, ground) }, "the face is the ground and nothing else")
            assertTrue(
                (MARGIN + 2 until MARGIN + height - 2).all { frame.close(MARGIN + width / 2, it, ground) },
                "no glyph above or below the label",
            )
            val edge = (MARGIN until MARGIN + width).map { frame.close(it, MARGIN, colors.line) }
            assertTrue(edge.count { it } > width / 3, "the top edge is drawn in line")
            assertTrue(edge.count { !it } > width / 4, "and dashed")
        }
    }

    // ---- 4. Secondary text ------------------------------------------------------------------

    @Test
    fun `a list row's second line is muted, not ink2`() {
        Harness {
            Ground {
                ListRow(state = ListRowState(title = " ", subtitle = "S3 E9 · 1.2 GB"), onClick = {}, modifier = Modifier.width(ROW_WIDTH.dp))
            }
        }.use { harness ->
            harness.settle()
            val frame = harness.pixels()
            val text = frame.brightest(MARGIN until MARGIN + ROW_WIDTH, MARGIN until MARGIN + ROW_HEIGHT)
            assertTrue(text in TEXT_SEEN..MUTED_LEVEL, "the brightest pixel, $text, is drawn and no brighter than muted")
        }
    }

    @Test
    fun `a state view's body is muted, not ink2`() {
        Harness {
            Ground {
                StateView(
                    prompt = StatePrompt(headline = " ", body = "Downloads you start will appear here.", action = StateAction(" ") {}),
                    requestInitialFocus = false,
                )
            }
        }.use { harness ->
            harness.settle()
            val frame = harness.pixels()
            val all = MARGIN until Harness.WIDTH - MARGIN
            // The action button is the one ink fill: everything above its first row is the copy.
            val button = (MARGIN until Harness.HEIGHT - MARGIN).first { y -> all.count { frame.argb(it, y) == colors.ink.toArgb() } > BUTTON_RUN }
            val text = frame.brightest(all, MARGIN until button - 2)
            assertTrue(text in TEXT_SEEN..MUTED_LEVEL, "the body's brightest pixel, $text, is drawn and no brighter than muted")
        }
    }

    // ---- 5. Chip as a tab ---------------------------------------------------------------------

    @Test
    fun `a selected chip is raised with an ink label and a muted edge, and an unselected one is muted on a line edge`() {
        val bounds = Bounds()
        Harness {
            Ground {
                Row {
                    Chip("Watching", selected = true, onClick = {}, modifier = Modifier.onGloballyPositioned(bounds.record(ON)), count = "142")
                    Chip("Completed", selected = false, onClick = {}, modifier = Modifier.onGloballyPositioned(bounds.record(OFF)), count = "88")
                }
            }
        }.use { harness ->
            harness.settle()
            val frame = harness.pixels()
            listOf(ON to colors.muted, OFF to colors.line).forEach { (name, edge) ->
                // Down the middle of the pill, where its edge is straight.
                val cell = bounds[name]
                val x = cell.center.x.toInt()
                val top = (cell.top.toInt() until cell.bottom.toInt()).first { frame.argb(x, it) != colors.bg.toArgb() }
                assertEquals(edge.toArgb(), frame.argb(x, top), "$name: the edge")
                assertEquals(colors.raised.toArgb(), frame.argb(x, top + 2), "$name: the fill is raised")
            }
            val on = bounds[ON].pixels()
            val off = bounds[OFF].pixels()
            assertTrue(frame.brightest(on.first, on.second) > INK2_LEVEL, "the selected label is ink")
            assertTrue(frame.brightest(off.first, off.second) <= MUTED_LEVEL, "the unselected label is muted")
            assertFalse(
                on.second.any { y -> on.first.count { frame.argb(it, y) == colors.ink.toArgb() } > BUTTON_RUN },
                "no white fill: white is focus only",
            )
        }
    }

    private fun Rect.pixels(): Pair<IntRange, IntRange> = left.toInt() until right.toInt() to (top.toInt() until bottom.toInt())

    // ---- 6. Failure banner ----------------------------------------------------------------------

    @Test
    fun `a failure banner spends no live, and its retry is the ink primary`() {
        Harness {
            Ground {
                FailureBanner(FailureNotice(headline = "3 sources failed", detail = "TIMEOUT", retry = StateAction("Retry failed") {}))
            }
        }.use { harness ->
            harness.settle()
            val frame = harness.pixels()
            val all = 0 until Harness.WIDTH
            val rows = 0 until Harness.HEIGHT
            assertFalse(rows.any { y -> all.any { frame.argb(it, y) == colors.live.toArgb() } }, "no live pixel")
            assertTrue(rows.any { y -> all.count { frame.argb(it, y) == colors.ink.toArgb() } > BUTTON_RUN }, "an ink fill")
        }
    }

    // ---- 7. Side panel ----------------------------------------------------------------------------

    @Test
    fun `the panel is surface, a selected row is raised, and the header starts at the chrome row`() {
        val bounds = Bounds()
        Harness(height = PANEL_HEIGHT) {
            SidePanel(header = { RailHeader(title = "SOURCES") }) {
                ListRow(ListRowState(title = " ", selected = true), {}, Modifier.onGloballyPositioned(bounds.record(ON)))
                ListRow(ListRowState(title = " "), {}, Modifier.onGloballyPositioned(bounds.record(OFF)))
            }
        }.use { harness ->
            harness.settle()
            val frame = harness.pixels()
            val on = bounds[ON]
            val off = bounds[OFF]
            val x = (on.right - ROW_PROBE).toInt()
            assertEquals(colors.raised.toArgb(), frame.argb(x, on.center.y.toInt()), "a selected row is raised")
            assertEquals(colors.surface.toArgb(), frame.argb(x, off.center.y.toInt()), "an unselected row shows the panel")
            assertEquals(colors.surface.toArgb(), frame.argb(x, (on.bottom + 4).toInt()), "the panel is surface")
            val inset = CloudStreamDimens.SidePanelContentInset.value.toInt()
            val header = (0 until CloudStreamDimens.SidePanelFirstRowY.value.toInt()).first { y ->
                (inset until inset + HEADER_SCAN).any { frame.argb(it, y) != colors.surface.toArgb() }
            }
            val top = CloudStreamDimens.ChromeRowTop.value.toInt()
            assertTrue(header in top until top + HEADER_SLACK, "the header's first ink is at $header, below $top as 4d")
        }
    }

    // ---- 8. The dim outside the host --------------------------------------------------------------

    @Test
    fun `an open panel dims a view outside its host, and closing it clears the dim`() {
        var expanded by mutableStateOf(false)
        Harness {
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.size(OUTSIDE.dp).background(colors.ink).sidePanelOutsideDim(colors.bg))
                SidePanelHost(expanded = expanded, onDismissRequest = {}, panel = { SidePanel { } }) {
                    Box(Modifier.fillMaxSize().background(colors.ink))
                }
            }
        }.use { harness ->
            harness.settle()
            assertEquals(colors.ink.toArgb(), harness.pixels().argb(OUTSIDE / 2, OUTSIDE / 2), "closed: no dim")
            assertEquals(0f, SidePanelOutsideDim.alpha)

            expanded = true
            harness.settle()
            val outside = harness.pixels()[OUTSIDE / 2, OUTSIDE / 2]
            val inside = harness.pixels()[OUTSIDE + 10, OUTSIDE / 2]
            assertTrue(SidePanelOutsideDim.alpha > 0.5f, "open: published ${SidePanelOutsideDim.alpha}")
            assertEquals(inside.toArgb(), outside.toArgb(), "the view outside is dimmed exactly as the host's content")

            expanded = false
            harness.settle()
            assertEquals(0f, SidePanelOutsideDim.alpha, "closed again")
            assertEquals(colors.ink.toArgb(), harness.pixels().argb(OUTSIDE / 2, OUTSIDE / 2), "and the dim is gone")
        }
    }

    private companion object {
        const val MARGIN = 16
        val ART = Color(0xFF808080)
        const val CARD_HEIGHT = 120
        const val FADE_IN = 4
        const val ROW_WIDTH = 300
        const val ROW_HEIGHT = 48
        const val MUTED_LEVEL = 0x85
        const val INK2_LEVEL = 0xCC
        const val TEXT_SEEN = 0x50
        const val BUTTON_RUN = 40
        const val ON = "selected"
        const val OFF = "unselected"
        const val PANEL_HEIGHT = 540
        const val ROW_PROBE = 12f
        const val HEADER_SCAN = 200
        const val HEADER_SLACK = 8
        const val OUTSIDE = 40
    }
}
