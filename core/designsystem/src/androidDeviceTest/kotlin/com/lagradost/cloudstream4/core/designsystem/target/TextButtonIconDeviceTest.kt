package com.lagradost.cloudstream4.core.designsystem.target

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
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
import com.lagradost.cloudstream4.core.designsystem.icon.CloudStreamIcons
import com.lagradost.cloudstream4.core.devicetest.Dpad
import com.lagradost.cloudstream4.core.devicetest.DpadDriver
import com.lagradost.cloudstream4.core.devicetest.assertScreenshot
import com.lagradost.cloudstream4.core.devicetest.focusedLabel
import com.lagradost.cloudstream4.core.theme.CloudStreamDimens
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme
import com.lagradost.cloudstream4.core.theme.cloudStreamDarkColors
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What the JVM tests cannot prove about a text button with a leading glyph: on a TV's density, real D-pad keys move
 * across a row of them, each takes focus with the ring 2dp clear and 2dp thick on all four sides, exactly as a
 * text-only button's, and focusing one moves nothing.
 */
@RunWith(AndroidJUnit4::class)
class TextButtonIconDeviceTest {

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
                Row(horizontalArrangement = Arrangement.spacedBy(CloudStreamDimens.MinFocusGap)) {
                    PrimaryTextButton(label = PRIMARY, onClick = {}, icon = CloudStreamIcons.Retry)
                    GhostTextButton(label = GHOST, onClick = {}, icon = CloudStreamIcons.Download)
                    GhostTextButton(label = PLAIN, onClick = {})
                }
            }
        }
    }

    private fun bounds(label: String): Rect {
        rule.waitForIdle()
        return rule.onNodeWithText(label).fetchSemanticsNode().boundsInRoot
    }

    private fun Dp.px(): Float = with(rule.density) { toPx() }

    /**
     * Within [RING_TOLERANCE] of the focus colour. Not exact: a glyph makes the button's width intrinsic, so its right
     * edge can fall between pixels, and the ring's middle there read 254 of 255 on `Television_720p`. The gap beside it
     * reads 0 and the edge pixels 87, so this tolerance still tells ring from not-ring.
     */
    private fun PixelMap.ringAt(x: Float, y: Float): Boolean {
        val pixel = this[x.toInt(), y.toInt()]
        val focus = cloudStreamDarkColors().focus
        return abs(pixel.red - focus.red) <= RING_TOLERANCE &&
            abs(pixel.green - focus.green) <= RING_TOLERANCE &&
            abs(pixel.blue - focus.blue) <= RING_TOLERANCE
    }

    /** Clear at 1dp out, the stroke's middle at 3dp, and clear again at 5dp, on each side of the button. */
    private fun assertEvenRing(label: String) {
        val box = bounds(label)
        val frame = rule.onRoot().captureToImage().toPixelMap()
        val midX = box.center.x
        val midY = box.center.y
        listOf(
            "left" to { out: Float -> frame.ringAt(box.left - out, midY) },
            "right" to { out: Float -> frame.ringAt(box.right + out, midY) },
            "top" to { out: Float -> frame.ringAt(midX, box.top - out) },
            "bottom" to { out: Float -> frame.ringAt(midX, box.bottom + out) },
        ).forEach { (side, ring) ->
            assertFalse("$label $side: the gap is clear", ring(GAP_MIDDLE.px()))
            assertTrue("$label $side: the stroke is here", ring(STROKE_MIDDLE.px()))
            assertFalse("$label $side: nothing past the stroke", ring(PAST_STROKE.px()))
        }
    }

    @Test
    fun dpadMovesAcrossButtonsWithGlyphsAndTheRingIsTheTextButtonsRing() {
        show()
        val labels = listOf(PRIMARY, GHOST, PLAIN)
        val before = labels.map(::bounds)
        before.forEach { assertEquals(CloudStreamDimens.TextButtonHeight.px(), it.height, 1f) }

        assertEquals(PRIMARY, dpad.enter())
        assertEvenRing(PRIMARY)
        assertEquals("focus moved nothing", before, labels.map(::bounds))

        assertEquals(GHOST, dpad.press(Dpad.Right))
        assertEvenRing(GHOST)
        assertEquals("focus moved nothing", before, labels.map(::bounds))

        assertEquals(PLAIN, dpad.press(Dpad.Right))
        assertEvenRing(PLAIN)
        assertEquals("focus moved nothing", before, labels.map(::bounds))

        assertEquals(GHOST, dpad.press(Dpad.Left))
        assertEvenRing(GHOST)
    }

    @Test
    fun screenshotPrimaryWithGlyphFocused() {
        show()
        assertEquals(PRIMARY, dpad.enter())
        rule.assertScreenshot("text-button-primary-icon-focus", rule.onNodeWithText(PRIMARY))
    }

    @Test
    fun screenshotGhostWithGlyphFocused() {
        show()
        dpad.enter()
        assertEquals(GHOST, dpad.press(Dpad.Right))
        rule.assertScreenshot("text-button-ghost-icon-focus", rule.onNodeWithText(GHOST))
    }

    @Test
    fun screenshotGhostWithGlyphAtRest() {
        show()
        assertEquals(PRIMARY, dpad.enter())
        rule.assertScreenshot("text-button-ghost-icon-rest", rule.onNodeWithText(GHOST))
    }

    private companion object {
        const val PRIMARY = "Try again"
        const val GHOST = "Download"
        const val PLAIN = "Skip for now"
        val GAP_MIDDLE = 1.dp
        val STROKE_MIDDLE = 3.dp
        val PAST_STROKE = 5.dp
        const val RING_TOLERANCE = 2f / 255f
    }
}
