package com.lagradost.cloudstream4.core.designsystem.rows

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.tooling.preview.Preview
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
 * @param state what to render. See [ListRowState].
 * @param onClick invoked on click or D-pad centre. Not invoked when `state.enabled` is false.
 * @param modifier applied to the row. Do not put a height on it — the 48dp is the design.
 * @param leading an optional [CloudStreamDimens.ListRowDiscSize] (24dp) square cell before the
 *   text: the disc, a checkbox, a provider icon. The cell is only laid out when non-null.
 * @param trailing an optional slot after the text, centred in the 32dp content box: a value, a
 *   chevron, a switch. Give it an intrinsic width; the text stack takes the remaining space.
 */
@Composable
fun ListRow(
    state: ListRowState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier = modifier
            .height(CloudStreamDimens.ListRowHeight)
            .onFocusChanged { focused = it.isFocused }
            .selectable(
                selected = state.selected,
                interactionSource = null,
                indication = null,
                enabled = state.enabled,
                onClick = onClick,
            )
            .tvFocusRing(focused = focused, shape = CloudStreamTheme.shapes.medium)
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
        )
        if (trailing != null) TrailingCell(trailing)
    }
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
