package com.lagradost.cloudstream4.feature.search

import androidx.compose.runtime.Immutable
import com.lagradost.cloudstream4.core.designsystem.state.FailureNotice
import com.lagradost.cloudstream4.core.designsystem.state.UiState

/**
 * What the search screen shows.
 *
 * [content] is a [UiState] rather than a set of booleans, per `.claude/rules/mvi-viewmodel.md`:
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
    val content: UiState<SearchContent> = UiState.Loading,
)

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
}

/**
 * The resolved copy for every non-content state, supplied from outside.
 *
 * This is the awkward seam between two rules, and it is worth saying why it exists rather than
 * leaving the next reader to wonder.
 *
 * `:core:designsystem`'s `StatePrompt` and `FailureNotice` hold **already-resolved display strings**
 * and a `StateAction` lambda — that module "never holds display strings" and takes them from the
 * caller. But `docs/plans/kmp-migration.md` Phase 7 says a ViewModel must expose `UiString`, **never**
 * a resource id, and a ViewModel that resolved strings itself would need a platform string loader in
 * `commonMain`.
 *
 * Injecting them satisfies both: the ViewModel holds `UiState` as the MVI rule requires, and the
 * strings are resolved by whoever constructs the ViewModel — the Android screen from
 * `stringResource(...)`, a test from literals. Nothing in this module is user-facing text.
 *
 * Every method takes the retry lambda the ViewModel will supply, because `StateAction` is a
 * **required** field of both types: the audit behind it found 18 of 20 screens whose failure state
 * had no action or an unfocusable one, which on a TV is a dead end with no way out but Back.
 */
interface SearchPrompts {

    /**
     * Nothing typed yet **and no history to show**. The action is usually "browse", not "retry".
     *
     * With history, the blank query is content — `SearchContent.Recent`, artboard 4c — so this is
     * the first-run case rather than the normal one.
     */
    fun idle(): UiState.Empty

    /**
     * The search succeeded and matched nothing: artboard **8c** of
     * `design/TV Search Suggestions.dc.html`. (Two clusters are numbered 8 — the onboarding file
     * has its own `8a`–`8d` — so an 8-series id without its file name is ambiguous.)
     *
     * [queried] is how many sources were asked, which 8c's copy states in words ("All nine
     * selected sources answered and none of them carry it") *and* in the mono status line
     * (`9 QUERIED · 9 ANSWERED · 0 FAILED · 0 RESULTS`). Both come from this one call so they
     * cannot disagree — the defect the suggestions cluster was re-solved to fix.
     *
     * Returns the state rather than a bare prompt so the status line travels with the copy that
     * quotes the same number. `UiState.Empty.detail` exists for this.
     */
    fun noResults(query: String, queried: Int, onRetry: () -> Unit): UiState.Empty

    /** Every provider failed. The `detail` on the returned state is the machine-readable line. */
    fun failed(detail: String, onRetry: () -> Unit): UiState.Error

    /** No network. */
    fun offline(onRetry: () -> Unit): UiState.Offline

    /** Some providers failed; content is still usable. Rides a chip row, so it has no body copy. */
    fun partial(answered: Int, total: Int, detail: String, onRetry: () -> Unit): FailureNotice
}
