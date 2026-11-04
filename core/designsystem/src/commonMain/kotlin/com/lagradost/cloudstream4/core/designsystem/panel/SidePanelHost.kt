package com.lagradost.cloudstream4.core.designsystem.panel

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme

/**
 * Opacity of the scrim over the content behind a fully open [SidePanel].
 *
 * Invented, and the one number in this file the design did not give. It is not a dimension, so
 * the 4dp step does not apply to it; it was chosen so content stays legible as context but no
 * longer competes with the panel for attention at three metres. A designer should confirm it.
 *
 * It is the scrim's value at rest. While the panel slides the scrim is this times the panel's
 * travel, so the two arrive together — see [SidePanelMotion].
 */
private const val SCRIM_ALPHA = 0.72f

/**
 * The panel's slide: **240ms, ease-out, and interruptible.**
 *
 * `Components.dc.html` §05 is the whole specification — *"SLIDE IN 240ms ease-out"* over
 * *"SIDE PANEL · 560 WIDE · SLIDES FROM RIGHT"*. 560px on the 1920x1080 artboard is the 280dp
 * [com.lagradost.cloudstream4.core.theme.CloudStreamDimens.SidePanelWidth] the panel already has,
 * so the travel is not a number anyone has to write down: it is the panel's own measured width,
 * read in the draw phase.
 *
 * ## Why an [Animatable] and not `AnimatedVisibility`
 *
 * Two reasons, and both are load-bearing on TV.
 *
 * **Interruption.** A panel that is dismissed 100ms into its entrance must slide back out from
 * where it actually is, not jump to fully-open and then leave. One [Animatable] driven from a
 * `LaunchedEffect(expanded)` gives that for free: the effect is cancelled and relaunched on every
 * change of `expanded`, and `animateTo` starts from the current value. A D-pad can produce
 * open/close pairs faster than 240ms, so this is an ordinary case and not a corner.
 *
 * **Clipping.** `tvFocusRing` bleeds
 * [com.lagradost.cloudstream4.core.theme.CloudStreamDimens.FocusRingBleed] outside every focusable
 * and its KDoc is explicit that *no ancestor may clip*. `AnimatedVisibility` introduces its own
 * layout around the content it animates; `Modifier.graphicsLayer` with `clip` left at its default
 * `false` introduces none, and translating a layer cannot move a sibling or change what the parent
 * measured. The panel's rows keep their rings all the way through the slide.
 *
 * The travel is read **inside** the `graphicsLayer` and `drawBehind` lambdas rather than passed
 * down as a `Float`, so an animating panel re-draws without recomposing its own content.
 */
private object SidePanelMotion {

    /** `Components`: 240ms. */
    const val SLIDE_MILLIS = 240

    /** Fully open: no translation, scrim at [SCRIM_ALPHA]. */
    const val OPEN = 0f

    /** Fully closed: translated its own width past the right edge, scrim at zero. */
    const val CLOSED = 1f

    /**
     * CSS `ease-out` — `cubic-bezier(0, 0, 0.58, 1)` — because the board's *"240ms ease-out"* is
     * written in a CSS document and `ease-out` is a CSS keyword with exactly this curve.
     *
     * Deliberately not `LinearOutSlowInEasing`, which is Material's decelerate
     * (`cubic-bezier(0, 0, 0.2, 1)`): the same family, a visibly harder stop, and not what the
     * board asked for.
     */
    val Ease: Easing = CubicBezierEasing(0f, 0f, 0.58f, 1f)

    fun spec() = tween<Float>(durationMillis = SLIDE_MILLIS, easing = Ease)
}

