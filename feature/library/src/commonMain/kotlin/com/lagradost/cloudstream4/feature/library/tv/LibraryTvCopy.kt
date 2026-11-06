package com.lagradost.cloudstream4.feature.library.tv

import androidx.compose.runtime.Composable
import com.lagradost.cloudstream4.core.designsystem.state.StateAction
import com.lagradost.cloudstream4.core.designsystem.state.StatePrompt
import com.lagradost.cloudstream4.core.designsystem.state.UiState
import com.lagradost.cloudstream4.core.strings.generated.resources.Res
import com.lagradost.cloudstream4.core.strings.generated.resources.empty_library_logged_in_message
import com.lagradost.cloudstream4.core.strings.generated.resources.empty_library_no_accounts_message
import com.lagradost.cloudstream4.core.strings.generated.resources.no_data
import com.lagradost.cloudstream4.core.strings.generated.resources.sort_clear
import com.lagradost.cloudstream4.core.strings.generated.resources.title_search
import com.lagradost.cloudstream4.feature.library.LibraryAction
import com.lagradost.cloudstream4.feature.library.LibraryCard
import com.lagradost.cloudstream4.feature.library.LibraryState
import com.lagradost.cloudstream4.feature.library.LibraryStatus
import com.lagradost.cloudstream4.feature.library.LibraryTab
import com.lagradost.cloudstream4.feature.library.label
import com.lagradost.cloudstream4.feature.library.toUiState
import org.jetbrains.compose.resources.stringResource

/**
 * The TV screen's states. Loading, content and failure are the shared copy; only the empty page differs,
 * because 5c says *an empty page is not an empty Library* and the action depends on what else is there.
 *
 * **This adds no string keys**, so `classes-jar.api` gains no `R` field. It costs 5c's own sentences:
 * *"Nothing on hold"* is the list's name here, the board's second sentence needs a fact nothing records
 * (when a title was last opened), and *"Go to Watching"* is the list's name on the button.
 */
@Composable
internal fun LibraryState.toTvUiState(onAction: (LibraryAction) -> Unit): UiState<List<LibraryCard>> {
    val shared = status.toUiState(onAction)
    if (status != LibraryStatus.Empty) return shared
    val here = countOf(tab)
    val elsewhere = tvTabs().firstOrNull { it != tab && (countOf(it) ?: 0) > 0 }
    return UiState.Empty(
        when {
            // The list has titles and the filter hides them all, so the filter is what is empty.
            here != null && here > 0 -> StatePrompt(
                headline = stringResource(Res.string.no_data),
                body = filter.trim(),
                action = StateAction(stringResource(Res.string.sort_clear)) {
                    onAction(LibraryAction.FilterChanged(""))
                },
            )

            elsewhere != null -> StatePrompt(
                headline = tab.label(),
                body = stringResource(Res.string.empty_library_logged_in_message),
                action = StateAction(elsewhere.label()) { onAction(LibraryAction.TabSelected(elsewhere)) },
            )

            else -> StatePrompt(
                headline = tab.label(),
                body = stringResource(Res.string.empty_library_no_accounts_message),
                action = StateAction(stringResource(Res.string.title_search)) {
                    onAction(LibraryAction.SearchRequested)
                },
            )
        },
    )
}

/**
 * The tabs this TV screen shows: the tracking service's own lists where one is shown, else the store's. A service whose
 * lists are not read yet, or could not be, has none: the store's would be the local list's, under the service's name.
 */
internal fun LibraryState.tvTabs(): List<LibraryTab> =
    syncedTabs ?: if (shownSource?.local == false) emptyList() else LibraryTvTabs

/** How many titles [tab] holds, from what was read, or null before a read. A list with nothing in it is 0. */
internal fun LibraryState.countOf(tab: LibraryTab): Int? {
    if (totalTitles == null) return null
    return when (tab) {
        is LibraryTab.Bucket -> counts[tab.bucket] ?: 0
        is LibraryTab.Synced -> syncedCounts[tab.key] ?: 0
        LibraryTab.ContinueWatching -> null
    }
}
