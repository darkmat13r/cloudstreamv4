package com.lagradost.cloudstream4.core.designsystem.rows

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import com.lagradost.cloudstream4.core.designsystem.focus.tvFocusRing
import com.lagradost.cloudstream4.core.designsystem.preview.PreviewSurface
import com.lagradost.cloudstream4.core.designsystem.primitive.SelectionMarker
import com.lagradost.cloudstream4.core.theme.CloudStreamDimens
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme

/**
 * The CloudStream 4 list row — the structure every settings-like surface on TV is built from.
 *
 * ## Geometry
 *
 * The row is [CloudStreamDimens.ListRowHeight] (48dp) tall with
 * [CloudStreamDimens.ListRowPadding] (8dp) of inner padding on every side, leaving a
 * [ListRowDefaults.ContentHeight] (32dp) content box. Across that box:
 *
 * ```
 * |8|4|8|  24  |8|        text stack        |8| trailing |8|
 *   ^  ^        ^                            ^
 *   |  |        leading disc (optional)      trailing slot (optional)
 *   |  marker cell, always present
 *   inner padding
 * ```
 *
 * Every x lands on the 4dp step: marker at 8, disc at 20, text at 52. The marker **cell** is
 * always laid out even when nothing is selected, so selecting a row never shifts its text; what
 * is painted inside that cell belongs to the `SelectionMarker` primitive, which the row reuses
 * rather than drawing its own.
 *
 * The text stack fills the 32dp content box exactly, which is rule 5 of the design — see
 * `ListRowTextStack.kt` for why its gap is 4dp and not 2dp.
 *
 * ## 48dp is a floor and a step, not a fixed height (`BUG-62`)
 *
 * **A row whose title fits one line is 48dp, exactly as it always was**, and in a list wide
 * enough for one line that is every row: a long title is ellipsised there, as it always was. Only
 * inside a `SidePanel`, where [LocalListRowTitleLines] allows a second line, does a title that
 * does not fit — a `sort_*` label in Tamil, Greek or Albanian — take one, and the row is then
 * **64dp**: its height is its content rounded up to a whole [ListRowDefaults.HeightStep] (16dp),
 * floored at [CloudStreamDimens.ListRowHeight].
 *
 * The step is what keeps the text centred on the 4dp grid. A row that simply wrapped its content
 * would be 56dp, and its stack would sit 4dp off where every other row's does; rule 5 is the same
 * statement about the stack, and this is its other half. See [ListRowDefaults.HeightStep], and
 * `rowHeight` below for why the **line count** and not the measured content decides which it is.
 *
 * A grown row costs its column exactly 16dp: a panel lays its rows out at a pitch of 48dp plus
 * [CloudStreamDimens.MinFocusGap], so a 64dp row moves the rows after it 16dp down. The titles
 * this actually grows are a side panel's `sort_*` labels, and the panel that lists them scrolls
 * already. A panel whose rows are a plain `Column` that fills the region has 16dp less slack than
 * it did — which is the case `SidePanel`'s own KDoc already asks a caller to add a scroll modifier
 * for.
 *
 * A row in a list that allows one line is `height(48.dp)`, exactly the modifier it has always
 * had.
 *
 * ## Focus
 *
 * The ring goes on the **row**, at `CloudStreamTheme.shapes.medium`, and is drawn — not
 * bordered — so the 48dp height and every x above are unchanged by focus. The ring bleeds
 * [CloudStreamDimens.FocusRingBleed] (8dp) outside the row, which is why a column of these needs
 * at least [CloudStreamDimens.MinFocusGap] (12dp) between rows and why **no ancestor may clip**.
 * A side panel's [CloudStreamDimens.SidePanelRowPitch] (60dp) leaves exactly 12dp.
 *
 * There is deliberately no `indication`: on TV the ring *is* the affordance, and a ripple that
 * follows a D-pad is noise. The row does not lift either — lift belongs to cards, and a lifting
 * row would owe its column [CloudStreamDimens.LiftedRowClearance].
 *
 * Focus is observed locally rather than hoisted; see [ListRowState] for why.
 *
 * ## [trailing] and [actions] are two different things
 *
 * `Components.dc.html` §03's anatomy table gives the trailing slot as *"64–96"* px — 32dp to 48dp
 * — and *"A real focus stop with its own ring. This is what made download and overflow buttons
 * unreachable before."* Both halves of that are true at once only because the chassis has **two**
 * trailing slots, and the boards draw the difference plainly:
 *
 * - **[trailing] is inside the row.** `TV Library` 5d's direction word, 6d's rate-and-ETA readout,
 *   `TV Search Suggestions` 8c's source count. These are *readouts*: markup puts them in the row's
 *   own `.ltrail`, they are not focusable, and they are centred in the 32dp content box.
 * - **[actions] are beside the row.** `TV Episodes Downloads` 6d's play / state-verb / delete cells
 *   are `.ibx` elements positioned **outside** the `.lrow` div, at x1496, 1616 and 1736, each
 *   96x96 px, while the row itself ends at x1472. Each is its own focus stop with its own ring.
 *
 * So the row and its controls are **siblings, not nested**. That is the point. `KMP-12` declined
 * the per-row remove on `TV Search` 4c because the alternative was *"a second focus stop nested
 * inside a row that is itself a focus stop"* — a focusable inside a focusable, where which one a
 * D-pad press reaches depends on focus-search internals. Putting the controls next to the row's
 * focus target instead removes the nesting, and the geometry falls out of one token:
 *
 * ```
 * |        row — one focus stop        |12|  action  |12|  action  |
 *                                       ^     48dp
 *                                       CloudStreamDimens.MinFocusGap
 * ```
 *
 * 6d measures row-end 1472 to first cell 1496 as 24px, and cell pitch 120px against a 96px cell
 * as another 24px — the same 12dp both times, which is [CloudStreamDimens.MinFocusGap]. One
 * `Arrangement.spacedBy(MinFocusGap)` reproduces the whole run. It is also wide enough for two
 * rings: each bleeds [CloudStreamDimens.FocusRingBleed] (4dp), so 8dp of ring sits in a 12dp gap
 * and neighbouring rings never touch.
 *
 * With [actions] present the row takes the remaining width and the actions take their intrinsic
 * width, which is what 6d draws; without them the row is laid out exactly as it was before this
 * slot existed, with no extra layout node.
 *
 * @param state what to render. See [ListRowState].
 * @param onClick invoked on click or D-pad centre. Not invoked when `state.enabled` is false,
 *   whether or not the row is still focusable.
 * @param modifier applied to the row, or to the row-and-actions run when [actions] is non-null.
 *   Do not put a height on it — the 48dp is the design.
 * @param leading an optional [CloudStreamDimens.ListRowDiscSize] (24dp) square cell before the
 *   text: the disc, a checkbox, a provider icon. The cell is only laid out when non-null.
 * @param trailing an optional **non-focusable** readout after the text, centred in the 32dp
 *   content box: a value, a count, a direction. Give it an intrinsic width; the text stack takes
 *   the remaining space. For something a D-pad must reach, use [actions].
 * @param actions optional focus stops **after** the row, [CloudStreamDimens.MinFocusGap] apart and
 *   the row's full [CloudStreamDimens.ListRowHeight] tall, so a 48dp `IconCell` fits with room for
 *   its ring. Each one is a separate stop; the row remains its own.
 */
