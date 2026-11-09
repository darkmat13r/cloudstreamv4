package com.lagradost.cloudstream4.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lagradost.cloudstream4.core.designsystem.rows.ListRow
import com.lagradost.cloudstream4.core.designsystem.rows.ListRowState
import com.lagradost.cloudstream4.core.designsystem.target.GhostTextButton
import com.lagradost.cloudstream4.core.designsystem.target.IconCell
import com.lagradost.cloudstream4.core.designsystem.target.PrimaryTextButton
import com.lagradost.cloudstream4.core.devicetest.Dpad
import com.lagradost.cloudstream4.core.devicetest.DpadDriver
import com.lagradost.cloudstream4.core.devicetest.TouchModeReset
import com.lagradost.cloudstream4.core.devicetest.focusedLabel
import com.lagradost.cloudstream4.core.theme.CloudStreamDimens
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

/**
 * `DS-10`'s two new focus behaviours under a **real remote**, which is the half the JVM suite cannot
 * reach.
 *
 * The JVM tests in this module drive focus with `FocusManager.moveFocus`, because arrow keys move no
 * focus in an offscreen scene at all. That proves the focus *graph* — that the row and its trailing
 * cells are separate stops, and that a disabled-but-focusable control is in the graph — and it says
 * nothing about the thing a remote actually does: Android's two-dimensional focus search, which picks
 * a candidate by **geometry**. A row and a 48dp cell 12dp to its right have to overlap enough for
 * RIGHT to find the cell and LEFT to find the row again, and no amount of `moveFocus` on the desktop
 * owner demonstrates that. Hence a device.
 *
 * The fixture is the design system's own components with fakes for their callbacks — no screen, no
 * `:app`. Every stop is given a distinct name, because [DpadDriver] names a stop by its label and two
 * stops with the same label read as one.
 */
@RunWith(AndroidJUnit4::class)
class FocusStopsDeviceTest {

    /** `BUG-59`: starts and ends out of touch mode, whatever ran before it. */
    @get:Rule
    val touchMode = TouchModeReset()

    @get:Rule
    val rule = createComposeRule()

    private val dpad = DpadDriver(readFocus = { rule.focusedLabel() })
    private val pressed = CopyOnWriteArrayList<String>()

