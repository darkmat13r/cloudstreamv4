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
import androidx.compose.ui.text.style.TextOverflow
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
 * `labelMono` — JetBrains Mono at 12sp in a 16sp box, which fills the 16dp badge exactly.
 * Horizontal padding is [BadgeDefaults.HorizontalPadding], 4dp, and the corner is
 * `CloudStreamTheme.shapes.small`, the role `CloudStreamShapes` names for badges: `.bdg` in
 * `TV Search`, `TV Home` and `TV Library` is `padding: 0 8px; border-radius: 8px`.
 *
 * **The width is intrinsic and that is correct.** Rule 6: a badge's width comes from its string.
 * The step governs the height, which is written here; it does not govern text measurement.
 *
 * **Intrinsic is not unbounded.** A badge is drawn over artwork no wider than a card, and the
 * longest translation of a type or a quality is far wider than one: `quality_sdr` is `SDR` in
 * English and `RANG DINAMIC SIMPLE (SDR)` — 154dp — in Catalan, inside an 80dp poster whose badge
 * box is 72dp (`BUG-62`). So the label **ellipsises at whatever width the caller allows**, and the
 * caller is the one that knows the cap: `MediaCard` gives its badges the card's width less
 * `MediaCardDefaults.ContentPadding` on both sides — 4dp, the same number as
 * [BadgeDefaults.HorizontalPadding] but the card's inset rather than the badge's own — and
 * [BadgeGroup] gives a pair of badges on one edge that same cap between them. A clipped `RANG DINAMIC SIMPL` ends mid-word with
 * nothing to say it was cut; `RANG DINAM…` says so.
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
 * colour — so the badge survives whatever artwork is beneath it. The alpha is the boards'
 * `.bdg` value; see [BadgeDefaults.GroundAlpha].
 *
 * The text is **not** upper-cased here. Every badge string the design shows is already upper
 * case, and silently transforming a caller's string is a policy the artboards never stated.
 *
 * @param text the badge string. Kept to one line and never wrapped; a string too long for the
 *   width the caller allows is ellipsised rather than reflowing or being cut mid-glyph.
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
                shape = CloudStreamTheme.shapes.small,
            )
            .padding(horizontal = BadgeDefaults.HorizontalPadding),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text = text,
            style = style,
            overflow = TextOverflow.Ellipsis,
            softWrap = false,
            maxLines = BADGE_MAX_LINES,
        )
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
     * Padding either side of the label: `.bdg`'s `padding: 0 8px`, halved. **Should become
     * `CloudStreamDimens.BadgeHorizontalPadding`.** Numerically equal to
     * [CloudStreamDimens.StackGap] but a different meaning, so it is not that token.
     */
    val HorizontalPadding: Dp = 4.dp

    /**
     * Opacity of the black ground: `.bdg`'s `background: rgba(0,0,0,0.78)` in `TV Search`,
     * `TV Home` and `TV Library`. No `:core:theme` token states an alpha for it.
     */
    const val GroundAlpha: Float = 0.78f

    /**
     * The gap between two badges on one edge of a card, in whichever axis [BadgeGroup] lays them
     * out: `.badges { gap: 8px }` in `TV Search` 4a, halved.
     *
     * Mirrors [CloudStreamDimens.StackGap] (4dp) numerically, and equals
     * [HorizontalPadding] — a badge's own inset and the gap to the next one are the same 8
     * artboard px. **Should become `CloudStreamDimens.BadgeGap`.**
     */
    val Gap: Dp = 4.dp
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
