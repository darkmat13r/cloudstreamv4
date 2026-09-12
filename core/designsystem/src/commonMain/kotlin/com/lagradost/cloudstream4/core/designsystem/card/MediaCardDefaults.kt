package com.lagradost.cloudstream4.core.designsystem.card

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Values the media cards need that `:core:theme` does not yet name.
 *
 * Every `Dp` here is a multiple of 4 (`.claude/rules/dimension-scale.md`) except
 * [SeeAllGlyphStroke], which is a stroke *weight* and therefore exempt in its own axis. Each one
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
     * Mirrors `CloudStreamDimens.ListRowPadding` (8dp) in value, but not in meaning: that one is a
     * list row's inner padding. Promote as `CardContentPadding`.
     */
    val ContentPadding: Dp = 8.dp

    /**
     * Thickness of the progress rule along the card's bottom edge.
     *
     * Mirrors `CloudStreamDimens.ListRowMarkerWidth` (4dp) — the same "this is a state marker, not
     * a surface" weight, turned on its side. Promote as `ProgressRuleThickness`.
     */
    val ProgressRuleHeight: Dp = 4.dp

    /**
     * Side of the chevron glyph on the "see all" card.
     *
     * Mirrors `CloudStreamDimens.ListRowDiscSize` (24dp), which is the design's other
     * "glyph that is not a chip glyph" size. Promote as `CardGlyphSize`.
     */
    val SeeAllGlyphSize: Dp = 24.dp

    /**
     * Stroke weight of the chevron glyph. A stroke weight, so exempt from the 4dp step; it is the
     * design's medium hairline, two device-independent pixels. Promote as `StrokeMedium` beside
     * `CloudStreamDimens.Hairline`, which is currently the only weight the theme names.
     */
    val SeeAllGlyphStroke: Dp = 2.dp

    /**
     * Where the reveal scrim starts, as a fraction of the card's height.
     *
     * Proportional rather than a `Dp` on purpose: the media ladder spans 36dp to 144dp of height,
     * and a fixed scrim height that reads correctly on a 192x108 backdrop swallows a 64x36 one
     * whole. A fraction is not a layout dimension, so the 4dp step does not apply to it.
     *
     * **The artboards do not state this value.** It is the one number on this page the design did
     * not give us; it wants a designer's eye before it is promoted.
     */
    const val SCRIM_START_FRACTION: Float = 0.45f

    /**
     * Opacity of `colors.bg` at the very bottom of the reveal scrim. Not fully opaque, so the
     * artwork still shows through behind the title rather than the card growing a black shelf.
     * Same caveat as [SCRIM_START_FRACTION]: invented, not measured.
     */
    const val SCRIM_END_ALPHA: Float = 0.92f

    /** The revealed title is clamped to two lines; a third line is a rail-layout problem. */
    const val TITLE_MAX_LINES: Int = 2

    /** The meta line is a single line, always. */
    const val META_MAX_LINES: Int = 1
}
