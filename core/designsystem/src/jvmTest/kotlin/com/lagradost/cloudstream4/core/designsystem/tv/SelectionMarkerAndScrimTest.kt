package com.lagradost.cloudstream4.core.designsystem.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream4.core.designsystem.harness.Bounds
import com.lagradost.cloudstream4.core.designsystem.harness.Harness
import com.lagradost.cloudstream4.core.designsystem.panel.SidePanel
import com.lagradost.cloudstream4.core.designsystem.panel.SidePanelHost
import com.lagradost.cloudstream4.core.designsystem.panel.SidePanelOutsideDim
import com.lagradost.cloudstream4.core.designsystem.rows.ListRow
import com.lagradost.cloudstream4.core.designsystem.rows.ListRowState
import com.lagradost.cloudstream4.core.theme.CloudStreamDimens
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme
import com.lagradost.cloudstream4.core.theme.cloudStreamDarkColors
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `DS-15`: two shared details the design review measured off the emulator, each held by what is drawn at density 1.
 * Both cases fail on the drawing they replace: a 2dp by 32dp square rule, and a 0.72 scrim.
 */
class SelectionMarkerAndScrimTest {

    private val colors = cloudStreamDarkColors()

    @Test
    fun `a selected row's marker is 4dp by 24dp, rounded, and centred in the row`() {
        val bounds = Bounds()
        Harness {
            Box(Modifier.fillMaxSize().background(CloudStreamTheme.colors.bg).padding(MARGIN.dp)) {
                ListRow(
                    ListRowState(title = " ", selected = true),
                    {},
                    Modifier.width(ROW_WIDTH.dp).onGloballyPositioned(bounds.record(ROW)),
                )
            }
        }.use { harness ->
            harness.settle()
            val frame = harness.pixels()
            val row = bounds[ROW]
            val left = row.left.toInt()
            val top = row.top.toInt()
            val raised = colors.raised.toArgb()
            // Everything not raised between the row's rounded corners and its text is the marker.
            val marked = (top + CORNER_CLEAR until top + ROW_HEIGHT - CORNER_CLEAR).flatMap { y ->
                (left + CORNER_CLEAR until left + LEAD_SCAN).filter { x -> frame[x, y].toArgb() != raised }.map { x -> x to y }
            }
            val xs = marked.map { it.first }
            val ys = marked.map { it.second }
            val cell = left + CloudStreamDimens.ListRowPadding.value.toInt()
            assertEquals(cell, xs.min(), "the mark starts at the cell")
            assertEquals(cell + MARK_WIDTH - 1, xs.max(), "and fills its 4dp")
            assertEquals(top + (ROW_HEIGHT - MARK_HEIGHT) / 2, ys.min(), "24dp tall, centred: top")
            assertEquals(top + (ROW_HEIGHT + MARK_HEIGHT) / 2 - 1, ys.max(), "24dp tall, centred: bottom")
            val mid = top + ROW_HEIGHT / 2
            assertEquals(colors.muted.toArgb(), frame[cell + 1, mid].toArgb(), "muted in the middle")
            val corner = top + (ROW_HEIGHT - MARK_HEIGHT) / 2
            assertTrue(frame[cell, corner].toArgb() != colors.muted.toArgb(), "the corner is rounded off")
        }
    }

    @Test
    fun `the panel's scrim is 4d's 0 point 6, inside the host and outside it`() {
        Harness {
            SidePanelHost(expanded = true, onDismissRequest = {}, panel = { SidePanel { } }) {
                Box(Modifier.fillMaxSize().background(CloudStreamTheme.colors.ink))
            }
        }.use { harness ->
            harness.settle()
            assertTrue(abs(SidePanelOutsideDim.alpha - SCRIM) < EPSILON, "published ${SidePanelOutsideDim.alpha}")
            val expected = colors.bg.copy(alpha = SCRIM).compositeOver(colors.ink)
            val actual = harness.pixels()[PROBE, PROBE]
            val step = 1.5f / 255f
            assertTrue(
                abs(actual.red - expected.red) <= step && abs(actual.green - expected.green) <= step &&
                    abs(actual.blue - expected.blue) <= step,
                "the content under the scrim is $actual, expected $expected",
            )
        }
    }

    private companion object {
        const val MARGIN = 16
        const val ROW = "row"
        const val ROW_WIDTH = 300
        const val ROW_HEIGHT = 48
        const val LEAD_SCAN = 16
        /** Past the 8dp corner's arc, which leaves `bg` showing at the row's corners. */
        const val CORNER_CLEAR = 6
        const val MARK_WIDTH = 4
        const val MARK_HEIGHT = 24
        const val SCRIM = 0.6f
        const val EPSILON = 0.001f
        const val PROBE = 10
    }
}
