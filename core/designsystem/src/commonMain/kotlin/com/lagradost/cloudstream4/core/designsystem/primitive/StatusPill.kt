package com.lagradost.cloudstream4.core.designsystem.primitive

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream4.core.designsystem.icon.CloudStreamIcon
import com.lagradost.cloudstream4.core.designsystem.icon.CloudStreamIconSize
import com.lagradost.cloudstream4.core.designsystem.icon.CloudStreamIcons
import com.lagradost.cloudstream4.core.designsystem.target.TargetLabel
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme

/** What a [StatusPill] is reporting, which decides its marker. */
@Immutable
enum class StatusPillState {
    /** Still being checked. A filled `muted` square. */
    Probing,

    /** Usable. A filled `ink` square. */
    Ready,

    /** Failed. A hollow `muted` square: never `live` (`DEC-17`). */
    Failed,

    /** Playing now. The only state that earns `live`, because it is the only playback state. */
    Playing,
}

/**
 * A marker and a short mono label on a raised pill (`Components` §01).
 *
 * The marker is a [StatusDot], except for [StatusPillState.Playing], which is the pack's play glyph. The board
 * draws that one as an 8dp CSS triangle; `.claude/rules/icons.md` rules out drawing it, and
 * [CloudStreamIconSize.Small] is the smallest size an icon comes in. It is 16dp in a 24dp pill, so it still
 * centres on the step.
 *
 * The label is `labelMono`, not the board's 20px. 16dp of line box centres in the pill at +4dp, where the 12dp
 * box of the 20px style would land at +6dp, off the step.
 *
 * @param label already localised; this module holds no user-facing strings. Not upper-cased here.
 */
@Composable
fun StatusPill(
    state: StatusPillState,
    label: String,
    modifier: Modifier = Modifier,
) {
    val colors = CloudStreamTheme.colors
    val shape = CloudStreamTheme.shapes.pill
    Row(
        modifier = modifier
            .height(StatusPillDefaults.Height)
            .background(colors.raised, shape)
            .insetOutline(colors.line, shape)
            .padding(horizontal = StatusPillDefaults.HorizontalPadding),
        horizontalArrangement = Arrangement.spacedBy(StatusPillDefaults.MarkerGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (state) {
            StatusPillState.Probing -> StatusDot(StatusDotState.Neutral)
            StatusPillState.Ready -> StatusDot(StatusDotState.Ok)
            StatusPillState.Failed -> StatusDot(StatusDotState.Failed)
            StatusPillState.Playing -> CloudStreamIcon(
                icon = CloudStreamIcons.Play,
                contentDescription = null,
                size = CloudStreamIconSize.Small,
                tint = colors.live,
            )
        }
        TargetLabel(text = label, style = CloudStreamTheme.typography.labelMono, color = colors.ink2)
    }
}

/** Values [StatusPill] needs that `:core:theme` does not name yet. */
@Immutable
object StatusPillDefaults {

    /** 48px on the artboard. Equal to `CloudStreamDimens.ChipVisualHeight` in value, not in meaning. */
    val Height: Dp = 24.dp

    /** 16px on the artboard. Equal to `CloudStreamDimens.ListRowPadding` in value, not in meaning. */
    val HorizontalPadding: Dp = 8.dp

    /** Between the marker and the label, 8px on the artboard. Mirrors `CloudStreamDimens.StackGap`. */
    val MarkerGap: Dp = 4.dp
}
