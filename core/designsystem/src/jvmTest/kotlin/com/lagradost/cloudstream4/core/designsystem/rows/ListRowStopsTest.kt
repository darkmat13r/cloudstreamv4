package com.lagradost.cloudstream4.core.designsystem.rows

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.layout.onGloballyPositioned
import com.lagradost.cloudstream4.core.designsystem.harness.Bounds
import com.lagradost.cloudstream4.core.designsystem.harness.Dpad
import com.lagradost.cloudstream4.core.designsystem.harness.Harness
import com.lagradost.cloudstream4.core.designsystem.target.GhostTextButton
import com.lagradost.cloudstream4.core.designsystem.target.IconCell
import com.lagradost.cloudstream4.core.theme.CloudStreamDimens
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `ListRow`'s two new behaviours, both of which exist because a D-pad could not reach something.
 *
 * - **The trailing focus stop.** `TV Episodes Downloads` 6d draws three verb cells beside each row,
 *   96x96 px at x1496/1616/1736 while the row itself ends at x1472. Those numbers are 48dp cells
 *   on 12dp gaps, flush with the trailing edge, and that is what the geometry tests check.
 * - **The disabled row that is still a stop.** `TV Library` 5d's unsupported sort criterion
 *   *"stays visible and disabled with the reason"*, and a reason nobody can put focus on is a
 *   reason nobody reads at three metres.
 */
class ListRowStopsTest {

    private val cell = CloudStreamDimens.IconTargetCell.value
    private val gap = CloudStreamDimens.MinFocusGap.value
    private val rowHeight = CloudStreamDimens.ListRowHeight.value

    @Test
    fun `the gap between two stops has room for both rings`() {
        assertTrue(
            gap >= CloudStreamDimens.FocusRingBleed.value * SIDES,
            "a ${gap}dp gap must hold two ${CloudStreamDimens.FocusRingBleed.value}dp ring " +
                "bleeds, or neighbouring rings touch",
        )
    }

    @Test
    fun `actions are 48dp cells, 12dp apart, flush with the row's trailing edge`() {
        val bounds = Bounds()
        Harness {
            ListRow(
                state = ListRowState(title = "Ozymandias", subtitle = "S5 E14"),
                onClick = {},
                modifier = Modifier.fillMaxWidth().onGloballyPositioned(bounds.record(RUN)),
                trailing = { BasicText(text = "4.2 MB/S") },
                actions = {
                    repeat(ACTION_COUNT) { index ->
                        ActionCell(Modifier.onGloballyPositioned(bounds.record("$ACTION$index")))
                    }
                },
            )
        }.use { harness ->
            harness.settle()

            val run = bounds[RUN]
            assertEquals(rowHeight, run.height, "the run is one row tall")

            val cells = List(ACTION_COUNT) { bounds["$ACTION$it"] }
            cells.forEachIndexed { index, rect ->
                assertEquals(cell, rect.width, "action $index is a ${cell}dp cell")
                assertEquals(cell, rect.height, "action $index is a ${cell}dp cell")
                assertEquals(run.top, rect.top, "action $index sits on the row's own band")
            }
            cells.zipWithNext { left, right ->
                assertEquals(gap, right.left - left.right, "actions are ${gap}dp apart")
            }
            assertEquals(
                run.right,
                cells.last().right,
                "the last action ends flush with the run, as 6d's x1832 does",
            )
            assertEquals(
                run.right - cell * ACTION_COUNT - gap * (ACTION_COUNT - 1),
                cells.first().left,
                "the actions take their intrinsic width and the row takes the rest",
            )
        }
    }

    @Test
    fun `a row without actions is still exactly one row tall`() {
        val bounds = Bounds()
        Harness {
            ListRow(
                state = ListRowState(title = "Automatic quality"),
                onClick = {},
                modifier = Modifier.fillMaxWidth().onGloballyPositioned(bounds.record(RUN)),
            )
        }.use { harness ->
            harness.settle()
            assertEquals(rowHeight, bounds[RUN].height)
        }
    }

