package com.lagradost.cloudstream4.feature.search.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.requestFocus
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lagradost.cloudstream4.core.designsystem.icon.CloudStreamIcons
import com.lagradost.cloudstream4.core.designsystem.rail.NavRail
import com.lagradost.cloudstream4.core.designsystem.rail.NavRailItem
import com.lagradost.cloudstream4.core.devicetest.Dpad
import com.lagradost.cloudstream4.core.devicetest.DpadDriver
import com.lagradost.cloudstream4.core.devicetest.assertScreenshot
import com.lagradost.cloudstream4.core.devicetest.focusedLabel
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme
import com.lagradost.cloudstream4.feature.search.SearchAction
import com.lagradost.cloudstream4.feature.search.SearchContent
import com.lagradost.cloudstream4.feature.search.SearchHistoryEntry
import com.lagradost.cloudstream4.feature.search.SearchHistoryStore
import com.lagradost.cloudstream4.feature.search.SearchOutcome
import com.lagradost.cloudstream4.feature.search.SearchResultId
import com.lagradost.cloudstream4.feature.search.SearchResultItem
import com.lagradost.cloudstream4.feature.search.SearchSource
import com.lagradost.cloudstream4.feature.search.SearchSources
import com.lagradost.cloudstream4.feature.search.SearchStatus
import com.lagradost.cloudstream4.feature.search.SearchSuggestionSource
import com.lagradost.cloudstream4.feature.search.SearchTypeFilter
import com.lagradost.cloudstream4.feature.search.SearchTypeFilters
import com.lagradost.cloudstream4.feature.search.SearchViewModel
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Artboards **8a** and **8b** of `design/TV Search Suggestions.dc.html` on the device, with the board's own data: twelve
 * sources selected, the six rows it draws and the counts it gives them.
 */
@RunWith(AndroidJUnit4::class)
class SearchSuggestionsDeviceTest {

    @get:Rule
    val rule = createComposeRule()

    private val dpad = DpadDriver(readFocus = { rule.focusedLabel() })
    private val source = BoardSource()
    private val viewModel = SearchViewModel(
        source = source,
        history = BoardHistory,
        suggestions = SearchSuggestionSource { query -> if (query.startsWith("dune", ignoreCase = true)) Suggested else emptyList() },
    )

    private fun show() {
        rule.setContent {
            val state by viewModel.state.collectAsState()
            CloudStreamTheme {
                Row(Modifier.fillMaxSize().background(CloudStreamTheme.colors.bg)) {
                    NavRail(Rail, selectedIndex = 1, onSelect = {})
                    SearchScreenTv(state = state, onAction = viewModel::onAction, modifier = Modifier.weight(1f))
                }
            }
        }
        rule.waitUntil(WAIT_MS) { viewModel.state.value.sources.total > 0 && viewModel.state.value.typeFilters.available.isNotEmpty() }
    }

    /** What typing sends. The field's own IME path is `BUG-30`'s tests; this is the screen's answer to it. */
    private fun type(query: String) {
        rule.onNode(Field).requestFocus()
        rule.onNode(Field).assertIsFocused()
        viewModel.onAction(SearchAction.QueryChanged(query))
    }

    private fun awaitRows(counted: Boolean = true) = rule.waitUntil(WAIT_MS) {
        val rows = (viewModel.state.value.status.contentOrNull() as? SearchContent.Suggestions)?.rows
        rows != null && (!counted || rows.all { it.sources != null })
    }

    private fun awaitText(text: String) = rule.waitUntil(WAIT_MS) {
        rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }

    // ---- 8a ----

    @Test
    fun typingShowsTheBoardsSixRowsInOneColumnWithTheirCounts() {
        show()
        type("dune")
        awaitRows()
        rule.waitForIdle()

        val rows = (viewModel.state.value.status.contentOrNull() as SearchContent.Suggestions).rows
        assertEquals(listOf("dune", "Dune: Part Two", "Dune: Prophecy", "Dune (1984)", "Duna", "dune messiah"), rows.map { it.query })
        assertEquals(listOf(7, 9, 6, 4, 2, 0), rows.map { it.sources })
        rule.onNodeWithText("RECENT · ALSO SUGGESTED").assertExists()
        rule.onNodeWithText("RECENT · 2 days ago").assertExists()
        for (count in listOf(7, 9, 6, 4, 2, 0)) rule.onNodeWithText("$count SOURCES").assertExists()
    }

    @Test
    fun downFromTheFieldWalksTheRowsAndRightStaysOnARow() {
        show()
        type("dune")
        awaitRows()
        rule.waitForIdle()

        assertEquals("Movies", dpad.press(Dpad.Down))
        assertEquals("dune", dpad.press(Dpad.Down))
        assertEquals("Dune: Part Two", dpad.press(Dpad.Down))
        assertEquals("Dune: Part Two", dpad.press(Dpad.Right))
        assertEquals("dune", dpad.press(Dpad.Up))
    }

    @Test
    fun okOnARowSearchesItsWordsAndShowsTheirRails() {
        show()
        type("dune")
        awaitRows()
        rule.waitForIdle()
        assertEquals("Movies", dpad.press(Dpad.Down))
        assertEquals("dune", dpad.press(Dpad.Down))
        assertEquals("Dune: Part Two", dpad.press(Dpad.Down))

        dpad.center()
        rule.waitUntil(WAIT_MS) { viewModel.state.value.status.contentOrNull() is SearchContent.Results }

        assertEquals(listOf("Dune: Part Two"), source.searched.toList())
        assertEquals("Dune: Part Two", viewModel.state.value.query)
    }

