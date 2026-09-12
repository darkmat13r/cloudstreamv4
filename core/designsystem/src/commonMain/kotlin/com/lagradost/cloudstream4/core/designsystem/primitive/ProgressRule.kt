package com.lagradost.cloudstream4.core.designsystem.primitive

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream4.core.theme.CloudStreamDimens
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme

/**
 * The watch-progress bar that sits on a card's bottom edge.
 *
 * ## Geometry
 *
 * [ProgressRuleDefaults.Thickness] is 2dp (4px on the artboard), and **2dp is deliberately off
 * the 4dp step**. The rule is a hairline in its own axis — a stroke weight, which
 * `.claude/rules/dimension-scale.md` exempts — so the number that has to be on the step is its
 * *far* edge, the card's bottom edge, not its near one. Place it flush against that edge and the
 * card stays on the ladder:
 *
 * ```kotlin
 * Box(Modifier.size(CloudStreamTheme.cardSizes.LandscapeMedium.asDpSize)) {
 *     Artwork()
 *     ProgressRule(progress = 0.4f, modifier = Modifier.align(Alignment.BottomCenter))
 * }
 * ```
 *
 * The rule fills the width it is given and does not inset itself; a card's progress runs the full
 * card width on every artboard that shows one.
 *
 * ## Colour
 *
 * The filled portion is `live`, which is exactly what that token is for — playback state. The
 * track defaults to `bg` at [ProgressRuleDefaults.TrackAlpha] rather than `line`, because the
 * rule's home is on top of artwork where a `line` (`#242A31`) track disappears against a bright
 * poster. On a solid surface — a settings row, a download list — pass
 * `CloudStreamTheme.colors.line` for the track instead.
 *
 * @param progress fraction watched. Coerced into `0f..1f`; callers routinely divide a position by
 *   a duration that can be zero or stale, and a rule that runs past its card is a layout bug.
 * @param modifier applied before the rule's own sizing.
 * @param color the filled portion. `live` is the only correct value in the design.
 * @param trackColor the unfilled portion. See above for the artwork-versus-surface choice.
 * @param thickness the stroke weight. Exempt from the step in its own axis; see above.
 */
@Composable
fun ProgressRule(
    progress: Float,
    modifier: Modifier = Modifier,
    color: Color = CloudStreamTheme.colors.live,
    trackColor: Color = CloudStreamTheme.colors.bg.copy(alpha = ProgressRuleDefaults.TrackAlpha),
    thickness: Dp = ProgressRuleDefaults.Thickness,
) {
    val fraction = progress.coerceIn(NO_PROGRESS, FULL_PROGRESS)
    Canvas(modifier = modifier.fillMaxWidth().height(thickness)) {
        drawRect(color = trackColor)
        if (fraction > NO_PROGRESS) {
            drawRect(color = color, size = Size(size.width * fraction, size.height))
        }
    }
}

/**
 * Values [ProgressRule] needs that `:core:theme` does not name yet.
 */
@Immutable
object ProgressRuleDefaults {

    /**
     * Rule thickness, 4px on the artboard. **Should become `CloudStreamDimens.ProgressRuleThickness`,
     * documented beside [CloudStreamDimens.Hairline] as the design's second stroke weight** — it
     * is off the 4dp step for the same reason that one is, and for no other.
     */
    val Thickness: Dp = 2.dp

    /**
     * Opacity of the track when the rule sits on artwork.
     *
     * **Invented — the artboards show the filled portion but do not state a track value.** This
     * is the lightest scrim at which the unfilled remainder still reads as a track rather than as
     * part of the poster. A designer should confirm it or name a real token; it is not a
     * candidate for promotion until then.
     */
    const val TrackAlpha: Float = 0.6f
}

private const val NO_PROGRESS = 0f
private const val FULL_PROGRESS = 1f

/** Part-watched, for the preview only. */
private const val PREVIEW_PART_PROGRESS = 0.35f

@Preview
@Composable
private fun ProgressRulePreview() {
    CloudStreamTheme {
        Column(
            modifier = Modifier
                .background(CloudStreamTheme.colors.bg)
                .padding(CloudStreamDimens.SidePanelContentInset),
            verticalArrangement = Arrangement.spacedBy(CloudStreamDimens.MinFocusGap),
        ) {
            ProgressRuleCardPreview(progress = NO_PROGRESS)
            ProgressRuleCardPreview(progress = PREVIEW_PART_PROGRESS)
            ProgressRuleCardPreview(progress = FULL_PROGRESS)
        }
    }
}

@Composable
private fun ProgressRuleCardPreview(progress: Float) {
    Box(
        modifier = Modifier
            .size(CloudStreamTheme.cardSizes.LandscapeMedium.asDpSize)
            .background(CloudStreamTheme.colors.raised),
    ) {
        ProgressRule(progress = progress, modifier = Modifier.align(Alignment.BottomCenter))
    }
}
