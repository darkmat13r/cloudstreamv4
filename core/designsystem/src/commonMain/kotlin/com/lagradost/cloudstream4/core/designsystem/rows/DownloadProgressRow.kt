package com.lagradost.cloudstream4.core.designsystem.rows

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream4.core.designsystem.primitive.ProgressRule
import com.lagradost.cloudstream4.core.designsystem.primitive.StatusDot
import com.lagradost.cloudstream4.core.designsystem.primitive.StatusDotState
import com.lagradost.cloudstream4.core.designsystem.target.TargetLabel
import com.lagradost.cloudstream4.core.theme.CloudStreamDimens
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme

/**
 * Everything a [DownloadProgressRow] renders, hoisted. Focus is not here, for the reason on [ListRowState].
 *
 * @property title the title or episode.
 * @property subtitle the second line: runtime, bytes, or a typed failure reason.
 * @property value the trailing figure, already formatted, such as `25%` or `4.24 GB`.
 * @property progress the transfer in `0f..1f`, or null for no bar, as on a finished or queued row.
 * @property failed the transfer stopped. See [DownloadProgressRow].
 * @property selected whether the row's selection marker is lit.
 */
@Immutable
data class DownloadProgressRowState(
    val title: String,
    val subtitle: String? = null,
    val value: String? = null,
    val progress: Float? = null,
    val failed: Boolean = false,
    val selected: Boolean = false,
)

/**
 * A [ListRow] on a raised fill with a transfer bar along its foot (`TV Episodes Downloads` 6c, 6d).
 *
 * Transfer is `ink` on a `line` track, and a stopped one is `muted`. It is not `live`, because a download is not
 * playback.
 *
 * **Departs from the boards on failure.** They draw a `live` rule on a failed row's leading edge, and `DEC-17`
 * has since ruled that a failure is a hollow neutral marker, never `live`. So the marker is a hollow [StatusDot]
 * beside the value, and the words carry the rest, in `ink2` (6c's closing note).
 *
 * The verbs on 6d (play, pause or retry, delete) are separate focus stops beside this row on the board, not
 * inside it, so they are the screen's to lay out.
 *
 * The bar's top edge is at [DownloadProgressRowDefaults.RuleTop]. It clears the 32dp text stack, which ends at
 * +40dp, and draws over the row without changing what the row measures.
 *
 * @param leading the 24dp cell before the text, as on [ListRow].
 */
@Composable
fun DownloadProgressRow(
    state: DownloadProgressRowState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
) {
    val colors = CloudStreamTheme.colors
    Box(modifier) {
        ListRow(
            state = ListRowState(title = state.title, subtitle = state.subtitle, selected = state.selected),
            onClick = onClick,
            modifier = Modifier.fillMaxWidth().background(colors.raised, CloudStreamTheme.shapes.medium),
            leading = leading,
            trailing = trailingOrNull(state),
        )
        if (state.progress != null) {
            ProgressRule(
                progress = state.progress,
                modifier = Modifier.padding(
                    start = CloudStreamDimens.ListRowPadding,
                    top = DownloadProgressRowDefaults.RuleTop,
                    end = CloudStreamDimens.ListRowPadding,
                ),
                color = if (state.failed) colors.muted else colors.ink,
                trackColor = colors.line,
            )
        }
    }
}

/** Null when there is nothing to show, so [ListRow] lays out no trailing cell at all. */
private fun trailingOrNull(state: DownloadProgressRowState): (@Composable () -> Unit)? =
    if (state.value == null && !state.failed) null else ({ DownloadTrailing(state.value, state.failed) })

@Composable
private fun DownloadTrailing(value: String?, failed: Boolean) {
    val colors = CloudStreamTheme.colors
    Row(
        horizontalArrangement = Arrangement.spacedBy(ListRowDefaults.InnerGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (failed) StatusDot(StatusDotState.Failed)
        if (value != null) {
            TargetLabel(
                text = value,
                style = CloudStreamTheme.typography.labelMono,
                color = if (failed) colors.ink2 else colors.ink,
            )
        }
    }
}

/** Values [DownloadProgressRow] needs that `:core:theme` does not name yet. */
@Immutable
object DownloadProgressRowDefaults {

    /** The bar's top edge, the board's +88px line. The bar's thickness is exempt; this edge is on the step. */
    val RuleTop: Dp = 44.dp
}
