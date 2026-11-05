package com.lagradost.cloudstream4.core.designsystem.card

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import com.lagradost.cloudstream4.core.designsystem.focus.tvFocusLift
import com.lagradost.cloudstream4.core.designsystem.focus.tvFocusRing
import com.lagradost.cloudstream4.core.theme.CardSize
import com.lagradost.cloudstream4.core.theme.CloudStreamCardSizes
import com.lagradost.cloudstream4.core.theme.CloudStreamDimens
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme

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
 * The card's face: the label, centred, on a darker ground than the cards it follows, inside a
 * dashed hairline — `.seeall` in `TV Search` 4a to 4c and `TV Home` 3d. It is a place to go, not
 * a piece of artwork, and the dashed edge is what says so.
 *
 * The ground is `colors.surface` at [MediaCardDefaults.SEE_ALL_GROUND_ALPHA], which over `bg` is
 * the boards' `#060A0D`. The outline is a [CloudStreamDimens.Hairline] `colors.line` stroke drawn
 * just inside the edge, dashed [MediaCardDefaults.SEE_ALL_DASH_ON] on and
 * [MediaCardDefaults.SEE_ALL_DASH_OFF] off in units of its own weight. It is draw-only, so it
 * costs the rung nothing. The card used to be a `raised` fill with a chevron drawn on a `Canvas`;
 * no board draws a glyph here, and `icons.md` forbids drawing one, so there is none.
 */
@Composable
private fun SeeAllContent(
    label: String,
    ink: Color,
    shape: Shape,
    modifier: Modifier = Modifier,
) {
    val colors = CloudStreamTheme.colors
    val style = CloudStreamTheme.typography.label
    Box(
        modifier = modifier
            .fillMaxSize()
            .clip(shape)
            .background(colors.surface.copy(alpha = MediaCardDefaults.SEE_ALL_GROUND_ALPHA))
            .dashedHairline(color = colors.line)
            .padding(MediaCardDefaults.ContentPadding),
        contentAlignment = Alignment.Center,
    ) {
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

/** A dashed [CloudStreamDimens.Hairline] rectangle just inside this element's edge. Draw-only. */
private fun Modifier.dashedHairline(color: Color): Modifier = drawWithCache {
    val stroke = CloudStreamDimens.Hairline.toPx()
    val half = stroke / HALF
    val style = Stroke(
        width = stroke,
        pathEffect = PathEffect.dashPathEffect(
            floatArrayOf(stroke * MediaCardDefaults.SEE_ALL_DASH_ON, stroke * MediaCardDefaults.SEE_ALL_DASH_OFF),
        ),
    )
    onDrawWithContent {
        drawContent()
        drawRect(
            color = color,
            topLeft = Offset(half, half),
            size = Size(size.width - stroke, size.height - stroke),
            style = style,
        )
    }
}

private const val HALF = 2f

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
