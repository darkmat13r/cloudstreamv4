package com.lagradost.cloudstream4.core.designsystem.card

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.lagradost.cloudstream4.core.designsystem.focus.tvFocusLift
import com.lagradost.cloudstream4.core.designsystem.focus.tvFocusRing
import com.lagradost.cloudstream4.core.theme.CardSize
import com.lagradost.cloudstream4.core.theme.CloudStreamCardSizes
import com.lagradost.cloudstream4.core.theme.CloudStreamDimens
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme
import androidx.compose.ui.tooling.preview.Preview

/**
 * The trailing card that opens a rail's full contents.
 *
 * **This is how a rail exposes its overflow.** The obvious alternative — a "see all" button in the
 * rail's header — is unreachable on a D-pad: from inside a horizontal rail, `up` leaves for the
 * previous row and there is no move that lands on the header. Putting the affordance at the end of
 * the rail means `right` past the last card reaches it, which is the direction a viewer is already
 * travelling when they run out of content.
 *
 * It sits at the **same rung** as the cards it follows, so the rail's fixed pitch holds all the way
 * to its end, and it carries the same ring and the same 4dp lift, drawn the same draw-only way.
 *
 * [label] is a parameter rather than a string this module owns: `:core:designsystem` ships no
 * string resources, and the label is user-facing text that must come from the app's translations.
 *
 * @param size the rung, taken from the same `CloudStreamCardSizes` value the rail's cards use.
 * @param label the localised label, e.g. `stringResource(Res.string.see_all)`.
 * @param focused whether this card is the focused one.
 */
@Composable
fun SeeAllCard(
    size: CardSize,
    label: String,
    focused: Boolean,
    modifier: Modifier = Modifier,
) {
    val shape = CloudStreamTheme.shapes.none
    val ink = if (focused) CloudStreamTheme.colors.ink else CloudStreamTheme.colors.ink2
    Box(
        modifier = modifier
            .size(size.asDpSize)
            .tvFocusLift(lifted = focused)
            .tvFocusRing(focused = focused, shape = shape),
    ) {
        SeeAllContent(label = label, ink = ink, shape = shape)
    }
}

/**
 * The card's face: a chevron over the label, both centred, on a `colors.raised` surface — the
 * design's "this is a control, not artwork" surface.
 */
@Composable
private fun SeeAllContent(
    label: String,
    ink: Color,
    shape: Shape,
    modifier: Modifier = Modifier,
) {
    val style = CloudStreamTheme.typography.label
    Column(
        modifier = modifier
            .fillMaxSize()
            .clip(shape)
            .background(CloudStreamTheme.colors.raised)
            .padding(MediaCardDefaults.ContentPadding),
        verticalArrangement = Arrangement.spacedBy(
            space = CloudStreamDimens.StackGap,
            alignment = Alignment.CenterVertically,
        ),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        SeeAllChevron(color = ink)
        BasicText(
            text = label,
            style = remember(style, ink) {
                style.copy(color = ink, textAlign = TextAlign.Center)
            },
            maxLines = MediaCardDefaults.META_MAX_LINES,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * A chevron drawn rather than shipped as an asset — `:core:designsystem` carries no drawables, and
 * a two-line glyph does not need one. The proportions are fractions of the glyph box, so the
 * 4dp step does not apply to them; only the box itself and the stroke weight are tokens.
 */
@Composable
private fun SeeAllChevron(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(MediaCardDefaults.SeeAllGlyphSize)) {
        val apex = Offset(size.width * CHEVRON_APEX_X, size.height * CHEVRON_MID_Y)
        drawLine(
            color = color,
            start = Offset(size.width * CHEVRON_BASE_X, size.height * CHEVRON_TOP_Y),
            end = apex,
            strokeWidth = MediaCardDefaults.SeeAllGlyphStroke.toPx(),
            cap = StrokeCap.Round,
        )
        drawLine(
            color = color,
            start = apex,
            end = Offset(size.width * CHEVRON_BASE_X, size.height * CHEVRON_BOTTOM_Y),
            strokeWidth = MediaCardDefaults.SeeAllGlyphStroke.toPx(),
            cap = StrokeCap.Round,
        )
    }
}

private const val CHEVRON_BASE_X = 0.32f
private const val CHEVRON_APEX_X = 0.68f
private const val CHEVRON_TOP_Y = 0.18f
private const val CHEVRON_MID_Y = 0.5f
private const val CHEVRON_BOTTOM_Y = 0.82f

private const val PREVIEW_LABEL = "See all"

@Preview
@Composable
private fun SeeAllCardPreview() {
    CardPreviewRow {
        SeeAllCard(
            size = CloudStreamCardSizes.PortraitMedium,
            label = PREVIEW_LABEL,
            focused = false,
        )
        SeeAllCard(
            size = CloudStreamCardSizes.PortraitMedium,
            label = PREVIEW_LABEL,
            focused = true,
        )
        SeeAllCard(
            size = CloudStreamCardSizes.LandscapeMedium,
            label = PREVIEW_LABEL,
            focused = true,
        )
    }
}