    /**
     * A row with two trailing stops, a disabled row that is still a stop, and a disabled button that
     * is still a stop — the anatomy of `TV Episodes Downloads` 6d and `TV Library` 5d side by side.
     *
     * The glyphs are plain `Box`es. `.claude/rules/icons.md` makes no exception for a test fixture,
     * and what is under test is the cell's focus behaviour rather than its picture.
     */
    private fun showFixture() {
        rule.setContent {
            CloudStreamTheme {
                Box(Modifier.fillMaxSize().background(CloudStreamTheme.colors.bg)) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(CloudStreamDimens.FocusBandInset),
                        verticalArrangement = Arrangement.spacedBy(CloudStreamDimens.MinFocusGap),
                    ) {
                        GhostTextButton(label = ABOVE, onClick = { pressed += ABOVE })
                        ListRow(
                            state = ListRowState(title = ROW, subtitle = "S3 E9 · 1.2 GB"),
                            onClick = { pressed += ROW },
                            modifier = Modifier.fillMaxWidth(),
                            actions = {
                                ActionCell(PLAY)
                                ActionCell(DELETE)
                            },
                        )
                        ListRow(
                            state = ListRowState(
                                title = DISABLED_ROW,
                                subtitle = "NOT STORED BY LOCAL LISTS",
                                enabled = false,
                                focusableWhenDisabled = true,
                            ),
                            onClick = { pressed += DISABLED_ROW },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        PrimaryTextButton(
                            label = DISABLED_BUTTON,
                            onClick = { pressed += DISABLED_BUTTON },
                            enabled = false,
                            focusableWhenDisabled = true,
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun ActionCell(name: String) {
        IconCell(
            onClick = { pressed += name },
            modifier = Modifier.semantics { contentDescription = name },
        ) {
            Box(Modifier.size(CloudStreamDimens.ChipGlyphSize).background(Color.White))
        }
    }

    /** Walks down to [name], which is how a person gets anywhere on a TV screen. */
    private fun focusByName(name: String): String {
        dpad.enter()
        return generateSequence { dpad.press(Dpad.Down) }
            .take(DOWN_ATTEMPTS)
            .firstOrNull { it == name }
            ?: error("$name was never reached with DOWN")
    }

    /**
     * The headline of `Components` §03's anatomy table: *"The trailing slot is a real focus stop with
     * its own ring. This is what made download and overflow buttons unreachable before."*
     *
     * RIGHT reaches each cell in turn and LEFT comes back, which is what makes 6d's
     * *"row ⇄ play ⇄ state verb ⇄ delete"* order real on a remote rather than in a focus graph.
     */
    @Test
    fun dpadReachesARowsTrailingStopsAndComesBack() {
        showFixture()
        focusByName(ROW)

        assertEquals(PLAY, dpad.press(Dpad.Right))
        assertEquals(DELETE, dpad.press(Dpad.Right))
        assertEquals(PLAY, dpad.press(Dpad.Left))
        assertEquals(ROW, dpad.press(Dpad.Left))
    }

    /** Each stop in that run acts on its own, and the row's click is not the cell's. */
    @Test
    fun theRowAndItsTrailingStopsEachActOnTheirOwn() {
        showFixture()
        focusByName(ROW)
        dpad.center()
        rule.waitUntil(WAIT_MS) { pressed.isNotEmpty() }
        assertEquals(listOf(ROW), pressed.toList())

        dpad.press(Dpad.Right)
        dpad.center()
        rule.waitUntil(WAIT_MS) { pressed.size > 1 }
        assertEquals(listOf(ROW, PLAY), pressed.toList())
    }

    /**
     * `TV Library` 5d: the sort criterion a provider cannot do *"stays visible and disabled with the
     * reason"*. A remote has to be able to land on it, and pressing it has to do nothing.
     */
    @Test
    fun aDisabledRowIsStillAStopAndCentreDoesNothing() {
        showFixture()
        assertEquals(DISABLED_ROW, focusByName(DISABLED_ROW))

        dpad.center()
        Thread.sleep(SETTLE_MS)
        assertEquals(emptyList<String>(), pressed.toList())
    }

    /**
     * `Components` §01: *"Disabled is 40% opacity and stays focusable so a loading screen never has
     * zero focus owners."*
     */
    @Test
    fun aDisabledButtonIsStillAStopAndCentreDoesNothing() {
        showFixture()
        assertEquals(DISABLED_BUTTON, focusByName(DISABLED_BUTTON))

        dpad.center()
        Thread.sleep(SETTLE_MS)
        assertEquals(emptyList<String>(), pressed.toList())
    }

    /**
     * Every stop reachable, nothing a remote cannot leave. The row-plus-actions run is exactly where
     * a trap would appear, because it is the one place this kit puts two focusables side by side with
     * only [CloudStreamDimens.MinFocusGap] between them.
     */
    @Test
    fun everyStopIsReachableWithNoDeadEndsOrTraps() {
        showFixture()

        val graph = dpad.walk()
        val expected = listOf(ABOVE, ROW, PLAY, DELETE, DISABLED_ROW, DISABLED_BUTTON)
        assertEquals(graph.toString(), emptyList<String>(), graph.unreachable(expected))
        assertEquals(graph.toString(), emptyList<String>(), graph.deadEnds())
        assertEquals(graph.toString(), emptyList<String>(), graph.traps())
        assertEquals(graph.toString(), emptySet<String>(), graph.keyboardOpenedOn)
    }

    private companion object {
        const val WAIT_MS = 10_000L
        const val SETTLE_MS = 1_000L
        const val DOWN_ATTEMPTS = 6
        const val ABOVE = "Above"
        const val ROW = "Riptide"
        const val PLAY = "Play"
        const val DELETE = "Delete"
        const val DISABLED_ROW = "Your rating"
        const val DISABLED_BUTTON = "Resume"
    }
}