/**
 * Hosts a [SidePanel] over a screen: the slide, the scrim, the mounting rule, the focus trap, and
 * BACK.
 *
 * ## The content stays mounted
 *
 * [content] is composed whether or not the panel is open, and is never swapped out for the panel.
 * A TV panel is a *side* panel precisely so the thing it is about remains on screen and in state:
 * a video keeps playing, a rail keeps its scroll position, a ViewModel keeps its subscription.
 * Replacing the screen with the panel, or hoisting the panel into a separate route, gives back
 * exactly the behaviour a bottom sheet has on phone and loses the reason to have a panel at all.
 *
 * ## It slides, and the slide survives being interrupted
 *
 * [SidePanelMotion] holds the specification and the reasoning. The panel is mounted from the frame
 * it starts entering until the frame it finishes leaving, which is why [panel] is composed for
 * [SidePanelMotion.SLIDE_MILLIS] after [expanded] goes false.
 *
 * ## Focus is trapped, and closing gives it back
 *
 * While open, the panel is a focus group whose exit is cancelled, so D-pad LEFT at the panel's
 * leading edge does nothing instead of silently landing on a control behind the scrim that the
 * user cannot see they are on. Focus moves into the panel when it opens — onto its first **row**,
 * which is not where it used to land; see [PanelLayer].
 *
 * On close, focus returns to the control that opened the panel. That is `Modifier.focusRestorer`
 * on the content group: the group records its focused child when focus leaves it and restores
 * that child when focus is asked back in. It needs no wiring from the caller and it stays correct
 * when several different controls can open the same panel.
 *
 * **The slide does not get in the way of either.** Focus is requested into the panel on the frame
 * the slide starts, not when it ends, so the first D-pad press of an opening panel is never
 * dropped, and focus is handed back to the content on the frame the exit starts rather than when
 * it finishes. A leaving panel's rows do stay reachable for those 240ms; [PanelLayer] records why
 * that is left alone.
 *
 * ## BACK
 *
 * BACK and ESCAPE are handled as key events on the panel, in `onPreviewKeyEvent`, so the module
 * stays in `commonMain`. Both key phases are consumed and [onDismissRequest] is raised on key-up,
 * which is the phase a user can still abort. The handler is installed only while [expanded], so a
 * panel already sliding out cannot raise a second request.
 *
 * **A host on Android should also wire its platform back** (the activity's
 * `OnBackPressedDispatcher`) to the same `onDismissRequest`, gated on `expanded`. This module
 * deliberately does not: Compose's multiplatform `BackHandler` is both `@ExperimentalComposeUiApi`
 * and `@Deprecated("Use NavigationEventHandler instead")` in Compose 1.12, and a design-system
 * module should not pin every consumer to an API that is on its way out. See the task's return
 * value.
 *
 * [onDismissRequest] is a *request* to close. Under `DEC-17` what closing means is a property of
 * the panel, not of the host — see [SidePanel].
 *
 * @param expanded whether the panel is open. Hoisted; the host owns no open/closed state.
 * @param onDismissRequest raised on BACK or ESCAPE while open.
 * @param modifier applied to the host, which fills its parent.
 * @param panel the panel itself, normally a [SidePanel]. Composed while open and while sliding.
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
    // Read live inside the panel's focus callbacks; see PanelLayer.
    val openNow = rememberUpdatedState(expanded)
    val travel = remember {
        Animatable(if (expanded) SidePanelMotion.OPEN else SidePanelMotion.CLOSED)
    }

    Box(modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .focusRequester(contentFocus)
                .focusRestorer()
                .focusGroup(),
            content = { content() },
        )
        // Mounted from the frame the entrance starts to the frame the exit finishes. Reading the
        // value here is what recomposes the host as the panel leaves, so it can unmount it.
        if (expanded || travel.value < SidePanelMotion.CLOSED) {
            Scrim(travel)
            PanelLayer(
                travel = travel,
                open = openNow,
                focusRequester = panelFocus,
                onDismissRequest = onDismissRequest,
                panel = panel,
            )
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

    LaunchedEffect(expanded) {
        travel.animateTo(
            targetValue = if (expanded) SidePanelMotion.OPEN else SidePanelMotion.CLOSED,
            animationSpec = SidePanelMotion.spec(),
        )
    }
}

/**
 * The scrim over [SidePanelHost]'s content, at [SCRIM_ALPHA] times the panel's travel.
 *
 * It is the `bg` token — the palette has nine colours and a scrim is not a tenth. Its opacity
 * rides the slide rather than cutting in: a scrim that appears at full strength on frame one
 * announces the panel before the panel arrives, and on a 10-foot screen that reads as a flash.
 *
 * It is **not** clickable: there is no pointer on a TV remote, and a click-outside-to-dismiss
 * would be a second commit path of exactly the kind [SidePanel] documents against. A host that
 * also ships to touch adds its own dismiss affordance over this.
 *
 * The alpha is read in `drawBehind`, so the scrim re-draws per frame without recomposing.
 */
