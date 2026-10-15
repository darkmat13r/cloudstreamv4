package com.lagradost.cloudstream4.feature.search

import androidx.compose.runtime.Immutable
import com.lagradost.cloudstream4.core.designsystem.state.FailureNotice
import com.lagradost.cloudstream4.core.designsystem.state.StatePrompt
import com.lagradost.cloudstream4.core.designsystem.state.UiState

/**
 * What the search screen shows.
 *
 * [results] is a [UiState] rather than a set of booleans, per `.claude/rules/mvi-viewmodel.md`:
 * six flags encode sixty-four states of which about five are reachable, and `when` over a sealed
 * type is what tells you about the branch you forgot.
 */
@Immutable
data class SearchState(
    /** What is in the field. Echoed back so the field is driven by state, not by itself. */
    val query: String = "",
    val results: UiState<List<SearchResultItem>> = UiState.Loading,
) {
    companion object {
        /**
         * Before anything has been typed.
         *
         * [UiState.Empty] and not [UiState.Loading], because nothing is in flight — a skeleton on an
         * untouched search screen is a lie about work being done.
         */
        fun idle(prompt: StatePrompt) = SearchState(query = "", results = UiState.Empty(prompt))
    }
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

    /** A result was activated. Becomes [SearchEffect.OpenResult]; the ViewModel does not navigate. */
    data class ResultActivated(val id: SearchResultId) : SearchAction

    /** The field was cleared. Distinct from `QueryChanged("")` so it can cancel in-flight work. */
    data object Cleared : SearchAction
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
    /** Nothing typed yet. The action is usually "browse" rather than "retry". */
    fun idle(): StatePrompt

    /** The search succeeded and matched nothing. Not a failure. */
    fun noResults(query: String, onRetry: () -> Unit): StatePrompt

    /** Every provider failed. [detail] is the machine-readable line. */
    fun failed(detail: String, onRetry: () -> Unit): StatePrompt

    /** No network. */
    fun offline(onRetry: () -> Unit): StatePrompt

    /** Some providers failed; content is still usable. Rides a chip row, so it has no body copy. */
    fun partial(answered: Int, total: Int, detail: String, onRetry: () -> Unit): FailureNotice
}
