package com.lagradost.cloudstream4.core.designsystem.state

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream4.core.designsystem.primitive.StatusDot
import com.lagradost.cloudstream4.core.designsystem.primitive.StatusDotState
import com.lagradost.cloudstream4.core.designsystem.primitive.insetOutline
import com.lagradost.cloudstream4.core.theme.CloudStreamDimens
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme

/** Literals this component owns, and what they should become in `:core:theme`. */
@Immutable
object FailureBannerDefaults {
    /**
     * The status dot: 16px on the artboard, the smallest thing on the 4dp step that still reads
     * at three metres. Should be promoted to `dimens.StatusDotSize`.
     */
    val DotSize: Dp = 8.dp

    /**
     * The pill's height: `.failpill`'s 48px, the same 24dp as a chip's visual. Mirrors `ChipVisualHeight`.
     */
    val PillHeight: Dp = CloudStreamDimens.ChipVisualHeight

    /** Inside the pill's ends: `.failpill`'s `padding: 0 24px`, halved. */
    val PillHorizontalPadding: Dp = 12.dp

    /**
     * Gap between the dot, the headline and the mono detail — all non-focusable, so
     * `dimens.MinFocusGap` does not apply. Mirrors `dimens.ListRowPadding` (8dp). Should be
     * promoted to `dimens.InlineGap`.
     */
    val ItemGap: Dp = 8.dp

    /**
     * The widest the mono detail measures before it ellipsises. A provider list can be
     * arbitrarily long and this rides a row that has other work to do. Should be promoted to
     * `dimens.FailureDetailMaxWidth`.
     */
    val DetailMaxWidth: Dp = 240.dp
}

/**
 * The partial-failure state: a status dot, a headline, a mono detail, and a retry that is a real
 * focus stop.
 *
 * **It does not get a band of its own.** A full-width banner costs 120px of vertical budget on
 * the artboard, which is exactly what pushed a rail past the focus band. This component is
 * therefore sized to ride a row that already exists: it is [CloudStreamDimens.ChipFocusCell]
 * (48dp) tall, the same as a chip's focus cell, so dropping it at the trailing end of a chip row
 * changes nothing about that row's geometry. Its retry button is 32dp tall and centres in the
 * 48dp cell at +8dp — on the step.
 *
 * ```kotlin
 * Row(
 *     modifier = Modifier.height(CloudStreamDimens.ChipFocusCell),
 *     horizontalArrangement = Arrangement.spacedBy(CloudStreamDimens.ChipRowSpacing),
 * ) {
 *     filterChips()
 *     state.failureNotice?.let { FailureBanner(it) }
 * }
 * ```
 *
 * The dot is a hollow `muted` [StatusDot]: **a failure is a hollow neutral marker, never `live`**
 * (`DEC-17`). The retry beside it is [StateActionEmphasis.Primary], the `ink` fill with a black
 * label that `TV Search` 4b draws as `.bbtn`: it is the one thing on the row to act on.
 *
 * @param notice what failed, in what detail, and how to retry.
 * @param modifier applied to the banner's row.
 */
@Composable
fun FailureBanner(
    notice: FailureNotice,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.height(CloudStreamDimens.ChipFocusCell),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(WEIGHT_REMAINING, fill = false)
                .height(FailureBannerDefaults.PillHeight)
                .background(color = CloudStreamTheme.colors.raised, shape = CloudStreamTheme.shapes.pill)
                .insetOutline(CloudStreamTheme.colors.line, CloudStreamTheme.shapes.pill)
                .padding(horizontal = FailureBannerDefaults.PillHorizontalPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NoticeLabels(notice = notice)
        }
        Spacer(Modifier.width(CloudStreamDimens.MinFocusGap))
        StateActionButton(
            action = notice.retry,
            emphasis = StateActionEmphasis.Primary,
        )
    }
}

/** The dot, the plain headline and the mono detail — everything left of the retry. */
@Composable
private fun RowScope.NoticeLabels(notice: FailureNotice) {
    val colors = CloudStreamTheme.colors
    val typography = CloudStreamTheme.typography
    StatusDot(state = StatusDotState.Failed, size = FailureBannerDefaults.DotSize)
    Spacer(Modifier.width(FailureBannerDefaults.ItemGap))
    Text(
        text = notice.headline,
        style = typography.label,
        color = colors.ink,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
    Spacer(Modifier.width(FailureBannerDefaults.ItemGap))
    Text(
        text = notice.detail,
        style = typography.labelMono,
        color = colors.muted,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.widthIn(max = FailureBannerDefaults.DetailMaxWidth),
    )
}

private const val WEIGHT_REMAINING = 1f

private val PreviewNotice = FailureNotice(
    headline = "3 of 12 providers failed",
    detail = "source-a.example, source-b.example - HTTP 503",
    retry = StateAction(label = "Retry", onActivate = {}),
)

@Preview
@Composable
private fun FailureBannerPreview() {
    StatePreviewSurface {
        FailureBanner(notice = PreviewNotice)
    }
}
