package com.lagradost.cloudstream4.feature.search.tv

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import com.lagradost.cloudstream4.core.designsystem.icon.CloudStreamIcons
import com.lagradost.cloudstream4.core.designsystem.state.FailureBanner
import com.lagradost.cloudstream4.core.designsystem.state.FailureNotice
import com.lagradost.cloudstream4.core.designsystem.state.StateAction
import com.lagradost.cloudstream4.core.designsystem.state.StateActionButton
import com.lagradost.cloudstream4.core.designsystem.target.Chip
import com.lagradost.cloudstream4.core.designsystem.target.ChipRow
import com.lagradost.cloudstream4.core.designsystem.target.ChromeButton
import com.lagradost.cloudstream4.core.designsystem.target.TextField
import com.lagradost.cloudstream4.core.strings.generated.resources.Res
import com.lagradost.cloudstream4.core.strings.generated.resources.search_tv_field_placeholder
import com.lagradost.cloudstream4.core.strings.generated.resources.search_tv_sources_label
import com.lagradost.cloudstream4.core.theme.CloudStreamDimens
import com.lagradost.cloudstream4.feature.search.SearchAction
import com.lagradost.cloudstream4.feature.search.SearchSources
import com.lagradost.cloudstream4.feature.search.SearchTypeFilter
import com.lagradost.cloudstream4.feature.search.SearchTypeFilters
import com.lagradost.cloudstream4.feature.search.sourcesCountText
import com.lagradost.cloudstream4.feature.search.typeFilterLabel
import org.jetbrains.compose.resources.stringResource

/**
 * The chrome row: the query field, then `Sources`, which says how many sources answer and opens 4d.
 *
 * Artboards 4a–4c, y56…152 on the artboard, which is `ChromeRowTop` (28dp) to `ChromeRowBottom`
 * (76dp) with `ChromeRowHeight` (48dp) between them.
 *
 * The field ends at the board's x and `Sources` spans its 212dp, with the gap between them left empty:
 * the board's icon button there was voice search, which the owner dropped, and it is not built. So the
 * space is [SearchScreenTvDefaults.DroppedIconSlot], kept rather than given to the field.
 *
 * @param fieldDown where Down from the field goes. See [SearchScreenTv].
 * @param sourcesDown where Down from `Sources` goes; [FocusRequester.Default] is by geometry.
 * @param onLeftEdge what Left from the field does when nothing here is to its left. See `TextField`.
 */
@Composable
internal fun SearchChromeRow(
    query: String,
    sources: SearchSources,
    onAction: (SearchAction) -> Unit,
    fieldDown: FocusRequester,
    sourcesDown: FocusRequester,
    sourcesFocus: FocusRequester,
    fieldFocus: FocusRequester,
    stops: SearchStops,
    onLeftEdge: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.height(CloudStreamDimens.ChromeRowHeight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextField(
            value = query,
            onValueChange = { onAction(SearchAction.QueryChanged(it)) },
            placeholder = stringResource(Res.string.search_tv_field_placeholder),
            modifier = Modifier
                .weight(FIELD_WEIGHT)
                .focusRequester(fieldFocus)
                .searchStop(stops)
                .focusProperties { down = fieldDown },
            imeAction = ImeAction.Search,
            onSubmit = { onAction(SearchAction.Submit) },
            keyboardOnOk = true,
            leadingIcon = CloudStreamIcons.Search,
            onLeftEdge = onLeftEdge,
        )
        Spacer(Modifier.width(SearchScreenTvDefaults.DroppedIconSlot))
        ChromeButton(
            label = stringResource(Res.string.search_tv_sources_label),
            count = sourcesCountText(sources),
            onClick = { onAction(SearchAction.SourcePanelOpened) },
            modifier = Modifier
                .width(SearchScreenTvDefaults.SourcesButtonWidth)
                .focusRequester(sourcesFocus)
                .searchStop(stops)
                .focusProperties {
                    down = sourcesDown
                    right = FocusRequester.Cancel
                },
        )
    }
}

