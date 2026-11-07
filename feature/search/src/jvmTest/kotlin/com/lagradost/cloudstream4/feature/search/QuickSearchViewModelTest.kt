package com.lagradost.cloudstream4.feature.search

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/** The Quick Search panel's state, artboards 8d and 8e, without a screen. */
@OptIn(ExperimentalCoroutinesApi::class)
class QuickSearchViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private class StepSource : QuickSearchSource {
        val asked = mutableListOf<String>()
        val answers = MutableSharedFlow<QuickSearchProgress>()
        override fun search(query: String): Flow<QuickSearchProgress> {
            asked += query
            return answers
        }
    }

    private val item = SearchResultItem(id = SearchResultId("Vidsrc/Dune"), title = "Dune", providerName = "Vidsrc")

    @Test
    fun theQueryIsSearchedOnceTrimmedAndEachAnswerReplacesTheLast() = runTest(dispatcher) {
        val source = StepSource()
        val viewModel = QuickSearchViewModel(query = "  dune ", source = source)
        assertEquals(listOf("dune"), source.asked)
        assertNull(viewModel.state.value.progress)

        val first = QuickSearchProgress(listOf(item), answered = 1, failed = 0, total = 12)
        source.answers.emit(first)
        assertEquals(first, viewModel.state.value.progress)
        val second = first.copy(answered = 3, failed = 2)
        source.answers.emit(second)
        assertEquals(second, viewModel.state.value.progress)
        assertEquals(false, second.finished)
    }

    @Test
    fun aBlankQueryAsksNobody() = runTest(dispatcher) {
        val source = StepSource()
        QuickSearchViewModel(query = "   ", source = source)
        assertEquals(emptyList(), source.asked)
    }

    @Test
    fun retryAsksAgainFromNothing() = runTest(dispatcher) {
        val source = StepSource()
        val viewModel = QuickSearchViewModel(query = "dune", source = source)
        source.answers.emit(QuickSearchProgress(emptyList(), answered = 0, failed = 12, total = 12))

        viewModel.onAction(QuickSearchAction.Retry)
        assertEquals(listOf("dune", "dune"), source.asked)
        assertNull(viewModel.state.value.progress)
    }

    @Test
    fun eachActionBecomesItsEffect() = runTest(dispatcher) {
        val viewModel = QuickSearchViewModel(query = "dunnne", source = StepSource())
        viewModel.onAction(QuickSearchAction.FullSearchRequested)
        assertEquals(QuickSearchEffect.OpenFullSearch("dunnne"), viewModel.effect.first())
        viewModel.onAction(QuickSearchAction.ResultActivated(item.id))
        assertEquals(QuickSearchEffect.OpenResult(item.id), viewModel.effect.first())
        viewModel.onAction(QuickSearchAction.Done)
        assertEquals(QuickSearchEffect.Close, viewModel.effect.first())
    }

    @Test
    fun finishedIsEveryoneAccountedFor() {
        assertEquals(true, QuickSearchProgress(emptyList(), answered = 10, failed = 2, total = 12).finished)
        assertEquals(false, QuickSearchProgress(emptyList(), answered = 10, failed = 1, total = 12).finished)
        assertEquals(true, QuickSearchProgress(emptyList(), answered = 0, failed = 0, total = 0).finished)
    }
}
