package com.lagradost.cloudstream4.core.designsystem.state

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lagradost.cloudstream4.core.devicetest.DpadDriver
import com.lagradost.cloudstream4.core.devicetest.assertScreenshot
import com.lagradost.cloudstream4.core.devicetest.focusedLabel
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme
import com.lagradost.cloudstream4.core.theme.cloudStreamDarkColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A focused [StateActionButton], the one action of every [StateView] and [FailureBanner]: the ring sits evenly around
 * the button, as on the text buttons, `FocusRingOffset` clear and `FocusRingStroke` wide on all four sides.
 */
@RunWith(AndroidJUnit4::class)
class StateActionDeviceTest {

    @get:Rule
    val rule = createComposeRule()

    private val dpad = DpadDriver(readFocus = { rule.focusedLabel() })

    private fun show() = rule.setContent {
        CloudStreamTheme {
            Box(Modifier.fillMaxSize().background(CloudStreamTheme.colors.bg)) {
                StateView(
                    prompt = StatePrompt(
                        headline = "Couldn't open this title",
                        body = "Its source may be unavailable.",
                        action = StateAction(label = RETRY, onActivate = {}),
                    ),
                    detail = "HTTP 503",
                )
            }
        }
    }

    private fun Dp.px(): Float = with(rule.density) { toPx() }

    private fun PixelMap.ringAt(x: Float, y: Float): Boolean = this[x.toInt(), y.toInt()] == cloudStreamDarkColors().focus

    @Test
    fun screenshotStateViewFailureFocusOnItsAction() {
        InstrumentationRegistry.getInstrumentation().setInTouchMode(false)
        show()
        assertEquals(RETRY, dpad.enter())
        rule.waitForIdle()
        rule.assertScreenshot("state-view-failure-focus-action")
        val button = rule.onNodeWithText(RETRY).fetchSemanticsNode().boundsInRoot
        val frame = rule.onRoot().captureToImage().toPixelMap()
        val midX = button.center.x
        val midY = button.center.y
        listOf(
            "left" to { out: Float -> frame.ringAt(button.left - out, midY) },
            "right" to { out: Float -> frame.ringAt(button.right + out, midY) },
            "top" to { out: Float -> frame.ringAt(midX, button.top - out) },
            "bottom" to { out: Float -> frame.ringAt(midX, button.bottom + out) },
        ).forEach { (side, ring) ->
            assertFalse("$side: the gap is clear", ring(GAP_MIDDLE.px()))
            assertTrue("$side: the stroke is here", ring(STROKE_MIDDLE.px()))
            assertFalse("$side: nothing past the stroke", ring(PAST_STROKE.px()))
        }
    }

    private companion object {
        const val RETRY = "Try again"
        val GAP_MIDDLE = 1.dp
        val STROKE_MIDDLE = 3.dp
        val PAST_STROKE = 5.dp
    }
}
