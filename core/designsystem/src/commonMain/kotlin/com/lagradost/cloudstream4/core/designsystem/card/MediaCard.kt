package com.lagradost.cloudstream4.core.designsystem.card

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream4.core.designsystem.focus.tvFocusLift
import com.lagradost.cloudstream4.core.designsystem.focus.tvFocusRing
import com.lagradost.cloudstream4.core.designsystem.primitive.BadgeDefaults
import com.lagradost.cloudstream4.core.theme.CardSize
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme

/**
 * The chassis every CloudStream 4 media card is built on. Not public: callers reach it through
 * `PosterCard` or `BackdropCard`, which are the two that can name a legal rung.
 *
 * ## The measurement contract — the reason this component exists
 *
 * **A card measures [CardSize] and nothing else, focused or not.** A rail lays its cards out at a
 * fixed pitch; if focusing a card changed what the rail measured, every card after it would move,
 * and on a D-pad that reads as the row sliding under your thumb. Three separate mechanisms are
 * therefore all draw-only:
 *
 * - the **focus ring** is a stroke drawn 4dp outside the card's edge (8dp of bleed), not a border
 *   and not padding. Drawn as a transparent border it pushed a card's badge 4dp off the step;
 *   see `tvFocusRing`.
 * - the **focus lift** is a `graphicsLayer` translation. **It is 4dp (8px on the artboard).** The
 *   original value was 12px, and 12px is not a multiple of 8: it took every focused card off the
 *   4dp step, which is why a lifted card's badges and title used to land on half-steps. A row that
 *   lifts leaves `CloudStreamDimens.LiftedRowClearance` (16dp) above it — 12dp of clearance plus
 *   the 4dp it rises.
 * - the **reveal** (title, meta, scrim), the badges and the progress rule are all children of the
 *   card's own clipped box, aligned rather than stacked, so none of them can grow it.
 *
 * ## Clipping
 *
 * The root box is deliberately **not** clipped — a clip there would eat the ring's 8dp bleed. The
 * artwork box inside it *is* clipped, which is what keeps a long title or an oversized badge from
 * escaping the card at the small rungs. The same reasoning applies one level up: a rail must not
 * clip its cards either, so a `LazyRow` needs `contentPadding` of at least
 * `CloudStreamDimens.FocusRingBleed` or the ring is silently cut at the viewport edge.
 *
 * @param size the rung, from the typed ladder in `:core:theme`. Overrides any size in [modifier];
 *   the ladder owns this, not the call site.
 * @param state focus, the revealed title and meta, and playback progress.
 * @param slots the optional leading and trailing badges. Hoist the instance with `remember`.
 * @param image the artwork. Receives the card's full size as a minimum constraint, so a plain
 *   `AsyncImage` or `Image` fills the card without the caller passing a modifier.
 */
@Composable
internal fun MediaCard(
    size: CardSize,
    state: MediaCardState,
    modifier: Modifier = Modifier,
    slots: MediaCardSlots = MediaCardSlots.None,
    image: @Composable () -> Unit,
) {
    val shape = CloudStreamTheme.shapes.none
    Box(
        modifier = modifier
            .size(size.asDpSize)
            .tvFocusLift(lifted = state.focused)
            .tvFocusRing(focused = state.focused, shape = shape),
    ) {
        MediaCardSurface(state = state, shape = shape, slots = slots, image = image)
    }
}

/**
 * The clipped artwork box and everything painted over it. Split out so [MediaCard] stays a
 * statement of the measurement contract rather than a stack of overlays.
 */
@Composable
private fun MediaCardSurface(
    state: MediaCardState,
    shape: Shape,
    slots: MediaCardSlots,
    image: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .clip(shape)
            .background(CloudStreamTheme.colors.raised),
    ) {
        Box(Modifier.fillMaxSize(), propagateMinConstraints = true) { image() }
        if (state.focused && state.title != null) {
            MediaCardReveal(
                title = state.title,
                meta = state.meta,
                contentPadding = rememberRevealPadding(hasProgressRule = state.progress != null),
                modifier = Modifier.align(Alignment.BottomStart),
            )
        }
        MediaCardBadges(slots)
        if (state.progress != null) {
            MediaCardProgressRule(
                progress = state.progress,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

/**
 * Badges live in the top corners, inset by [MediaCardDefaults.ContentPadding]. They are inside the
 * clipped box, so a badge that is too big for its rung is cropped rather than allowed to overlap
 * the focus ring.
 *
 * ## The card is what caps a badge's width (`BUG-62`)
 *
 * The two slots are **one row across the card's top**, not two independently aligned corners, and
 * that is what gives each badge a width it can ellipsise against. The leading badge is weighted
 * with `fill = false`, so `Row` measures the trailing badge at the card's full box first, hands
 * the leading badge what is left, and the leading badge takes its own intrinsic width inside
 * that. The two therefore cannot overlap however long a translation is: `asian_drama_singular` is
 * `ASIAN DRAMA` in English and `DARAAMADA EESHIYAANKA` — 147dp — in Somali, on a 96dp card whose
 * badge box is 88dp.
 *
 * Aligned corners cannot express that. Each corner box was measured against the whole card, so a
 * long leading badge ran under the trailing one and both were cut by the card's clip with nothing
 * to say they had been. `Arrangement.SpaceBetween` keeps the trailing badge on the trailing edge,
 * which is where `.tr` draws it in `TV Library`, and [BadgeDefaults.Gap] after the leading badge
 * keeps the two apart when the leading one fills what it was given.
 *
 * Nothing about this is measured by the card: the row is a child of the card's own clipped box, so
 * like every other overlay it cannot change what a rail measures.
 */
@Composable
private fun BoxScope.MediaCardBadges(slots: MediaCardSlots) {
    val leading = slots.leadingBadge
    val trailing = slots.trailingBadge
    if (leading == null && trailing == null) return
    Row(
        modifier = Modifier
            .align(Alignment.TopStart)
            .fillMaxWidth()
            .padding(MediaCardDefaults.ContentPadding),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Box(
            modifier = Modifier
                .weight(WEIGHT_REMAINING, fill = false)
                .padding(end = if (trailing == null) NO_GAP else BadgeDefaults.Gap),
            content = { leading?.invoke() },
        )
        trailing?.invoke()
    }
}

/** The leading badge takes what the trailing one leaves, and no more. */
private const val WEIGHT_REMAINING = 1f

/** A card with one badge has nothing to keep it apart from. */
private val NO_GAP: Dp = 0.dp

/**
 * Inset for the revealed text stack inside its scrim block. The top is the scrim's lead above the
 * text. The bottom gains the rule's thickness when a progress rule is present, so the title clears
 * it: 4 + 4 = 8dp, still on the step.
 */
@Composable
private fun rememberRevealPadding(hasProgressRule: Boolean): PaddingValues =
    remember(hasProgressRule) {
        val inset = MediaCardDefaults.ContentPadding
        PaddingValues(
            start = inset,
            top = MediaCardDefaults.RevealScrimLead,
            end = inset,
            bottom = if (hasProgressRule) inset + MediaCardDefaults.ProgressRuleHeight else inset,
        )
    }