    // ---- 8b ----

    @Test
    fun nothingMatchesKeepsTheQueryAndPutsItsActionOnTheChipRow() {
        show()
        type("dunnne")
        rule.waitUntil(WAIT_MS) { viewModel.state.value.status is SearchStatus.NoSuggestions }
        awaitText(SEARCH_ALL)
        rule.waitForIdle()

        rule.onNodeWithText("Nothing matches “dunnne”").assertExists()
        rule.onNodeWithText("Press the keyboard’s search key to search all 12 sources anyway.").assertExists()
        rule.onNodeWithText("SUGGESTIONS ANSWERED · 0 MATCHES").assertExists()
        rule.onNode(Field).assertIsFocused()

        // The chips first, then the action at the row's end; nothing below it to fall into.
        assertEquals("Movies", dpad.press(Dpad.Down))
        assertEquals(SEARCH_ALL, generateSequence { dpad.press(Dpad.Right) }.take(CHIP_PRESSES).first { it == SEARCH_ALL })
        dpad.center()
        rule.waitUntil(WAIT_MS) { source.searched.isNotEmpty() }
        assertEquals(listOf("dunnne"), source.searched.toList())
    }

    // ---- Screenshots ----

    /** 8a as drawn: every count final, the focus on the collapsed row. */
    @Test
    fun screenshotSuggestionsCountedFocusOnTheFirstRow() {
        show()
        type("dune")
        awaitRows()
        dpad.press(Dpad.Down)
        assertEquals("dune", dpad.press(Dpad.Down))
        rule.waitForIdle()
        rule.assertScreenshot("search-suggestions-counted")
    }

    /** Before the fan-out answers: rows, and no number that could change. */
    @Test
    fun screenshotSuggestionsBeforeTheCounts() {
        source.gate = CompletableDeferred()
        show()
        type("dune")
        awaitRows(counted = false)
        rule.waitForIdle()
        rule.assertScreenshot("search-suggestions-counting")
        source.gate?.complete(Unit)
    }

    /** 8a's chip row: three of twelve failed, and the counts are of the nine that answered. */
    @Test
    fun screenshotSuggestionsPartialFailure() {
        source.failed = 3
        show()
        type("dune")
        awaitRows()
        awaitText(RETRY_FAILED)
        rule.waitForIdle()
        rule.assertScreenshot("search-suggestions-partial")
    }

    /** 8b: the field keeps focus and the query stays typed. */
    @Test
    fun screenshotNothingMatchesFocusOnTheField() {
        show()
        type("dunnne")
        awaitText(SEARCH_ALL)
        rule.waitForIdle()
        rule.assertScreenshot("search-suggestions-nothing-matches")
    }

    private fun SearchStatus.contentOrNull(): SearchContent? = when (this) {
        is SearchStatus.Showing -> content
        is SearchStatus.Partial -> content
        else -> null
    }

    /** Twelve sources, each carrying the titles the board counts against them. */
    private class BoardSource : SearchSource {
        @Volatile var gate: CompletableDeferred<Unit>? = null
        @Volatile var failed = 0
        val searched = CopyOnWriteArrayList<String>()

        override suspend fun sources() = SearchSources(selected = 12, total = 48, quickSearchable = true)

        override suspend fun typeFilters() = SearchTypeFilters(
            available = listOf(SearchTypeFilter.Movies, SearchTypeFilter.TvSeries, SearchTypeFilter.Anime, SearchTypeFilter.Live),
            selected = setOf(SearchTypeFilter.Movies, SearchTypeFilter.TvSeries),
        )

        override suspend fun quickSearch(query: String): SearchOutcome {
            gate?.await()
            val items = Counts.flatMap { (title, sources) -> (1..sources).map { hit(title, "Source $it") } }
            return if (failed == 0) {
                SearchOutcome.Complete(items)
            } else {
                SearchOutcome.Partial(items, answered = 12 - failed, total = 12, detail = "TIMEOUT 10S")
            }
        }

        override suspend fun search(query: String): SearchOutcome {
            searched += query
            return SearchOutcome.Complete(listOf(hit(query, "Source 1")))
        }
    }

    private object BoardHistory : SearchHistoryStore {
        override suspend fun recent() = listOf(SearchHistoryEntry("dune", "today"), SearchHistoryEntry("dune messiah", "2 days ago"))
        override suspend fun remove(query: String) = Unit
        override suspend fun clear() = Unit
    }

    private companion object {
        const val WAIT_MS = 10_000L
        const val CHIP_PRESSES = 8
        const val SEARCH_ALL = "Search all 12 sources"
        const val RETRY_FAILED = "Retry failed"
        val Field = SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText)
        val Rail = listOf(
            NavRailItem("Home", CloudStreamIcons.Home),
            NavRailItem("Search", CloudStreamIcons.Search),
            NavRailItem("Library", CloudStreamIcons.Library),
            NavRailItem("Downloads", CloudStreamIcons.Download),
            NavRailItem("Settings", CloudStreamIcons.Settings),
        )
        val Suggested = listOf("Dune", "Dune: Part Two", "Dune: Prophecy", "Dune (1984)", "Duna")

        /** 8a's `raw`: the counts the board gives each title. `dune messiah` is carried by nobody. */
        val Counts = listOf("Dune" to 7, "Dune: Part Two" to 9, "Dune: Prophecy" to 6, "Dune (1984)" to 4, "Duna" to 2)

        fun hit(title: String, provider: String) =
            SearchResultItem(id = SearchResultId("$provider/$title"), title = title, providerName = provider)
    }
}
