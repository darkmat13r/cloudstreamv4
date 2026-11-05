package com.lagradost.cloudstream4.core.designsystem.target

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
 * [CloudStreamDimens.ChipRowSpacing] (24dp), the token named for this job. Note that this is
 * double [CloudStreamDimens.MinFocusGap] (12dp), which is the *minimum* legal gap between any two
 * focusables; chips sit at twice the minimum. There is no spacing parameter, because the design
 * has one chip row and one spacing.
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
    val arrangement = remember { Arrangement.spacedBy(CloudStreamDimens.ChipRowSpacing) }
    Row(
        modifier = modifier.height(CloudStreamDimens.ChipFocusCell),
        horizontalArrangement = arrangement,
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}
