package com.lagradost.cloudstream4.core.designsystem.card

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import com.lagradost.cloudstream4.core.theme.CloudStreamDimens
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme

/**
 * The revealed text stack, on its own scrim: a title clamped to [MediaCardDefaults.TITLE_MAX_LINES]
 * lines and an optional mono meta line under it.
 *
 * **This is an overlay, never layout.** The caller aligns it to the card's bottom edge inside the
 * clipped artwork box; it has no measured relationship with the card's size, so revealing it
 * cannot change what a rail measures. Clamping is what keeps it inside the card at the small rungs
 * — on a 64x36 backdrop a long title is ellipsised, not spilled.
 *
 * **The scrim is this block's own background**, as `.revt` draws it in `TV Search` and `TV Home`:
 * [contentPadding] carries [MediaCardDefaults.RevealScrimLead] on top, so the block is lead + text
 * + inset, solid `bg` at [MediaCardDefaults.SCRIM_END_ALPHA] across its bottom
 * [MediaCardDefaults.SCRIM_SOLID_FRACTION] and fading to nothing at its top. A two-line title gets
 * a taller scrim than a one-line one, which a fixed fraction of the card cannot do. It paints over
 * the image without resizing anything and without reaching the focus ring, which lives outside.
 *
 * The gap between the two lines is `CloudStreamDimens.StackGap`, the design's one text-stack gap.
 *
 * @param title the clamped title.
 * @param meta the optional mono second line.
 * @param contentPadding the text's inset inside the scrim block.
 */
@Composable
internal fun MediaCardReveal(
    title: String,
    meta: String?,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val ground = CloudStreamTheme.colors.bg
    val scrim = remember(ground) {
        val solid = ground.copy(alpha = MediaCardDefaults.SCRIM_END_ALPHA)
        Brush.verticalGradient(
            SCRIM_TOP to Color.Transparent,
            SCRIM_BOTTOM - MediaCardDefaults.SCRIM_SOLID_FRACTION to solid,
            SCRIM_BOTTOM to solid,
        )
    }
    Column(
        modifier = modifier.fillMaxWidth().background(scrim).padding(contentPadding),
        verticalArrangement = Arrangement.spacedBy(CloudStreamDimens.StackGap),
    ) {
        BasicText(
            text = title,
            style = rememberInkStyle(CloudStreamTheme.typography.body, CloudStreamTheme.colors.ink),
            maxLines = MediaCardDefaults.TITLE_MAX_LINES,
            overflow = TextOverflow.Ellipsis,
        )
        if (meta != null) {
            BasicText(
                text = meta,
                style = rememberInkStyle(
                    CloudStreamTheme.typography.labelMono,
                    CloudStreamTheme.colors.ink2,
                ),
                maxLines = MediaCardDefaults.META_MAX_LINES,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * The progress rule along the card's bottom edge: a `colors.line` track with a `colors.live` fill.
 *
 * `live` is the design's playback-state colour and this is one of the three things it is for. The
 * rule is [MediaCardDefaults.ProgressRuleHeight] tall and sits inside the card's clipped box, so
 * like every other overlay it costs the card nothing in measurement.
 *
 * @param progress already validated to `0f..1f` by [MediaCardState].
 */
@Composable
internal fun MediaCardProgressRule(
    progress: Float,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .fillMaxWidth()
            .height(MediaCardDefaults.ProgressRuleHeight)
            .background(CloudStreamTheme.colors.line),
    ) {
        Box(
            Modifier
                .fillMaxWidth(progress)
                .fillMaxHeight()
                .background(CloudStreamTheme.colors.live),
        )
    }
}

/**
 * A ramp style carrying a token colour. Remembered because `TextStyle.copy` allocates, and a card
 * that recomposes on every focus move would otherwise allocate two styles per frame.
 */
@Composable
private fun rememberInkStyle(style: TextStyle, color: Color): TextStyle =
    remember(style, color) { style.copy(color = color) }

/** The top and bottom of the reveal block, as gradient stops. */
private const val SCRIM_TOP = 0f
private const val SCRIM_BOTTOM = 1f
