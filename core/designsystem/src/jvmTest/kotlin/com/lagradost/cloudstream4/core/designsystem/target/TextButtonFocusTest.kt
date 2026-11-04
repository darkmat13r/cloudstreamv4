package com.lagradost.cloudstream4.core.designsystem.target

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import com.lagradost.cloudstream4.core.designsystem.harness.Dpad
import com.lagradost.cloudstream4.core.designsystem.harness.Harness
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `Components.dc.html` §01: *"Disabled is 40% opacity and stays focusable so a loading screen never
 * has zero focus owners."*
 *
 * The cost of getting this wrong is not cosmetic. A TV screen with nothing focused leaves Compose's
 * focus on the root, where a D-pad press has no origin to search from and no ring anywhere to tell
 * the user why nothing is happening. These tests are about reachability, not about opacity.
 */
class TextButtonFocusTest {

    @Test
    fun `a disabled button is not a focus stop by default`() {
        assertNotAStop { focused, _ ->
            PrimaryTextButton(
                label = "Resume",
                onClick = {},
                modifier = Modifier.onFocusChanged { focused(it.isFocused) },
                enabled = false,
            )
        }
    }

    @Test
    fun `a disabled ghost button is not a focus stop by default`() {
        assertNotAStop { focused, _ ->
            GhostTextButton(
                label = "Trailer",
                onClick = {},
                modifier = Modifier.onFocusChanged { focused(it.isFocused) },
                enabled = false,
            )
        }
    }

    @Test
    fun `a disabled button asked to stay focusable takes focus and does not click`() {
        assertStopThatDoesNotAct { focused, clicked ->
            PrimaryTextButton(
                label = "Resume",
                onClick = clicked,
                modifier = Modifier.onFocusChanged { focused(it.isFocused) },
                enabled = false,
                focusableWhenDisabled = true,
            )
        }
    }

    @Test
    fun `a disabled ghost button asked to stay focusable takes focus and does not click`() {
        assertStopThatDoesNotAct { focused, clicked ->
            GhostTextButton(
                label = "Trailer",
                onClick = clicked,
                modifier = Modifier.onFocusChanged { focused(it.isFocused) },
                enabled = false,
                focusableWhenDisabled = true,
            )
        }
    }

    @Test
    fun `an enabled button is unaffected by the new option`() {
        var clicks = 0
        var focused = false
        val dpad = Dpad()
        Harness {
            Above(dpad) {
                PrimaryTextButton(
                    label = "Resume",
                    onClick = { clicks++ },
                    modifier = Modifier.onFocusChanged { focused = it.isFocused },
                    focusableWhenDisabled = true,
                )
            }
        }.use { harness ->
            harness.settle()
            dpad.press(FocusDirection.Down, harness)
            assertTrue(focused, "an enabled button was always a focus stop")
            harness.press(Key.Enter)
            assertEquals(1, clicks, "and it still clicks")
        }
    }

    private fun assertNotAStop(button: @Composable ((Boolean) -> Unit, () -> Unit) -> Unit) {
        var focused = false
        val dpad = Dpad()
        Harness { Above(dpad) { button({ focused = it }, {}) } }.use { harness ->
            harness.settle()
            dpad.press(FocusDirection.Down, harness)
            assertFalse(
                focused,
                "the default disabled button keeps the behaviour every caller already has",
            )
        }
    }

    private fun assertStopThatDoesNotAct(
        button: @Composable ((Boolean) -> Unit, () -> Unit) -> Unit,
    ) {
        var focused = false
        var clicks = 0
        val dpad = Dpad()
        Harness { Above(dpad) { button({ focused = it }, { clicks++ }) } }.use { harness ->
            harness.settle()
            dpad.press(FocusDirection.Down, harness)
            assertTrue(focused, "the board's disabled button stays focusable")

            harness.press(Key.Enter)
            assertEquals(0, clicks, "focusable is not actionable: there is no click handler at all")
        }
    }

    /** A focusable above the button under test, so DOWN is what tries to enter it. */
    @Composable
    private fun Above(dpad: Dpad, button: @Composable () -> Unit) {
        dpad.Install()
        val first = remember { FocusRequester() }
        Column {
            GhostTextButton(
                label = "Above",
                onClick = {},
                modifier = Modifier.focusRequester(first),
            )
            button()
        }
        LaunchedEffect(Unit) { first.requestFocus() }
    }
}
