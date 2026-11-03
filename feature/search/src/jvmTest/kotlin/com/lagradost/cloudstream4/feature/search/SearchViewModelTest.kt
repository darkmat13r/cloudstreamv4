package com.lagradost.cloudstream4.feature.search

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext

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

    /** Sleeps past `DebounceQuery`'s real 200 ms. See the class KDoc. */
    private suspend fun settle() = withContext(Dispatchers.Default) { delay(600) }

    private fun item(title: String, provider: String = "p") =
        SearchResultItem(id = SearchResultId("$provider/$title"), title = title, providerName = provider)

    private class FakeSource(
        var outcome: SearchOutcome = SearchOutcome.Complete(emptyList()),
        private val sources: SearchSources = SearchSources(selected = 9, total = 48),
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
        SearchViewModel(source, history, dispatcher)

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
                val vm = viewModel(FakeSource(), FakeHistory("dune", "blade runner"))
                val content = vm.state.value.status
                assertIs<SearchStatus.Showing>(content)
                val recent = assertIs<SearchContent.Recent>(content.content)
                assertEquals(listOf("dune", "blade runner"), recent.entries.map { it.query })
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
            vm.onAction(SearchAction.QueryChanged("dune"))
            assertEquals("dune", vm.state.value.query)
        }
    }

    @Test
    fun `typing is debounced to one quick search, not one per keystroke`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        try {
            val source = FakeSource(SearchOutcome.Complete(listOf(item("Dune"))))
            val vm = viewModel(source)
            for (q in listOf("d", "du", "dun", "dune")) vm.onAction(SearchAction.QueryChanged(q))
            settle()

            assertEquals(listOf("dune"), source.quickSearched, "only the settled query is searched")
            assertTrue(source.searched.isEmpty(), "typing must not run the full search")
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `adding a letter and deleting it again does not stick on loading`() = runTest(dispatcher) {
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

    @Test
    fun `clearing the field and typing the same words again searches again`() = runTest(dispatcher) {
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

    @Test
    fun `submit runs the full search without waiting for the debounce`() = runTest(dispatcher) {
        withMain {
            val source = FakeSource(SearchOutcome.Complete(listOf(item("Dune"))))
            val vm = viewModel(source)
            vm.onAction(SearchAction.QueryChanged("dune"))
            vm.onAction(SearchAction.Submit)

            assertEquals(listOf("dune"), source.searched)
            assertIs<SearchStatus.Showing>(vm.state.value.status)
        }
    }

    @Test
    fun `results arrive grouped one rail per provider — artboard 4a`() = runTest(dispatcher) {
        withMain {
            val hits = listOf(
                item("Dune", "Filmxy"),
                item("Dune Part Two", "Filmxy"),
                item("Dune", "Vidplay"),
            )
            val source = FakeSource(SearchOutcome.Complete(hits))
            val vm = viewModel(source)
            vm.onAction(SearchAction.QueryChanged("dune"))
            vm.onAction(SearchAction.Submit)

            val content = assertIs<SearchStatus.Showing>(vm.state.value.status)
            val results = assertIs<SearchContent.Results>(content.content)
            assertEquals(listOf("Filmxy", "Vidplay"), results.groups.map { it.providerName })
            assertEquals(listOf(2, 1), results.groups.map { it.items.size })
        }
    }

    @Test
    fun `each outcome becomes its own status`() = runTest(dispatcher) {
        withMain {
            val source = FakeSource()
            val vm = viewModel(source)
            vm.onAction(SearchAction.QueryChanged("dune"))

            source.outcome = SearchOutcome.Complete(listOf(item("Dune")))
            vm.onAction(SearchAction.Submit)
            assertIs<SearchStatus.Showing>(vm.state.value.status)

            // A successful search that matched nothing is Empty, not Error — and it carries the
            // mono status line artboard 8c draws under the copy.
            source.outcome = SearchOutcome.Complete(emptyList())
            vm.onAction(SearchAction.Submit)
            assertEquals(SearchStatus.NoResults(queried = 9), vm.state.value.status)

            // Partial keeps the content and reports the shortfall; it must not become Error.
            source.outcome = SearchOutcome.Partial(listOf(item("Dune")), 9, 12, "host down")
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
            val source = FakeSource(SearchOutcome.Complete(listOf(item("Dune"))))
            val vm = viewModel(source, FakeHistory("dune"))
            vm.onAction(SearchAction.QueryChanged("dune"))
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
            val source = FakeSource(SearchOutcome.Complete(listOf(item("Dune"))))
            val vm = viewModel(source, FakeHistory("dune"))
            vm.onAction(SearchAction.RecentActivated("dune"))

            assertEquals("dune", vm.state.value.query)
            assertEquals(listOf("dune"), source.searched)
        }
    }

    @Test
    fun `removing one recent entry re-reads the list`() = runTest(dispatcher) {
        withMain {
            val history = FakeHistory("dune", "blade runner")
            val vm = viewModel(FakeSource(), history)
            vm.onAction(SearchAction.RecentRemoved("dune"))

            val content = assertIs<SearchStatus.Showing>(vm.state.value.status)
            val recent = assertIs<SearchContent.Recent>(content.content)
            assertEquals(listOf("blade runner"), recent.entries.map { it.query })
        }
    }

    @Test
    fun `clearing all history falls back to the idle prompt`() = runTest(dispatcher) {
        withMain {
            val history = FakeHistory("dune")
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
}
