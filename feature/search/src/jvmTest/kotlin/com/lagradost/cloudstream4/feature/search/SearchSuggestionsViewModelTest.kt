package com.lagradost.cloudstream4.feature.search

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import com.lagradost.cloudstream4.core.connectivity.Connectivity
import com.lagradost.cloudstream4.core.connectivity.ConnectivityFlow
import com.lagradost.cloudstream4.core.connectivity.UnknownConnectivityFlow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/**
 * Artboards 8a and 8b in the ViewModel. Everything, the debounce included, runs on [dispatcher], so time is the test's
 * virtual clock and nothing waits on a real one (`BUG-71`: the debounce used to run on `Dispatchers.Default` against
 * the real clock, the test slept 600 ms for real, and under load that was not always enough).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchSuggestionsViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    /** Past the debounce's 200 ms window and whatever it started, on the virtual clock. */
    private fun TestScope.settle() {
        advanceTimeBy(SETTLE_MS)
        runCurrent()
    }

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
        connectivity: ConnectivityFlow = UnknownConnectivityFlow,
    ) = SearchViewModel(
        source,
        history,
        dispatcher,
        suggestions = suggestions,
        connectivity = connectivity,
        debounceDispatcher = dispatcher,
    )

    private fun network(now: Connectivity) = object : ConnectivityFlow {
        override val status: StateFlow<Connectivity> = MutableStateFlow(now)
    }

    private fun SearchStatus.rows(): List<SearchSuggestion> = when (this) {
        is SearchStatus.Showing -> assertIs<SearchContent.Suggestions>(content).rows
        is SearchStatus.Partial -> assertIs<SearchContent.Suggestions>(content).rows
        else -> error("expected suggestions, was $this")
    }

    @Test
    fun `typing shows 8a's column, with each row's count from the quick search`() = runTest(dispatcher) {
        val source = FakeSource(
            SearchOutcome.Complete(listOf(item("Tide", "Source A"), item("Tide", "Source B"), item("Tide: Part Two", "Source A"))),
        )
        val suggestions = FakeSuggestions(listOf("Tide", "Tide: Part Two", "Tida"))
        val vm = viewModel(source, suggestions, FakeHistory("tide"))

        for (q in listOf("d", "du", "dun", "tide")) vm.onAction(SearchAction.QueryChanged(q))
        settle()

        assertEquals(listOf("tide"), suggestions.asked, "only the settled query is asked")
        assertEquals(listOf("tide"), source.quickSearched)
        assertTrue(source.searched.isEmpty(), "typing does not run the full search")
        val rows = vm.state.value.status.rows()
        assertEquals(listOf("tide", "Tide: Part Two", "Tida"), rows.map { it.query })
        assertEquals(listOf(2, 1, 0), rows.map { it.sources })
    }

    @Test
    fun `the rows land before the fan-out answers, and with no count until it has`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val source = FakeSource(SearchOutcome.Complete(listOf(item("Tide", "Source A"))), quickGate = gate)
        val vm = viewModel(source, FakeSuggestions(listOf("Tide")))

        vm.onAction(SearchAction.QueryChanged("tide"))
        settle()
        assertEquals(listOf<Int?>(null), vm.state.value.status.rows().map { it.sources })

        gate.complete(Unit)
        settle()
        assertEquals(listOf<Int?>(1), vm.state.value.status.rows().map { it.sources })
    }

    @Test
    fun `nothing suggested and nothing recent is 8b, quoting the query and the selected sources`() = runTest(dispatcher) {
        val vm = viewModel(FakeSource(), FakeSuggestions(emptyList()), FakeHistory("salt road"))

        vm.onAction(SearchAction.QueryChanged("dunnne"))
        settle()

        assertEquals(SearchStatus.NoSuggestions(query = "dunnne", selected = 12), vm.state.value.status)
    }

    @Test
    fun `8b's action searches every selected source for what is typed`() = runTest(dispatcher) {
        val source = FakeSource(SearchOutcome.Complete(listOf(item("Tiddde", "Source A"))))
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

        vm.onAction(SearchAction.QueryChanged("tide"))
        settle()

        assertEquals(SearchStatus.Offline, vm.state.value.status)
    }

    /** Most installs: no source can quick-search, so no fan-out runs to find out, and the device has to be asked. */
    @Test
    fun `offline with no quick-searchable source and nothing suggested says offline`() = runTest(dispatcher) {
        val source = FakeSource(sources = SearchSources(selected = 12, total = 48, quickSearchable = false))
        val vm = viewModel(source, FakeSuggestions(emptyList()), connectivity = network(Connectivity.OFFLINE))
        settle()

        vm.onAction(SearchAction.QueryChanged("tide"))
        settle()

        assertEquals(SearchStatus.Offline, vm.state.value.status)
        assertTrue(source.quickSearched.isEmpty())
    }

    /**
     * Like `AndroidConnectivityFlow`: a cold source shared `WhileSubscribed`, so its `value` only follows the device while
     * something collects it. Starts online and goes offline after the screen is made, before anything is typed.
     */
    @Test
    fun `losing the network after search opened, nothing suggested says offline`() = runTest(dispatcher) {
        val device = MutableStateFlow(Connectivity.ONLINE)
        val shared = object : ConnectivityFlow {
            override val status: StateFlow<Connectivity> =
                flow { device.collect { emit(it) } }.stateIn(backgroundScope, SharingStarted.WhileSubscribed(), Connectivity.ONLINE)
        }
        val source = FakeSource(sources = SearchSources(selected = 12, total = 48, quickSearchable = false))
        val vm = viewModel(source, FakeSuggestions(emptyList()), connectivity = shared)
        settle()

        device.value = Connectivity.OFFLINE
        vm.onAction(SearchAction.QueryChanged("tide"))
        settle()

        assertEquals(SearchStatus.Offline, vm.state.value.status)
    }

    /** Unknown is never drawn as offline (`Connectivity.UNKNOWN`), and online with nothing suggested is 8b. */
    @Test
    fun `online or unknown with no quick-searchable source and nothing suggested is 8b`() = runTest(dispatcher) {
        for (now in listOf(Connectivity.ONLINE, Connectivity.UNKNOWN)) {
            val source = FakeSource(sources = SearchSources(selected = 12, total = 48, quickSearchable = false))
            val vm = viewModel(source, FakeSuggestions(emptyList()), connectivity = network(now))
            settle()

            vm.onAction(SearchAction.QueryChanged("tide"))
            settle()

            assertEquals(SearchStatus.NoSuggestions("tide", 12), vm.state.value.status, "$now")
        }
    }

    @Test
    fun `a fan-out that partly failed rides the chip row, and the counts are of those that answered`() = runTest(dispatcher) {
        val source = FakeSource(SearchOutcome.Partial(listOf(item("Tide", "Source A")), answered = 9, total = 12, detail = "3 FAILED"))
        val vm = viewModel(source, FakeSuggestions(listOf("Tide")))

        vm.onAction(SearchAction.QueryChanged("tide"))
        settle()

        val status = assertIs<SearchStatus.Partial>(vm.state.value.status)
        assertEquals(9 to 12, status.answered to status.total)
        assertEquals(listOf<Int?>(1), status.rows().map { it.sources })
    }

    @Test
    fun `with no source that can quick-search, the rows come with no count and nothing is searched`() = runTest(dispatcher) {
        val source = FakeSource(sources = SearchSources(selected = 12, total = 48, quickSearchable = false))
        val vm = viewModel(source, FakeSuggestions(listOf("Tide")))
        settle()

        vm.onAction(SearchAction.QueryChanged("tide"))
        settle()

        assertEquals(listOf<Int?>(null), vm.state.value.status.rows().map { it.sources })
        assertTrue(source.quickSearched.isEmpty() && source.searched.isEmpty())
    }

    @Test
    fun `a row runs the full search for its words, and shows results`() = runTest(dispatcher) {
        val source = FakeSource(SearchOutcome.Complete(listOf(item("Tide: Part Two", "Source A"))))
        val vm = viewModel(source, FakeSuggestions(listOf("Tide: Part Two")))
        vm.onAction(SearchAction.QueryChanged("tide"))
        settle()

        vm.onAction(SearchAction.SuggestionActivated("Tide: Part Two"))
        settle()

        assertEquals(listOf("Tide: Part Two"), source.searched)
        assertEquals("Tide: Part Two", vm.state.value.query, "the field shows what was searched")
        assertIs<SearchContent.Results>(assertIs<SearchStatus.Showing>(vm.state.value.status).content)
    }

    @Test
    fun `without a suggestion source, typing still runs the quick search into rails`() = runTest(dispatcher) {
        val source = FakeSource(SearchOutcome.Complete(listOf(item("Tide", "Source A"))))
        val vm = viewModel(source, suggestions = null)

        vm.onAction(SearchAction.QueryChanged("tide"))
        settle()

        assertIs<SearchContent.Results>(assertIs<SearchStatus.Showing>(vm.state.value.status).content)
    }

    private companion object {
        /** Three of the debounce's 200 ms windows: the window, and room for what it starts. */
        const val SETTLE_MS = 600L
    }
}
