package com.lagradost.cloudstream4.feature.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lagradost.cloudstream4.compose.ActionHandler
import com.lagradost.cloudstream4.compose.DebounceQuery
import com.lagradost.cloudstream4.compose.DefaultEffectContainer
import com.lagradost.cloudstream4.compose.DefaultStateContainer
import com.lagradost.cloudstream4.compose.EffectContainer
import com.lagradost.cloudstream4.compose.SingleActiveQuery
import com.lagradost.cloudstream4.compose.StateContainer
import com.lagradost.cloudstream4.core.designsystem.state.UiState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * The search screen's state machine. No Android, no display strings, no navigation.
 *
 * Implements the three containers **by delegation** rather than inheriting a base, per
 * `.claude/rules/mvi-viewmodel.md` — there is no `BaseViewModel` here and one should not be added,
 * because a base class drags its dependencies in and the `jvm()` canary rejects them.
 *
 * Two behaviours it exists to get right, both of which the old `SearchViewModel` did not:
 *
 * - **Typing does not spam providers.** `QueryChanged` echoes into state immediately and feeds
 *   [DebounceQuery], which is `:core:ui`'s 200 ms debounce with `distinctUntilChanged`. Only the
 *   settled query reaches [SearchSource.quickSearch].
 * - **A late reply cannot overwrite a newer one.** Every request goes through [SingleActiveQuery],
 *   which cancels and joins the previous job before starting the next, so results arrive in query
 *   order regardless of which provider is slow.
 *
 * [dispatcher] is injected rather than hardcoded to `Dispatchers.IO`, so tests drive it.
 */
class SearchViewModel(
    private val source: SearchSource,
    private val prompts: SearchPrompts,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel(),
    StateContainer<SearchState> by DefaultStateContainer(SearchState()),
    EffectContainer<SearchEffect> by DefaultEffectContainer(),
    ActionHandler<SearchAction> {

    private val debounce = DebounceQuery()
    private val inFlight = SingleActiveQuery(dispatcher)

    init {
        // Start idle rather than Loading: nothing is in flight before anything is typed.
        updateState { SearchState.idle(prompts.idle()) }
        viewModelScope.launch {
            debounce.launch { settled -> runSearch(settled, quick = true) }
        }
    }

    override fun onAction(action: SearchAction) {
        when (action) {
            is SearchAction.QueryChanged -> {
                updateState { copy(query = action.query) }
                if (action.query.isBlank()) {
                    showIdle()
                } else {
                    updateState { copy(results = UiState.Loading) }
                    viewModelScope.launch { debounce.emit(action.query) }
                }
            }

            SearchAction.Submit -> submitCurrentQuery()
            SearchAction.Retry -> submitCurrentQuery()

            SearchAction.Cleared -> {
                updateState { copy(query = "") }
                showIdle()
            }

            is SearchAction.ResultActivated ->
                viewModelScope.launch { postEffect { SearchEffect.OpenResult(action.id) } }
        }
    }

    private fun submitCurrentQuery() {
        val query = state.value.query
        if (query.isBlank()) {
            showIdle()
            return
        }
        updateState { copy(results = UiState.Loading) }
        viewModelScope.launch { runSearch(query, quick = false) }
    }

    /** Clears any in-flight work: an empty field must not be overwritten by an earlier reply. */
    private fun showIdle() {
        viewModelScope.launch { inFlight.launch { } }
        updateState { copy(results = UiState.Empty(prompts.idle())) }
    }

    private suspend fun runSearch(query: String, quick: Boolean) {
        inFlight.launch {
            val outcome = if (quick) source.quickSearch(query) else source.search(query)
            updateState { copy(results = outcome.toUiState(query)) }
        }
    }

    private fun SearchOutcome.toUiState(query: String): UiState<List<SearchResultItem>> =
        when (this) {
            is SearchOutcome.Complete ->
                if (items.isEmpty()) {
                    UiState.Empty(prompts.noResults(query) { onAction(SearchAction.Retry) })
                } else {
                    UiState.Content(items)
                }

            is SearchOutcome.Partial ->
                // Content that arrived is shown; the shortfall rides the chip row as a notice.
                // Deliberately not Error: the results are usable, and replacing them with a failure
                // band is what `UiState`'s KDoc says Partial exists to prevent.
                UiState.Partial(
                    value = items,
                    notice = prompts.partial(answered, total, detail) {
                        onAction(SearchAction.Retry)
                    },
                )

            is SearchOutcome.Failed ->
                UiState.Error(
                    prompt = prompts.failed(detail) { onAction(SearchAction.Retry) },
                    detail = detail,
                )

            SearchOutcome.Offline ->
                UiState.Offline(prompts.offline { onAction(SearchAction.Retry) })
        }
}
