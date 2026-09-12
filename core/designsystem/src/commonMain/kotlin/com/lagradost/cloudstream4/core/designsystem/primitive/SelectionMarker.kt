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
 * carrying a [SelectionMarkerDefaults.RuleWidth] (2dp) rule when the row is selected.
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
 * The rule is 2dp wide — a stroke weight, exempt from the step in its own axis, the same
 * exemption [CloudStreamDimens.Hairline] and [ProgressRuleDefaults.Thickness] take. It is centred
 * in the 4dp cell by the layout, so no off-step number is ever written.
 *
 * ## Height
 *
 * The marker fills the height it is given, so it belongs in a row of fixed height — a
 * [CloudStreamDimens.ListRowHeight] row. To draw a shorter tick, inset it with
 * `Modifier.padding(vertical = …)` using an on-step value rather than giving it a fixed height.
 *
 * ```kotlin
 * Row(Modifier.height(CloudStreamDimens.ListRowHeight).padding(CloudStreamDimens.ListRowPadding)) {
 *     SelectionMarker(selected = isSelected)
 *     // … 24dp disc, then the text stack
 * }
 * ```
 *
 * @param selected whether the rule is painted. The cell is laid out either way.
 * @param modifier applied before the cell's own sizing.
 * @param color the rule colour. `ink` is the design's selected-state ink; `live` would be a
 *   contract violation here, since selection is not playback state.
 * @param ruleWidth the stroke weight of the rule. See [SelectionMarkerDefaults.RuleWidth].
 */
@Composable
fun SelectionMarker(
    selected: Boolean,
    modifier: Modifier = Modifier,
    color: Color = CloudStreamTheme.colors.ink,
    ruleWidth: Dp = SelectionMarkerDefaults.RuleWidth,
) {
    Box(
        modifier = modifier.width(CloudStreamDimens.ListRowMarkerWidth).fillMaxHeight(),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Box(modifier = Modifier.width(ruleWidth).fillMaxHeight().background(color))
        }
    }
}

/**
 * Values [SelectionMarker] needs that `:core:theme` does not name yet.
 */
@Immutable
object SelectionMarkerDefaults {

    /**
     * Width of the rule inside the cell, 4px on the artboard. **Should become
     * `CloudStreamDimens.SelectionMarkerRuleWidth`, documented beside
     * [CloudStreamDimens.Hairline] as a stroke weight** — it is off the 4dp step for that reason
     * and no other. Numerically equal to [ProgressRuleDefaults.Thickness]; the two are separate
     * because one is a selection affordance and one is playback state.
     */
    val RuleWidth: Dp = 2.dp
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
