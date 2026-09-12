package com.lagradost.cloudstream4.core.designsystem.card

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import com.lagradost.cloudstream4.core.theme.CardSize
import com.lagradost.cloudstream4.core.theme.CloudStreamCardSizes
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme
import androidx.compose.ui.tooling.preview.Preview

/**
 * The three 16:9 rungs of the media ladder, and the only sizes a [BackdropCard] can be.
 *
 * Note what is **not** here: 320x180 px is 160x90 dp, and 90 is off the 4dp step. It is banned,
 * and the typed ladder in `:core:theme` makes it unconstructible rather than merely discouraged.
 */
@Immutable
enum class BackdropRung(val size: CardSize) {
    /** 192x108 dp — a hero rail, continue-watching. */
    Large(CloudStreamCardSizes.LandscapeLarge),

    /** 128x72 dp — the default episode rung. */
    Medium(CloudStreamCardSizes.LandscapeMedium),

    /** 64x36 dp — a dense list, an inline chooser. */
    Small(CloudStreamCardSizes.LandscapeSmall),
}

/**
 * A 16:9 backdrop card — episodes, continue-watching, anything that wants a still rather than a
 * poster.
 *
 * Identical in behaviour to [PosterCard]; see the measurement contract on [MediaCard]. The one
 * thing worth restating here: **the focus lift is 4dp, not the 12px the design originally
 * carried.** 12px is not a multiple of 8 on the artboard, so every focused card landed off the
 * 4dp step and took its badges and title with it. The lift and the ring are both draw-only, so a
 * rail that measures [BackdropRung.size] measures the same value focused or not.
 *
 * At [BackdropRung.Small] a two-line title plus a meta line does not fit in 36dp. It is clamped
 * and ellipsised inside the card's clipped box rather than spilling — but a rail using the small
 * rung should usually pass `title = null` and label its cards elsewhere.
 *
 * @param rung which of the three 16:9 sizes this card is.
 * @param state focus, the revealed title and meta, and playback progress.
 * @param slots optional leading and trailing badges. Hoist the instance with `remember`.
 * @param image the artwork; it receives the card's full size as a minimum constraint.
 */
@Composable
fun BackdropCard(
    rung: BackdropRung,
    state: MediaCardState,
    modifier: Modifier = Modifier,
    slots: MediaCardSlots = MediaCardSlots.None,
    image: @Composable () -> Unit,
) {
    MediaCard(
        size = rung.size,
        state = state,
        modifier = modifier,
        slots = slots,
        image = image,
    )
}

private const val PREVIEW_TITLE = "Cold Open"
private const val PREVIEW_META = "E4 · 48m"
private const val PREVIEW_PROGRESS = 0.7f
private const val PREVIEW_LIVE_LABEL = "LIVE"
private const val PREVIEW_QUALITY_LABEL = "4K"

/**
 * A badge shaped the way the design system's real badges will be: a pill on a token surface with a
 * label-mono glyph. Preview-only — the badge components themselves are another group's work.
 */
@Composable
private fun PreviewBadge(label: String, onLive: Boolean) {
    val colors = CloudStreamTheme.colors
    val style = CloudStreamTheme.typography.labelMono
    BasicText(
        text = label,
        modifier = Modifier
            .clip(CloudStreamTheme.shapes.small)
            .background(if (onLive) colors.live else colors.raised)
            .padding(MediaCardDefaults.ContentPadding),
        style = remember(style, colors.ink) { style.copy(color = colors.ink) },
    )
}

@Preview
@Composable
private fun BackdropCardRungsPreview() {
    CardPreviewRow {
        BackdropRung.entries.forEach { rung ->
            BackdropCard(rung = rung, state = MediaCardState()) { MediaCardArtPlaceholder() }
        }
    }
}

@Preview
@Composable
private fun BackdropCardFocusedPreview() {
    CardPreviewRow {
        BackdropCard(
            rung = BackdropRung.Medium,
            state = MediaCardState(title = PREVIEW_TITLE, meta = PREVIEW_META),
        ) { MediaCardArtPlaceholder() }
        BackdropCard(
            rung = BackdropRung.Medium,
            state = MediaCardState(
                focused = true,
                title = PREVIEW_TITLE,
                meta = PREVIEW_META,
                progress = PREVIEW_PROGRESS,
            ),
        ) { MediaCardArtPlaceholder() }
    }
}

/** Hoisted to a top-level `val`: a slot holder built in a composition body allocates per frame. */
private val PreviewBadgeSlots = MediaCardSlots(
    leadingBadge = { PreviewBadge(label = PREVIEW_LIVE_LABEL, onLive = true) },
    trailingBadge = { PreviewBadge(label = PREVIEW_QUALITY_LABEL, onLive = false) },
)

@Preview
@Composable
private fun BackdropCardBadgesPreview() {
    CardPreviewRow {
        BackdropCard(
            rung = BackdropRung.Large,
            state = MediaCardState(focused = true, title = PREVIEW_TITLE),
            slots = PreviewBadgeSlots,
        ) { MediaCardArtPlaceholder() }
    }
}
