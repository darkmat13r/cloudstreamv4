package com.lagradost.cloudstream4.core.designsystem.target

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream4.core.designsystem.focus.tvFocusRing
import com.lagradost.cloudstream4.core.theme.CloudStreamDimens
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme

/**
 * The **primary** text button: an ink fill with a bg label.
 *
 * ## There is no colour parameter, and that is the point
 *
 * `CloudStreamTheme.colors.live` (#CD3D4B) is **playback state only** — progress, LIVE badges, a
 * failure marker. It is never a button. That rule cannot be enforced by a comment on a `color`
 * parameter, so this API does not have one: the only two buttons that exist are this one and
 * [GhostTextButton], and neither can be told to fill itself with `live`. If a screen needs a red
 * button, the answer is that the design does not have one — raise it, do not reach past this API.
 *
 * ## Geometry
 *
 * [CloudStreamDimens.TextButtonHeight] (32dp) tall and at least
 * [CloudStreamDimens.TextButtonMinWidth] (120dp) wide. The width above that minimum is intrinsic:
 * it comes from the label plus [TextButtonDefaults.HorizontalPadding] on each side, which geometry
 * rule 6 exempts from the 4dp step. The height and the minimum are not exempt and are fixed here.
 *
 * The ring goes on the button itself. It bleeds [CloudStreamDimens.FocusRingBleed] (8dp) outside
 * that 32x120 box without changing it, so a focused button never resizes or moves its neighbours —
 * but it does need 8dp of unclipped space around it.
 *
 * @param label already-localised button text. One line; it ellipsises rather than wrapping.
 * @param onClick invoked on D-pad centre, Enter, or a tap.
 * @param modifier applied to the button, which is the focusable and the ring's target. Pass
 *   `Modifier.onFocusChanged { … }` here if a parent needs to observe focus.
 * @param enabled when false the button is not focusable and renders on
 *   `CloudStreamTheme.colors.raised` with a `muted` label.
 */
@Composable
fun PrimaryTextButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = CloudStreamTheme.colors
    val skin = remember(colors, enabled) {
        TextButtonSkin(
            fill = if (enabled) colors.ink else colors.raised,
            label = if (enabled) colors.bg else colors.muted,
            hairline = null,
        )
    }
    TargetTextButton(
        label = label,
        skin = skin,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
    )
}

/**
 * The **ghost** text button: no fill, a `line` hairline, an `ink` label.
 *
 * Same geometry and the same absence of a colour parameter as [PrimaryTextButton] — read that
 * KDoc first; only the skin differs.
 *
 * **The hairline consumes no layout.** It is stroked on the button's own outline in a
 * `drawWithCache`, not added as a `Modifier.border`, because a 1dp border shifts the padding box
 * by 1dp and puts the label — and every dimension derived from the button's content box — off the
 * 4dp step. A ghost button and a primary button of the same label are therefore exactly the same
 * size, which is what lets them sit side by side in a row without one of them shifting the other.
 * `CloudStreamDimens.Hairline` is a stroke *weight* and is the one value in the system that is
 * deliberately not on the 4dp step.
 *
 * @param label already-localised button text.
 * @param onClick invoked on D-pad centre, Enter, or a tap.
 * @param modifier applied to the button, which is the focusable and the ring's target.
 * @param enabled when false the button is not focusable and its label drops to `muted`.
 */
@Composable
fun GhostTextButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = CloudStreamTheme.colors
    val skin = remember(colors, enabled) {
        TextButtonSkin(
            fill = Color.Transparent,
            label = if (enabled) colors.ink else colors.muted,
            hairline = colors.line,
        )
    }
    TargetTextButton(
        label = label,
        skin = skin,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
    )
}

/** The three colours that distinguish one text button from another. */
@Immutable
private data class TextButtonSkin(
    val fill: Color,
    val label: Color,
    val hairline: Color?,
)

@Composable
private fun TargetTextButton(
    label: String,
    skin: TextButtonSkin,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val shape = CloudStreamTheme.shapes.medium
    Box(
        modifier = modifier
            .height(CloudStreamDimens.TextButtonHeight)
            .widthIn(min = CloudStreamDimens.TextButtonMinWidth)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .tvFocusRing(focused = focused, shape = shape)
            .background(color = skin.fill, shape = shape)
            .hairlineOutline(color = skin.hairline, shape = shape)
            .padding(horizontal = TextButtonDefaults.HorizontalPadding),
        contentAlignment = Alignment.Center,
    ) {
        TargetLabel(
            text = label,
            style = CloudStreamTheme.typography.label,
            color = skin.label,
        )
    }
}

/**
 * Strokes [shape] at [CloudStreamDimens.Hairline] without touching the layout phase.
 *
 * The stroke straddles the outline, so half of it falls just outside the button's box. That is
 * half a hairline, it is a stroke weight rather than a layout dimension, and geometry rule 6
 * exempts a hairline's own axis from the step. What matters is the half that does *not* happen:
 * nothing is inset, so the content box is identical with and without the hairline.
 */
private fun Modifier.hairlineOutline(color: Color?, shape: Shape): Modifier =
    if (color == null) {
        this
    } else {
        drawWithCache {
            val outline = shape.createOutline(size, layoutDirection, this)
            val style = Stroke(width = CloudStreamDimens.Hairline.toPx())
            onDrawWithContent {
                drawContent()
                drawOutline(outline = outline, color = color, style = style)
            }
        }
    }

/** Values the text buttons need that `:core:theme` does not name yet. */
@Immutable
object TextButtonDefaults {

    /**
     * Padding between the button's edge and its label: 32px on the artboard, halved.
     *
     * Mirrors `CloudStreamDimens.SidePanelContentInset` (16dp) numerically only — that token is a
     * panel inset and has nothing to do with a button. **Promote this to
     * `CloudStreamDimens.TextButtonHorizontalPadding`.** It is load-bearing: it, plus the label's
     * intrinsic width, is what takes a button past the 120dp minimum.
     */
    val HorizontalPadding: Dp = 16.dp
}
