package com.lagradost.cloudstream4.core.designsystem.state

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream4.core.designsystem.focus.tvFocusRing
import com.lagradost.cloudstream4.core.theme.CloudStreamDimens
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme

/**
 * How loud the button is.
 *
 * There are two, and neither of them is [com.lagradost.cloudstream4.core.theme.CloudStreamColors.live]:
 * `live` is playback state only — progress, LIVE badges, a failure marker — and is never a
 * button.
 */
@Immutable
enum class StateActionEmphasis {
    /** An `ink` fill with a `bg` label. The one action of a full-surface state. */
    Primary,

    /** A `raised` fill with an `ink` label. No caller uses it since `FailureBanner`'s retry became [Primary]. */
    Secondary,
}

/** Literals this button owns, and what they should become in `:core:theme`. */
@Immutable
object StateActionButtonDefaults {
    /**
     * Horizontal padding inside the button, between its edge and its label. Mirrors
     * `dimens.SidePanelContentInset` (16dp) — the same step, a different meaning. Should be
     * promoted to `dimens.TextButtonHorizontalPadding`.
     */
    val HorizontalPadding: Dp = 16.dp
}

/**
 * The text button every state in this package uses for its single action.
 *
 * Geometry is the design's text-button class verbatim: [CloudStreamDimens.TextButtonHeight]
 * (32dp) tall, at least [CloudStreamDimens.TextButtonMinWidth] (120dp) wide, with the focus ring
 * on the button itself. The ring is drawn, not laid out, so it changes nothing the parent
 * measured — but it does bleed 8dp outward, so **no ancestor may clip this button.**
 *
 * `Modifier.clickable` is what makes it a focus stop: it brings `focusableInNonTouchMode` with
 * it, so the button is in the D-pad order the moment it is composed. The indication is `null`
 * because the ring *is* the indication.
 *
 * **Focus is read with `onFocusChanged`, not from the interaction source.** [StateView] requests
 * focus on this button in its first frame, before `collectIsFocusedAsState` has subscribed, and an
 * interaction source does not replay: the `Focus` interaction was lost, so the button held focus
 * with no ring until focus left and came back. `DS-15` measured it on the emulator. `ListRow` reads
 * its focus the same way.
 *
 * This is a local button, not the shared one. When the controls group lands a general text
 * button with this geometry, delete this file and point [StateView] and [FailureBanner] at it.
 *
 * @param action label and behaviour, inseparable. See [StateAction].
 * @param modifier applied to the button's own box.
 * @param emphasis see [StateActionEmphasis].
 */
@Composable
fun StateActionButton(
    action: StateAction,
    modifier: Modifier = Modifier,
    emphasis: StateActionEmphasis = StateActionEmphasis.Primary,
) {
    val interactionSource = remember { MutableInteractionSource() }
    var isFocused by remember { mutableStateOf(false) }
    val colors = CloudStreamTheme.colors
    val shape = CloudStreamTheme.shapes.medium
    val isPrimary = emphasis == StateActionEmphasis.Primary

    Box(
        modifier = modifier
            .height(CloudStreamDimens.TextButtonHeight)
            .widthIn(min = CloudStreamDimens.TextButtonMinWidth)
            .background(color = if (isPrimary) colors.ink else colors.raised, shape = shape)
            .onFocusChanged { isFocused = it.isFocused }
            .tvFocusRing(focused = isFocused, shape = shape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClick = action.onActivate,
            )
            .padding(horizontal = StateActionButtonDefaults.HorizontalPadding),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = action.label,
            style = CloudStreamTheme.typography.label,
            color = if (isPrimary) colors.bg else colors.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private val PreviewAction = StateAction(label = "Retry", onActivate = {})

@Preview
@Composable
private fun StateActionButtonPrimaryPreview() {
    StatePreviewSurface {
        StateActionButton(action = PreviewAction)
    }
}

@Preview
@Composable
private fun StateActionButtonSecondaryPreview() {
    StatePreviewSurface {
        StateActionButton(action = PreviewAction, emphasis = StateActionEmphasis.Secondary)
    }
}
