package com.lagradost.cloudstream4.core.designsystem.state

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme

/** Literals this component owns, and what they should become in `:core:theme`. */
@Immutable
object StateViewDefaults {
    /**
     * The glyph's box: 96px on the artboard. Mirrors `dimens.IconTargetCell` (48dp) in size but
     * not in meaning — this is decoration, never a focus target. Should be promoted to
     * `dimens.StateGlyphSize`.
     */
    val GlyphSize: Dp = 48.dp

    /**
     * Gap between the glyph and the headline, and between the body and the action. Mirrors
     * `dimens.ChipRowSpacing` (24dp). Should be promoted to `dimens.StackBlockGap`.
     */
    val BlockGap: Dp = 24.dp

    /**
     * Gap between the headline and the body copy. Mirrors `dimens.ListRowPadding` (8dp). Should
     * be promoted to `dimens.StackTextGap`.
     */
    val TextGap: Dp = 8.dp

    /**
     * The widest the body copy is allowed to measure. The content band is 832dp across
     * (`dimens.ContentOriginX`..`dimens.ContentEndX`) and a paragraph set that wide is unreadable
     * at three metres. Should be promoted to `dimens.ProseMaxWidth`.
     */
    val MaxTextWidth: Dp = 480.dp
}

/**
 * The full-surface state: a glyph, a headline, a line of body copy, and **exactly one action.**
 *
 * The action is not a slot and not optional — it arrives inside [StatePrompt], which cannot be
 * constructed without a [StateAction]. That is deliberate: the defect this component exists to
 * kill was found on 18 of 20 audited screens, where the empty or failed state either offered no
 * action or offered one the D-pad could not reach. Here the action is always present, and it is
 * always a real focus stop, because [StateActionButton] is `clickable` and therefore focusable.
 *
 * The glyph box is reserved whether or not [glyph] draws anything, so the headline sits at the
 * same height in every state and switching Empty to Error does not shift the stack.
 *
 * Strings are user-facing and arrive already resolved; this module holds no display text.
 *
 * @param prompt what to say and what the one action does.
 * @param modifier applied to the root. The component fills the space it is given and centres
 *   itself inside it.
 * @param detail optional machine-readable detail — a host, a status code, a provider name — set
 *   in the mono ramp beneath the body copy. This is the line that replaces a vanished Toast: the
 *   part a user can read back to you. Omit it for states that are not failures.
 * @param requestInitialFocus moves focus to the action when this state appears. On by default,
 *   and the reason is structural: a full-surface state replaces whatever was focused a moment
 *   ago, and a TV whose focus has nowhere to land answers the D-pad with nothing. Turn it off
 *   only when something else on the screen is deliberately taking focus instead.
 * @param glyph optional decoration, measured into a [StateViewDefaults.GlyphSize] box. Not
 *   focusable, not interactive. The caller chooses it, from `CloudStreamIcons`.
 * @param showAction `false` when the caller draws [StatePrompt.action] somewhere else, which
 *   `TV Search Suggestions` 8c does: its action rides the chip row. The caller then owes that action
 *   a focus stop, and owes it [requestInitialFocus]'s job too. The exactly-one-action rule still holds.
 * @param actionButton how [StatePrompt.action] is drawn: [StateActionButton] unless a platform has its own button. It
 *   is given the action and a modifier that carries [requestInitialFocus]'s requester, and must apply that modifier to
 *   a focusable control. The Mac's outlined one is `DesktopStateView`'s (`KMP-101`).
 */
@Composable
fun StateView(
    prompt: StatePrompt,
    modifier: Modifier = Modifier,
    detail: String? = null,
    requestInitialFocus: Boolean = true,
    glyph: @Composable () -> Unit = {},
    showAction: Boolean = true,
    actionButton: @Composable (action: StateAction, modifier: Modifier) -> Unit = { action, focus ->
        StateActionButton(action = action, modifier = focus)
    },
) {
    val actionFocus = remember { FocusRequester() }
    // Keyed on the flag alone, deliberately. `prompt.action` holds a lambda, so a ViewModel that
    // rebuilds its state emits a new `StateAction` every time and keying on it would re-run this
    // effect on every emission — dragging focus back to the button while the user is trying to
    // leave it. "When this state appears" is entry into composition, which this key already means:
    // each `when` branch in `UiStateHost` is its own composition group, so Empty -> Error disposes
    // this `StateView` and the effect runs again on the new one.
    LaunchedEffect(requestInitialFocus, showAction) {
        if (requestInitialFocus && showAction) actionFocus.requestFocus()
    }
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier.size(StateViewDefaults.GlyphSize),
                contentAlignment = Alignment.Center,
                content = { glyph() },
            )
            Spacer(Modifier.height(StateViewDefaults.BlockGap))
            PromptText(prompt = prompt, detail = detail)
            if (showAction) {
                Spacer(Modifier.height(StateViewDefaults.BlockGap))
                actionButton(prompt.action, Modifier.focusRequester(actionFocus))
            }
        }
    }
}

/** The headline, body and optional detail, none wider than [StateViewDefaults.MaxTextWidth]. */
@Composable
private fun PromptText(prompt: StatePrompt, detail: String?) {
    val colors = CloudStreamTheme.colors
    val typography = CloudStreamTheme.typography
    Text(
        text = prompt.headline,
        style = typography.h1,
        color = colors.ink,
        textAlign = TextAlign.Center,
        modifier = Modifier.widthIn(max = StateViewDefaults.MaxTextWidth),
    )
    Spacer(Modifier.height(StateViewDefaults.TextGap))
    // `muted`, as `TV Library` draws `.svb`; `TV Search`'s closing note, point 11, gives the contrast.
    Text(
        text = prompt.body,
        style = typography.body,
        color = colors.muted,
        textAlign = TextAlign.Center,
        modifier = Modifier.widthIn(max = StateViewDefaults.MaxTextWidth),
    )
    if (detail == null) return
    Spacer(Modifier.height(StateViewDefaults.TextGap))
    Text(
        text = detail,
        style = typography.labelMono,
        color = colors.muted,
        textAlign = TextAlign.Center,
        modifier = Modifier.widthIn(max = StateViewDefaults.MaxTextWidth),
    )
}

private val PreviewPrompt = StatePrompt(
    headline = "Nothing downloaded yet",
    body = "Downloads you start will appear here, and stay available without a connection.",
    action = StateAction(label = "Browse", onActivate = {}),
)

private val PreviewFailurePrompt = StatePrompt(
    headline = "Could not load this title",
    body = "The source answered, but not with anything we could play.",
    action = StateAction(label = "Try again", onActivate = {}),
)

@Preview
@Composable
private fun StateViewPreview() {
    StatePreviewSurface {
        StateView(prompt = PreviewPrompt)
    }
}

@Preview
@Composable
private fun StateViewWithDetailPreview() {
    StatePreviewSurface {
        StateView(prompt = PreviewFailurePrompt, detail = "source-a.example - HTTP 503")
    }
}