    @Test
    fun `the row and each of its actions are separate focus stops`() {
        val focused = mutableMapOf<String, Boolean>()
        val pressed = mutableListOf<String>()
        val dpad = Dpad()
        Harness {
            FocusableColumn(dpad) {
                ListRow(
                    state = ListRowState(title = "Ozymandias"),
                    onClick = { pressed += ROW },
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { focused[RUN] = it.hasFocus },
                    actions = {
                        repeat(ACTION_COUNT) { index ->
                            ActionCell(
                                modifier = Modifier.onFocusChanged {
                                    focused["$ACTION$index"] = it.isFocused
                                },
                                onClick = { pressed += "$ACTION$index" },
                            )
                        }
                    },
                )
            }
        }.use { harness ->
            harness.settle()
            dpad.press(FocusDirection.Down, harness)
            assertEquals(true, focused[RUN], "DOWN from the opener enters the row: $focused")

            // Which stop inside the run holds focus is read from what Enter does, because the row
            // body is not observable from outside: with `actions` the caller's `modifier` lands on
            // the run. If a screen ever needs to watch the row column itself, that is a hook to
            // add then — no board needs it yet.
            harness.press(Key.Enter)
            assertEquals(listOf(ROW), pressed, "the row is its own stop and acts on its own")

            repeat(ACTION_COUNT) { index ->
                dpad.press(FocusDirection.Right, harness)
                assertEquals(
                    true,
                    focused["$ACTION$index"],
                    "RIGHT ${index + 1} should reach action $index: $focused",
                )
                harness.press(Key.Enter)
                assertEquals("$ACTION$index", pressed.last(), "each action acts on its own")
            }
            assertEquals(1, pressed.count { it == ROW }, "the row never acted twice: $pressed")
        }
    }

    @Test
    fun `a disabled row is not a focus stop`() {
        val focused = mutableMapOf<String, Boolean>()
        val dpad = Dpad()
        Harness {
            FocusableColumn(dpad) {
                ListRow(
                    state = ListRowState(
                        title = "Your rating",
                        subtitle = "NOT STORED BY LOCAL LISTS",
                        enabled = false,
                    ),
                    onClick = {},
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { focused[ROW] = it.isFocused },
                )
            }
        }.use { harness ->
            harness.settle()
            dpad.press(FocusDirection.Down, harness)
            assertFalse(
                focused[ROW] == true,
                "the default disabled row keeps the behaviour it has always had: $focused",
            )
        }
    }

    @Test
    fun `a disabled row that keeps its stop takes focus and still does not act`() {
        val focused = mutableMapOf<String, Boolean>()
        var clicks = 0
        val dpad = Dpad()
        Harness {
            FocusableColumn(dpad) {
                ListRow(
                    state = ListRowState(
                        title = "Your rating",
                        subtitle = "NOT STORED BY LOCAL LISTS",
                        enabled = false,
                        focusableWhenDisabled = true,
                    ),
                    onClick = { clicks++ },
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { focused[ROW] = it.isFocused },
                )
            }
        }.use { harness ->
            harness.settle()
            dpad.press(FocusDirection.Down, harness)
            assertEquals(
                true,
                focused[ROW],
                "5d's row has to be reachable for its reason to be readable: $focused",
            )

            harness.press(Key.Enter)
            assertEquals(0, clicks, "reachable is not the same as actionable")
        }
    }

    /** A focusable above the row under test, so DOWN is what enters it. */
    @Composable
    private fun FocusableColumn(dpad: Dpad, row: @Composable () -> Unit) {
        dpad.Install()
        val opener = remember { FocusRequester() }
        Column {
            GhostTextButton(
                label = "Above",
                onClick = {},
                modifier = Modifier.focusRequester(opener),
            )
            row()
        }
        LaunchedEffect(Unit) { opener.requestFocus() }
    }

    /**
     * Stands in for the real occupant of an action slot, at the same
     * [CloudStreamDimens.IconTargetCell] an `IconCell` is, so the measurements are 6d's.
     *
     * It *is* an [IconCell] — with a `Box` for a glyph, because `.claude/rules/icons.md` does not
     * make an exception for tests.
     */
    @Composable
    private fun ActionCell(modifier: Modifier = Modifier, onClick: () -> Unit = {}) {
        IconCell(onClick = onClick, modifier = modifier) {
            Box(Modifier.size(CloudStreamDimens.ChipGlyphSize).background(Color.White))
        }
    }

    private companion object {
        const val RUN = "run"
        const val ROW = "row"
        const val ACTION = "action"
        const val ACTION_COUNT = 3
        const val SIDES = 2
    }
}
