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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * The search screen's state machine. No Android, no display strings, no navigation.
 *
 * Implements the three containers **by delegation** rather than inheriting a base, per
 * `.claude/rules/mvi-viewmodel.md` — there is no `BaseViewModel` here and one should not be added,
 * because a base class drags its dependencies in and the `jvm()` canary rejects them.
 *
 * Three behaviours it exists to get right, none of which the old `SearchViewModel` did:
 *
 * - **Typing does not spam providers.** `QueryChanged` echoes into state immediately and feeds
 *   [DebounceQuery], which is `:core:ui`'s 200 ms debounce with `distinctUntilChanged`. Only the
 *   settled query reaches [SearchSource.quickSearch].
 * - **A late reply cannot overwrite a newer one.** Every request goes through [SingleActiveQuery],
 *   which cancels and joins the previous job before starting the next, so results arrive in query
 *   order regardless of which provider is slow.
 * - **A blank query is content, not a void.** Artboard 4c draws recent searches there; the old
 *   screen drew nothing at all for a new user and never showed the `searchedAt` it had stored.
 *
 * [dispatcher] is injected rather than hardcoded to `Dispatchers.IO`, so tests drive it.
 *
 * ## Why [scope] is a parameter, and why that is not a rule violation
 *
 * `.claude/rules/mvi-viewmodel.md` says "`viewModelScope` only, never `GlobalScope`", and the
 * default here **is** `viewModelScope`. It has to be overridable because `viewModelScope` is
 * `Dispatchers.Main.immediate`, and on desktop JVM `Dispatchers.Main` has no provider: the app dies
 * with *"Module with the Main dispatcher is missing"* the moment this class is constructed.
 * Supplying one means adding `kotlinx-coroutines-swing`, which is a dependency decision
 * (`.claude/rules/dependency-security.md`); a host that already owns a scope can simply pass it.
 *
 * The rule's target is an unowned scope that outlives its screen. A scope the host owns and
 * cancels is the opposite of that, so this honours it rather than evading it.
 */
class SearchViewModel(
    private val source: SearchSource,
    private val history: SearchHistoryStore,
    private val prompts: SearchPrompts,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val scope: CoroutineScope? = null,
) : ViewModel(),
    StateContainer<SearchState> by DefaultStateContainer(SearchState()),
    EffectContainer<SearchEffect> by DefaultEffectContainer(),
    ActionHandler<SearchAction> {

    private val debounce = DebounceQuery()
    private val inFlight = SingleActiveQuery(dispatcher)

    /** [scope] when the host supplied one, `viewModelScope` otherwise. */
    private val work: CoroutineScope get() = scope ?: viewModelScope

    init {
        // Start on the idle prompt rather than Loading: the history read is fast and local, and a
        // skeleton that flashes for one frame reads as a glitch.
        updateState { copy(content = prompts.idle()) }
        work.launch { refreshIdle() }
        work.launch {
            val sources = source.sources()
            updateState { copy(sources = sources) }
        }
        work.launch {
            debounce.launch { settled -> runSearch(settled, quick = true) }
        }
    }

    override fun onAction(action: SearchAction) {
        when (action) {
            is SearchAction.QueryChanged -> onQueryChanged(action.query)

            SearchAction.Submit -> submitCurrentQuery()
            SearchAction.Retry -> submitCurrentQuery()

            SearchAction.Cleared -> {
                updateState { copy(query = "") }
                showIdle()
            }

            is SearchAction.ResultActivated ->
                work.launch { postEffect { SearchEffect.OpenResult(action.id) } }

            is SearchAction.RecentActivated -> {
                updateState { copy(query = action.query, content = UiState.Loading) }
                work.launch { runSearch(action.query, quick = false) }
            }

            is SearchAction.RecentRemoved -> work.launch {
                history.remove(action.query)
                refreshIdle()
            }

            SearchAction.RecentCleared -> work.launch {
                history.clear()
                refreshIdle()
            }
        }
    }

    private fun onQueryChanged(query: String) {
        updateState { copy(query = query) }
        if (query.isBlank()) {
            showIdle()
        } else {
            updateState { copy(content = UiState.Loading) }
            work.launch { debounce.emit(query) }
        }
    }

    private fun submitCurrentQuery() {
        val query = state.value.query
        if (query.isBlank()) {
            showIdle()
            return
        }
        updateState { copy(content = UiState.Loading) }
        work.launch { runSearch(query, quick = false) }
    }

    /** Clears any in-flight work: an empty field must not be overwritten by an earlier reply. */
    private fun showIdle() {
        work.launch { inFlight.launch { } }
        updateState { copy(content = prompts.idle()) }
        work.launch { refreshIdle() }
    }

    /**
     * Re-reads the stored history and shows it, but **only while the field is still blank**.
     *
     * The read is asynchronous and the user keeps typing during it, so the guard is what stops a
     * history list landing on top of a search that started in the meantime.
     */
    private suspend fun refreshIdle() {
        val entries = history.recent()
        updateState {
            if (query.isNotBlank()) {
                this
            } else if (entries.isEmpty()) {
                copy(content = prompts.idle())
            } else {
                copy(content = UiState.Content(SearchContent.Recent(entries)))
            }
        }
    }

    private suspend fun runSearch(query: String, quick: Boolean) {
        inFlight.launch {
            val outcome = if (quick) source.quickSearch(query) else source.search(query)
            updateState { copy(content = outcome.toUiState(query, sources.selected)) }
        }
    }

    private fun SearchOutcome.toUiState(query: String, queried: Int): UiState<SearchContent> =
        when (this) {
            is SearchOutcome.Complete ->
                if (items.isEmpty()) {
                    prompts.noResults(query, queried) { onAction(SearchAction.Retry) }
                } else {
                    UiState.Content(SearchContent.Results(items.groupByProvider()))
                }

            is SearchOutcome.Partial ->
                // Content that arrived is shown; the shortfall rides the chip row as a notice.
                // Deliberately not Error: the results are usable, and replacing them with a failure
                // band is what `UiState`'s KDoc says Partial exists to prevent.
                UiState.Partial(
                    value = SearchContent.Results(items.groupByProvider()),
                    notice = prompts.partial(answered, total, detail) {
                        onAction(SearchAction.Retry)
                    },
                )

            is SearchOutcome.Failed -> prompts.failed(detail) { onAction(SearchAction.Retry) }

            SearchOutcome.Offline -> prompts.offline { onAction(SearchAction.Retry) }
        }
}
