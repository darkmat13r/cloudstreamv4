package com.lagradost.cloudstream4.core.designsystem.rows

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream4.core.designsystem.preview.PreviewSurface
import com.lagradost.cloudstream4.core.theme.CloudStreamDimens
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme

private const val SINGLE_LINE = 1

/**
 * Measurements [RailHeader] needs that `:core:theme` does not name yet.
 *
 * Unlike [ListRowDefaults], [Height] here is **not** derivable from an existing token — the
 * artboard simply states 56px for a rail title and nothing in `CloudStreamDimens` means that.
 * It is on the 4dp step and it matches `typography.railTitle`'s line height exactly, which is the
 * check that it is the right number rather than a transcribed one.
 */
@Immutable
object RailHeaderDefaults {

    /**
     * The header's height: 56px on the artboard, **28dp**.
     *
     * Equal to `typography.railTitle`'s 28sp line box at font scale 1.0, so the title fills it
     * without a measured gap. It is also numerically equal to
     * [CloudStreamDimens.FocusBandInset] — that is a coincidence, not a relationship; do not
     * express one in terms of the other.
     *
     * Should be promoted as `CloudStreamDimens.RailHeaderHeight`.
     */
    val Height: Dp = 28.dp

    /**
     * Gap between the title and its count. Mirrors [CloudStreamDimens.ListRowPadding] (8dp).
     *
     * Not [CloudStreamDimens.MinFocusGap]: neither the title nor the count is focusable, so the
     * focus-gap minimum does not apply and 12dp reads as a break rather than a pairing.
     *
     * Should be promoted as `CloudStreamDimens.RailHeaderTitleCountGap`.
     */
    val TitleCountGap: Dp = CloudStreamDimens.ListRowPadding
}

/**
 * The title above a rail or a list — `CONTINUE WATCHING`, `SOURCES`, `SUBTITLES` — with an
 * optional count beside it.
 *
 * **Non-focusable by design.** A rail header is a label for the row beneath it, and a TV user
 * pressing down through a screen should land on content, never on a heading. It carries no
 * focusable modifier and no focus ring, and it is marked as a semantic heading so a screen
 * reader still announces the structure. Do not add `focusable()` to make it "reachable" — if a
 * header needs an action (an "All" affordance, say), that action is a separate focusable target
 * beside the header, at [CloudStreamDimens.MinFocusGap] or more.
 *
 * The title uses `typography.railTitle` (40/56px) and the count `typography.labelMono` (24/32px):
 * the count is a number, and numbers in this design are set in the mono face so a changing digit
 * does not reflow the line beside it.
 *
 * The header is [RailHeaderDefaults.Height] tall and does **not** reserve
 * [CloudStreamDimens.FocusRingBleed] beneath itself. The row below it is responsible for its own
 * clearance — and if that row lifts on focus it owes [CloudStreamDimens.LiftedRowClearance]
 * (16dp: 12 of clearance plus the 4 it rises), which is more than a static gap.
 *
 * @param title the heading. Rendered on one line and ellipsised; a rail title that does not fit
 *   is a content problem, not a layout one.
 * @param modifier applied to the header row.
 * @param count an optional count, already formatted. `null` lays out nothing — no empty cell,
 *   because unlike the list row's marker there is nothing after it to keep in place.
 */
@Composable
fun RailHeader(
    title: String,
    modifier: Modifier = Modifier,
    count: String? = null,
) {
    val typography = CloudStreamTheme.typography
    val colors = CloudStreamTheme.colors
    val titleStyle = typography.railTitle
    val countStyle = typography.labelMono

    Row(
        modifier = modifier.height(RailHeaderDefaults.Height).semantics { heading() },
        horizontalArrangement = Arrangement.spacedBy(RailHeaderDefaults.TitleCountGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(
            text = title,
            style = remember(titleStyle, colors) { titleStyle.copy(color = colors.ink) },
            maxLines = SINGLE_LINE,
            overflow = TextOverflow.Ellipsis,
        )
        if (count != null) {
            BasicText(
                text = count,
                style = remember(countStyle, colors) { countStyle.copy(color = colors.muted) },
                maxLines = SINGLE_LINE,
            )
        }
    }
}

@Preview
@Composable
private fun RailHeaderPreview() {
    PreviewSurface {
        Column(verticalArrangement = Arrangement.spacedBy(CloudStreamDimens.MinFocusGap)) {
            RailHeader(title = "CONTINUE WATCHING")
            RailHeader(title = "SOURCES", count = "14")
        }
    }
}
