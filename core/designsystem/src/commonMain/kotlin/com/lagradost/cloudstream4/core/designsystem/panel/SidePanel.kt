package com.lagradost.cloudstream4.core.designsystem.panel

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import com.lagradost.cloudstream4.core.designsystem.primitive.HairlineSide
import com.lagradost.cloudstream4.core.designsystem.primitive.hairline
import com.lagradost.cloudstream4.core.designsystem.rows.ListRowDefaults
import com.lagradost.cloudstream4.core.designsystem.rows.LocalListRowTitleLines
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
     * `TV Library` 5d and `TV Detail Player` 7e both put their `Done` row at artboard y928,
     * which is this same 464dp, so the one region serves every panel that has a bottom row.
     *
     * Should be promoted as `CloudStreamDimens.SidePanelCommitRegionHeight`.
     */
    val CommitRegionHeight: Dp = CloudStreamDimens.ListRowHeight + CloudStreamDimens.FocusBandInset

    /**
     * What the region above collapses to for a panel with **no** bottom row: the
     * [CloudStreamDimens.FocusBandInset] (28dp) band alone.
     *
     * The band is not optional. It is where the last row's focus ring bleeds — 4dp of
     * [CloudStreamDimens.FocusRingBleed] into a 28dp reserve — so a panel that gave its rows the
     * full height would clip the ring on the bottom one. Only the 48dp row goes away.
     */
    val BandOnlyHeight: Dp = CloudStreamDimens.FocusBandInset

    /**
     * The least gap between the header and the first row. Mirrors [CloudStreamDimens.MinFocusGap]
     * (12dp).
     *
     * The header is **top**-aligned at [CloudStreamDimens.ChromeRowTop], as `TV Search` 4d draws
     * its title at artboard y56, level with the screen's search field. It used to be
     * bottom-aligned against this gap, which put a one-line header about 60dp lower than 4d. This
     * gap now only bounds the header from below: the header is measured in the 88dp left between
     * the two, so a two-line `RailHeader` with a meta line (4d's 44dp) fits with room to spare.
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
 * ## When a panel commits — `DEC-17`
 *
 * **There is no Apply and no Cancel.** The rule is one line per panel kind:
 *
 * | Panel | Choice | When it applies | What BACK does |
 * |---|---|---|---|
 * | Subtitle, audio, sort | single | **at once**, on the row press | closes |
 * | Sources | multi-select | on **Done** | closes, discarding |
 *
 * A single-choice panel therefore has nothing for an Apply to apply: pressing the row *is* the
 * commit, which is what `TV Detail Player` 7e says in so many words — *"Every row carries its own
 * trailing verb, so pressing the row IS the commit — there is no pending edit for Apply to apply
 * or for a dismiss to swallow."* A multi-select panel holds its draft in the caller's state and
 * writes it when Done is pressed.
 *
 * That leaves [commit] holding at most **one** control, and every board draws the same one: a
 * `Done` row, full panel width, in the commit region — `TV Library` 5d and 5e, `TV Detail Player`
 * 7e, all at artboard y928. On a single-choice panel it only closes; on a multi-select panel it
 * applies and closes. A panel that needs neither passes nothing and the region collapses to the
 * bottom band ([SidePanelDefaults.BandOnlyHeight]).
 *
 * What this rule replaces, and why it is not a style preference: the current app shows the
 * Apply/Cancel row in `player_select_source_and_subs.xml` but hides it —
 * `applyHolder.isVisible = realShowApply` — for every single-select picker, so tapping an item
 * commits it and dismisses immediately. There was nothing for Cancel to undo because the change
 * had already happened, and the button the user *could* see in the multi-select case taught them
 * an expectation the single-select case broke. `DEC-17` keeps the behaviour that worked and drops
 * the buttons that lied about it.
 *
 * [SidePanelHost] raises `onDismissRequest` on BACK. It is a request to *close*; the table above
 * says what closing means for each kind, and the caller is the one that knows which kind it is.
 *
 * ## Geometry
 *
 * ```
 *   0 ┌───────────────────────── panel, 280dp wide, full height
 *  28 │  header, top-aligned       <- CloudStreamDimens.ChromeRowTop, as 4d draws it
 *     │  header region, 128dp      <- CloudStreamDimens.SidePanelFirstRowY
 * 128 │  row 0        48dp
 * 188 │  row 1        48dp         <- pitch 60 = 48 + SidePanelDefaults.RowGap
 * 248 │  row 2 ...                    (scrolls; the region takes the slack)
 * 464 │  commit row   48dp         <- 540 - SidePanelDefaults.CommitRegionHeight; `Done`, or
 *     │                                  absent, in which case the rows reach 512 instead
 * 512 └──  28dp bottom band
 * ```
 *
 * The panel is the `surface` token, as 4d's `.panel` is `var(--surf)`, so a selected row's
 * `raised` fill reads against it. It used to be `raised` itself, which made selection invisible.
 *
 * Content is inset [CloudStreamDimens.SidePanelContentInset] (16dp) horizontally, which is more
 * than the [CloudStreamDimens.FocusRingBleed] (8dp) a focused row bleeds, so rings never touch the
 * panel edge. The panel's leading hairline is **drawn**, not padded — it consumes no layout, so
 * the 280dp width and the 16dp inset are unchanged by its presence.
 *
 * @param modifier applied to the panel. It already carries its own width and height; a modifier
 *   that changes either is a design change.
 * @param header the region above the rows, top-aligned at [CloudStreamDimens.ChromeRowTop] and
 *   kept at least [SidePanelDefaults.HeaderGap] above the first row. A `RailHeader` is the usual
 *   occupant.
 * @param commit the panel's bottom row, normally a single `Done` `ListRow`. `null` for a panel
 *   that needs none, which collapses the region to [SidePanelDefaults.BandOnlyHeight]. **Not**
 *   Apply and Cancel — see `DEC-17` above.
 * @param rows the panel's rows, normally `ListRow`s. Laid out in a [ColumnScope] at the design's
 *   pitch; add a scroll modifier here if the list can outgrow the region.
 *
 *   **A row here may give its title a second line** ([LocalListRowTitleLines]), because 232dp of
 *   title is not enough for every language's `sort_*` label (`BUG-62`), and a row that does is
 *   64dp instead of 48dp.
 *
 *   A scrolling panel keeps a focused grown row inside its own box: the four `sort_*` criteria in
 *   `ta`, all grown, come to 300dp inside the 336dp this region leaves on a 540dp screen, and
 *   walking down a panel too short for its rows parks each focused row at the same y as the list
 *   scrolls under it (`SidePanelLongLabelDeviceTest`). What does **not** work is asking focus for
 *   a row the first frame does not compose — a `LazyColumn` disposes the item and cancels its
 *   effect first — and that holds for one-line rows too, so it is `BUG-82` rather than this.
 *
 *   The [commit] row is deliberately left out of the second line, and the premise is measured
 *   rather than assumed: a 64dp row here would eat 16dp of the bottom focus band, and no label a
 *   commit row is given needs one. In the **220.1dp** this row allows a title on `Television_720p`,
 *   the longest are `search_quick_open_full` at 199.2dp in `mk`, 180.9 in `el`, 171.0 in `fr`,
 *   168.4 in `hr` and 146.5 in `my`; `go_back` is 160.0 in `ta`, `cancel` 68.4 in `my`,
 *   `setup_done` 62.3 in `ta` and `search_state_retry` 55.2 in English. The widest is 21dp clear,
 *   on one line and unellipsised, and `SidePanelLongLabelDeviceTest` drives all nine so a new
 *   caller or a longer translation fails rather than quietly clips.
 */
