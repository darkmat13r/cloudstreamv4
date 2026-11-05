package com.lagradost.cloudstream4.core.designsystem.state

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import com.lagradost.cloudstream4.core.designsystem.harness.Harness
import com.lagradost.cloudstream4.core.theme.CloudStreamDimens
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme
import com.lagradost.cloudstream4.core.theme.cloudStreamDarkColors
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `DS-15`: a [StateView] puts focus on its action in its first frame, and the action must draw its ring then.
 *
 * It did not: [StateActionButton] read focus from its interaction source, which had not subscribed when the focus
 * arrived, so the button held focus with no ring. The case reads the drawn width of everything white at the bottom of the
 * stack, at density 1: the button alone is [CloudStreamDimens.TextButtonMinWidth] wide, and with its ring it is
 * [CloudStreamDimens.FocusRingBleed] wider on each side.
 */
class StateActionRingTest {

    @Test
    fun `the action a state view focuses on arrival draws its ring`() {
        Harness(height = HEIGHT) {
            Box(Modifier.fillMaxSize().background(CloudStreamTheme.colors.bg)) {
                StateView(
                    prompt = StatePrompt(
                        headline = "Couldn't open this title",
                        body = "Its source may be unavailable.",
                        action = StateAction(label = "Retry", onActivate = {}),
                    ),
                )
            }
        }.use { harness ->
            harness.settle()
            val frame = harness.pixels()
            val white = cloudStreamDarkColors().focus.toArgb()
            val bottom = (HEIGHT - 1 downTo 0).first { y -> (0 until Harness.WIDTH).any { frame[it, y].toArgb() == white } }
            val rows = (bottom - BAND + 1)..bottom
            val xs = rows.flatMap { y -> (0 until Harness.WIDTH).filter { frame[it, y].toArgb() == white } }
            val drawn = xs.max() - xs.min() + 1
            val bleed = CloudStreamDimens.FocusRingBleed.value.toInt()
            assertEquals(CloudStreamDimens.TextButtonMinWidth.value.toInt() + 2 * bleed, drawn, "the button and its ring")
        }
    }

    private companion object {
        const val HEIGHT = 540
        /** The button's 32dp and the ring's 4dp below it: the bottom of the stack, clear of the text above. */
        const val BAND = 36
    }
}
