package com.lagradost.cloudstream4.feature.library.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import com.lagradost.cloudstream4.core.designsystem.panel.SidePanel
import com.lagradost.cloudstream4.core.designsystem.panel.SidePanelDefaults
import com.lagradost.cloudstream4.core.designsystem.rows.ListRow
import com.lagradost.cloudstream4.core.designsystem.rows.ListRowState
import com.lagradost.cloudstream4.core.designsystem.rows.RailHeader
import com.lagradost.cloudstream4.core.strings.UiString
import com.lagradost.cloudstream4.core.strings.generated.resources.Res
import com.lagradost.cloudstream4.core.strings.generated.resources.library_sort_not_kept
import com.lagradost.cloudstream4.core.strings.generated.resources.setup_done
import com.lagradost.cloudstream4.core.strings.generated.resources.sort
import com.lagradost.cloudstream4.core.strings.generated.resources.sort_alphabetical_a
import com.lagradost.cloudstream4.core.strings.generated.resources.sort_alphabetical_z
import com.lagradost.cloudstream4.core.strings.generated.resources.sort_by
import com.lagradost.cloudstream4.core.strings.generated.resources.sort_rating_asc
import com.lagradost.cloudstream4.core.strings.generated.resources.sort_rating_desc
import com.lagradost.cloudstream4.core.strings.generated.resources.sort_release_date_new
import com.lagradost.cloudstream4.core.strings.generated.resources.sort_release_date_old
import com.lagradost.cloudstream4.core.strings.generated.resources.sort_updated_new
import com.lagradost.cloudstream4.core.strings.generated.resources.sort_updated_old
import com.lagradost.cloudstream4.core.strings.resolve
import com.lagradost.cloudstream4.core.theme.CloudStreamDimens
import com.lagradost.cloudstream4.feature.library.LibraryAction
import com.lagradost.cloudstream4.feature.library.LibrarySort
import com.lagradost.cloudstream4.feature.library.LibrarySortCriterion
import com.lagradost.cloudstream4.feature.library.LibraryState
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * 5a's sort button, in the chrome row's right slot: `Sort` over the sort the shown source's cards are in. That is the
 * applied one, the fallback where the source cannot sort by the choice (`DEC-20`), so the button never names an order
 * the grid is not in. It opens 5d.
 */
@Composable
internal fun LibrarySortButton(state: LibraryState, onClick: () -> Unit, modifier: Modifier = Modifier) {
    LibraryChromeButton(
        title = stringResource(Res.string.sort),
        meta = stringResource(state.appliedSort.label()),
        onClick = onClick,
        modifier = modifier,
    )
}

/**
 * 5d: the four criteria, each in its direction. A single choice, so pressing a row applies it at once (`DEC-17`) and
 * the panel stays open: pressing the applied row again turns it the other way round, and the grid behind it moves.
 * `Done` and Back close it. It opens on the applied row.
 *
 * A criterion the shown source keeps nothing for is listed, disabled with its reason, and still a stop, as a source
 * that is not connected is in 5e.
 *
 * Each row names its criterion and direction in one of the app's own eight sort labels, so nothing new is translated
 * for it. The board's second line per row and its `3 OF 4 CRITERIA` note are left out: the disabled row and its reason
 * already say which.
 */
@Composable
internal fun LibrarySortPanelTv(state: LibraryState, onAction: (LibraryAction) -> Unit) {
    val applied = state.appliedSort
    val shown = state.shownSource
    // As it opened, so a flip, which recomposes every row, does not ask for focus again.
    val landOn = remember { applied.criterion }
    SidePanel(
        header = { RailHeader(title = stringResource(Res.string.sort_by)) },
        commit = {
            ListRow(
                state = ListRowState(title = stringResource(Res.string.setup_done)),
                onClick = { onAction(LibraryAction.SortDismissed) },
                modifier = Modifier.fillMaxWidth(),
            )
        },
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(SidePanelDefaults.RowGap),
            // A focused row's ring needs this much room, and the list clips at its own edge.
            contentPadding = PaddingValues(CloudStreamDimens.FocusRingBleed),
        ) {
            items(items = LibrarySortCriterion.entries, key = { it.name }) { criterion ->
                val requester = remember { FocusRequester() }
                // After `SidePanelHost` has moved focus into the panel, which lands on the first row it finds.
                LaunchedEffect(Unit) {
                    if (criterion == landOn) {
                        withFrameNanos { }
                        requester.requestFocusIfAttached()
                    }
                }
                val kept = shown == null || criterion in shown.sorts
                val sort = if (criterion == applied.criterion) applied else LibrarySort.of(criterion)
                ListRow(
                    modifier = Modifier.focusRequester(requester),
                    state = ListRowState(
                        title = stringResource(sort.label()),
                        subtitle = if (kept) null else {
                            UiString.Resource(Res.string.library_sort_not_kept, listOf(shown.name)).resolve()
                        },
                        selected = criterion == applied.criterion,
                        enabled = kept,
                        focusableWhenDisabled = true,
                    ),
                    onClick = { onAction(LibraryAction.SortChosen(criterion)) },
                )
            }
        }
    }
}

/** The app's own words for a sort, one of the eight the old screen lists. */
internal fun LibrarySort.label(): StringResource = when (criterion) {
    LibrarySortCriterion.Updated -> if (descending) Res.string.sort_updated_new else Res.string.sort_updated_old
    LibrarySortCriterion.Title -> if (descending) Res.string.sort_alphabetical_z else Res.string.sort_alphabetical_a
    LibrarySortCriterion.Released ->
        if (descending) Res.string.sort_release_date_new else Res.string.sort_release_date_old
    LibrarySortCriterion.Rating -> if (descending) Res.string.sort_rating_desc else Res.string.sort_rating_asc
}
