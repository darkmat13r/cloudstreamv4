package com.lagradost.cloudstream4.core.designsystem.primitive

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream4.core.theme.CloudStreamDimens
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme

/**
 * The leading marker cell of a list row: a [CloudStreamDimens.ListRowMarkerWidth] (4dp) cell
 * carrying a [SelectionMarkerDefaults.RuleWidth] (4dp) by [SelectionMarkerDefaults.RuleHeight]
 * (24dp) rounded mark when the row is selected.
 *
 * ## Why the cell is 4dp, and why it is always there
 *
 * The cell exists at that width **precisely so the disc after it lands on the step**. A list row
 * opens with [CloudStreamDimens.ListRowPadding] (8dp / 16px), then this cell (4dp / 8px), then an
 * optional [CloudStreamDimens.ListRowDiscSize] (24dp / 48px) disc. 8 + 4 = 12dp, on the 4dp step.
 * A 2dp cell would put the disc at 10dp and a 6dp cell at 14dp — both off it, and every element
 * after the disc inherits the error.
 *
 * It follows that **the cell is occupied whether or not the row is selected.** Selection changes
 * what is painted inside it, never its width; a marker that appeared on selection would shove the
 * rest of the row 4dp sideways on every focus change. If you find yourself wrapping this in an
 * `if (selected)`, that is the bug.
 *
 * ## The mark fills the cell, and is 24dp tall
 *
 * Every board draws it the same way: `.lmark { width: 8px; height: 48px; border-radius: 4px }`
 * (`TV Search` 4d and the other list-row boards), and `Design System v2` says *"The marker cell is 8
 * wide, not 4"*. Halved, that is a 4dp by 24dp mark with fully rounded ends, centred in the row. Until
 * `DS-15` this drew a 2dp square-ended rule the full 32dp of the row's content box, which is what the
 * design review measured off the emulator.
 *
 * Both numbers are on the 4dp step, so the mark needs no stroke exemption. The cell keeps the row's
 * height and centres the mark in it, so it belongs in a row of fixed height — a
 * [CloudStreamDimens.ListRowHeight] row.
 *
 * ```kotlin
 * Row(Modifier.height(CloudStreamDimens.ListRowHeight).padding(CloudStreamDimens.ListRowPadding)) {
 *     SelectionMarker(selected = isSelected)
 *     // … 24dp disc, then the text stack
 * }
 * ```
 *
 * ## The marker is `muted`, and white would be a different statement
 *
 * **`ink` is focus.** A selected row is `raised` fill, an `ink` label and a `muted` marker; white
 * on a 10-foot screen means *you are here now*, and a white marker on a selected row says that
 * about a row the user is not on. `Components.dc.html` §03 puts it as a prohibition — *"raised fill
 * plus a muted 4px marker, **never a white bar**"* — and all five TV boards encode the same thing
 * in one line of CSS, `.lrow.sel .lmark { background: var(--mut) }`
 * (`Components:148`, `TV Library:149`, `TV Detail Player:108`, `TV Episodes Downloads:97`,
 * `TV Search Suggestions:123`).
 *
 * This default was `ink` until `DS-10`, which is the bug those boards describe. The two states are
 * meant to be legible together: a focused row has a white *ring* and a selected row has a muted
 * *marker*, and a row that is both says so without either reading as the other.
 *
 * `live` would be a contract violation here whatever the boards said, since selection is not
 * playback state.
 *
 * @param selected whether the rule is painted. The cell is laid out either way.
 * @param modifier applied before the cell's own sizing.
 * @param color the rule colour. `muted` is the design's selection ink; see above before changing
 *   it at a call site.
 * @param ruleWidth the mark's width. See [SelectionMarkerDefaults.RuleWidth].
 */
@Composable
fun SelectionMarker(
    selected: Boolean,
    modifier: Modifier = Modifier,
    color: Color = CloudStreamTheme.colors.muted,
    ruleWidth: Dp = SelectionMarkerDefaults.RuleWidth,
) {
    Box(
        modifier = modifier.width(CloudStreamDimens.ListRowMarkerWidth).fillMaxHeight(),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Box(
                modifier = Modifier
                    .size(width = ruleWidth, height = SelectionMarkerDefaults.RuleHeight)
                    .background(color, CloudStreamTheme.shapes.pill),
            )
        }
    }
}

/**
 * Values [SelectionMarker] needs that `:core:theme` does not name yet.
 */
@Immutable
object SelectionMarkerDefaults {

    /**
     * Width of the mark: the whole cell, `.lmark`'s 8px. Mirrors
     * [CloudStreamDimens.ListRowMarkerWidth]; it was 2dp, a stroke, until `DS-15`.
     */
    val RuleWidth: Dp = 4.dp

    /**
     * Height of the mark, `.lmark`'s 48px, centred in the row. Numerically equal to
     * [CloudStreamDimens.ListRowDiscSize], with a different meaning, so it is not that token.
     */
    val RuleHeight: Dp = 24.dp
}

@Preview
@Composable
private fun SelectionMarkerPreview() {
    CloudStreamTheme {
        Column(
            modifier = Modifier
                .background(CloudStreamTheme.colors.bg)
                .padding(CloudStreamDimens.SidePanelContentInset),
            verticalArrangement = Arrangement.spacedBy(CloudStreamDimens.MinFocusGap),
        ) {
            SelectionMarkerRowPreview(selected = true)
            SelectionMarkerRowPreview(selected = false)
        }
    }
}

@Composable
private fun SelectionMarkerRowPreview(selected: Boolean) {
    Row(
        modifier = Modifier
            .height(CloudStreamDimens.ListRowHeight)
            .background(CloudStreamTheme.colors.raised)
            .padding(CloudStreamDimens.ListRowPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SelectionMarker(selected = selected)
        Box(
            modifier = Modifier
                .padding(start = CloudStreamDimens.ListRowPadding)
                .size(CloudStreamDimens.ListRowDiscSize)
                .background(CloudStreamTheme.colors.surface, CloudStreamTheme.shapes.pill),
        )
    }
}
