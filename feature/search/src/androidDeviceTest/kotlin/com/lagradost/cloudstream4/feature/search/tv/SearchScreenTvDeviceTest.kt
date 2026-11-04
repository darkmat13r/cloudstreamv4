package com.lagradost.cloudstream4.feature.search.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lagradost.cloudstream4.compose.ObserveEffect
import com.lagradost.cloudstream4.core.devicetest.Dpad
import com.lagradost.cloudstream4.core.devicetest.DpadDriver
import com.lagradost.cloudstream4.core.devicetest.focusedLabel
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme
import com.lagradost.cloudstream4.feature.search.SearchEffect
import com.lagradost.cloudstream4.feature.search.SearchHistoryEntry
import com.lagradost.cloudstream4.feature.search.SearchHistoryStore
import com.lagradost.cloudstream4.feature.search.SearchOutcome
import com.lagradost.cloudstream4.feature.search.SearchResultId
import com.lagradost.cloudstream4.feature.search.SearchResultItem
import com.lagradost.cloudstream4.feature.search.SearchSource
import com.lagradost.cloudstream4.feature.search.SearchSources
import com.lagradost.cloudstream4.feature.search.SearchViewModel
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The TV search screen on a device, with a fake source and the real ViewModel: no `:app`, no network.
 * The template for a feature's device tests: a fake at the module's seam, the screen in a small host,
 * Compose semantics for what is on screen and [DpadDriver] for what a remote can do with it.
 */
@RunWith(AndroidJUnit4::class)
class SearchScreenTvDeviceTest {

    @get:Rule
    val rule = createComposeRule()

    private val dpad = DpadDriver(readFocus = { rule.focusedLabel() })
    private val source = FakeSource()
    private val effects = CopyOnWriteArrayList<SearchEffect>()

    private fun showScreen() {
        val viewModel = SearchViewModel(source = source, history = NoHistory)
        rule.setContent {
            val state by viewModel.state.collectAsState()
            ObserveEffect(viewModel.effect) { effects += it }
            CloudStreamTheme {
                Box(Modifier.fillMaxSize().background(CloudStreamTheme.colors.bg)) {
                    SearchScreenTv(state = state, onAction = viewModel::onAction)
                }
            }
        }
    }

    private fun search(query: String) {
        rule.onNode(hasSetTextAction()).performTextInput(query)
        rule.onNode(hasSetTextAction()).performImeAction()
        dpad.closeKeyboard()
    }

    private fun awaitText(text: String) = rule.waitUntil(WAIT_MS) {
        rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }

    /** Happy path: a query lands one rail per provider, and D-pad centre opens the focused card. */
    @Test
    fun searchShowsRailsAndCentreOpensTheFocusedResult() {
        source.outcome = SearchOutcome.Complete(Results)
        showScreen()

        search("blade")
        awaitText("Filmxy")
        rule.onNodeWithText("Vidplay").assertExists()
        assertEquals(listOf("blade"), source.searched)

        dpad.enter()
        // Down from the field lands on the first rail, which is what the design's fixed DOWN target asks.
        val card = generateSequence { dpad.press(Dpad.Down) }.take(4).first { it in Titles }
        dpad.center()
        rule.waitUntil(WAIT_MS) { effects.isNotEmpty() }
        val opened = effects.single() as SearchEffect.OpenResult
        assertEquals(Results.first { it.title == card }.id, opened.id)
    }

    /** Unhappy path: when every source fails, a remote can reach `Try again` and it works. */
    @Test
    fun failureRetryIsReachableWithTheDpadAndRecovers() {
        source.outcome = SearchOutcome.Failed("HTTP 503")
        showScreen()

        search("blade")
        awaitText(RETRY)
        rule.onNodeWithText("HTTP 503").assertExists()

        source.outcome = SearchOutcome.Complete(Results)
        dpad.enter()
        generateSequence { dpad.press(Dpad.Down) }.take(4).first { it == RETRY }
        dpad.center()

        awaitText("Filmxy")
        assertEquals(listOf("blade", "blade"), source.searched)
    }

    /** The field, the chip and every card can be reached by remote, and left again. */
    @Test
    fun everyStopOnTheResultsScreenIsReachableWithNoDeadEndsOrTraps() {
        source.outcome = SearchOutcome.Complete(Results)
        showScreen()

        search("blade")
        awaitText("Filmxy")

        val graph = dpad.walk()
        val expected = Titles + "Sources"
        assertEquals(graph.toString(), emptyList<String>(), graph.unreachable(expected))
        assertEquals(graph.toString(), emptyList<String>(), graph.deadEnds())
        assertEquals(graph.toString(), emptyList<String>(), graph.traps())
        // Pins today's behaviour, reported in `INF-10`: until Back closes it, the keyboard this opens
        // takes the D-pad. Change this line when that is decided.
        assertEquals(graph.toString(), setOf("blade"), graph.keyboardOpenedOn)
    }

    private companion object {
        const val WAIT_MS = 10_000L
        const val RETRY = "Try again"

        fun item(title: String, provider: String) = SearchResultItem(
            id = SearchResultId("$provider/$title"),
            title = title,
            providerName = provider,
        )

        val Results = listOf(
            item("Blade Runner", "Filmxy"),
            item("Blade Runner 2049", "Filmxy"),
            item("Blade Runner: Black Out", "Filmxy"),
            item("Blade", "Vidplay"),
            item("Blade II", "Vidplay"),
        )
        val Titles = Results.map { it.title }
    }

    private class FakeSource : SearchSource {
        @Volatile var outcome: SearchOutcome = SearchOutcome.Complete(emptyList())
        val searched = CopyOnWriteArrayList<String>()

        override suspend fun sources() = SearchSources(selected = 2, total = 2, quickSearchable = false)
        override suspend fun search(query: String): SearchOutcome = outcome.also { searched += query }
        override suspend fun quickSearch(query: String): SearchOutcome = outcome
    }

    private object NoHistory : SearchHistoryStore {
        override suspend fun recent() = emptyList<SearchHistoryEntry>()
        override suspend fun remove(query: String) = Unit
        override suspend fun clear() = Unit
    }
}
