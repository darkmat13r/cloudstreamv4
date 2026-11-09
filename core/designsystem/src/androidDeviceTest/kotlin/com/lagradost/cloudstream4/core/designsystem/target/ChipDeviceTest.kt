package com.lagradost.cloudstream4.core.designsystem.target

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lagradost.cloudstream4.core.devicetest.Dpad
import com.lagradost.cloudstream4.core.devicetest.DpadDriver
import com.lagradost.cloudstream4.core.devicetest.TouchModeReset
import com.lagradost.cloudstream4.core.devicetest.focusedLabel
import com.lagradost.cloudstream4.core.theme.CloudStreamDimens
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme
import com.lagradost.cloudstream4.core.theme.cloudStreamDarkColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What the JVM tests cannot prove about [Chip]: real D-pad keys through the input dispatcher move along a [ChipRow] on a
 * TV's density, and on every stop the ring sits evenly around the 24dp visual and nothing moves (`DEC-17`, 2026-11-05).
 */
@RunWith(AndroidJUnit4::class)
class ChipDeviceTest {

    /** `BUG-59`: starts and ends out of touch mode, whatever ran before it. */
    @get:Rule
    val touchMode = TouchModeReset()

    @get:Rule
    val rule = createComposeRule()

    private val dpad = DpadDriver(readFocus = { rule.focusedLabel() })

    private fun show() = rule.setContent {
        CloudStreamTheme {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(CloudStreamTheme.colors.bg)
                    .padding(CloudStreamDimens.FocusBandInset),
            ) {
                ChipRow {
                    Chip(MOVIES, selected = false, onClick = {})
                    Chip(SERIES, selected = true, onClick = {})
                    Chip(LIVE, selected = false, onClick = {})
                }
            }
        }
    }

    private fun cell(label: String): Rect {
        rule.waitForIdle()
        return rule.onNodeWithText(label).fetchSemanticsNode().boundsInRoot
    }

    private fun Dp.px(): Float = with(rule.density) { toPx() }

    private fun PixelMap.ringAt(x: Float, y: Float): Boolean = this[x.toInt(), y.toInt()] == cloudStreamDarkColors().focus

    /** Clear at 1dp out, the stroke's middle at 3dp, and clear again at 5dp, on each side of the visual. */
    private fun assertEvenRing(label: String) {
        val cell = cell(label)
        val frame = rule.onRoot().captureToImage().toPixelMap()
        val top = cell.center.y - (CloudStreamDimens.ChipVisualHeight / 2).px()
        val bottom = cell.center.y + (CloudStreamDimens.ChipVisualHeight / 2).px()
        val midX = cell.center.x
        val midY = cell.center.y
        listOf(
            "left" to { out: Float -> frame.ringAt(cell.left - out, midY) },
            "right" to { out: Float -> frame.ringAt(cell.right + out, midY) },
            "top" to { out: Float -> frame.ringAt(midX, top - out) },
            "bottom" to { out: Float -> frame.ringAt(midX, bottom + out) },
        ).forEach { (side, ring) ->
            assertFalse("$label $side: the gap is clear", ring(GAP_MIDDLE.px()))
            assertTrue("$label $side: the stroke is here", ring(STROKE_MIDDLE.px()))
            assertFalse("$label $side: nothing past the stroke", ring(PAST_STROKE.px()))
        }
        assertFalse("$label: nothing at the cell's old ring", frame.ringAt(midX, cell.top - STROKE_MIDDLE.px()))
    }

    @Test
    fun dpadMovesAcrossChipsAndTheRingStaysEvenWithNothingMoving() {
        show()
        val labels = listOf(MOVIES, SERIES, LIVE)
        val cells = labels.map(::cell)

        assertEquals(MOVIES, dpad.enter())
        assertEvenRing(MOVIES)
        assertEquals("focus moved nothing", cells, labels.map(::cell))

        assertEquals(SERIES, dpad.press(Dpad.Right))
        assertEvenRing(SERIES)
        assertEquals("focus moved nothing", cells, labels.map(::cell))

        assertEquals(LIVE, dpad.press(Dpad.Right))
        assertEvenRing(LIVE)
        assertEquals("focus moved nothing", cells, labels.map(::cell))

        assertEquals("the last chip holds focus", LIVE, dpad.press(Dpad.Right))
        assertEquals(SERIES, dpad.press(Dpad.Left))
        assertEvenRing(SERIES)
    }

    private companion object {
        const val MOVIES = "Movies"
        const val SERIES = "Series"
        const val LIVE = "Live"
        val GAP_MIDDLE = 1.dp
        val STROKE_MIDDLE = 3.dp
        val PAST_STROKE = 5.dp
    }
}
