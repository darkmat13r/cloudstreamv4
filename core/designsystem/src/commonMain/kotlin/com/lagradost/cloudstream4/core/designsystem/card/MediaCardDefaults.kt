package com.lagradost.cloudstream4.core.designsystem.card

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream4.core.theme.CloudStreamDimens

/**
 * Values the media cards need that `:core:theme` does not yet name.
 *
 * Every `Dp` here is a multiple of 4 (`.claude/rules/dimension-scale.md`). Each one
 * carries the token it mirrors, or — where it mirrors nothing — what it should become when it is
 * promoted into `CloudStreamDimens`.
 *
 * This object exists because a plain `object` has no composition to read tokens from, and because
 * another agent owns `:core:theme`. Everything here is a promotion candidate, not a second scale.
 */
@Immutable
object MediaCardDefaults {

    /**
     * Inset between the card's edge and anything drawn over the artwork — badges, the revealed
     * title, the meta line.
     *
     * 8px on the boards, halved: `.badges { left: 8px; top: 8px }` and `.revt`'s side and bottom
     * padding in `TV Search` and `TV Home`, and `.tl`/`.tr` in `TV Library`. Mirrors
     * `CloudStreamDimens.StackGap` (4dp) in value, but not in meaning. Promote as
     * `CardContentPadding`.
     */
    val ContentPadding: Dp = 4.dp

    /**
     * Thickness of the progress rule along the card's bottom edge.
     *
     * Mirrors `CloudStreamDimens.ListRowMarkerWidth` (4dp) — the same "this is a state marker, not
     * a surface" weight, turned on its side. Promote as `ProgressRuleThickness`.
     */
    val ProgressRuleHeight: Dp = 4.dp

    /**
     * How far the reveal scrim reaches above the revealed text: `.revt`'s `padding-top: 32px` in
     * `TV Search` and `TV Home`, halved. Mirrors no token. Promote as `CardRevealLead`.
     *
     * **The scrim is a block behind the text, not a fraction of the card.** The boards size it to
     * the text it serves: lead + title + inset. Measured in Chromium with the fonts loaded, that is
     * 104 of 240px (43%) on `TV Search` 4a, whose title takes two lines, and 72 of 240px (30%) on
     * `TV Home` 3d, whose title takes one: the same rule, one line apart. The old fixed
     * `SCRIM_START_FRACTION` (0.45) covered 55% of the card whatever the title was.
     */
    val RevealScrimLead: Dp = 16.dp

    /**
     * The share of the scrim block, from its bottom, that is solid at [SCRIM_END_ALPHA]; above it
     * the scrim fades to nothing at the block's top. `.revt`'s
     * `linear-gradient(0deg, rgba(0,0,0,0.92) 30%, rgba(0,0,0,0))`. A fraction, not a dimension.
     */
    const val SCRIM_SOLID_FRACTION: Float = 0.30f

    /**
     * Opacity of `colors.bg` across the solid part of the reveal scrim: the boards' `0.92`. Not
     * fully opaque, so the artwork still shows through behind the title.
     */
    const val SCRIM_END_ALPHA: Float = 0.92f

    /**
     * The "see all" card's ground: `colors.surface` at this opacity, `.seeall`'s
     * `rgba(10,16,22,0.6)`, which is `#060A0D` over `bg` (read from the board's pixels).
     */
    const val SEE_ALL_GROUND_ALPHA: Float = 0.6f

    /**
     * The "see all" card's dashed outline, as multiples of its [CloudStreamDimens.Hairline] stroke:
     * Chromium draws `.seeall`'s `outline: 1px dashed` as 3px on and 2px off (read from the
     * pixels). A stroke pattern, so the 4dp step does not apply to it.
     */
    const val SEE_ALL_DASH_ON: Float = 3f

    /** The gap between dashes; see [SEE_ALL_DASH_ON]. */
    const val SEE_ALL_DASH_OFF: Float = 2f

    /** The revealed title is clamped to two lines; a third line is a rail-layout problem. */
    const val TITLE_MAX_LINES: Int = 2

    /** The meta line is a single line, always. */
    const val META_MAX_LINES: Int = 1
}
