package com.lagradost.cloudstream4.feature.search

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The behaviours that make this ViewModel worth having: every outcome maps to a distinct `SearchStatus`,
 * typing is debounced rather than sending one request per keystroke, and a blank query is content
 * rather than a void.
 *
 * ## Why these tests wait on real time instead of advancing virtual time
 *
 * `:core:ui`'s `DebounceQuery` hardcodes `.flowOn(Dispatchers.Default)` and a 200 ms
 * `debounce(...)`. Neither is injectable, so `advanceTimeBy` on a `TestDispatcher` does not move it
 * — the pipeline is running on a real dispatcher against the real clock. A first draft of this suite
 * used `StandardTestDispatcher` + `advanceTimeBy(1_000)` and the debounced search never fired, which
 * reads as a product bug and is not one.
 *
 * So `Dispatchers.Main` is an `UnconfinedTestDispatcher` (because `viewModelScope` uses Main) and the
 * debounce tests [settle] by sleeping past 200 ms for real. That costs a few hundred milliseconds
 * and is honest about what is being measured. Making `DebounceQuery` take a dispatcher and a
 * duration would remove the wait, but it is existing `:core:ui` code and
 * `.claude/rules/no-unsolicited-changes.md` says report it rather than change it — see `KMP-12`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()

    /**
     * Past `DebounceQuery`'s 200 ms window and whatever it started, on the virtual clock (`BUG-102`, as `BUG-71` did for
     * the suggestions): the view models here debounce on [dispatcher]. It used to sleep 600 ms for real while the
     * debounce ran on `Dispatchers.Default` against the real clock, and under load that was not always enough.
     */
    private fun TestScope.settle() {
        advanceTimeBy(SETTLE_MS)
        runCurrent()
    }

    /** The real clock, for the one test whose models must run on real threads (`BUG-25`). */
    private suspend fun settleReal() = withContext(Dispatchers.Default) { delay(SETTLE_MS) }

    private fun item(title: String, provider: String = "p") =
        SearchResultItem(id = SearchResultId("$provider/$title"), title = title, providerName = provider)

    private class FakeSource(
        var outcome: SearchOutcome = SearchOutcome.Complete(emptyList()),
        var sources: SearchSources = SearchSources(selected = 9, total = 48),
    ) : SearchSource {
        val searched = mutableListOf<String>()
        val quickSearched = mutableListOf<String>()
        override suspend fun sources(): SearchSources = sources
        override suspend fun search(query: String): SearchOutcome {
            searched += query
            return outcome
        }
        override suspend fun quickSearch(query: String): SearchOutcome {
            quickSearched += query
            return outcome
        }
    }

    private class FakeHistory(vararg initial: String) : SearchHistoryStore {
        val entries = initial.map { SearchHistoryEntry(it, "2 days ago") }.toMutableList()
        var cleared = false
        override suspend fun recent(): List<SearchHistoryEntry> = entries.toList()
        override suspend fun remove(query: String) {
            entries.removeAll { it.query == query }
        }
        override suspend fun clear() {
            cleared = true
            entries.clear()
        }
    }

    private fun viewModel(source: SearchSource, history: SearchHistoryStore = FakeHistory()) =
        SearchViewModel(source, history, dispatcher, debounceDispatcher = dispatcher)

    private fun <T> withMain(block: () -> T): T {
        Dispatchers.setMain(dispatcher)
        return try {
            block()
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `with no history, a blank query is the idle prompt and not loading`() =
        runTest(dispatcher) {
            withMain {
                val vm = viewModel(FakeSource())
                val content = vm.state.value.status
                assertEquals(SearchStatus.Idle, content)
                assertEquals("", vm.state.value.query)
            }
        }

    @Test
    fun `with history, a blank query shows it — artboard 4c, not an empty prompt`() =
        runTest(dispatcher) {
            withMain {
                val vm = viewModel(FakeSource(), FakeHistory("tide", "salt road"))
                val content = vm.state.value.status
                assertIs<SearchStatus.Showing>(content)
                val recent = assertIs<SearchContent.Recent>(content.content)
                assertEquals(listOf("tide", "salt road"), recent.entries.map { it.query })
            }
        }

    @Test
    fun `the source counts reach state, because the no-results copy quotes them`() =
        runTest(dispatcher) {
            withMain {
                val vm = viewModel(FakeSource())
                assertEquals(SearchSources(selected = 9, total = 48), vm.state.value.sources)
            }
        }

    @Test
    fun `the query echoes into state immediately`() = runTest(dispatcher) {
        withMain {
            val vm = viewModel(FakeSource())
            vm.onAction(SearchAction.QueryChanged("tide"))
            assertEquals("tide", vm.state.value.query)
        }
    }

    @Test
    fun `typing is debounced to one quick search, not one per keystroke`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        try {
            val source = FakeSource(SearchOutcome.Complete(listOf(item("Tide"))))
            val vm = viewModel(source)
            for (q in listOf("t", "ti", "tid", "tide")) vm.onAction(SearchAction.QueryChanged(q))
            settle()

            assertEquals(listOf("tide"), source.quickSearched, "only the settled query is searched")
            assertTrue(source.searched.isEmpty(), "typing must not run the full search")
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `adding a letter and deleting it again does not stick on loading`() = runTest(dispatcher) {
        withMain {
            val source = FakeSource(SearchOutcome.Complete(listOf(item("Film"))))
            val vm = viewModel(source)
            vm.onAction(SearchAction.QueryChanged("film"))
            settle()
            assertIs<SearchStatus.Showing>(vm.state.value.status)

            vm.onAction(SearchAction.QueryChanged("films"))
            vm.onAction(SearchAction.QueryChanged("film"))
            settle()
            assertIs<SearchStatus.Showing>(vm.state.value.status)
        }
    }

    /**
     * On a scope with more than one thread, as on desktop, two keystrokes can reach the debounce in the wrong order. Many
     * at once, because each pair only sometimes does (`BUG-25`).
     */
    @Test
    fun `keystrokes that reach the debounce out of order still search what is in the field`() = runTest(dispatcher) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val models = List(STRESS_MODELS) {
                SearchViewModel(FakeSource(SearchOutcome.Complete(listOf(item("Film")))), FakeHistory(), dispatcher, scope)
            }
            settleReal()
            models.forEach { vm ->
                vm.onAction(SearchAction.QueryChanged("films"))
                vm.onAction(SearchAction.QueryChanged("film"))
            }
            // BUG-102: until no model is loading, not two fixed sleeps. A model stuck on loading (BUG-25) still fails,
            // after the time limit, below.
            withContext(Dispatchers.Default) {
                withTimeoutOrNull(STRESS_LIMIT_MS) { while (models.any { it.state.value.status is SearchStatus.Loading }) delay(10) }
            }
            val stuck = models.count { it.state.value.status is SearchStatus.Loading }
            assertEquals(0, stuck, "of $STRESS_MODELS searches, left on loading")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `clearing the field and typing the same words again searches again`() = runTest(dispatcher) {
        withMain {
            val source = FakeSource(SearchOutcome.Complete(listOf(item("Film"))))
            val vm = viewModel(source)
            vm.onAction(SearchAction.QueryChanged("film"))
            settle()
            vm.onAction(SearchAction.Cleared)
            settle()
            vm.onAction(SearchAction.QueryChanged("film"))
            settle()
            assertIs<SearchStatus.Showing>(vm.state.value.status)
            assertEquals(listOf("film", "film"), source.quickSearched)
        }
    }

    @Test
    fun `submit runs the full search without waiting for the debounce`() = runTest(dispatcher) {
        withMain {
            val source = FakeSource(SearchOutcome.Complete(listOf(item("Tide"))))
            val vm = viewModel(source)
            vm.onAction(SearchAction.QueryChanged("tide"))
            vm.onAction(SearchAction.Submit)

            assertEquals(listOf("tide"), source.searched)
            assertIs<SearchStatus.Showing>(vm.state.value.status)
        }
    }

    @Test
    fun `results arrive grouped one rail per provider — artboard 4a`() = runTest(dispatcher) {
        withMain {
            val hits = listOf(
                item("Tide", "Source A"),
                item("Tide Part Two", "Source A"),
                item("Tide", "Source B"),
            )
            val source = FakeSource(SearchOutcome.Complete(hits))
            val vm = viewModel(source)
            vm.onAction(SearchAction.QueryChanged("tide"))
            vm.onAction(SearchAction.Submit)

            val content = assertIs<SearchStatus.Showing>(vm.state.value.status)
            val results = assertIs<SearchContent.Results>(content.content)
            assertEquals(listOf("Source A", "Source B"), results.groups.map { it.providerName })
            assertEquals(listOf(2, 1), results.groups.map { it.items.size })
        }
    }

    @Test
    fun `each outcome becomes its own status`() = runTest(dispatcher) {
        withMain {
            val source = FakeSource()
            val vm = viewModel(source)
            vm.onAction(SearchAction.QueryChanged("tide"))

            source.outcome = SearchOutcome.Complete(listOf(item("Tide")))
            vm.onAction(SearchAction.Submit)
            assertIs<SearchStatus.Showing>(vm.state.value.status)

            // A successful search that matched nothing is Empty, not Error — and it carries the
            // mono status line artboard 8c draws under the copy.
            source.outcome = SearchOutcome.Complete(emptyList())
            vm.onAction(SearchAction.Submit)
            assertEquals(SearchStatus.NoResults(queried = 9), vm.state.value.status)

            // Partial keeps the content and reports the shortfall; it must not become Error.
            source.outcome = SearchOutcome.Partial(listOf(item("Tide")), 9, 12, "host down")
            vm.onAction(SearchAction.Submit)
            val partial = assertIs<SearchStatus.Partial>(vm.state.value.status)
            val partialResults = assertIs<SearchContent.Results>(partial.content)
            assertEquals(1, partialResults.groups.size, "partial content must still be shown")
            assertEquals(9, partial.answered)
            assertEquals(12, partial.total)
            assertEquals("host down", partial.detail)

            source.outcome = SearchOutcome.Failed("503")
            vm.onAction(SearchAction.Submit)
            assertEquals(SearchStatus.Failed("503"), vm.state.value.status)

            source.outcome = SearchOutcome.Offline
            vm.onAction(SearchAction.Submit)
            assertEquals(SearchStatus.Offline, vm.state.value.status)
        }
    }

    @Test
    fun `clearing the field returns to the history and does not search`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        try {
            val source = FakeSource(SearchOutcome.Complete(listOf(item("Tide"))))
            val vm = viewModel(source, FakeHistory("tide"))
            vm.onAction(SearchAction.QueryChanged("tide"))
            settle()
            vm.onAction(SearchAction.Cleared)
            settle()

            assertEquals("", vm.state.value.query)
            val content = assertIs<SearchStatus.Showing>(vm.state.value.status)
            assertIs<SearchContent.Recent>(content.content)
            assertTrue(source.searched.isEmpty(), "clearing must not run a search")
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `activating a recent search runs the full search for it`() = runTest(dispatcher) {
        withMain {
            val source = FakeSource(SearchOutcome.Complete(listOf(item("Tide"))))
            val vm = viewModel(source, FakeHistory("tide"))
            vm.onAction(SearchAction.RecentActivated("tide"))

            assertEquals("tide", vm.state.value.query)
            assertEquals(listOf("tide"), source.searched)
        }
    }

    @Test
    fun `removing one recent entry re-reads the list`() = runTest(dispatcher) {
        withMain {
            val history = FakeHistory("tide", "salt road")
            val vm = viewModel(FakeSource(), history)
            vm.onAction(SearchAction.RecentRemoved("tide"))

            val content = assertIs<SearchStatus.Showing>(vm.state.value.status)
            val recent = assertIs<SearchContent.Recent>(content.content)
            assertEquals(listOf("salt road"), recent.entries.map { it.query })
        }
    }

    @Test
    fun `clearing all history falls back to the idle prompt`() = runTest(dispatcher) {
        withMain {
            val history = FakeHistory("tide")
            val vm = viewModel(FakeSource(), history)
            vm.onAction(SearchAction.RecentCleared)

            assertTrue(history.cleared)
            assertEquals(SearchStatus.Idle, vm.state.value.status)
        }
    }

    @Test
    fun `activating a result posts an effect and does not navigate itself`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        try {
            val vm = viewModel(FakeSource())
            val received = mutableListOf<SearchEffect>()
            // backgroundScope is cancelled for us when the test ends. Unconfined, so the collector
            // is already suspended on the rendezvous channel before the effect is posted —
            // `DefaultEffectContainer` uses an unbuffered `Channel`, so a late collector misses it.
            backgroundScope.launch { vm.effect.collect { received += it } }
            vm.onAction(SearchAction.ResultActivated(SearchResultId("p/abc")))
            settle()

            assertEquals(
                listOf<SearchEffect>(SearchEffect.OpenResult(SearchResultId("p/abc"))),
                received,
            )
        } finally {
            Dispatchers.resetMain()
        }
    }

    /*
     * The rest of this file's cases came out of switching Android TV to this screen (KMP-12 step 4),
     * and all of them failed against the desktop app as shipped (`BUG-24`).
     * `MainAPI.hasQuickSearch` defaults to false, so a real provider set usually cannot answer a
     * quick search at all, and the first keystroke used to land on a failure band.
     *
     * A fourth case found here — the same query retyped after a clear — is not below: `BUG-23`
     * landed first and `clearing the field and typing the same words again searches again` above
     * already guards it.
     */

    @Test
    fun `with no quick-search source, typing leaves the screen alone until submit`() =
        runTest(dispatcher) {
            Dispatchers.setMain(dispatcher)
            try {
                val source = FakeSource(
                    SearchOutcome.Complete(listOf(item("Tide"))),
                    SearchSources(selected = 9, total = 48, quickSearchable = false),
                )
                val vm = viewModel(source, FakeHistory("salt road"))
                val before = vm.state.value.status
                vm.onAction(SearchAction.QueryChanged("tide"))
                settle()

                assertEquals("tide", vm.state.value.query, "the field still echoes")
                assertEquals(before, vm.state.value.status, "no skeleton, and above all no failure")
                assertTrue(source.quickSearched.isEmpty())

                vm.onAction(SearchAction.Submit)
                settle()
                assertEquals(listOf("tide"), source.searched)
                assertIs<SearchStatus.Showing>(vm.state.value.status)
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun `a debounce still pending at submit cannot replace the full search with a quick one`() =
        runTest(dispatcher) {
            Dispatchers.setMain(dispatcher)
            try {
                val source = FakeSource(SearchOutcome.Complete(listOf(item("Tide"))))
                val vm = viewModel(source)
                vm.onAction(SearchAction.QueryChanged("tide"))
                vm.onAction(SearchAction.Submit)
                settle()

                assertEquals(listOf("tide"), source.searched)
                assertTrue(source.quickSearched.isEmpty(), "quick ran after the submit: ${source.quickSearched}")
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun `a clear inside the debounce window does not search the text that was cleared`() =
        runTest(dispatcher) {
            Dispatchers.setMain(dispatcher)
            try {
                val source = FakeSource(SearchOutcome.Complete(listOf(item("Tide"))))
                val vm = viewModel(source)
                vm.onAction(SearchAction.QueryChanged("tide"))
                vm.onAction(SearchAction.Cleared)
                settle()

                assertTrue(source.quickSearched.isEmpty(), "ran: ${source.quickSearched}")
                assertEquals(SearchStatus.Idle, vm.state.value.status)
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun `sources changing re-reads the count, because plugins load after the screen can open`() =
        runTest(dispatcher) {
            withMain {
                val source = FakeSource()
                val vm = viewModel(source)
                source.sources = SearchSources(selected = 12, total = 50)
                vm.onAction(SearchAction.SourcesChanged)
                assertEquals(SearchSources(selected = 12, total = 50), vm.state.value.sources)
            }
        }

    // ---- Artboard 4d's panel, the type chips, and 8c's action ----

    private class FilterSource(
        var outcome: SearchOutcome = SearchOutcome.Complete(emptyList()),
    ) : SearchSource {
        val calls = mutableListOf<String>()
        var selected = setOf("a", "b")
        var types = setOf(SearchTypeFilter.Movies)

        override suspend fun sources() = SearchSources(selected = selected.size, total = 3, quickSearchable = false)
        override suspend fun choices() = listOf("a", "b", "c").map { SearchSourceChoice(it, it, selected = it in selected) }
        override suspend fun selectSources(ids: Set<String>) {
            calls += "select$ids"
            selected = ids
        }
        override suspend fun typeFilters() =
            SearchTypeFilters(listOf(SearchTypeFilter.Movies, SearchTypeFilter.Anime), types)
        override suspend fun selectTypes(types: Set<SearchTypeFilter>) {
            calls += "types$types"
            this.types = types
        }
        override suspend fun clearFilters() {
            calls += "clear"
            selected = setOf("a", "b", "c")
            types = emptySet()
        }
        override suspend fun search(query: String): SearchOutcome {
            calls += "search:${selected.size}"
            return outcome
        }
        override suspend fun quickSearch(query: String) = outcome
    }

    private fun filterTest(block: suspend TestScope.(FilterSource, SearchViewModel) -> Unit) = withMain {
        runTest(dispatcher) {
            val source = FilterSource()
            block(source, viewModel(source))
        }
    }

    @Test
    fun `opening the panel offers the rows with their selection as the draft`() = filterTest { source, vm ->
        vm.onAction(SearchAction.SourcePanelOpened)
        settle()
        val panel = vm.state.value.sourcePanel!!
        assertEquals(listOf("a", "b", "c"), panel.choices.map { it.id })
        assertEquals(setOf("a", "b"), panel.draft)
        assertEquals(emptyList<String>(), source.calls)
    }

    @Test
    fun `a row press changes only the draft`() = filterTest { source, vm ->
        vm.onAction(SearchAction.SourcePanelOpened)
        settle()
        vm.onAction(SearchAction.SourceToggled("a"))
        vm.onAction(SearchAction.SourceToggled("c"))
        assertEquals(setOf("b", "c"), vm.state.value.sourcePanel!!.draft)
        settle()
        assertEquals(emptyList<String>(), source.calls)
    }

    @Test
    fun `back discards the draft`() = filterTest { source, vm ->
        vm.onAction(SearchAction.SourcePanelOpened)
        settle()
        vm.onAction(SearchAction.SourceToggled("a"))
        vm.onAction(SearchAction.SourcePanelDismissed)
        settle()
        assertEquals(null, vm.state.value.sourcePanel)
        assertEquals(setOf("a", "b"), source.selected)
        assertEquals(emptyList<String>(), source.calls)
    }

    @Test
    fun `done writes before it searches and the count follows`() = filterTest { source, vm ->
        vm.onAction(SearchAction.QueryChanged("tide"))
        vm.onAction(SearchAction.SourcePanelOpened)
        settle()
        vm.onAction(SearchAction.SourceToggled("a"))
        vm.onAction(SearchAction.SourcePanelDone)
        settle()
        assertEquals(null, vm.state.value.sourcePanel)
        assertEquals(listOf("select[b]", "search:1"), source.calls)
        assertEquals(1, vm.state.value.sources.selected)
    }

    @Test
    fun `done with nothing changed writes nothing and does not search`() = filterTest { source, vm ->
        vm.onAction(SearchAction.QueryChanged("tide"))
        vm.onAction(SearchAction.SourcePanelOpened)
        settle()
        vm.onAction(SearchAction.SourceToggled("a"))
        vm.onAction(SearchAction.SourceToggled("a"))
        vm.onAction(SearchAction.SourcePanelDone)
        settle()
        assertEquals(emptyList<String>(), source.calls)
    }

    @Test
    fun `done with no query writes and does not search`() = filterTest { source, vm ->
        vm.onAction(SearchAction.SourcePanelOpened)
        settle()
        vm.onAction(SearchAction.SourceToggled("a"))
        vm.onAction(SearchAction.SourcePanelDone)
        settle()
        assertEquals(listOf("select[b]"), source.calls)
    }

    @Test
    fun `a chip lights at once, writes, and searches again`() = filterTest { source, vm ->
        settle()
        vm.onAction(SearchAction.QueryChanged("tide"))
        vm.onAction(SearchAction.TypeFilterToggled(SearchTypeFilter.Anime))
        assertEquals(setOf(SearchTypeFilter.Movies, SearchTypeFilter.Anime), vm.state.value.typeFilters.selected)
        settle()
        assertEquals(1, source.calls.count { it.startsWith("types") })
        assertEquals("search:2", source.calls.last())
        vm.onAction(SearchAction.TypeFilterToggled(SearchTypeFilter.Movies))
        settle()
        assertEquals(setOf(SearchTypeFilter.Anime), source.types)
    }

    @Test
    fun `search everything clears both filters and then searches`() = filterTest { source, vm ->
        settle()
        vm.onAction(SearchAction.QueryChanged("tide"))
        vm.onAction(SearchAction.SearchEverything)
        settle()
        assertEquals(listOf("clear", "search:3"), source.calls)
        assertEquals(emptySet<SearchTypeFilter>(), vm.state.value.typeFilters.selected)
        assertEquals(3, vm.state.value.sources.selected)
    }

    @Test
    fun `no results quotes the count after the filters were cleared`() = filterTest { _, vm ->
        settle()
        vm.onAction(SearchAction.QueryChanged("tide"))
        vm.onAction(SearchAction.SearchEverything)
        settle()
        assertEquals(SearchStatus.NoResults(3), vm.state.value.status)
    }

    // ---- A rail's trailing card ----

    private class PagedSource : SearchSource {
        val asked = mutableListOf<Int>()
        var next: SearchOutcome = SearchOutcome.Complete(emptyList())
        override suspend fun sources() = SearchSources(selected = 1, total = 1, quickSearchable = false)
        override suspend fun search(query: String): SearchOutcome =
            SearchOutcome.Complete(listOf(SearchResultItem(SearchResultId("a"), "a", providerName = "p")), setOf("p"))
        override suspend fun quickSearch(query: String) = search(query)
        override suspend fun searchMore(query: String, providerName: String, page: Int): SearchOutcome {
            asked += page
            return next
        }
    }

    private fun railGroup(vm: SearchViewModel) =
        ((vm.state.value.status as SearchStatus.Showing).content as SearchContent.Results).groups.single()

    @Test
    fun `the next page lands on the same rail, without repeats, and the card goes with the last page`() = withMain {
        runTest(dispatcher) {
            val source = PagedSource()
            val vm = viewModel(source)
            vm.onAction(SearchAction.RecentActivated("tide"))
            settle()
            assertEquals(true, railGroup(vm).hasMore)

            source.next = SearchOutcome.Complete(
                listOf(
                    SearchResultItem(SearchResultId("a"), "a", providerName = "p"),
                    SearchResultItem(SearchResultId("b"), "b", providerName = "p"),
                ),
                setOf("p"),
            )
            vm.onAction(SearchAction.RailMoreRequested("p"))
            settle()
            assertEquals(listOf("a", "b"), railGroup(vm).items.map { it.id.value })

            source.next = SearchOutcome.Complete(emptyList())
            vm.onAction(SearchAction.RailMoreRequested("p"))
            settle()
            assertEquals(false, railGroup(vm).hasMore)
            assertEquals(listOf(2, 3), source.asked)
        }
    }

    @Test
    fun `a page that fails keeps the rail and says so on the chip row`() = withMain {
        runTest(dispatcher) {
            val source = PagedSource()
            val vm = viewModel(source)
            vm.onAction(SearchAction.RecentActivated("tide"))
            settle()
            source.next = SearchOutcome.Failed("HTTP 503")
            vm.onAction(SearchAction.RailMoreRequested("p"))
            settle()
            val status = vm.state.value.status as SearchStatus.Partial
            assertEquals("HTTP 503", status.detail)
            assertEquals(1, status.total - status.answered)
            assertEquals(true, ((status.content) as SearchContent.Results).groups.single().hasMore)
        }
    }
}

private const val STRESS_MODELS = 200

/** Past the debounce's 200 ms window and what it starts. */
private const val SETTLE_MS = 600L

/** How long the stress test waits for its models to leave loading before it counts the ones that did not. */
private const val STRESS_LIMIT_MS = 10_000L
