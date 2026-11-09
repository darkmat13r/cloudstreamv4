package com.lagradost.cloudstream4.core.designsystem.target

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.input.ImeAction
import com.lagradost.cloudstream4.core.designsystem.harness.Bounds
import com.lagradost.cloudstream4.core.designsystem.harness.Harness
import com.lagradost.cloudstream4.core.theme.CloudStreamDimens
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The field `DS-10` moved out of `:feature:search`, measured against the board that specified it
 * and against the private `QueryField` it replaces.
 *
 * `TV Search`'s own CSS is the whole specification — `height: 96px`, `padding: 0 24px` — and on the
 * 1920x1080 artboard that halves to 48dp and 12dp.
 *
 * The move promised the search screen would render identically, and the two tests that hold it to
 * that compare the new tokens against the two the private copy used: `ChromeRowHeight` and
 * `MinFocusGap`. If someone changes a `TextFieldDefaults` value, those are the tests that say the
 * search screen moved with it.
 */
class TextFieldTest {

    @Test
    fun `the field is 48dp tall, which is the artboard's 96px halved`() {
        val bounds = Bounds()
        Harness {
            TextField(
                value = "",
                onValueChange = {},
                placeholder = PLACEHOLDER,
                modifier = Modifier.fillMaxWidth().onGloballyPositioned(bounds.record(FIELD)),
            )
        }.use { harness ->
            harness.settle()
            assertEquals(HALVED_ARTBOARD_HEIGHT, bounds[FIELD].height, "height: 96px, halved")
        }
    }

    @Test
    fun `its own tokens are the numbers the private QueryField used`() {
        assertEquals(
            CloudStreamDimens.ChromeRowHeight,
            TextFieldDefaults.Height,
            "the private copy took its height from the chrome row; the move must not resize it",
        )
        assertEquals(
            CloudStreamDimens.MinFocusGap,
            TextFieldDefaults.HorizontalPadding,
            "the private copy's FieldDefaults.HorizontalPadding mirrored MinFocusGap",
        )
        assertEquals(
            HALVED_ARTBOARD_PADDING,
            TextFieldDefaults.HorizontalPadding.value,
            "padding: 0 24px, halved",
        )
    }

    @Test
    fun `the action key submits, whatever ime action the caller named`() {
        var submits = 0
        Harness { FocusedField(onSubmit = { submits++ }) }.use { harness ->
            harness.settle()
            harness.press(Key.Enter)
            assertEquals(
                1,
                submits,
                "the private copy wired this as KeyboardActions(onSearch); it is now onAny, " +
                    "which must fire for whatever ImeAction the caller passes",
            )
        }
    }

    @Test
    fun `the field is a focus stop`() {
        var focused = false
        Harness { FocusedField(onFocus = { focused = it }) }.use { harness ->
            harness.settle()
            assertTrue(focused, "on TV the field is normally the leading focusable of its screen")
        }
    }

    @Test
    fun `the field holds no state of its own`() {
        var value by mutableStateOf("tide")
        Harness {
            TextField(
                value = value,
                onValueChange = { value = it },
                placeholder = PLACEHOLDER,
                modifier = Modifier.fillMaxWidth(),
            )
        }.use { harness ->
            harness.settle()
            value = "tide warden"
            harness.settle()
            assertEquals("tide warden", value, "the caller's state is the only state")
        }
    }

    /** A field that has focus by the time the scene settles, so key events reach it. */
    @androidx.compose.runtime.Composable
    private fun FocusedField(onSubmit: () -> Unit = {}, onFocus: (Boolean) -> Unit = {}) {
        val requester = remember { FocusRequester() }
        TextField(
            value = "",
            onValueChange = {},
            placeholder = PLACEHOLDER,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(requester)
                .onFocusChanged { onFocus(it.isFocused) },
            imeAction = ImeAction.Search,
            onSubmit = onSubmit,
        )
        LaunchedEffect(Unit) { requester.requestFocus() }
    }

    private companion object {
        const val FIELD = "field"
        const val PLACEHOLDER = "Search all sources"
        const val HALVED_ARTBOARD_HEIGHT = 48f
        const val HALVED_ARTBOARD_PADDING = 12f
    }
}
