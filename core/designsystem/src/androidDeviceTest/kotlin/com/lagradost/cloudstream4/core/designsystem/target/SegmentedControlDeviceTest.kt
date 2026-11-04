package com.lagradost.cloudstream4.core.designsystem.target

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lagradost.cloudstream4.core.devicetest.Dpad
import com.lagradost.cloudstream4.core.devicetest.DpadDriver
import com.lagradost.cloudstream4.core.devicetest.focusedLabel
import com.lagradost.cloudstream4.core.theme.CloudStreamDimens
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme
import com.lagradost.cloudstream4.core.theme.cloudStreamDarkColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What the JVM tests cannot prove about [SegmentedControl]: real D-pad keys through the input dispatcher move
 * between its options, centre chooses, and the screen shows where focus is.
 */
@RunWith(AndroidJUnit4::class)
class SegmentedControlDeviceTest {

    @get:Rule
    val rule = createComposeRule()

    private val dpad = DpadDriver(readFocus = { rule.focusedLabel() })
    private var chosen by mutableIntStateOf(0)

    private fun show() = rule.setContent {
        CloudStreamTheme {
            // Room for the ring, as the chassis gives every focusable.
            Box(
                Modifier
                    .fillMaxSize()
                    .background(CloudStreamTheme.colors.bg)
                    .padding(CloudStreamDimens.FocusBandInset),
            ) {
                SegmentedControl(listOf(MERGED, BY_SOURCE), chosen, { chosen = it })
            }
        }
    }

    /** Whether the ring shows above [label]'s cell: 3dp out is the middle of its stroke, and above the 24dp visual. */
    private fun ringAbove(label: String): Boolean {
        rule.waitForIdle()
        val bounds = rule.onNodeWithText(label).fetchSemanticsNode().boundsInRoot
        val out = with(rule.density) { RING_MIDDLE.toPx() }
        val pixel = rule.onRoot().captureToImage().toPixelMap()[bounds.center.x.toInt(), (bounds.top - out).toInt()]
        return pixel == cloudStreamDarkColors().focus
    }

    @Test
    fun dpadMovesBetweenOptionsAndTheRingFollows() {
        show()

        assertEquals(MERGED, dpad.enter())
        assertEquals("focused first", true, ringAbove(MERGED))
        assertEquals(false, ringAbove(BY_SOURCE))

        assertEquals(BY_SOURCE, dpad.press(Dpad.Right))
        assertEquals("the ring moved", true, ringAbove(BY_SOURCE))
        assertEquals("the ring left", false, ringAbove(MERGED))

        assertEquals("the last option holds focus", BY_SOURCE, dpad.press(Dpad.Right))
        assertEquals(MERGED, dpad.press(Dpad.Left))
        assertEquals(true, ringAbove(MERGED))
    }

    @Test
    fun movingChoosesNothingAndCentreChoosesTheFocusedOption() {
        show()
        dpad.enter()
        dpad.press(Dpad.Right)
        rule.waitForIdle()
        assertEquals("travelling commits nothing", 0, chosen)
        rule.onNodeWithText(MERGED).assertIsSelected()

        dpad.center()
        rule.waitUntil(WAIT_MS) { chosen == 1 }
        rule.onNodeWithText(BY_SOURCE).assertIsSelected()
        rule.onNodeWithText(MERGED).assertIsNotSelected()
        assertEquals("choosing keeps focus", BY_SOURCE, dpad.focused())
    }

    @Test
    fun everyOptionIsReachableWithNoTraps() {
        show()
        val graph = dpad.walk()
        assertEquals(graph.toString(), emptyList<String>(), graph.unreachable(listOf(MERGED, BY_SOURCE)))
        assertEquals(graph.toString(), emptyList<String>(), graph.deadEnds())
        assertEquals(graph.toString(), emptyList<String>(), graph.traps())
        assertNotEquals(graph.toString(), emptySet<String>(), graph.reached)
    }

    private companion object {
        const val MERGED = "Merged"
        const val BY_SOURCE = "By source"
        const val WAIT_MS = 10_000L
        val RING_MIDDLE = 3.dp
    }
}
