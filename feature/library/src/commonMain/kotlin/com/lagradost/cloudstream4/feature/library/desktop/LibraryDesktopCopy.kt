package com.lagradost.cloudstream4.feature.library.desktop

import androidx.compose.runtime.Composable
import com.lagradost.cloudstream4.core.designsystem.state.StateAction
import com.lagradost.cloudstream4.core.designsystem.state.StatePrompt
import com.lagradost.cloudstream4.core.designsystem.state.UiState
import com.lagradost.cloudstream4.core.strings.UiString
import com.lagradost.cloudstream4.core.strings.generated.resources.Res
import com.lagradost.cloudstream4.core.strings.generated.resources.library_go_to_list
import com.lagradost.cloudstream4.core.strings.generated.resources.library_list_empty_body
import com.lagradost.cloudstream4.core.strings.generated.resources.library_list_empty_headline
import com.lagradost.cloudstream4.core.strings.generated.resources.no_data
import com.lagradost.cloudstream4.core.strings.generated.resources.sort_clear
import com.lagradost.cloudstream4.core.strings.generated.resources.title_search
import com.lagradost.cloudstream4.core.strings.resolve
import com.lagradost.cloudstream4.feature.library.LibraryAction
import com.lagradost.cloudstream4.feature.library.LibraryCard
import com.lagradost.cloudstream4.feature.library.LibraryState
import com.lagradost.cloudstream4.feature.library.LibraryStatus
import com.lagradost.cloudstream4.feature.library.LibraryTab
import com.lagradost.cloudstream4.feature.library.label
import com.lagradost.cloudstream4.feature.library.offlineBody
import com.lagradost.cloudstream4.feature.library.toUiState
import com.lagradost.cloudstream4.feature.library.tv.countOf
import org.jetbrains.compose.resources.stringResource

/**
 * The lists this window offers as buttons: a tracking service's own where one is shown, else Continue watching and
 * the store's lists. A service whose lists are not read yet has none, as on TV: the store's would be the local list's,
 * under the service's name.
 */
internal fun LibraryState.desktopTabs(): List<LibraryTab> =
    syncedTabs ?: if (shownSource?.local == false) emptyList() else LibraryTab.all

/**
 * The window's states. Loading, content and failure are the shared copy. The empty page is L3's: it says what lands
 * in this list and offers one that has something, in `I18N-15`'s words, which carry the list's name.
 */
@Composable
internal fun LibraryState.toDesktopUiState(onAction: (LibraryAction) -> Unit): UiState<List<LibraryCard>> {
    val shared = status.toUiState(onAction)
    if (status == LibraryStatus.Offline && shared is UiState.Offline) {
        // The service by name: it is what needs the connection, and the local list does not.
        val name = shownSource?.name ?: return shared
        return UiState.Offline(shared.prompt.copy(body = offlineBody(name)))
    }
    if (status != LibraryStatus.Empty) return shared
    val here = countOf(tab)
    val name = tab.label()
    val elsewhere = desktopTabs().firstOrNull { it != tab && (countOf(it) ?: 0) > 0 }
    return UiState.Empty(
        when {
            // The list has titles and the filter hides them all, so the filter is what is empty.
            here != null && here > 0 && filter.isNotBlank() -> StatePrompt(
                headline = stringResource(Res.string.no_data),
                body = filter.trim(),
                action = StateAction(stringResource(Res.string.sort_clear)) {
                    onAction(LibraryAction.FilterChanged(""))
                },
            )

            else -> StatePrompt(
                headline = UiString.Resource(Res.string.library_list_empty_headline, listOf(name)).resolve(),
                body = UiString.Resource(Res.string.library_list_empty_body, listOf(name)).resolve(),
                action = if (elsewhere != null) {
                    val other = elsewhere.label()
                    StateAction(UiString.Resource(Res.string.library_go_to_list, listOf(other)).resolve()) {
                        onAction(LibraryAction.TabSelected(elsewhere))
                    }
                } else {
                    // Nothing kept anywhere: the one thing to offer is the screen that finds something.
                    StateAction(stringResource(Res.string.title_search)) { onAction(LibraryAction.SearchRequested) }
                },
            )
        },
    )
}
