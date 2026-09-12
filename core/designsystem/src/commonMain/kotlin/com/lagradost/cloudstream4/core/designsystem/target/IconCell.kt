package com.lagradost.cloudstream4.core.designsystem.target

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream4.core.designsystem.focus.tvFocusRing
import com.lagradost.cloudstream4.core.theme.CloudStreamDimens
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme

/**
 * The **icon-only** target class: a [CloudStreamDimens.IconTargetCell] (48dp) square focus cell
 * with a [IconCellDefaults.GlyphSize] (16dp) glyph centred inside it.
 *
 * ## Why the cell and the glyph are different sizes
 *
 * The cell is the *target*, not the picture. 48dp is what a D-pad has to be able to land on and
 * what the ring is drawn around; 16dp is how big the picture actually is. Sizing the cell to the
 * glyph would give a 16dp focus target and a ring 16dp across, and centring the resulting odd box
 * in its slot would land it off the 4dp step — the failure geometry rule 4 describes for chips,
 * in its icon form. The cell is the fixed thing; the glyph floats in the middle of it.
 *
 * This cell carries **no background**. Unfocused it is invisible: the glyph is the only thing on
 * screen. Focus is communicated entirely by the ring, which is why the cell must not sit inside a
 * clipping ancestor (see [tvFocusRing]).
 *
 * ## Focus
 *
 * The cell owns its own focus state and hands it to the [glyph] slot, so the picture can respond
 * — typically `CloudStreamTheme.colors.muted` when unfocused and `CloudStreamTheme.colors.ink`
 * when focused. There is no `interactionSource` parameter: a parent that needs to know when this
 * cell takes focus passes `Modifier.onFocusChanged { … }` through [modifier], which sits ahead of
 * this cell's own focus target in the chain and therefore sees every change.
 *
 * The cell does **not** lift. `tvFocusLift` belongs to media cards, whose rows budget
 * [CloudStreamDimens.LiftedRowClearance] for the rise; a chrome or nav-rail icon has no such
 * budget and lifting one would push it off the step.
 *
 * ## Placement
 *
 * In the shared chassis these are the nav rail's icons — [CloudStreamDimens.NavRailIconCell] is
 * this same 48dp cell, with its left edge at [CloudStreamDimens.NavRailIconInset] — and the
 * chrome row's actions. Leave at least [CloudStreamDimens.MinFocusGap] (12dp) between two of them.
 *
 * @param onClick invoked on D-pad centre, Enter, or a tap.
 * @param modifier applied to the cell itself, which is the focusable and the ring's target.
 * @param enabled when false the cell is neither focusable nor clickable. The glyph is still drawn;
 *   colouring a disabled glyph `CloudStreamTheme.colors.muted` is the caller's job, because only
 *   the caller knows whether "disabled" or "inactive" is the thing being shown.
 * @param glyph the picture, given this cell's current focus state. Size it to
 *   [IconCellDefaults.GlyphSize]; the slot is already that size and centred.
 */
@Composable
fun IconCell(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    glyph: @Composable (focused: Boolean) -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    Box(
        modifier = modifier
            .size(CloudStreamDimens.IconTargetCell)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .tvFocusRing(focused = focused, shape = CloudStreamTheme.shapes.medium),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier.size(IconCellDefaults.GlyphSize),
            contentAlignment = Alignment.Center,
        ) {
            glyph(focused)
        }
    }
}

/** Values [IconCell] needs that `:core:theme` does not name yet. */
@Immutable
object IconCellDefaults {

    /**
     * The glyph inside the cell: 32px on the 1920x1080 artboard, halved.
     *
     * Mirrors `CloudStreamDimens.ChipGlyphSize`, which is the same 16dp measured on a chip's
     * glyph rather than an icon cell's. **Promote this to `CloudStreamDimens.IconGlyphSize`** —
     * the two are the same number today but they are different measurements, and a design change
     * to one should not silently move the other.
     */
    val GlyphSize: Dp = 16.dp
}
