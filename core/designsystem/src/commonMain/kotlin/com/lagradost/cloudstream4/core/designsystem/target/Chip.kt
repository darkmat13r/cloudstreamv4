package com.lagradost.cloudstream4.core.designsystem.target

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream4.core.designsystem.focus.tvFocusRing
import com.lagradost.cloudstream4.core.theme.CloudStreamDimens
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme

/**
 * The **chip** target class: a [CloudStreamDimens.ChipVisualHeight] (24dp) visual centred in a
 * [CloudStreamDimens.ChipFocusCell] (48dp) focus cell.
 *
 * ## The ring goes on the cell, not on the visual
 *
 * This is the whole reason the chip has two boxes instead of one, and it is the rule that costs
 * the most to rediscover. **Centring an odd size in an even cell lands off the step:** a 40px
 * chip centred in a 96px cell starts at y 204, which is not a multiple of 8 and therefore not a
 * multiple of 4dp. 48px (24dp) in 96px (48dp) centres at 24px — on the step. So the visual is
 * 24dp, the cell is 48dp, and the ring is drawn around the *cell*: a focused chip's ring is a
 * 48dp-tall stadium sitting 4dp outside the cell, not a tight outline around the 24dp pill.
 * Ringing the visual instead would put the ring's geometry on a box whose position the layout is
 * not allowed to depend on.
 *
 * The cell's **width is intrinsic** — it is the visual's width, which is the label's width plus
 * [ChipDefaults.VisualHorizontalPadding] on each side, plus the count if there is one. Geometry
 * rule 6 exempts text measurement from the step; see [ChipRow], which is where the next reader is
 * most likely to try to "fix" that.
 *
 * ## Selection and focus are different things
 *
 * `selected` is application state, hoisted: the chip renders it and never changes it. Focus is
 * transient and owned here. A selected chip is an ink fill with a bg label — the same inversion
 * the primary button uses — and an unselected one is a raised fill with an ink2 label. Neither
 * state uses `CloudStreamTheme.colors.live`: that role is playback state only.
 *
 * There is no `enabled` parameter. A chip in this design is a filter, and the design has no
 * disabled filter — a filter that cannot be applied is not shown. If one appears, that is a
 * design decision to raise rather than a parameter to add.
 *
 * @param label already-localised chip text.
 * @param selected whether this chip is the applied one. Hoisted; the caller owns it.
 * @param onClick invoked on D-pad centre, Enter, or a tap. Emits the intent, nothing more.
 * @param modifier applied to the focus *cell*, which is the focusable and the ring's target.
 * @param count optional trailing count, rendered in `CloudStreamTheme.typography.labelMono`. It is
 *   a `String` rather than an `Int` because formatting a number is locale work — thousands
 *   separators, "999+" — and a design system module has no locale. Format it at the call site.
 */
@Composable
fun Chip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    count: String? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val shape = CloudStreamTheme.shapes.pill
    Box(
        modifier = modifier
            .height(CloudStreamDimens.ChipFocusCell)
            .selectable(
                selected = selected,
                interactionSource = interactionSource,
                indication = null,
                role = Role.Tab,
                onClick = onClick,
            )
            .tvFocusRing(focused = focused, shape = shape),
        contentAlignment = Alignment.Center,
    ) {
        ChipVisual(label = label, selected = selected, count = count, shape = shape)
    }
}

@Composable
private fun ChipVisual(
    label: String,
    selected: Boolean,
    count: String?,
    shape: Shape,
    modifier: Modifier = Modifier,
) {
    val colors = CloudStreamTheme.colors
    val typography = CloudStreamTheme.typography
    val arrangement = remember { Arrangement.spacedBy(ChipDefaults.CountGap) }
    Row(
        modifier = modifier
            .height(CloudStreamDimens.ChipVisualHeight)
            .background(color = if (selected) colors.ink else colors.raised, shape = shape)
            .padding(horizontal = ChipDefaults.VisualHorizontalPadding),
        horizontalArrangement = arrangement,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TargetLabel(
            text = label,
            style = typography.label,
            color = if (selected) colors.bg else colors.ink2,
        )
        if (count != null) {
            TargetLabel(
                text = count,
                style = typography.labelMono,
                color = if (selected) colors.bg else colors.muted,
            )
        }
    }
}

/** Values [Chip] needs that `:core:theme` does not name yet. */
@Immutable
object ChipDefaults {

    /**
     * Padding between the chip visual's edge and its label: 24px on the artboard, halved.
     *
     * Mirrors `CloudStreamDimens.MinFocusGap` (12dp) numerically only — that token is the gap
     * *between* two focusables, not padding inside one. **Promote this to
     * `CloudStreamDimens.ChipVisualHorizontalPadding`.**
     */
    val VisualHorizontalPadding: Dp = 12.dp

    /**
     * Gap between the chip's label and its trailing count: 16px on the artboard, halved.
     *
     * Mirrors `CloudStreamDimens.ListRowPadding` (8dp) numerically only. **Promote this to
     * `CloudStreamDimens.ChipCountGap`.**
     */
    val CountGap: Dp = 8.dp
}
