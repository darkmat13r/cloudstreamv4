package com.lagradost.cloudstream4.core.designsystem.panel

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import com.lagradost.cloudstream4.core.designsystem.harness.Dpad
import com.lagradost.cloudstream4.core.designsystem.harness.Harness
import com.lagradost.cloudstream4.core.designsystem.harness.firstChangeOnRow
import com.lagradost.cloudstream4.core.designsystem.rows.ListRow
import com.lagradost.cloudstream4.core.designsystem.rows.ListRowState
import com.lagradost.cloudstream4.core.designsystem.target.GhostTextButton
import com.lagradost.cloudstream4.core.theme.CloudStreamDimens
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The panel's 240ms slide, and the two things it must not break on the way: the focus trap and the
 * restore.
 *
 * Every number here is read off a rendered frame or off real focus, because neither a slide nor a
 * focus trap shows in a still and the screenshot suite cannot render at all (`DEC-10`).
 *
 * The probe for "where is the panel" is deliberately indirect: the content behind it is one flat
 * colour, so the first colour change along a scanline **is** the panel's leading edge, and the test
 * asserts nothing about what the panel is made of.
 */
class SidePanelMotionTest {

    private val openEdge = Harness.WIDTH - CloudStreamDimens.SidePanelWidth.value.toInt()

    @Test
    fun `a closed panel is not on screen and an open one is flush against the right edge`() {
        val expanded = mutableStateOf(false)
        Harness { Host(expanded) }.use { harness ->
            harness.settle()
            assertNull(
                harness.pixels().firstChangeOnRow(PROBE_Y, Harness.WIDTH),
                "nothing but content should be drawn while the panel is closed",
            )

            expanded.value = true
            harness.settle()
            assertEquals(
                openEdge,
                harness.pixels().firstChangeOnRow(PROBE_Y, Harness.WIDTH),
                "an open panel's leading edge is its own width in from the right",
            )
        }
    }

    @Test
    fun `the panel is mid-travel halfway through the slide, and has arrived by the end of it`() {
        val expanded = mutableStateOf(false)
        Harness { Host(expanded) }.use { harness ->
            harness.settle()
            expanded.value = true
            harness.seedAnimation()

            harness.advance(SLIDE_MILLIS / 2)
            val midway = assertNotNull(
                harness.pixels().firstChangeOnRow(PROBE_Y, Harness.WIDTH),
                "the panel should already be drawing halfway through its entrance",
            )
            assertTrue(
                midway in (openEdge + 1) until Harness.WIDTH,
                "halfway through, the edge should be between open ($openEdge) and off screen " +
                    "(${Harness.WIDTH}); it was $midway",
            )

            harness.advance(SLIDE_MILLIS / 2 + ONE_FRAME)
            assertEquals(
                openEdge,
                harness.pixels().firstChangeOnRow(PROBE_Y, Harness.WIDTH),
                "240ms is the whole slide, so the panel is open by the end of it",
            )
        }
    }

    /**
     * The interruption contract: a panel dismissed mid-entrance leaves **from where it is**.
     *
     * The assertion is continuity rather than a position, which is what distinguishes the three
     * ways this can be got wrong. A panel that restarted its exit from fully-open would be found
     * *closer* to open than it was a frame earlier; one that snapped shut would not be drawn at
     * all; only one that reversed in place keeps moving outward.
     */
    @Test
    fun `a panel dismissed halfway through its entrance leaves from where it is`() {
        val expanded = mutableStateOf(false)
        Harness { Host(expanded) }.use { harness ->
            harness.settle()
            expanded.value = true
            harness.seedAnimation()
            harness.advance(SLIDE_MILLIS / 2)
            val atReversal = assertNotNull(
                harness.pixels().firstChangeOnRow(PROBE_Y, Harness.WIDTH),
                "the panel should be mid-entrance before anything is interrupted",
            )

            expanded.value = false
            harness.seedAnimation()
            harness.advance(ONE_FRAME)
            val afterReversal = assertNotNull(
                harness.pixels().firstChangeOnRow(PROBE_Y, Harness.WIDTH),
                "the panel must still be drawn while it leaves, not unmounted on the spot",
            )
            assertTrue(
                afterReversal > atReversal,
                "the exit should carry on outward from $atReversal, not restart; it was at " +
                    "$afterReversal",
            )

            harness.settle()
            assertNull(
                harness.pixels().firstChangeOnRow(PROBE_Y, Harness.WIDTH),
                "and it should be gone once the exit finishes",
            )
        }
    }