/**
 * The chip row: the type chips at the start and, at the end, whatever the state's action is.
 *
 * Artboards 4a, 4b, 8c, y176…272: `ChipRowTop` to `ChipRowBottom`, one `ChipFocusCell` tall. The row is
 * mounted even when there is nothing in it, because it is a band of the chassis: a row that appears and
 * disappears moves the content region under the user's focus.
 *
 * ## The chips scroll
 *
 * `ChipRow` refuses to, because a clipped edge cuts the ring. The board draws seven chips with room and
 * fewer beside the failure group, and four on 4b are all that fit: the old screen has ten, and a filter
 * that cannot be reached cannot be turned off. So this row scrolls, with the same answer the rails give
 * to clipping: padding inside the viewport, so a ring has room at the viewport's edge. The cross axis
 * does not clip, because a ring stays inside the cell's height.
 *
 * @param action the action of a state that has one here: 8c's. It takes the first focus when it appears,
 *   which is the job `StateView` does for an action it draws itself.
 * @param trailingFocus attached to whichever of [notice] or [action] is showing.
 * @param firstChipFocus attached to the first chip.
 * @param contentDown where Down from anything in this row goes: the first card of the first row.
 */
@Composable
internal fun SearchChipRow(
    filters: SearchTypeFilters,
    notice: FailureNotice?,
    action: StateAction?,
    onAction: (SearchAction) -> Unit,
    trailingFocus: FocusRequester,
    firstChipFocus: FocusRequester,
    contentDown: FocusRequester,
    stops: SearchStops,
    modifier: Modifier = Modifier,
    actionTakesFocus: Boolean = true,
) {
    val hasTrailing = notice != null || action != null
    Row(
        modifier = modifier.fillMaxWidth().height(CloudStreamDimens.ChipFocusCell),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // `BUG-65`: the design system's chip row, so these chips are spaced like every other TV chip row.
        ChipRow(
            modifier = Modifier
                .weight(CHIPS_WEIGHT)
                .horizontalScroll(rememberScrollState())
                .padding(
                    start = SearchScreenTvDefaults.ContentInsetStart,
                    end = CloudStreamDimens.ChipRowSpacing,
                ),
        ) {
            filters.available.forEach { type ->
                TypeChip(
                    type = type,
                    selected = type in filters.selected,
                    onAction = onAction,
                    contentDown = contentDown,
                    focus = firstChipFocus.takeIf { type == filters.available.first() },
                    stops = stops,
                    stopsRight = !hasTrailing && type == filters.available.last(),
                )
            }
        }
        val trailing = Modifier
            .padding(end = SearchScreenTvDefaults.ContentInsetEnd)
            .focusRequester(trailingFocus)
            .searchStop(stops)
            .focusProperties {
                down = contentDown
                right = FocusRequester.Cancel
            }
        if (notice != null) {
            FailureBanner(notice = notice, modifier = trailing)
        } else if (action != null) {
            StateActionButton(action = action, modifier = trailing)
            // 8c's action takes the focus; 8b's leaves it in the field, where the user is still typing.
            if (actionTakesFocus) LaunchedEffect(Unit) { trailingFocus.requestFocus() }
        }
    }
}

@Composable
private fun TypeChip(
    type: SearchTypeFilter,
    selected: Boolean,
    onAction: (SearchAction) -> Unit,
    contentDown: FocusRequester,
    focus: FocusRequester?,
    stops: SearchStops,
    stopsRight: Boolean,
) {
    val requester = if (focus != null) Modifier.focusRequester(focus) else Modifier
    Chip(
        label = typeFilterLabel(type),
        selected = selected,
        onClick = { onAction(SearchAction.TypeFilterToggled(type)) },
        modifier = requester.searchStop(stops).focusProperties {
            down = contentDown
            if (stopsRight) right = FocusRequester.Cancel
        },
    )
}

private const val FIELD_WEIGHT = 1f
private const val CHIPS_WEIGHT = 1f
