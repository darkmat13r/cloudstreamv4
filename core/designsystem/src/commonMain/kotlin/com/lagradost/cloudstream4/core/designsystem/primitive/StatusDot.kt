package com.lagradost.cloudstream4.core.designsystem.primitive

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream4.core.theme.CloudStreamDimens
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme

/**
 * What a [StatusDot] is reporting.
 *
 * Three states, and the colour contract supplies all three without inventing anything: there is
 * no green and no amber in this design, so "ok" is simply full-strength ink and "neutral" is the
 * muted grey everything inactive uses.
 */
@Immutable
enum class StatusDotState {
    /** Reachable, healthy, enabled. Drawn in `ink`. */
    Ok,

    /**
     * Unreachable, errored, failed.
     *
     * Drawn hollow, in `muted`: **a failure is a hollow neutral marker, never `live`** (`DEC-17`).
     * `live` stays for playback, and a failure reads from its shape and the words beside it.
     */
    Failed,

    /** Unknown, not yet checked, disabled. Drawn in `muted`. */
    Neutral,
}

/**
 * An 8dp status marker — a provider's health, a sync result, an extension's state.
 *
 * **It is a square, not a circle**, despite the name. The artboards draw a 16px square; the name
 * is the one the design system uses for the role and is kept so the two match. If you want it
 * round, that is a design change, not a call-site one.
 *
 * 8dp (16px) is on the 4dp step, and it centres onto the step inside any of the even cells the
 * design defines — a [CloudStreamDimens.ListRowHeight] row, a
 * [CloudStreamDimens.IconTargetCell] — which is rule 4 working in the component's favour rather
 * than against it.
 *
 * The dot has no built-in gap. Space it from its neighbour with the surrounding layout, using an
 * on-step value such as [CloudStreamDimens.ListRowPadding].
 *
 * @param state see [StatusDotState]; this also decides the colour, which is why there is no
 *   `color` parameter. A status marker whose colour disagrees with its state is a bug waiting to
 *   happen.
 * @param modifier applied before the dot's own sizing and fill.
 * @param size the square's edge. Defaults to [StatusDotDefaults.Size]; any other value must stay
 *   on the 4dp step.
 */
@Composable
fun StatusDot(
    state: StatusDotState,
    modifier: Modifier = Modifier,
    size: Dp = StatusDotDefaults.Size,
) {
    val colors = CloudStreamTheme.colors
    val shape = CloudStreamTheme.shapes.none
    val sized = modifier.size(size)
    Box(
        modifier = when (state) {
            StatusDotState.Ok -> sized.background(colors.ink, shape)
            StatusDotState.Failed -> sized.insetOutline(colors.muted, shape)
            StatusDotState.Neutral -> sized.background(colors.muted, shape)
        },
    )
}

/**
 * Values [StatusDot] needs that `:core:theme` does not name yet.
 */
@Immutable
object StatusDotDefaults {

    /**
     * The square's edge, 16px on the artboard. **Should become `CloudStreamDimens.StatusDotSize`.**
     * Equal to [CloudStreamDimens.ChipGlyphSize] by coincidence of the step, not by meaning, so
     * it is not that token.
     */
    val Size: Dp = 8.dp
}

@Preview
@Composable
private fun StatusDotPreview() {
    CloudStreamTheme {
        Row(
            modifier = Modifier
                .background(CloudStreamTheme.colors.bg)
                .padding(CloudStreamDimens.SidePanelContentInset),
            horizontalArrangement = Arrangement.spacedBy(CloudStreamDimens.ListRowPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusDot(state = StatusDotState.Ok)
            StatusDot(state = StatusDotState.Failed)
            StatusDot(state = StatusDotState.Neutral)
        }
    }
}
