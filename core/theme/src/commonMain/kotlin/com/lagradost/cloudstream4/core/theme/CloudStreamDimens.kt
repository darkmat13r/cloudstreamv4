package com.lagradost.cloudstream4.core.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Every dimension the CloudStream 4 design system names, in dp.
 *
 * All of them come off the 1920x1080 TV artboard, where `artboard px / 2 = dp` at xhdpi. Every
 * artboard value is a multiple of 8px, so every token here is a multiple of 4dp — the step
 * `.claude/rules/dimension-scale.md` requires. **Convert by halving; never transcribe an artboard
 * px as a dp.**
 *
 * The one exception is [Hairline], which is a stroke *weight*, not a layout dimension.
 *
 * This is an `object` rather than a constructor-injected class on purpose: these are not a
 * "scale" a caller can substitute, they are the measurements of a specific design. A second set
 * of numbers is a second design system.
 */
@Immutable
object CloudStreamDimens {

    // ---- Hairlines -------------------------------------------------------------------------

    /**
     * The one stroke weight, and the one value here that is not on the 4dp step.
     *
     * **A hairline must not consume layout.** A 1dp border shifts the padding box by 1 and puts
     * everything inside it off the step — a 560px artboard panel became 561, starting at 1359.
     * Draw it in a `DrawScope` (`Modifier.drawBehind`, or an inset stroke), never by adding
     * padding or a `Modifier.border` that the content box then has to absorb. Whatever mechanism
     * you pick, the content box must be unchanged by the hairline's presence.
     */
    val Hairline: Dp = 1.dp

    // ---- Focus -----------------------------------------------------------------------------

    /**
     * **A focus ring is not a border.** It is a [FocusRingStroke] stroke sitting
     * [FocusRingOffset] outside the target, so it occupies [FocusRingBleed] *outside* the
     * target's box and must not change the target's size. Drawn as a transparent border it
     * pushed a card's badge 4px off the step.
     */
    val FocusRingStroke: Dp = 2.dp

    /** The gap between the target's edge and the inner edge of the ring. */
    val FocusRingOffset: Dp = 2.dp

    /**
     * Total space the ring claims outside the target: [FocusRingOffset] + [FocusRingStroke].
     *
     * 4dp, which is the 8 artboard px that [FocusBandInset] reserves. These three were the only
     * tokens in this file not halved when the artboard was converted — at 4/4/8dp the ring was
     * twice the drawn width and bled 16px into a margin that reserves 8, so a focusable at the
     * band edge would have lost its ring to the overscan.
     */
    val FocusRingBleed: Dp = 4.dp

    /**
     * **The focus lift is 4dp (8px), not 6dp.** A 12px lift takes every focused card off the
     * step.
     */
    val FocusLift: Dp = 4.dp

    /**
     * Space a row that lifts must leave above itself: 24px of clearance plus the 8px it rises,
     * which is 32px — 16dp.
     */
    val LiftedRowClearance: Dp = 16.dp

    /** Minimum gap between two focusables (24px). */
    val MinFocusGap: Dp = 12.dp

    /**
     * The TV focus band. On a 1920x1080 artboard every focusable's own box lies inside
     * x 56..1864 and y 56..1024. The ring's 8px bleed lives in the 48..56 reserve, which is
     * exactly why the margin is 56px and not 48px — this inset already pays for
     * [FocusRingBleed].
     */
    val FocusBandInset: Dp = 28.dp

    // ---- Target classes --------------------------------------------------------------------

    /** Icon-only target: a 48dp square focus cell with the visual inside it. */
    val IconTargetCell: Dp = 48.dp

    /** Text button: 32dp tall, ring on the button itself. */
    val TextButtonHeight: Dp = 32.dp

    /** Text button minimum width. */
    val TextButtonMinWidth: Dp = 120.dp

    /**
     * Chip *visual* height. **Centring an odd size in an even cell lands off the step** — a 40px
     * chip centred in a 96px cell lands at 204. 48px (24dp) centres onto the step, and reads
     * better at three metres, which is where the constraint happened to push.
     */
    val ChipVisualHeight: Dp = 24.dp

    /** The chip's focus cell. The ring goes on the **cell**, not on the visual. */
    val ChipFocusCell: Dp = 48.dp

    /** A glyph inside a chip: 32px, so it too centres onto the step. */
    val ChipGlyphSize: Dp = 16.dp

    /** Spacing between chips in a chip row. Chip widths themselves are intrinsic. */
    val ChipRowSpacing: Dp = 24.dp

    /** List row: 48dp tall, ring on the row. */
    val ListRowHeight: Dp = 48.dp

    /** Inner padding of a list row. */
    val ListRowPadding: Dp = 8.dp

    /** The selection-marker cell at the leading edge of a list row. */
    val ListRowMarkerWidth: Dp = 4.dp

    /** The optional leading disc in a list row. */
    val ListRowDiscSize: Dp = 24.dp

    // ---- Text stacks -----------------------------------------------------------------------

    /**
     * **A text stack must total a multiple of 16px to centre in a 96px row.** Title 32 + gap 4 +
     * subtitle 24 is 60px, which centres at +18 — off the step. The gap is 8px, so the stack is
     * 64px and the offset 16px: in dp, [StackTitleLineBox] + [StackGap] + [StackSubtitleLineBox]
     * = 32dp, centred in a 48dp row at +8dp.
     */
    val StackTitleLineBox: Dp = 16.dp

    /** See [StackTitleLineBox]. */
    val StackGap: Dp = 4.dp

    /** See [StackTitleLineBox]. */
    val StackSubtitleLineBox: Dp = 12.dp

    /**
     * A single line centred alone in a [ListRowHeight] row needs a 48px line box, not 40, to land
     * at +24px.
     */
    val StackSingleLineBox: Dp = 24.dp

    // ---- The shared chassis ----------------------------------------------------------------

    /** Nav rail width. */
    val NavRailWidth: Dp = 76.dp

    /** Nav rail icon cell — an [IconTargetCell] by another name. */
    val NavRailIconCell: Dp = 48.dp

    /** Left edge of the nav rail's icon cells. Equals [FocusBandInset]. */
    val NavRailIconInset: Dp = 28.dp

    /** Top of the chrome row. */
    val ChromeRowTop: Dp = 28.dp

    /** Bottom of the chrome row; its height is [ChromeRowHeight]. */
    val ChromeRowBottom: Dp = 76.dp

    /** Height of the chrome row. */
    val ChromeRowHeight: Dp = 48.dp

    /** x origin of content. Chrome-row content spans [ContentOriginX]..[ContentEndX]. */
    val ContentOriginX: Dp = 100.dp

    /** x end of content. */
    val ContentEndX: Dp = 932.dp

    /** Top of the chip row. */
    val ChipRowTop: Dp = 88.dp

    /** Bottom of the chip row; its height is [ChipFocusCell]. */
    val ChipRowBottom: Dp = 136.dp

    /** Side panel width, measured from the right edge. */
    val SidePanelWidth: Dp = 280.dp

    /** Content inset inside the side panel. */
    val SidePanelContentInset: Dp = 16.dp

    /** y of the side panel's first row. Rows sit at `SidePanelFirstRowY + i * SidePanelRowPitch`. */
    val SidePanelFirstRowY: Dp = 128.dp

    /** Vertical pitch between side panel rows. */
    val SidePanelRowPitch: Dp = 60.dp
}