    @Test
    fun `focus moves into the panel as it opens, not after it has arrived`() {
        val expanded = mutableStateOf(false)
        val focused = mutableMapOf<String, Boolean>()
        Harness { Host(expanded, focused) }.use { harness ->
            harness.settle()
            expanded.value = true
            harness.seedAnimation()
            harness.advance(SLIDE_MILLIS / 2)
            assertEquals(
                true,
                focused[PANEL_ROW],
                "focus must arrive while the panel is still sliding, not when it lands: $focused",
            )
            // "While sliding" is asserted from the frame, not from a clock reading: `advance`
            // returns the scene's absolute time, which `settle()` has already run up.
            val edge = assertNotNull(harness.pixels().firstChangeOnRow(PROBE_Y, Harness.WIDTH))
            assertTrue(
                edge > openEdge,
                "the panel had not finished arriving when focus got there; its edge was at " +
                    "$edge, and open is $openEdge",
            )
        }
    }

    @Test
    fun `closing the panel gives focus back to the control that opened it`() {
        val expanded = mutableStateOf(false)
        val focused = mutableMapOf<String, Boolean>()
        Harness { Host(expanded, focused) }.use { harness ->
            harness.settle()
            assertEquals(true, focused[OPENER], "the opener starts focused: $focused")

            expanded.value = true
            harness.settle()
            assertEquals(false, focused[OPENER], "focus left the content for the panel: $focused")

            expanded.value = false
            harness.settle()
            assertEquals(
                true,
                focused[OPENER],
                "focus must come back to the control that opened the panel, not to the first " +
                    "control on the screen: $focused",
            )
            assertFalse(
                focused[ELSEWHERE] == true,
                "and specifically not to the content's first focusable: $focused",
            )
        }
    }

    /**
     * The handover happens when the exit **starts**, which is the half of it a user would notice:
     * at no point in the 240ms is the screen without a focus owner.
     *
     * Everything here is read while the panel is still on screen. A `settle()` would run 600ms
     * past a 240ms exit and probe a panel that had already unmounted, which is how the first
     * version of this test managed to ask its question of nothing at all.
     */
    @Test
    fun `closing hands focus back as the exit starts, not when it finishes`() {
        val expanded = mutableStateOf(true)
        val focused = mutableMapOf<String, Boolean>()
        Harness { Host(expanded, focused) }.use { harness ->
            harness.settle()
            assertEquals(true, focused[PANEL_ROW], "the panel holds focus while it is open")

            expanded.value = false
            repeat(FRAMES_INTO_EXIT) { harness.advance(ONE_FRAME) }
            assertNotNull(
                harness.pixels().firstChangeOnRow(PROBE_Y, Harness.WIDTH),
                "the panel should still be drawing this early in its exit",
            )
            assertFalse(
                focused[PANEL_ROW] == true,
                "focus has already left the panel: $focused",
            )
            assertEquals(true, focused[OPENER], "and the opener already has it back: $focused")
        }
    }

