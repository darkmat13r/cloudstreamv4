package com.lagradost.cloudstream4.feature.search

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext

/**
 * Artboards 8a and 8b in the ViewModel. Waits on real time for the same reason as `SearchViewModelTest`: the debounce
 * runs on a real dispatcher against the real clock.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchSuggestionsViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private suspend fun settle() = withContext(Dispatchers.Default) { delay(600) }

    private fun item(title: String, provider: String) =
        SearchResultItem(id = SearchResultId("$provider/$title"), title = title, providerName = provider)

    private class FakeSource(
        var outcome: SearchOutcome = SearchOutcome.Complete(emptyList()),
        var sources: SearchSources = SearchSources(selected = 12, total = 48),
        val quickGate: CompletableDeferred<Unit>? = null,
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
            quickGate?.await()
            return outcome
        }
    }

    private class FakeHistory(vararg initial: String) : SearchHistoryStore {
        val entries = initial.map { SearchHistoryEntry(it, "2 days ago") }.toMutableList()
        override suspend fun recent(): List<SearchHistoryEntry> = entries.toList()
        override suspend fun remove(query: String) {
            entries.removeAll { it.query == query }
        }
        override suspend fun clear() = entries.clear()
    }

    private class FakeSuggestions(private val answer: List<String>) : SearchSuggestionSource {
        val asked = mutableListOf<String>()
        override suspend fun suggest(query: String): List<String> {
            asked += query
            return answer
        }
    }

    private fun viewModel(
        source: SearchSource,
        suggestions: SearchSuggestionSource?,
        history: SearchHistoryStore = FakeHistory(),
    ) = SearchViewModel(source, history, dispatcher, suggestions = suggestions)

    private fun SearchStatus.rows(): List<SearchSuggestion> = when (this) {
        is SearchStatus.Showing -> assertIs<SearchContent.Suggestions>(content).rows
        is SearchStatus.Partial -> assertIs<SearchContent.Suggestions>(content).rows
        else -> error("expected suggestions, was $this")
    }

    @Test
    fun `typing shows 8a's column, with each row's count from the quick search`() = runTest(dispatcher) {
        val source = FakeSource(
            SearchOutcome.Complete(listOf(item("Dune", "Filmxy"), item("Dune", "Vidplay"), item("Dune: Part Two", "Filmxy"))),
        )
        val suggestions = FakeSuggestions(listOf("Dune", "Dune: Part Two", "Duna"))
        val vm = viewModel(source, suggestions, FakeHistory("dune"))

        for (q in listOf("d", "du", "dun", "dune")) vm.onAction(SearchAction.QueryChanged(q))
        settle()

        assertEquals(listOf("dune"), suggestions.asked, "only the settled query is asked")
        assertEquals(listOf("dune"), source.quickSearched)
        assertTrue(source.searched.isEmpty(), "typing does not run the full search")
        val rows = vm.state.value.status.rows()
        assertEquals(listOf("dune", "Dune: Part Two", "Duna"), rows.map { it.query })
        assertEquals(listOf(2, 1, 0), rows.map { it.sources })
    }

    @Test
    fun `the rows land before the fan-out answers, and with no count until it has`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val source = FakeSource(SearchOutcome.Complete(listOf(item("Dune", "Filmxy"))), quickGate = gate)
        val vm = viewModel(source, FakeSuggestions(listOf("Dune")))

        vm.onAction(SearchAction.QueryChanged("dune"))
        settle()
        assertEquals(listOf<Int?>(null), vm.state.value.status.rows().map { it.sources })

        gate.complete(Unit)
        settle()
        assertEquals(listOf<Int?>(1), vm.state.value.status.rows().map { it.sources })
    }

    @Test
    fun `nothing suggested and nothing recent is 8b, quoting the query and the selected sources`() = runTest(dispatcher) {
        val vm = viewModel(FakeSource(), FakeSuggestions(emptyList()), FakeHistory("blade runner"))

        vm.onAction(SearchAction.QueryChanged("dunnne"))
        settle()

        assertEquals(SearchStatus.NoSuggestions(query = "dunnne", selected = 12), vm.state.value.status)
    }

    @Test
    fun `8b's action searches every selected source for what is typed`() = runTest(dispatcher) {
        val source = FakeSource(SearchOutcome.Complete(listOf(item("Dunnne", "Filmxy"))))
        val vm = viewModel(source, FakeSuggestions(emptyList()))
        vm.onAction(SearchAction.QueryChanged("dunnne"))
        settle()

        vm.onAction(SearchAction.Submit)
        settle()

        assertEquals(listOf("dunnne"), source.searched)
        assertIs<SearchContent.Results>(assertIs<SearchStatus.Showing>(vm.state.value.status).content)
    }

    @Test
    fun `offline with nothing suggested says offline, not that nothing matches`() = runTest(dispatcher) {
        val vm = viewModel(FakeSource(SearchOutcome.Offline), FakeSuggestions(emptyList()))

        vm.onAction(SearchAction.QueryChanged("dune"))
        settle()

        assertEquals(SearchStatus.Offline, vm.state.value.status)
    }

    @Test
    fun `a fan-out that partly failed rides the chip row, and the counts are of those that answered`() = runTest(dispatcher) {
        val source = FakeSource(SearchOutcome.Partial(listOf(item("Dune", "Filmxy")), answered = 9, total = 12, detail = "3 FAILED"))
        val vm = viewModel(source, FakeSuggestions(listOf("Dune")))

        vm.onAction(SearchAction.QueryChanged("dune"))
        settle()

        val status = assertIs<SearchStatus.Partial>(vm.state.value.status)
        assertEquals(9 to 12, status.answered to status.total)
        assertEquals(listOf<Int?>(1), status.rows().map { it.sources })
    }

    @Test
    fun `with no source that can quick-search, the rows come with no count and nothing is searched`() = runTest(dispatcher) {
        val source = FakeSource(sources = SearchSources(selected = 12, total = 48, quickSearchable = false))
        val vm = viewModel(source, FakeSuggestions(listOf("Dune")))
        settle()

        vm.onAction(SearchAction.QueryChanged("dune"))
        settle()

        assertEquals(listOf<Int?>(null), vm.state.value.status.rows().map { it.sources })
        assertTrue(source.quickSearched.isEmpty() && source.searched.isEmpty())
    }

    @Test
    fun `a row runs the full search for its words, and shows results`() = runTest(dispatcher) {
        val source = FakeSource(SearchOutcome.Complete(listOf(item("Dune: Part Two", "Filmxy"))))
        val vm = viewModel(source, FakeSuggestions(listOf("Dune: Part Two")))
        vm.onAction(SearchAction.QueryChanged("dune"))
        settle()

        vm.onAction(SearchAction.SuggestionActivated("Dune: Part Two"))
        settle()

        assertEquals(listOf("Dune: Part Two"), source.searched)
        assertEquals("Dune: Part Two", vm.state.value.query, "the field shows what was searched")
        assertIs<SearchContent.Results>(assertIs<SearchStatus.Showing>(vm.state.value.status).content)
    }

    @Test
    fun `without a suggestion source, typing still runs the quick search into rails`() = runTest(dispatcher) {
        val source = FakeSource(SearchOutcome.Complete(listOf(item("Dune", "Filmxy"))))
        val vm = viewModel(source, suggestions = null)

        vm.onAction(SearchAction.QueryChanged("dune"))
        settle()

        assertIs<SearchContent.Results>(assertIs<SearchStatus.Showing>(vm.state.value.status).content)
    }
}
