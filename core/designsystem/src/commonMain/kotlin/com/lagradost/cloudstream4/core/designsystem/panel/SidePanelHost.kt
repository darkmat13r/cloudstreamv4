package com.lagradost.cloudstream4.core.designsystem.panel

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme

/**
 * Opacity of the scrim over the content behind an open [SidePanel].
 *
 * Invented, and the one number in this file the design did not give. It is not a dimension, so
 * the 4dp step does not apply to it; it was chosen so content stays legible as context but no
 * longer competes with the panel for attention at three metres. A designer should confirm it.
 */
private const val SCRIM_ALPHA = 0.72f

/**
 * Hosts a [SidePanel] over a screen: the scrim, the mounting rule, the focus trap, and BACK.
 *
 * ## The content stays mounted
 *
 * [content] is composed whether or not the panel is open, and is never swapped out for the panel.
 * A TV panel is a *side* panel precisely so the thing it is about remains on screen and in state:
 * a video keeps playing, a rail keeps its scroll position, a ViewModel keeps its subscription.
 * Replacing the screen with the panel, or hoisting the panel into a separate route, gives back
 * exactly the behaviour a bottom sheet has on phone and loses the reason to have a panel at all.
 *
 * ## Focus is trapped, and BACK gives it back
 *
 * While open, the panel is a focus group whose exit is cancelled, so D-pad LEFT at the panel's
 * leading edge does nothing instead of silently landing on a control behind the scrim that the
 * user cannot see they are on. Focus moves into the panel when it opens.
 *
 * On close, focus returns to the control that opened the panel. That is `Modifier.focusRestorer`
 * on the content group: the group records its focused child when focus leaves it and restores
 * that child when focus is asked back in. It needs no wiring from the caller and it stays correct
 * when several different controls can open the same panel.
 *
 * ## BACK
 *
 * BACK and ESCAPE are handled as key events on the panel, in `onPreviewKeyEvent`, so the module
 * stays in `commonMain`. Both key phases are consumed and [onDismissRequest] is raised on key-up,
 * which is the phase a user can still abort.
 *
 * **A host on Android should also wire its platform back** (the activity's
 * `OnBackPressedDispatcher`) to the same `onDismissRequest`, gated on `expanded`. This module
 * deliberately does not: Compose's multiplatform `BackHandler` is both `@ExperimentalComposeUiApi`
 * and `@Deprecated("Use NavigationEventHandler instead")` in Compose 1.12, and a design-system
 * module should not pin every consumer to an API that is on its way out. See the task's return
 * value.
 *
 * [onDismissRequest] is a *request*. It does not commit anything — see [SidePanel]'s note on why
 * dismissal must never be the commit path.
 *
 * @param expanded whether the panel is open. Hoisted; the host owns no open/closed state.
 * @param onDismissRequest raised on BACK or ESCAPE while open.
 * @param modifier applied to the host, which fills its parent.
 * @param panel the panel itself, normally a [SidePanel]. Composed only while [expanded].
 * @param content the screen behind the panel. Always composed.
 */
@Composable
fun SidePanelHost(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    panel: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    val contentFocus = remember { FocusRequester() }
    val panelFocus = remember { FocusRequester() }
    var hasEverOpened by remember { mutableStateOf(false) }

    Box(modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .focusRequester(contentFocus)
                .focusRestorer()
                .focusGroup(),
            content = { content() },
        )
        if (expanded) {
            Scrim()
            PanelLayer(panelFocus, onDismissRequest, panel)
        }
    }

    LaunchedEffect(expanded) {
        if (expanded) {
            hasEverOpened = true
            panelFocus.requestFocus()
        } else if (hasEverOpened) {
            contentFocus.requestFocus()
        }
    }
}

/**
 * The scrim over [SidePanelHost]'s content.
 *
 * It is the `bg` token at [SCRIM_ALPHA] — the palette has nine colours and a scrim is not a tenth.
 * It is **not** clickable: there is no pointer on a TV remote, and a click-outside-to-dismiss
 * would be a second, invisible commit path of exactly the kind [SidePanel] documents against. A
 * host that also ships to touch adds its own dismiss affordance over this.
 */
@Composable
private fun Scrim() {
    val ground = CloudStreamTheme.colors.bg
    val scrim = remember(ground) { ground.copy(alpha = SCRIM_ALPHA) }
    Box(Modifier.fillMaxSize().background(scrim))
}

/**
 * The panel layer: right-anchored, focus-trapped, and listening for BACK.
 *
 * `focusProperties { onExit = { cancelFocusChange() } }` must precede `focusGroup()` — it applies
 * to the focus target that follows it — and [focusRequester] must precede both so a request lands
 * on the group and is delegated into its first focusable child.
 */
@Composable
private fun BoxScope.PanelLayer(
    focusRequester: FocusRequester,
    onDismissRequest: () -> Unit,
    panel: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .align(Alignment.CenterEnd)
            .focusRequester(focusRequester)
            .focusProperties { onExit = { cancelFocusChange() } }
            .focusGroup()
            .onPreviewKeyEvent { event -> event.consumeAsDismiss(onDismissRequest) },
        content = { panel() },
    )
}

/**
 * Consumes BACK and ESCAPE, raising [onDismissRequest] on key-up.
 *
 * Both phases are consumed: letting key-down through and only acting on key-up leaves the
 * platform free to act on the down event, which on Android closes the whole screen behind the
 * panel.
 */
private fun KeyEvent.consumeAsDismiss(onDismissRequest: () -> Unit): Boolean {
    if (key != Key.Back && key != Key.Escape) return false
    if (type == KeyEventType.KeyUp) onDismissRequest()
    return true
}