@Composable
private fun Scrim(travel: Animatable<Float, AnimationVector1D>) {
    val ground = CloudStreamTheme.colors.bg
    Box(
        Modifier.fillMaxSize().drawBehind {
            drawRect(
                color = ground,
                alpha = SCRIM_ALPHA * (SidePanelMotion.CLOSED - travel.value),
            )
        },
    )
}

/**
 * The panel layer: right-anchored, slid in from the right edge, focus-trapped, and listening for
 * BACK.
 *
 * `focusProperties { … }` must precede `focusGroup()` — it applies to the focus target that
 * follows it — and [focusRequester] must precede both so a request lands on the group and is
 * delegated into its first focusable child.
 *
 * ## The trap has to let go when the panel closes, or it traps the restore too
 *
 * `onExit = { cancelFocusChange() }` is the trap, and uncancelled it also cancels
 * **[SidePanelHost]'s own restore**: `contentFocus.requestFocus()` has to move focus out of a row
 * *inside* this group, which is an exit, which the trap refuses. The close then clears focus
 * without re-placing it and the screen is left with **nothing focused at all** — the dead TV
 * screen `focusableWhenDisabled` exists to prevent, one layer up.
 *
 * So the cancellation is gated on [open], and [open] is a [State] read **inside** the callback,
 * at the moment focus tries to move, not when the modifier was composed. By the time the host
 * restores, `expanded` is already false, the exit is allowed, and the trap has done its job for
 * the whole time the panel was open.
 *
 * This was not visible before `DS-08`. The trap predates it, but so did
 * `focusProperties { canFocus = … }` *before* `focusGroup()` — and `focusGroup()` is itself
 * `focusProperties { canFocus = false }.focusTarget()`, so it overwrote that property and the
 * group was focusable. Focus therefore landed on the **group**, not on a row inside it, and moving
 * it away was never an exit for the trap to block. Removing the dead `canFocus` is what put focus
 * on a row, which is where the design wants it, and that is what exposed this.
 *
 * ## What is still not guarded
 *
 * For the [SidePanelMotion.SLIDE_MILLIS] of the exit the panel is still composed and its trap is
 * off, so a D-pad move can reach its rows while it slides away. Focus has already gone back to the
 * content, so this takes a deliberate press into a panel the user is watching leave, and the panel
 * unmounts a moment later. Sealing it means an `onEnter` that cancels while closing, and every
 * form of that tried so far also defeats the restore — which is the worse failure of the two. It
 * is a glitch, not a lost control; the test for it asserts the gap on purpose.
 *
 * `translationX` is the layer's **own** `size.width`, so the panel leaves by exactly its width
 * whatever that width is, and the 280dp token is never restated here.
 *
 * @param open whether the panel is open, read live. False while it is leaving: the trap lets go,
 *   BACK no longer answers, and it keeps drawing until the slide finishes.
 */
@Composable
private fun BoxScope.PanelLayer(
    travel: Animatable<Float, AnimationVector1D>,
    open: State<Boolean>,
    focusRequester: FocusRequester,
    onDismissRequest: () -> Unit,
    panel: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .align(Alignment.CenterEnd)
            .graphicsLayer { translationX = travel.value * size.width }
            .focusRequester(focusRequester)
            .focusProperties { onExit = { if (open.value) cancelFocusChange() } }
            .focusGroup()
            .onPreviewKeyEvent { event ->
                open.value && event.consumeAsDismiss(onDismissRequest)
            },
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
