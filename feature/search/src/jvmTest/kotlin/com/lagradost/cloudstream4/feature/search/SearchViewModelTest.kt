package com.lagradost.cloudstream4.feature.search

import com.lagradost.cloudstream4.core.designsystem.state.FailureNotice
import com.lagradost.cloudstream4.core.designsystem.state.StateAction
import com.lagradost.cloudstream4.core.designsystem.state.StatePrompt
import com.lagradost.cloudstream4.core.designsystem.state.UiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext

/**
 * The behaviours that make this ViewModel worth having: every outcome maps to a distinct `UiState`,
 * and typing is debounced rather than sending one request per keystroke.
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

    private fun prompt(headline: String) =
        StatePrompt(headline = headline, body = "body", action = StateAction("label") {})

    private val prompts = object : SearchPrompts {
        override fun idle() = prompt("idle")
        override fun noResults(query: String, onRetry: () -> Unit) = prompt("no results for $query")
        override fun failed(detail: String, onRetry: () -> Unit) = prompt("failed")
        override fun offline(onRetry: () -> Unit) = prompt("offline")
        override fun partial(answered: Int, total: Int, detail: String, onRetry: () -> Unit) =
            FailureNotice("$answered of $total answered", detail, StateAction("retry") {})
    }

    private fun item(title: String) =
        SearchResultItem(id = SearchResultId(title), title = title, providerName = "p")

    private class FakeSource(
        var outcome: SearchOutcome = SearchOutcome.Complete(emptyList()),
    ) : SearchSource {
        val searched = mutableListOf<String>()
        val quickSearched = mutableListOf<String>()
        override suspend fun search(query: String): SearchOutcome {
            searched += query
            return outcome
        }
        override suspend fun quickSearch(query: String): SearchOutcome {
            quickSearched += query
            return outcome
        }
    }

    private fun viewModel(source: SearchSource) =
        SearchViewModel(source, prompts, dispatcher)

    @Test
    fun `starts idle rather than loading, because nothing is in flight`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        try {
            val vm = viewModel(FakeSource())
            val results = vm.state.value.results
            assertIs<UiState.Empty>(results)
            assertEquals("idle", results.prompt.headline)
            assertEquals("", vm.state.value.query)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `the query echoes into state immediately`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        try {
            val vm = viewModel(FakeSource())
            vm.onAction(SearchAction.QueryChanged("dune"))
            assertEquals("dune", vm.state.value.query)
        } finally {
            Dispatchers.resetMain()
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
    fun `submit runs the full search without waiting for the debounce`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        try {
            val source = FakeSource(SearchOutcome.Complete(listOf(item("Dune"))))
            val vm = viewModel(source)
            vm.onAction(SearchAction.QueryChanged("dune"))
            vm.onAction(SearchAction.Submit)

            assertEquals(listOf("dune"), source.searched)
            assertIs<UiState.Content<List<SearchResultItem>>>(vm.state.value.results)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `each outcome becomes its own UiState`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        try {
            val source = FakeSource()
            val vm = viewModel(source)
            vm.onAction(SearchAction.QueryChanged("dune"))

            source.outcome = SearchOutcome.Complete(listOf(item("Dune")))
            vm.onAction(SearchAction.Submit)
            assertIs<UiState.Content<List<SearchResultItem>>>(vm.state.value.results)

            // A successful search that matched nothing is Empty, not Error.
            source.outcome = SearchOutcome.Complete(emptyList())
            vm.onAction(SearchAction.Submit)
            assertIs<UiState.Empty>(vm.state.value.results)

            // Partial keeps the content and reports the shortfall; it must not become Error.
            source.outcome = SearchOutcome.Partial(listOf(item("Dune")), 9, 12, "host down")
            vm.onAction(SearchAction.Submit)
            val partial = vm.state.value.results
            assertIs<UiState.Partial<List<SearchResultItem>>>(partial)
            assertEquals(1, partial.value.size, "partial content must still be shown")
            assertEquals("9 of 12 answered", partial.notice.headline)
            assertEquals("host down", partial.notice.detail)

            source.outcome = SearchOutcome.Failed("503")
            vm.onAction(SearchAction.Submit)
            val error = vm.state.value.results
            assertIs<UiState.Error>(error)
            assertEquals("503", error.detail)

            source.outcome = SearchOutcome.Offline
            vm.onAction(SearchAction.Submit)
            assertIs<UiState.Offline>(vm.state.value.results)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `clearing the field returns to idle and does not search`() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        try {
            val source = FakeSource(SearchOutcome.Complete(listOf(item("Dune"))))
            val vm = viewModel(source)
            vm.onAction(SearchAction.QueryChanged("dune"))
            settle()
            vm.onAction(SearchAction.Cleared)
            settle()

            assertEquals("", vm.state.value.query)
            val results = vm.state.value.results
            assertIs<UiState.Empty>(results)
            assertEquals("idle", results.prompt.headline)
        } finally {
            Dispatchers.resetMain()
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
            vm.onAction(SearchAction.ResultActivated(SearchResultId("abc")))
            settle()

            assertEquals(
                listOf<SearchEffect>(SearchEffect.OpenResult(SearchResultId("abc"))),
                received,
            )
        } finally {
            Dispatchers.resetMain()
        }
    }
}
