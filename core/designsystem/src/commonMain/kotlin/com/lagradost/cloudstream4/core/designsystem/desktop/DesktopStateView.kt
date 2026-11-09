package com.lagradost.cloudstream4.core.designsystem.desktop

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.lagradost.cloudstream4.core.designsystem.state.StatePrompt
import com.lagradost.cloudstream4.core.designsystem.state.StateView

/**
 * [StateView] on the Mac (`KMP-101`): the same glyph, words and one action, with the action an outlined
 * [DesktopButton] (boards D4 and S6). An empty or failed page offers its way on without making it the loudest thing
 * in the window, and the button has the hover a pointer needs, which the TV one does not.
 *
 * The parameters are [StateView]'s.
 */
@Composable
fun DesktopStateView(
    prompt: StatePrompt,
    modifier: Modifier = Modifier,
    detail: String? = null,
    requestInitialFocus: Boolean = true,
    glyph: @Composable () -> Unit = {},
) {
    StateView(
        prompt = prompt,
        modifier = modifier,
        detail = detail,
        requestInitialFocus = requestInitialFocus,
        glyph = glyph,
        actionButton = { action, focus ->
            DesktopButton(
                label = action.label,
                onClick = action.onActivate,
                icon = action.icon,
                tone = DesktopButtonTone.Outlined,
                modifier = focus,
            )
        },
    )
}
