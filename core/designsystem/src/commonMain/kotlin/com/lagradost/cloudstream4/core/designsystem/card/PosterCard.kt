package com.lagradost.cloudstream4.core.designsystem.card

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.lagradost.cloudstream4.core.theme.CardSize
import com.lagradost.cloudstream4.core.theme.CloudStreamCardSizes
import androidx.compose.ui.tooling.preview.Preview

/**
 * The three 2:3 rungs of the media ladder, and the only sizes a [PosterCard] can be.
 *
 * `CardSize` already makes an off-ladder size unconstructible; this enum goes one step further and
 * makes an off-*aspect* size unnameable, so a poster cannot accidentally be handed a 16:9 rung.
 * The artboard px are on `CloudStreamCardSizes`.
 */
@Immutable
enum class PosterRung(val size: CardSize) {
    /** 96x144 dp — a browse grid, a "because you watched" rail. */
    Large(CloudStreamCardSizes.PortraitLarge),

    /** 80x120 dp — the default rail rung. */
    Medium(CloudStreamCardSizes.PortraitMedium),

    /** 64x96 dp — a dense rail, a side panel. */
    Small(CloudStreamCardSizes.PortraitSmall),
}

/**
 * A 2:3 poster card.
 *
 * Everything that matters about its behaviour is the measurement contract on [MediaCard]: the
 * card measures [PosterRung.size] whether or not it is focused, because the ring is drawn outside
 * its box, the lift is a `graphicsLayer` translation of 4dp — **not the 12px the design started
 * with, which took every focused card off the 4dp step** — and the revealed title is an overlay
 * inside the card rather than a row beneath it.
 *
 * A rail that lifts must leave `CloudStreamDimens.LiftedRowClearance` (16dp) above the row, and
 * must not clip: give a `LazyRow` at least `CloudStreamDimens.FocusRingBleed` of content padding
 * or the ring is cut at the viewport edge.
 *
 * ```kotlin
 * PosterCard(
 *     rung = PosterRung.Medium,
 *     state = MediaCardState(focused = isFocused, title = title, meta = "S2 E4", progress = 0.4f),
 *     modifier = Modifier.onFocusChanged { isFocused = it.isFocused }.focusable(),
 * ) { AsyncImage(model = posterUrl, contentDescription = null) }
 * ```
 *
 * @param rung which of the three 2:3 sizes this card is.
 * @param state focus, the revealed title and meta, and playback progress.
 * @param slots optional leading and trailing badges. Hoist the instance with `remember`.
 * @param image the artwork; it receives the card's full size as a minimum constraint.
 */
@Composable
fun PosterCard(
    rung: PosterRung,
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

private const val PREVIEW_TITLE = "The Man in the High Castle"
private const val PREVIEW_META = "S2 E4 · 48m"
private const val PREVIEW_PROGRESS = 0.4f

@Preview
@Composable
private fun PosterCardRungsPreview() {
    CardPreviewRow {
        PosterRung.entries.forEach { rung ->
            PosterCard(rung = rung, state = MediaCardState()) { MediaCardArtPlaceholder() }
        }
    }
}

@Preview
@Composable
private fun PosterCardFocusedPreview() {
    CardPreviewRow {
        PosterCard(
            rung = PosterRung.Medium,
            state = MediaCardState(title = PREVIEW_TITLE, meta = PREVIEW_META),
        ) { MediaCardArtPlaceholder() }
        PosterCard(
            rung = PosterRung.Medium,
            state = MediaCardState(focused = true, title = PREVIEW_TITLE, meta = PREVIEW_META),
        ) { MediaCardArtPlaceholder() }
    }
}

@Preview
@Composable
private fun PosterCardProgressPreview() {
    CardPreviewRow {
        PosterCard(
            rung = PosterRung.Large,
            state = MediaCardState(
                focused = true,
                title = PREVIEW_TITLE,
                meta = PREVIEW_META,
                progress = PREVIEW_PROGRESS,
            ),
        ) { MediaCardArtPlaceholder() }
        PosterCard(
            rung = PosterRung.Small,
            state = MediaCardState(title = PREVIEW_TITLE, progress = PREVIEW_PROGRESS),
        ) { MediaCardArtPlaceholder() }
    }
}