@Composable
fun ListRow(
    state: ListRowState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
) {
    if (actions == null) {
        RowBody(state, onClick, modifier, leading, trailing)
        return
    }
    Row(
        modifier = modifier.heightIn(min = CloudStreamDimens.ListRowHeight),
        horizontalArrangement = Arrangement.spacedBy(CloudStreamDimens.MinFocusGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RowBody(state, onClick, Modifier.weight(WEIGHT_REMAINING), leading, trailing)
        actions()
    }
}

/**
 * The row itself: the one focus stop, the ring, and the three in-row slots.
 *
 * Split out of [ListRow] so that a row with [ListRow]'s `actions` can hand it a
 * `Modifier.weight` while a row without them keeps its original single-node layout.
 */
@Composable
private fun RowBody(
    state: ListRowState,
    onClick: () -> Unit,
    modifier: Modifier,
    leading: (@Composable () -> Unit)?,
    trailing: (@Composable () -> Unit)?,
) {
    var focused by remember { mutableStateOf(false) }
    var measuredTitleLines by remember { mutableIntStateOf(ListRowDefaults.TITLE_LINES) }
    val titleLines = measuredTitleLines.coerceIn(ListRowDefaults.TITLE_LINES, LocalListRowTitleLines.current)
    val interactionSource = remember { MutableInteractionSource() }
    Row(
        modifier = modifier
            .height(rowHeight(titleLines))
            .onFocusChanged { focused = it.isFocused }
            .rowBehaviour(state, interactionSource, onClick)
            .tvFocusRing(focused = focused, shape = CloudStreamTheme.shapes.medium)
            .selectedFill(state.selected)
            .padding(CloudStreamDimens.ListRowPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SelectionMarker(selected = state.selected)
        Spacer(Modifier.width(ListRowDefaults.InnerGap))
        if (leading != null) LeadingCell(leading)
        ListRowTextStack(
            title = state.title,
            subtitle = state.subtitle,
            enabled = state.enabled,
            modifier = Modifier.weight(WEIGHT_REMAINING),
            titleLines = titleLines,
            onTitleLines = { measuredTitleLines = it },
        )
        if (trailing != null) TrailingCell(trailing)
    }
}

/**
 * How tall a row whose title took [titleLines] lines is: [CloudStreamDimens.ListRowHeight], plus
 * one [ListRowDefaults.HeightStep] per line after the first.
 *
 * ## The line count decides, not the measured content
 *
 * This looks like something a layout could work out for itself — measure the content, round up to
 * the next step — and that version was written, run on the device, and thrown away. Two things it
 * could not do, both of which only appear where a dp is not a whole number of pixels (here, 1.33):
 *
 * - **A row with a trailing readout measured 65px against a 64px row**, because the row's 8dp
 *   padding rounds to 11px a side and the trailing box's 32dp to 43px. Rounding noise read as
 *   "needs another step", and every downloads and suggestion row became 64dp: four device tests in
 *   two other modules failed, and a list moved 12dp.
 * - **Even with that fixed, it moved text by a pixel in every row in the app.** A fixed height
 *   measures the content at exactly 48dp less its padding, which is what squeezes that 43px box
 *   back to 42; measuring the content first and sizing to it does not, so the whole stack sat half
 *   a pixel off. Fifteen committed screenshots changed, none of them with anything to show for it.
 *
 * A line count has none of that: it is 1 or 2, it comes from the text that was actually laid out,
 * and the height it maps to is written in dp. A one-line row is `height(48.dp)`, which is what the
 * row has always been, so its pixels are untouched.
 *
 * The cost is one extra layout pass on the frame where a title first turns out to need a second
 * line, which happens only in a side panel. It cannot oscillate: a title's line count depends on
 * the row's width, and nothing here changes that.
 */
private fun rowHeight(titleLines: Int): Dp =
    CloudStreamDimens.ListRowHeight +
        ListRowDefaults.HeightStep * (titleLines - ListRowDefaults.TITLE_LINES).coerceAtLeast(0)

/**
 * A selected row's `raised` fill, at the row's own corners: `.lrow.sel { background: var(--raised) }`
 * on every board with a list row, and `Design System v2`'s *"raised fill + ink label + muted
 * marker"*. With the muted [SelectionMarker] it is how selection reads, so focus alone is white.
 * On `SidePanel`'s `surface` it is the only thing that tells a selected row from the others' ground.
 */
@Composable
private fun Modifier.selectedFill(selected: Boolean): Modifier =
    if (selected) background(CloudStreamTheme.colors.raised, CloudStreamTheme.shapes.medium) else this

/**
 * Selectable when enabled; focusable-but-not-selectable when disabled and
 * [ListRowState.focusableWhenDisabled]; neither otherwise.
 *
 * ## A disabled row that is still a focus stop
 *
 * `TV Library` 5d draws the case: four sort criteria, and the one the provider cannot do —
 * *"Your rating"*, subtitle *"NOT STORED BY LOCAL LISTS"*, trailing *"N/A"* — *"stays visible and
 * disabled with the reason"*. A row removed from a panel takes its explanation with it and the
 * user is left wondering why their provider has three criteria and not four; a row that is present
 * but unreachable is worse, because the reason is on screen and they cannot put focus on it to
 * read it at three metres.
 *
 * **The reason is the subtitle.** There is no new field for it: [ListRowState.subtitle] already
 * holds the secondary line, 5d puts the reason there, and a dedicated `reason` property would be
 * the same string in a second slot with no second place to draw it.
 *
 * `Modifier.selectable(enabled = false)` drops the focus target along with the click, so a row in
 * this state takes `Modifier.focusable` and no click handler at all — [onClick] is unreachable
 * rather than guarded. The row keeps the `muted` text it already had from
 * [ListRowState.enabled], and is marked disabled in the semantics tree, so what a screen reader
 * and a test see is what is on screen: a focusable row that does not act.
 */
private fun Modifier.rowBehaviour(
    state: ListRowState,
    interactionSource: MutableInteractionSource,
    onClick: () -> Unit,
): Modifier = when {
    state.enabled -> selectable(
        selected = state.selected,
        interactionSource = null,
        indication = null,
        enabled = true,
        onClick = onClick,
    )

    // `mergeDescendants = true` matches what `selectable` does on the enabled path, and it is
    // what puts the row's title and reason on the focused node. Without it the row is reachable
    // and **anonymous**: a device test read its focused node as `node#150`, so the reason 5d
    // exists to show would not be announced at all.
    state.focusableWhenDisabled -> this
        .semantics(mergeDescendants = true) { disabled() }
        .focusable(enabled = true, interactionSource = interactionSource)

    else -> this
}

/**
 * The [CloudStreamDimens.ListRowDiscSize] (24dp) leading cell and the gap after it.
 *
 * A square cell, not a wrapper around whatever the caller passes: the disc, a checkbox and a
 * provider logo must all occupy the same 24dp so the text after them starts at x=52 in every row
 * of a list.
 */
@Composable
private fun LeadingCell(leading: @Composable () -> Unit) {
    Box(
        modifier = Modifier.size(CloudStreamDimens.ListRowDiscSize),
        contentAlignment = Alignment.Center,
        content = { leading() },
    )
    Spacer(Modifier.width(ListRowDefaults.InnerGap))
}

/**
 * The gap before the trailing slot and the slot itself, centred in the
 * [ListRowDefaults.ContentHeight] content box.
 *
 * The slot's width is intrinsic — it is the one dimension here the 4dp step does not govern,
 * because it comes from a string or an icon rather than from someone writing a number.
 */
@Composable
private fun TrailingCell(trailing: @Composable () -> Unit) {
    Spacer(Modifier.width(ListRowDefaults.InnerGap))
    Box(
        modifier = Modifier.height(ListRowDefaults.ContentHeight),
        contentAlignment = Alignment.Center,
        content = { trailing() },
    )
}

private const val WEIGHT_REMAINING = 1f

// ---- Previews ------------------------------------------------------------------------------
//
// The focus ring cannot be shown in a static preview: `focused` is driven by real focus, which
// only exists in interactive preview or on device. These cover the states that do render — the
// two-line and single-line stacks, the marker cell lit and unlit, disabled, and both slots.

private const val PREVIEW_WIDTH = 248

/** Wide enough for a row plus three 48dp cells on 12dp gaps, and their rings. */
private const val PREVIEW_ACTIONS_WIDTH = 440

@Preview(widthDp = PREVIEW_WIDTH)
@Composable
private fun ListRowStatesPreview() {
    PreviewSurface {
        Column(verticalArrangement = Arrangement.spacedBy(CloudStreamDimens.MinFocusGap)) {
            ListRow(
                state = ListRowState(title = "1080p", subtitle = "Direct · vidsrc"),
                onClick = {},
            )
            ListRow(
                state = ListRowState(
                    title = "720p",
                    subtitle = "Direct · vidsrc",
                    selected = true,
                ),
                onClick = {},
            )
            ListRow(state = ListRowState(title = "Automatic quality"), onClick = {})
            ListRow(
                state = ListRowState(title = "4K", subtitle = "Unavailable", enabled = false),
                onClick = {},
            )
        }
    }
}

@Preview(widthDp = PREVIEW_WIDTH)
@Composable
private fun ListRowSlotsPreview() {
    PreviewSurface {
        Column(verticalArrangement = Arrangement.spacedBy(CloudStreamDimens.MinFocusGap)) {
            ListRow(
                state = ListRowState(title = "English", subtitle = "Subtitles", selected = true),
                onClick = {},
                leading = { PreviewDisc() },
                trailing = { PreviewCount(text = "12") },
            )
            ListRow(
                state = ListRowState(title = "Swedish", subtitle = "Subtitles"),
                onClick = {},
                leading = { PreviewDisc() },
                trailing = { PreviewCount(text = "3") },
            )
        }
    }
}

/**
 * `TV Episodes Downloads` 6d's anatomy: a readout inside the row, three verb cells beside it.
 *
 * Also `TV Library` 5d's disabled-with-a-reason row, which is still a focus stop. The rings are
 * the thing worth looking at here and a static preview cannot draw them; what it *can* show is
 * that the four stops line up on one 48dp band and that nothing moves when the row is disabled.
 */
@Preview(widthDp = PREVIEW_ACTIONS_WIDTH)
@Composable
private fun ListRowActionsPreview() {
    PreviewSurface {
        Column(verticalArrangement = Arrangement.spacedBy(CloudStreamDimens.MinFocusGap)) {
            ListRow(
                state = ListRowState(title = "Ozymandias", subtitle = "S5 E14 · 1.2 GB"),
                onClick = {},
                leading = { PreviewDisc() },
                trailing = { PreviewCount(text = "4.2 MB/S") },
                actions = {
                    PreviewActionCell()
                    PreviewActionCell()
                    PreviewActionCell()
                },
            )
            ListRow(
                state = ListRowState(
                    title = "Your rating",
                    subtitle = "NOT STORED BY LOCAL LISTS",
                    enabled = false,
                    focusableWhenDisabled = true,
                ),
                onClick = {},
                trailing = { PreviewCount(text = "N/A") },
            )
        }
    }
}

/**
 * Stands in for an `IconCell`, which is the real occupant of an action slot — the same
 * [CloudStreamDimens.IconTargetCell] (48dp) square, so the preview measures what 6d measures.
 *
 * It is a plain `Box`, not a glyph: `.claude/rules/icons.md` forbids drawing one, and a preview is
 * not an exception to that.
 */
@Composable
private fun PreviewActionCell() {
    Box(
        Modifier
            .size(CloudStreamDimens.IconTargetCell)
            .background(CloudStreamTheme.colors.raised, CloudStreamTheme.shapes.medium),
    )
}

/** Stands in for whatever a caller puts in the 24dp leading cell. */
@Composable
private fun PreviewDisc() {
    Box(
        Modifier
            .size(CloudStreamDimens.ListRowDiscSize)
            .background(CloudStreamTheme.colors.raised, CloudStreamTheme.shapes.pill),
    )
}

@Composable
private fun PreviewCount(text: String) {
    val style = CloudStreamTheme.typography.labelMono
    val color = CloudStreamTheme.colors.muted
    BasicText(text = text, style = remember(style, color) { style.copy(color = color) })
}
