package com.lagradost.cloudstream4.core.designsystem.target

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onInterceptKeyBeforeSoftKeyboard
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics

/**
 * A text field the D-pad can cross: landing on it only highlights it, and OK or Enter opens the
 * keyboard. The owner's ruling of 2026-11-04, `BUG-30`.
 *
 * ## Why `readOnly`, and not `KeyboardOptions.showKeyboardOnFocus`
 *
 * `showKeyboardOnFocus = false` is the obvious answer and does nothing here: the `value` overloads of
 * `BasicTextField` document that they do not support it. The field starts an input session, and with it
 * the keyboard, whenever it is focused and writable, so it stays read-only until the user asks. Compose
 * starts the session when `readOnly` goes false on a focused field, and ends it and hides the keyboard
 * when `readOnly` goes true again.
 *
 * Only in [InputMode.Keyboard]. A tap puts the view in touch mode, where the field is writable and opens
 * the keyboard as any field does; `isLayout(TV)` is a preference, so this screen can be on a touchscreen.
 *
 * Back is read before the keyboard gets it, because the keyboard consumes it and nothing else reports that
 * it closed. Without that, a remote whose OK sends Enter would submit the query on its next press.
 */
@Stable
internal class KeyboardOnOk {
    private var open by mutableStateOf(false)
    private val focus = FocusRequester()

    /** For `BasicTextField(readOnly = ...)`. */
    val readOnly: Boolean
        @Composable get() = !open && LocalInputModeManager.current.inputMode == InputMode.Keyboard

    /** For `BasicTextField(modifier = ...)`. */
    @OptIn(ExperimentalComposeUiApi::class)
    val modifier: Modifier = Modifier
        .focusRequester(focus)
        .onFocusChanged { if (!it.isFocused) open = false }
        .onPreviewKeyEvent { event ->
            val opensNow = !open && event.key in OkKeys
            if (opensNow && event.type == KeyEventType.KeyDown) open = true
            opensNow
        }
        .onInterceptKeyBeforeSoftKeyboard { event ->
            if (event.key == Key.Back && event.type == KeyEventType.KeyUp) open = false
            false
        }
        // A screen reader's activation. A read-only field's own action would only take focus.
        .semantics {
            onClick {
                focus.requestFocus()
                open = true
                true
            }
        }

    /** Whether the user has asked for the keyboard, so a D-pad key belongs to the field. */
    val isOpen: Boolean get() = open

    /** The field has done with the keyboard: the action key was pressed. */
    fun close() {
        open = false
    }

    private companion object {
        val OkKeys = setOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter)
    }
}
