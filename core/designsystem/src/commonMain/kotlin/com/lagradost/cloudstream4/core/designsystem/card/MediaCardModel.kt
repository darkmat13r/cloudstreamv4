package com.lagradost.cloudstream4.core.designsystem.card

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import com.lagradost.cloudstream4.core.designsystem.primitive.BadgeGroup

/**
 * Everything a media card renders that is *data* rather than a slot.
 *
 * Hoisted, as every state on a CloudStream card is: the card never observes focus, never fetches
 * a title and never derives progress. A rail decides what is focused and what each card says, and
 * hands the card a value.
 *
 * **[title] and [meta] are a reveal, not layout.** They are drawn as an overlay on top of the
 * artwork while [focused], inside the card's own box. Nothing about them changes what the card
 * measures, so a rail laid out at a fixed pitch sees the same measurement focused or not.
 *
 * @param focused whether this card is the focused one. Drives the ring, the lift and the reveal.
 * @param title the card's title. Clamped to [MediaCardDefaults.TITLE_MAX_LINES] lines; `null`
 *   means the card reveals nothing and stays pure artwork even when focused.
 * @param meta an optional second line, rendered in the mono style — a year, a runtime, `S2 E4`.
 *   Ignored when [title] is `null`, because a meta line alone has nothing to hang under.
 * @param progress playback progress in `0f..1f`, or `null` for a card with no playback state.
 *   Drawn as the progress rule at the bottom edge in `colors.live`, which is the only role that
 *   colour has.
 */
@Immutable
data class MediaCardState(
    val focused: Boolean = false,
    val title: String? = null,
    val meta: String? = null,
    val progress: Float? = null,
) {
    init {
        require(progress == null || progress in PROGRESS_EMPTY..PROGRESS_FULL) {
            "progress must be null or within $PROGRESS_EMPTY..$PROGRESS_FULL, was $progress"
        }
    }

    companion object {
        /** Nothing watched. */
        const val PROGRESS_EMPTY: Float = 0f

        /** Finished. */
        const val PROGRESS_FULL: Float = 1f
    }
}

/**
 * The optional decorations a media card can carry, as composable slots.
 *
 * Badges are slots rather than data because what a badge *is* differs per surface — a `LIVE`
 * pill, a resolution label, a source glyph, a checkmark — and the design system should not
 * enumerate them on behalf of every future rail. Both slots are drawn inside the card's clipped
 * artwork box, so a badge can never escape the card and can never push the focus ring off the
 * step.
 *
 * A badge that means "playback state" is the one place `colors.live` is allowed on a card.
 *
 * **Two badges on one edge go in a [BadgeGroup], not a `Row`.** The card gives each slot a width
 * capped by its own, and a `Row` spends all of it on its first child: in Catalan or Tamil the
 * second badge of a pair is measured at zero and vanishes. The group stacks the pair instead. See
 * `BUG-62`.
 *
 * **Hoist instances with `remember`.** Constructing a `MediaCardSlots` in a composition body
 * allocates on every recomposition and defeats skipping; build it once per rail, or use [None].
 *
 * @param leadingBadge drawn at the card's top-leading corner.
 * @param trailingBadge drawn at the card's top-trailing corner.
 */
@Stable
class MediaCardSlots(
    val leadingBadge: (@Composable () -> Unit)? = null,
    val trailingBadge: (@Composable () -> Unit)? = null,
) {
    companion object {
        /** No badges. The default for every card, and free to reference anywhere. */
        val None: MediaCardSlots = MediaCardSlots()
    }
}
