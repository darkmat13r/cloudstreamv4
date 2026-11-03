package com.lagradost.cloudstream4.feature.search

import androidx.compose.runtime.Immutable

/**
 * What the search screen shows.
 *
 * [status] is a sealed type rather than a set of booleans, per `.claude/rules/mvi-viewmodel.md`:
 * six flags encode sixty-four states of which about five are reachable, and `when` over a sealed
 * type is what tells you about the branch you forgot.
 *
 * It carries a [SearchContent] rather than a bare result list because the drawn screen has two
 * kinds of content — recent searches before anything is typed (artboard 4c) and per-provider
 * rails after (4a, 4b). See [SearchContent] for why that is one state machine and not two.
 */
@Immutable
data class SearchState(
    /** What is in the field. Echoed back so the field is driven by state, not by itself. */
    val query: String = "",
    /** The chrome row's `Sources 12 / 48`, and the number 8c's no-results copy quotes. */
    val sources: SearchSources = SearchSources(),
    val status: SearchStatus = SearchStatus.Loading,
)

/**
 * What the search reached, with no words in it. The screen turns this into a `UiState` where it can
 * read the locale ([toUiState]), so a ViewModel never holds a display string and a language change
 * reaches a state that is already on screen.
 */
sealed interface SearchStatus {
    /** Nothing typed yet **and no history to show**. With history, it is [Showing] `Recent`. */
    data object Idle : SearchStatus

    data object Loading : SearchStatus

    data class Showing(val content: SearchContent) : SearchStatus

    /** Some sources failed. What arrived is still shown; the shortfall rides the chip row. */
    data class Partial(
        val content: SearchContent,
        val answered: Int,
        val total: Int,
        val detail: String,
    ) : SearchStatus

    /**
     * Every source answered and none carries it: artboard 8c of `design/TV Search Suggestions.dc.html`.
     * [queried] is the number both its sentence and its status line quote.
     */
    data class NoResults(val queried: Int) : SearchStatus

    /** Every source failed. [detail] is the machine-readable line. */
    data class Failed(val detail: String) : SearchStatus

    data object Offline : SearchStatus
}

/** Everything the user can do. */
sealed interface SearchAction {
    /**
     * A keystroke. Debounced, and it runs the *quick* search.
     *
     * The echo of the query into state is immediate; only the request is delayed.
     */
    data class QueryChanged(val query: String) : SearchAction

    /** Enter, or the search button: runs the full search without waiting for the debounce. */
    data object Submit : SearchAction

    /** From a failure state's action, which `StateAction` guarantees exists and is focusable. */
    data object Retry : SearchAction

    /** The first-run prompt's button. Becomes [SearchEffect.BrowseHome]; the ViewModel does not navigate. */
    data object BrowseRequested : SearchAction

    /** A result was activated. Becomes [SearchEffect.OpenResult]; the ViewModel does not navigate. */
    data class ResultActivated(val id: SearchResultId) : SearchAction

    /** The field was cleared. Distinct from `QueryChanged("")` so it can cancel in-flight work. */
    data object Cleared : SearchAction

    /**
     * A recent search was activated: artboard 4c's rows.
     *
     * Carries the query rather than an index, because the list can be re-read between the render
     * and the press — a remove on another row shifts every index after it.
     */
    data class RecentActivated(val query: String) : SearchAction

    /** One row's remove affordance. */
    data class RecentRemoved(val query: String) : SearchAction

    /** `Clear all searches`, the row pinned at the foot of 4c's list. */
    data object RecentCleared : SearchAction
}

/**
 * One-shot events. Delivered through `EffectContainer`, observed with `ObserveEffect`.
 *
 * Navigation is an effect rather than state because it must happen exactly once: state replayed
 * after a configuration change would open the result again.
 */
sealed interface SearchEffect {
    data class OpenResult(val id: SearchResultId) : SearchEffect
    data object BrowseHome : SearchEffect
}