    /**
     * A D-pad move during the exit does **not** re-enter the leaving panel — but it does **lose
     * focus**, and that second half is a defect this test pins rather than hides.
     *
     * Measured: after a RIGHT move three frames into the exit, nothing at all is focused — not the
     * panel's row, and no longer the opener either. So the exit window is not a hole focus can
     * fall *into*, which is what mattered; it is a hole focus can fall *out of*, for the 240ms the
     * panel is still laid out to the right of the content. A second press recovers, because the
     * panel is gone by then.
     *
     * Not fixed here. Nothing in `PanelLayer` blocks entry any more — the guards that did also
     * broke the restore (see its KDoc) — and the remaining fix is for the *content* to refuse to
     * give focus up while a panel is leaving, which is a behaviour change to the host's content
     * group that wants its own change and its own look at the diff. It is reported with this
     * batch.
     */
    @Test
    fun `a D-pad move during the exit does not re-enter the leaving panel`() {
        val expanded = mutableStateOf(true)
        val focused = mutableMapOf<String, Boolean>()
        val dpad = Dpad()
        Harness { dpad.Install(); Host(expanded, focused) }.use { harness ->
            harness.settle()
            expanded.value = false
            repeat(FRAMES_INTO_EXIT) { harness.advance(ONE_FRAME) }
            assertNotNull(
                harness.pixels().firstChangeOnRow(PROBE_Y, Harness.WIDTH),
                "the panel is still on screen, so the question is a real one",
            )

            // Not a key press: arrow keys move no focus in an offscreen scene, so the first
            // version of this test passed without moving anything at all. See `Dpad`.
            dpad.press(FocusDirection.Right, harness)
            assertFalse(
                focused[PANEL_ROW] == true,
                "RIGHT during the exit must not land in the leaving panel: $focused",
            )
            assertFalse(
                focused[OPENER] == true,
                "the known defect, asserted so that fixing it fails here: the move drops focus " +
                    "instead of leaving it on the opener. $focused",
            )
        }
    }

    @Test
    fun `escape dismisses an open panel once, and a leaving one not at all`() {
        val expanded = mutableStateOf(true)
        var requests = 0
        Harness { Host(expanded, onDismissRequest = { requests++ }) }.use { harness ->
            harness.settle()
            assertTrue(harness.press(Key.Escape), "an open panel consumes escape")
            assertEquals(1, requests, "one request per press")

            expanded.value = false
            harness.advance(ONE_FRAME)
            harness.press(Key.Escape)
            assertEquals(1, requests, "a panel already leaving has nothing left to request")
        }
    }

    /** The host, its opener, and a one-row panel. */
    @Composable
    private fun Host(
        expanded: MutableState<Boolean>,
        focused: MutableMap<String, Boolean> = mutableMapOf(),
        onDismissRequest: () -> Unit = { expanded.value = false },
    ) {
        val opener = remember { FocusRequester() }
        SidePanelHost(
            expanded = expanded.value,
            onDismissRequest = onDismissRequest,
            panel = {
                SidePanel {
                    ListRow(
                        state = ListRowState(title = "1080p"),
                        onClick = {},
                        modifier = Modifier.onFocusChanged { focused[PANEL_ROW] = it.isFocused },
                    )
                }
            },
            content = {
                Box(Modifier.fillMaxSize().background(CloudStreamTheme.colors.bg)) {
                    Column {
                        GhostTextButton(
                            label = "Elsewhere",
                            onClick = {},
                            modifier = Modifier.onFocusChanged { focused[ELSEWHERE] = it.isFocused },
                        )
                        GhostTextButton(
                            label = "Sources",
                            onClick = { expanded.value = true },
                            modifier = Modifier
                                .focusRequester(opener)
                                .onFocusChanged { focused[OPENER] = it.isFocused },
                        )
                    }
                }
                LaunchedEffect(Unit) { opener.requestFocus() }
            },
        )
    }

    private companion object {
        /**
         * Inside the panel's empty 128dp header region, where it is one flat colour — and below
         * the content's two 32dp buttons, which the first version of this probe ran straight
         * through and reported as the panel's edge at x=1.
         */
        const val PROBE_Y = 100
        const val SLIDE_MILLIS = 240L
        const val ONE_FRAME = Harness.FRAME_MILLIS

        /**
         * Frames to run after a close: enough for the restore effect, few enough that the
         * panel is still mid-exit (3 x 20ms against a 240ms slide).
         */
        const val FRAMES_INTO_EXIT = 3
        const val OPENER = "opener"
        /** The content's *first* focusable, which is not the one that opened the panel. */
        const val ELSEWHERE = "elsewhere"
        const val PANEL_ROW = "panel-row"
    }
}
