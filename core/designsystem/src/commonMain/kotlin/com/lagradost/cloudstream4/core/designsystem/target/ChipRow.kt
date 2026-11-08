package com.lagradost.cloudstream4.core.designsystem.target

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import com.lagradost.cloudstream4.core.theme.CloudStreamDimens

/**
 * The horizontal band of [Chip]s: the chip row that sits at [CloudStreamDimens.ChipRowTop] (88dp)
 * to [CloudStreamDimens.ChipRowBottom] (136dp) in the shared chassis, which is exactly one
 * [CloudStreamDimens.ChipFocusCell] (48dp) tall.
 *
 * ## Read this before "fixing" the widths
 *
 * **The children are not on the 4dp grid, and that is correct.** A chip's width comes from its
 * string: label text, plus [ChipDefaults.VisualHorizontalPadding] either side, plus an optional
 * count. So does the x of every chip after it. Geometry rule 6 is explicit that **intrinsic widths
 * are exempt** — the 4dp step governs every dimension a person writes and the origin and size of
 * every fixed-size element, but it does not govern text measurement. Nobody chose 137dp for the
 * second chip; the renderer measured it.
 *
 * What this row *does* fix is the two numbers a person wrote: the 48dp height, and the spacing
 * between chips. Forcing chips onto the step instead — a fixed chip width, or a grid of equal
 * cells — would mean either truncating labels or padding short ones into ambiguity, and would
 * still not be the design.
 *
 * ## Spacing
 *
 * **[CloudStreamDimens.MinFocusGap] (12dp) between two chips' visuals** (`BUG-65`, owner,
 * 2026-11-08: "the spacing between chips is too much", the gap to come from tokens only). It was
 * [CloudStreamDimens.ChipRowSpacing] (24dp). Each [Chip]'s cell now holds its own ring, a
 * [CloudStreamDimens.FocusRingBleed] (4dp) either side of the visual, so the cells sit
 * `MinFocusGap - 2 * FocusRingBleed` (4dp) apart: a focused chip's ring ends 8dp short of its
 * neighbour, and no two cells touch. There is no spacing parameter, because the design has one chip
 * row and one spacing.
 *
 * ## Where the row starts and ends
 *
 * At the first chip's visual and the last one's, as before: the end cells' outer
 * [CloudStreamDimens.FocusRingBleed] lies just outside the row, where the ring used to bleed, so a
 * caller's inset still lines the first chip up with the content beside it.
 *
 * ## This row does not scroll
 *
 * Deliberately. Every scrolling container clips at its viewport edge, and a clipped edge silently
 * cuts the [CloudStreamDimens.FocusRingBleed] (4dp) off the ring of the first and last focused
 * chip — the failure the focus-ring contract warns about. The chip row in the chassis spans
 * [CloudStreamDimens.ContentOriginX] to [CloudStreamDimens.ContentEndX] and is dimensioned to hold
 * its chips. A screen that genuinely has more chips than fit has an overflow problem this
 * component cannot silently solve; raise it.
 *
 * @param modifier applied to the row.
 * @param content the chips. `RowScope` is exposed so a caller can weight or align an individual
 *   child, but a chip should be left at its intrinsic width.
 */
@Composable
fun ChipRow(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    val arrangement = remember {
        Arrangement.spacedBy(CloudStreamDimens.MinFocusGap - CloudStreamDimens.FocusRingBleed * 2)
    }
    Row(
        modifier = modifier.height(CloudStreamDimens.ChipFocusCell).ringRoomOutside(),
        horizontalArrangement = arrangement,
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/**
 * Lays the cells out [CloudStreamDimens.FocusRingBleed] wider than the row on each side, and shifts them
 * back by as much, so the row's own box runs from the first chip's visual to the last one's.
 */
private fun Modifier.ringRoomOutside(): Modifier = layout { measurable, constraints ->
    val room = CloudStreamDimens.FocusRingBleed.roundToPx()
    val wider = if (constraints.hasBoundedWidth) constraints.maxWidth + room * 2 else constraints.maxWidth
    val placeable = measurable.measure(constraints.copy(minWidth = 0, maxWidth = wider))
    val width = (placeable.width - room * 2).coerceIn(constraints.minWidth, constraints.maxWidth)
    layout(width, placeable.height) { placeable.place(-room, 0) }
}
