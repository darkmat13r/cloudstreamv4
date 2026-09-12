package com.lagradost.cloudstream4.core.designsystem.primitive

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream4.core.theme.CloudStreamDimens
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme

/**
 * What a [Badge] is saying, which decides its label colour.
 *
 * There are exactly two, because the colour contract allows exactly two. [Neutral] is an
 * `ink` label; [Live] is the one playback-state badge and is the only reason a badge may use
 * `live`.
 */
@Immutable
enum class BadgeTone {
    /** A descriptive badge: `4K`, `DUB`, `CACHED`, `HDR`. Label is `ink`. */
    Neutral,

    /**
     * A playback-state badge: `LIVE`. Label is `live`, which the colour contract permits here
     * ("progress, LIVE badges, a failure marker") and nowhere else in this component.
     */
    Live,
}

/**
 * A short mono-type marker that sits on top of artwork: `4K`, `DUB`, `CACHED`, `LIVE`.
 *
 * ## Geometry
 *
 * [BadgeDefaults.Height] is 16dp (32px on the artboard). The label is the ramp's
 * `labelMono` — JetBrains Mono at 12sp in a 16sp box, which is 24/32px on the artboard and
 * leaves 4px of air above and below inside the 32px pill. Horizontal padding is
 * [BadgeDefaults.HorizontalPadding].
 *
 * **The width is intrinsic and that is correct.** Rule 6: a badge's width comes from its string.
 * The step governs the height, which is written here; it does not govern text measurement.
 *
 * **The height is a floor, not a fixed size** — `heightIn(min = …)` rather than `height(…)`. At
 * the design's font scale the two are identical and the badge is exactly 16dp, on the step. Above
 * it, a fixed height would clip the label rather than grow, and a clipped `CACHED` is worse than
 * a badge 2dp off the step on a device nobody designed for. Layouts that place a badge at a fixed
 * y should therefore read its measured height rather than assume 16dp.
 *
 * ## Ground
 *
 * The ground is `bg` at [BadgeDefaults.GroundAlpha] — a translucent black scrim, not a tenth
 * colour — so the badge survives whatever artwork is beneath it. The alpha is not in the design
 * and is flagged as invented on [BadgeDefaults.GroundAlpha].
 *
 * The text is **not** upper-cased here. Every badge string the design shows is already upper
 * case, and silently transforming a caller's string is a policy the artboards never stated.
 *
 * @param text the badge string. Kept to one line and never wrapped; a long string is clipped by
 *   the caller's constraints rather than reflowing the pill.
 * @param modifier applied before the badge's own sizing and ground.
 * @param tone see [BadgeTone].
 */
@Composable
fun Badge(
    text: String,
    modifier: Modifier = Modifier,
    tone: BadgeTone = BadgeTone.Neutral,
) {
    val colors = CloudStreamTheme.colors
    val labelColor = when (tone) {
        BadgeTone.Neutral -> colors.ink
        BadgeTone.Live -> colors.live
    }
    val base = CloudStreamTheme.typography.labelMono
    val style = remember(base, labelColor) { base.copy(color = labelColor) }
    Box(
        modifier = modifier
            .heightIn(min = BadgeDefaults.Height)
            .background(
                color = colors.bg.copy(alpha = BadgeDefaults.GroundAlpha),
                shape = CloudStreamTheme.shapes.pill,
            )
            .padding(horizontal = BadgeDefaults.HorizontalPadding),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(text = text, style = style, softWrap = false, maxLines = BADGE_MAX_LINES)
    }
}

/**
 * Values [Badge] needs that `:core:theme` does not name yet.
 *
 * Each one is on the 4dp step and each should be promoted into `CloudStreamDimens` once a single
 * agent owns that file again; they live here only so this module does not edit it concurrently.
 */
@Immutable
object BadgeDefaults {

    /**
     * Badge height, 32px on the artboard. **Should become `CloudStreamDimens.BadgeHeight`.**
     * Mirrors no existing token — it is a target class of its own, half a
     * [CloudStreamDimens.IconTargetCell].
     */
    val Height: Dp = 16.dp

    /**
     * Padding either side of the label, 16px on the artboard. **Should become
     * `CloudStreamDimens.BadgeHorizontalPadding`.** Numerically equal to
     * [CloudStreamDimens.ListRowPadding] but a different meaning, so it is not that token.
     */
    val HorizontalPadding: Dp = 8.dp

    /**
     * Opacity of the black ground.
     *
     * **Invented — the artboards do not state it.** The requirement they do state is that the
     * badge survives any artwork beneath it, and this is the lightest scrim at which `ink` on
     * `bg` still reads over a bright poster at three metres. A designer should confirm or replace
     * it; unlike the dimensions above it is not a candidate for promotion until then.
     */
    const val GroundAlpha: Float = 0.72f
}

private const val BADGE_MAX_LINES = 1

@Preview
@Composable
private fun BadgePreview() {
    CloudStreamTheme {
        Row(
            modifier = Modifier
                .background(CloudStreamTheme.colors.raised)
                .padding(CloudStreamDimens.SidePanelContentInset),
            horizontalArrangement = Arrangement.spacedBy(CloudStreamDimens.MinFocusGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Badge(text = "4K")
            Badge(text = "DUB")
            Badge(text = "CACHED")
            Badge(text = "LIVE", tone = BadgeTone.Live)
        }
    }
}