@Composable
fun SidePanel(
    modifier: Modifier = Modifier,
    header: @Composable () -> Unit = {},
    commit: (@Composable () -> Unit)? = null,
    rows: @Composable ColumnScope.() -> Unit,
) {
    val colors = CloudStreamTheme.colors
    Column(
        modifier = modifier
            .width(CloudStreamDimens.SidePanelWidth)
            .fillMaxHeight()
            .background(colors.surface)
            .hairline(side = HairlineSide.Start)
            .padding(horizontal = CloudStreamDimens.SidePanelContentInset),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(CloudStreamDimens.SidePanelFirstRowY)
                .padding(top = CloudStreamDimens.ChromeRowTop, bottom = SidePanelDefaults.HeaderGap),
            contentAlignment = Alignment.TopStart,
            content = { header() },
        )
        CompositionLocalProvider(
            LocalListRowTitleLines provides ListRowDefaults.TITLE_LINES_IN_PANEL,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().weight(WEIGHT_REMAINING),
                verticalArrangement = Arrangement.spacedBy(SidePanelDefaults.RowGap),
                content = rows,
            )
        }
        if (commit == null) {
            Spacer(Modifier.height(SidePanelDefaults.BandOnlyHeight))
        } else {
            Box(
                modifier = Modifier.fillMaxWidth().height(SidePanelDefaults.CommitRegionHeight),
                contentAlignment = Alignment.TopStart,
                content = { commit() },
            )
        }
    }
}

private const val WEIGHT_REMAINING = 1f
