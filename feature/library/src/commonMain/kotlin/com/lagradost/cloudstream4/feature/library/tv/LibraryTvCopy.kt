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
import com.lagradost.cloudstream4.feature.library.LibraryBucket
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
    val here = (tab as? LibraryTab.Bucket)?.bucket
    val elsewhere = LibraryTvTabs.firstOrNull { it != tab && it.heldIn(counts) }
    return UiState.Empty(
        when {
            // The list has titles and the filter hides them all, so the filter is what is empty.
            here != null && (counts[here] ?: 0) > 0 -> StatePrompt(
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

private fun LibraryTab.heldIn(counts: Map<LibraryBucket, Int>): Boolean =
    this is LibraryTab.Bucket && (counts[bucket] ?: 0) > 0
