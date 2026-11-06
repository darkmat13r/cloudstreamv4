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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
 *   [DebounceQuery], which is `:core:ui`'s 200 ms debounce. Only the
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
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val scope: CoroutineScope? = null,
) : ViewModel(),
    StateContainer<SearchState> by DefaultStateContainer(SearchState()),
    EffectContainer<SearchEffect> by DefaultEffectContainer(),
    ActionHandler<SearchAction> {

    private val debounce = DebounceQuery()
    private val inFlight = SingleActiveQuery(dispatcher)
    private val filterWrites = Mutex()

    /** The last page loaded per provider, for the rail's card. A new search starts them again. */
    private val pages = HashMap<String, Int>()
    private val loadingMore = HashSet<String>()

    /**
     * The query a full search has already started for, until the next keystroke.
     *
     * A debounced quick search can still be pending when Enter arrives (an IME can commit text and
     * press its action in one gesture), and [SingleActiveQuery] would let it cancel the full search
     * and replace it with the quick subset. Read and written on [work]'s dispatcher.
     */
    private var submitted: String? = null

    /** [scope] when the host supplied one, `viewModelScope` otherwise. */
    private val work: CoroutineScope get() = scope ?: viewModelScope

    init {
        // Start on the idle prompt rather than Loading: the history read is fast and local, and a
        // skeleton that flashes for one frame reads as a glitch.
        updateState { copy(status = SearchStatus.Idle) }
        work.launch { refreshIdle() }
        work.launch { refreshSources() }
        work.launch {
            // What the field says now, and not what settled: keystrokes reach the debounce from separate coroutines, so on
            // a scope with more than one thread the last to arrive can be an older one. Comparing the two left that
            // search unstarted, and the screen on loading for good (`BUG-25`).
            debounce.launch {
                val current = state.value.query
                if (current.isNotBlank() && current != submitted) {
                    runSearch(current, quick = true)
                }
            }
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

            SearchAction.BrowseRequested -> work.launch { postEffect { SearchEffect.BrowseHome } }

            is SearchAction.ResultActivated ->
                work.launch { postEffect { SearchEffect.OpenResult(action.id) } }

            SearchAction.SourcesChanged -> work.launch { refreshSources() }

            is SearchAction.RailMoreRequested -> loadMore(action.providerName)

            SearchAction.SourcePanelOpened -> work.launch { openSourcePanel() }

            is SearchAction.SourceToggled -> updateState {
                val panel = sourcePanel ?: return@updateState this
                val draft = if (action.id in panel.draft) panel.draft - action.id else panel.draft + action.id
                copy(sourcePanel = panel.copy(draft = draft))
            }

            SearchAction.SourcePanelDone -> commitSourcePanel()

            SearchAction.SourcePanelDismissed -> updateState { copy(sourcePanel = null) }

            is SearchAction.TypeFilterToggled -> toggleType(action.type)

            SearchAction.SearchEverything -> applyFiltersThenSearch { source.clearFilters() }

            is SearchAction.RecentActivated -> {
                submitted = action.query
                updateState { copy(query = action.query, status = SearchStatus.Loading) }
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
        submitted = null
        updateState { copy(query = query) }
        if (query.isBlank()) {
            showIdle()
        } else if (state.value.sources.quickSearchable) {
            updateState { copy(status = SearchStatus.Loading) }
            work.launch { debounce.emit(query) }
        }
    }

    private fun submitCurrentQuery() {
        val query = state.value.query
        if (query.isBlank()) {
            showIdle()
            return
        }
        submitted = query
        updateState { copy(status = SearchStatus.Loading) }
        work.launch { runSearch(query, quick = false) }
    }

    /** Clears any in-flight work: an empty field must not be overwritten by an earlier reply. */
    private fun showIdle() {
        // A blank through the debounce, which restarts its window and so drops the query queued
        // behind this clear. Without it, text cleared inside 200 ms is still searched and the
        // results land under an empty field (`BUG-24`). The consumer ignores the blank itself.
        work.launch { debounce.emit("") }
        work.launch { inFlight.launch { } }
        updateState { copy(status = SearchStatus.Idle) }
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
                copy(status = SearchStatus.Idle)
            } else {
                copy(status = SearchStatus.Showing(SearchContent.Recent(entries)))
            }
        }
    }

    private suspend fun refreshSources() {
        val sources = source.sources()
        val filters = source.typeFilters()
        updateState { copy(sources = sources, typeFilters = filters) }
    }

    private suspend fun openSourcePanel() {
        val choices = source.choices()
        updateState {
            copy(
                sourcePanel = SourcePanelState(
                    choices = choices,
                    draft = choices.filter { it.selected }.mapTo(HashSet()) { it.id },
                ),
            )
        }
    }

    /** Closes on the press. A Done that changed nothing writes nothing and does not search again. */
    private fun commitSourcePanel() {
        val panel = state.value.sourcePanel ?: return
        updateState { copy(sourcePanel = null) }
        val before = panel.choices.filter { it.selected }.mapTo(HashSet()) { it.id }
        if (panel.draft == before) return
        applyFiltersThenSearch { source.selectSources(panel.draft) }
    }

    /** The chip lights on the press, in state; the write and the search follow. */
    private fun toggleType(type: SearchTypeFilter) {
        val current = state.value.typeFilters
        val selected = if (type in current.selected) current.selected - type else current.selected + type
        updateState { copy(typeFilters = current.copy(selected = selected)) }
        applyFiltersThenSearch { source.selectTypes(selected) }
    }

    /**
     * The write finishes before the search starts, and the counts are read in between, or the search
     * asks the old providers and the no-results copy quotes the old number. Writes queue on [filterWrites]
     * so two quick presses land in order.
     */
    private fun applyFiltersThenSearch(write: suspend () -> Unit) {
        val query = state.value.query
        if (query.isNotBlank()) {
            submitted = query
            updateState { copy(status = SearchStatus.Loading) }
        }
        work.launch {
            filterWrites.withLock {
                write()
                refreshSources()
            }
            val current = state.value.query
            if (current.isNotBlank() && current == query) runSearch(current, quick = false)
        }
    }

    private suspend fun runSearch(query: String, quick: Boolean) {
        pages.clear()
        inFlight.launch {
            val outcome = if (quick) source.quickSearch(query) else source.search(query)
            updateState { copy(status = outcome.toStatus(sources.selected)) }
        }
    }

    /**
     * One rail's next page, appended where it is. A press while a page is on its way does nothing, and a reply
     * for results that have since been replaced is dropped: [content] is the state it was asked from.
     */
    private fun loadMore(provider: String) {
        val query = state.value.query
        val content = state.value.status.resultsOrNull() ?: return
        if (!loadingMore.add(provider)) return
        val page = (pages[provider] ?: FIRST_PAGE) + 1
        work.launch {
            val outcome = try {
                source.searchMore(query, provider, page)
            } finally {
                loadingMore.remove(provider)
            }
            updateState {
                if (status.resultsOrNull() !== content && status.resultsOrNull() != content) return@updateState this
                when (outcome) {
                    is SearchOutcome.Complete -> {
                        pages[provider] = page
                        copy(status = status.withResults(content.withPage(provider, outcome.items, provider in outcome.morePages)))
                    }
                    is SearchOutcome.Partial -> {
                        pages[provider] = page
                        copy(status = status.withResults(content.withPage(provider, outcome.items, provider in outcome.morePages)))
                    }
                    // What is on screen stays, and the shortfall rides the chip row like any other.
                    is SearchOutcome.Failed -> copy(status = SearchStatus.Partial(content, 0, 1, outcome.detail))
                    SearchOutcome.Offline -> copy(status = SearchStatus.Partial(content, 0, 1, OFFLINE_DETAIL))
                }
            }
        }
    }

    private fun SearchStatus.resultsOrNull(): SearchContent.Results? = when (this) {
        is SearchStatus.Showing -> content as? SearchContent.Results
        is SearchStatus.Partial -> content as? SearchContent.Results
        else -> null
    }

    private fun SearchStatus.withResults(results: SearchContent.Results): SearchStatus = when (this) {
        is SearchStatus.Partial -> copy(content = results)
        else -> SearchStatus.Showing(results)
    }

    /** Repeats are dropped, because a provider's pages can overlap and a rail's keys are the ids. */
    private fun SearchContent.Results.withPage(
        provider: String,
        items: List<SearchResultItem>,
        hasMore: Boolean,
    ) = copy(
        groups = groups.map { group ->
            if (group.providerName != provider) {
                group
            } else {
                val seen = group.items.mapTo(HashSet()) { it.id }
                group.copy(items = group.items + items.filter { seen.add(it.id) }, hasMore = hasMore)
            }
        },
    )

    private fun SearchOutcome.toStatus(queried: Int): SearchStatus = when (this) {
        is SearchOutcome.Complete ->
            if (items.isEmpty()) {
                SearchStatus.NoResults(queried)
            } else {
                SearchStatus.Showing(SearchContent.Results(items.groupByProvider(morePages)))
            }

        // Content that arrived is shown; the shortfall rides the chip row as a notice. Deliberately
        // not Failed: the results are usable, and replacing them with a failure band is what
        // `UiState`'s KDoc says Partial exists to prevent.
        is SearchOutcome.Partial ->
            SearchStatus.Partial(SearchContent.Results(items.groupByProvider(morePages)), answered, total, detail)

        is SearchOutcome.Failed -> SearchStatus.Failed(detail)

        SearchOutcome.Offline -> SearchStatus.Offline
    }
}

private const val FIRST_PAGE = 1

/** A status code for the mono line, like `SearchOutcome.Failed.detail`, so not translated. */
private const val OFFLINE_DETAIL = "OFFLINE"
