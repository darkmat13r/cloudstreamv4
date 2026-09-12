package com.lagradost.cloudstream4.core.designsystem.panel

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import com.lagradost.cloudstream4.core.designsystem.primitive.HairlineSide
import com.lagradost.cloudstream4.core.designsystem.primitive.hairline
import com.lagradost.cloudstream4.core.theme.CloudStreamDimens
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme

/**
 * Measurements [SidePanel] needs that `:core:theme` does not name yet. All three are **derived**
 * from tokens the theme already has, so the artboard's absolute y values fall out of them rather
 * than being transcribed.
 */
@Immutable
object SidePanelDefaults {

    /**
     * Vertical gap between rows: [CloudStreamDimens.SidePanelRowPitch] (60dp) less
     * [CloudStreamDimens.ListRowHeight] (48dp) = **12dp**.
     *
     * Expressing the artboard's `y = 128 + i * 60` as "first row at 128, then 12dp apart"
     * reproduces it exactly and survives a row that is not 48dp tall. The 12dp also happens to be
     * [CloudStreamDimens.MinFocusGap] — the design's row pitch is the tightest legal one, which
     * is worth knowing before anyone tightens it.
     *
     * Should be promoted as `CloudStreamDimens.SidePanelRowGap`.
     */
    val RowGap: Dp = CloudStreamDimens.SidePanelRowPitch - CloudStreamDimens.ListRowHeight

    /**
     * Height of the region holding the commit row: [CloudStreamDimens.ListRowHeight] (48dp) for
     * the row plus [CloudStreamDimens.FocusBandInset] (28dp) of bottom band = **76dp**.
     *
     * On the 1080p artboard (540dp tall) this puts the commit row's top at 540 − 76 = **464dp**,
     * which is where the design draws it, and its bottom at 512dp — exactly the bottom edge of
     * the TV focus band. Anchoring to the band rather than to y=464 is what makes the panel
     * correct on a screen that is not 540dp tall, and it is *why* the artboard says 464.
     *
     * Should be promoted as `CloudStreamDimens.SidePanelCommitRegionHeight`.
     */
    val CommitRegionHeight: Dp = CloudStreamDimens.ListRowHeight + CloudStreamDimens.FocusBandInset

    /**
     * Gap between the header and the first row. Mirrors [CloudStreamDimens.MinFocusGap] (12dp).
     *
     * The artboard fixes the first row at y=128 but does not say where inside 0..128 the header
     * sits, so the header is bottom-aligned in that region with this gap beneath it — a title
     * that floats in the middle of a 128dp void reads as unrelated to the rows it labels. This is
     * the one value in this file that is a judgement rather than a derivation.
     *
     * Should be promoted as `CloudStreamDimens.SidePanelHeaderGap`.
     */
    val HeaderGap: Dp = CloudStreamDimens.MinFocusGap
}

/**
 * The CloudStream 4 side panel — **TV's replacement for a bottom sheet.**
 *
 * ## Why this exists
 *
 * TV never uses a bottom sheet. A sheet is a touch idiom: it assumes a thumb near the bottom edge
 * and a drag to dismiss, and it puts its content in the part of a 10-foot screen that is furthest
 * from the eye and closest to the soundbar. Every place the phone app would open a sheet, TV
 * opens this: a [CloudStreamDimens.SidePanelWidth] (280dp) panel anchored to the right edge, over
 * content that **stays mounted** behind it — see [SidePanelHost].
 *
 * ## Commit is explicit
 *
 * A panel that changes a setting must show its commit row, and the caller must not treat dismissal
 * as acceptance. This is not a style preference: the current app gets it wrong. In
 * `SingleSelectionHelper.showDialog` the Apply/Cancel row is present in
 * `player_select_source_and_subs.xml` but hidden — `applyHolder.isVisible = realShowApply` — for
 * every single-select picker, so tapping an item commits it and dismisses immediately. There is
 * nothing for Cancel to undo because the change already happened, and the button the user can see
 * in the multi-select case teaches them an expectation the single-select case breaks.
 *
 * Put Apply and Cancel in [commit], keep the draft in the caller's state, and apply it only when
 * Apply is pressed. [SidePanelHost] raises `onDismissRequest` on BACK; that is a request to
 * *close*, and the caller decides whether closing discards.
 *
 * ## Geometry
 *
 * ```
 *   0 ┌───────────────────────── panel, 280dp wide, full height
 *     │  header region, 128dp      <- CloudStreamDimens.SidePanelFirstRowY
 * 128 │  row 0        48dp
 * 188 │  row 1        48dp         <- pitch 60 = 48 + SidePanelDefaults.RowGap
 * 248 │  row 2 ...                    (scrolls; the region takes the slack)
 * 464 │  commit row   48dp         <- 540 - SidePanelDefaults.CommitRegionHeight
 * 512 └──  28dp bottom band
 * ```
 *
 * Content is inset [CloudStreamDimens.SidePanelContentInset] (16dp) horizontally, which is more
 * than the [CloudStreamDimens.FocusRingBleed] (8dp) a focused row bleeds, so rings never touch the
 * panel edge. The panel's leading hairline is **drawn**, not padded — it consumes no layout, so
 * the 280dp width and the 16dp inset are unchanged by its presence.
 *
 * @param modifier applied to the panel. It already carries its own width and height; a modifier
 *   that changes either is a design change.
 * @param header the region above the rows, bottom-aligned so it sits
 *   [SidePanelDefaults.HeaderGap] above the first row. A `RailHeader` is the usual occupant.
 * @param commit the commit row — Apply and Cancel. Empty for a panel that only navigates; see the
 *   section above before leaving it empty for a panel that changes anything.
 * @param rows the panel's rows, normally `ListRow`s. Laid out in a [ColumnScope] at the design's
 *   pitch; add a scroll modifier here if the list can outgrow the region.
 */
@Composable
fun SidePanel(
    modifier: Modifier = Modifier,
    header: @Composable () -> Unit = {},
    commit: @Composable () -> Unit = {},
    rows: @Composable ColumnScope.() -> Unit,
) {
    val colors = CloudStreamTheme.colors
    Column(
        modifier = modifier
            .width(CloudStreamDimens.SidePanelWidth)
            .fillMaxHeight()
            .background(colors.raised)
            .hairline(side = HairlineSide.Start)
            .padding(horizontal = CloudStreamDimens.SidePanelContentInset),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(CloudStreamDimens.SidePanelFirstRowY)
                .padding(bottom = SidePanelDefaults.HeaderGap),
            contentAlignment = Alignment.BottomStart,
            content = { header() },
        )
        Column(
            modifier = Modifier.fillMaxWidth().weight(WEIGHT_REMAINING),
            verticalArrangement = Arrangement.spacedBy(SidePanelDefaults.RowGap),
            content = rows,
        )
        Box(
            modifier = Modifier.fillMaxWidth().height(SidePanelDefaults.CommitRegionHeight),
            contentAlignment = Alignment.TopStart,
            content = { commit() },
        )
    }
}

private const val WEIGHT_REMAINING = 1f
