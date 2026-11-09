package com.lagradost.cloudstream4.feature.search.tv

import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.lagradost.cloudstream4.core.designsystem.icon.CloudStreamIcons
import com.lagradost.cloudstream4.core.designsystem.rail.NavRail
import com.lagradost.cloudstream4.core.designsystem.rail.NavRailItem
import com.lagradost.cloudstream4.core.devicetest.Dpad
import com.lagradost.cloudstream4.core.devicetest.DpadDriver
import com.lagradost.cloudstream4.core.devicetest.TouchModeReset
import com.lagradost.cloudstream4.core.devicetest.assertScreenshot
import com.lagradost.cloudstream4.core.devicetest.focusedLabel
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme
import com.lagradost.cloudstream4.feature.search.SearchAction
import com.lagradost.cloudstream4.feature.search.SearchHistoryEntry
import com.lagradost.cloudstream4.feature.search.SearchHistoryStore
import com.lagradost.cloudstream4.feature.search.SearchOutcome
import com.lagradost.cloudstream4.feature.search.SearchResultId
import com.lagradost.cloudstream4.feature.search.SearchResultItem
import com.lagradost.cloudstream4.feature.search.SearchSource
import com.lagradost.cloudstream4.feature.search.SearchSourceChoice
import com.lagradost.cloudstream4.feature.search.SearchSourceHealth
import com.lagradost.cloudstream4.feature.search.SearchSources
import com.lagradost.cloudstream4.feature.search.SearchStatus
import com.lagradost.cloudstream4.feature.search.SearchTypeFilter
import com.lagradost.cloudstream4.feature.search.SearchTypeFilters
import com.lagradost.cloudstream4.feature.search.SearchViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Artboard 4d's panel, the type chips, 8c's action, and where the D-pad goes between them, with a fake
 * that behaves as `:app`'s source does: the picks and the lit chips decide who a search asks.
 */
@RunWith(AndroidJUnit4::class)
class SearchFiltersDeviceTest {

    /** `BUG-59`: starts and ends out of touch mode, whatever ran before it. */
    @get:Rule
    val touchMode = TouchModeReset()

    @get:Rule
    val rule = createComposeRule()

    private val dpad = DpadDriver(readFocus = { rule.focusedLabel() })
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val source = FilterSource()
    private val leftEdges = CopyOnWriteArrayList<Unit>()
    private val viewModel = SearchViewModel(source = source, history = NoHistory)

    private fun show(viewModel: SearchViewModel = this.viewModel) {
        rule.setContent {
            val state by viewModel.state.collectAsState()
            CloudStreamTheme {
                Box(Modifier.fillMaxSize().background(CloudStreamTheme.colors.bg)) {
                    PlatformBackClosesThePanel(state.sourcePanel != null, viewModel)
                    SearchScreenTv(
                        state = state,
                        onAction = viewModel::onAction,
                        onLeftEdge = { leftEdges += Unit },
                    )
                }
            }
        }
    }

    /** What a host owes, the same as `SearchTvFragment`. `TvSearchNavigationTest` covers the fragment's own. */
    @Composable
    private fun PlatformBackClosesThePanel(open: Boolean, viewModel: SearchViewModel) {
        val dispatcher = (LocalContext.current as ComponentActivity).onBackPressedDispatcher
        DisposableEffect(open) {
            val callback = object : OnBackPressedCallback(open) {
                override fun handleOnBackPressed() = viewModel.onAction(SearchAction.SourcePanelDismissed)
            }
            dispatcher.addCallback(callback)
            onDispose { callback.remove() }
        }
    }

    private fun showWithRail() {
        rule.setContent {
            val state by viewModel.state.collectAsState()
            CloudStreamTheme {
                Row(Modifier.fillMaxSize().background(CloudStreamTheme.colors.bg)) {
                    NavRail(Rail, selectedIndex = 1, onSelect = {})
                    SearchScreenTv(state = state, onAction = viewModel::onAction, modifier = Modifier.weight(1f))
                }
            }
        }
    }

    private fun awaitText(text: String) = rule.waitUntil(WAIT_MS) {
        rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }

    private fun awaitGone(text: String) = rule.waitUntil(WAIT_MS) {
        rule.onAllNodesWithText(text).fetchSemanticsNodes().isEmpty()
    }

    private fun searchFor(query: String = QUERY) {
        viewModel.onAction(SearchAction.RecentActivated(query))
        rule.waitUntil(WAIT_MS) { viewModel.state.value.status !is SearchStatus.Loading }
    }

    private fun onTheField() {
        rule.onNode(Field).requestFocus()
        rule.onNode(Field).assertIsFocused()
    }

    private fun openPanel() {
        onTheField()
        assertEquals(SOURCES, dpad.press(Dpad.Right))
        dpad.center()
        awaitText(DONE)
    }

    private fun pressDownUntil(label: String) {
        val reached = generateSequence { dpad.press(Dpad.Down) }.take(PANEL_PRESSES).firstOrNull { it == label }
        assertEquals(label, reached)
    }

    // ---- The panel ----

    @Test
    fun theSourcesButtonOpensThePanelWithFocusOnItsFirstRow() {
        show()
        rule.waitUntil(WAIT_MS) { viewModel.state.value.sources.total > 0 }
        openPanel()
        assertEquals("Source A", dpad.focused())
        rule.onNodeWithText("2 OF 3 ENABLED · CHANGES APPLY ON DONE").assertExists()
    }

    @Test
    fun doneAppliesADeselectionAndTheResultsChange() {
        source.types = emptySet() // Nothing lit, so every pick answers.
        show()
        rule.waitUntil(WAIT_MS) { viewModel.state.value.sources.total > 0 }
        searchFor()
        awaitText("Source B")

        openPanel()
        pressDownUntil("Source B")
        dpad.center()
        rule.onNodeWithText("1 OF 3 ENABLED · CHANGES APPLY ON DONE").assertExists()
        assertEquals("Nothing is written before Done", setOf("Source A", "Source B"), source.selected)

        pressDownUntil(DONE)
        dpad.center()
        awaitGone(DONE)

        rule.waitUntil(WAIT_MS) { rule.onAllNodesWithText("Source B").fetchSemanticsNodes().isEmpty() }
        rule.onNodeWithText("Source A").assertExists()
        assertEquals(setOf("Source A"), source.selected)
        rule.onNodeWithText("1 / 3").assertExists()
        assertEquals("Focus returns to what opened it", SOURCES, dpad.focused())
    }

    @Test
    fun backDiscardsTheDraftAndKeepsTheResults() {
        source.types = emptySet() // Nothing lit, so every pick answers.
        show()
        viewModel.onAction(SearchAction.SourcesChanged) // The ViewModel read the count before this test set it.
        rule.waitUntil(WAIT_MS) { viewModel.state.value.sources.selected == 2 }
        searchFor()
        awaitText("Source B")
        val searches = source.searched.size

        openPanel()
        dpad.center() // Source A, off in the draft.
        rule.onNodeWithText("1 OF 3 ENABLED · CHANGES APPLY ON DONE").assertExists()
        device.pressBack()
        awaitGone(DONE)

        assertEquals(setOf("Source A", "Source B"), source.selected)
        assertEquals(searches, source.searched.size)
        rule.onNodeWithText("2 / 3").assertExists()
        assertEquals(SOURCES, dpad.focused())
    }

    /** A card a user left for the rail is the card they come back to, and not the geometry's pick. */
    @Test
    fun rightFromTheRailReturnsToTheCardThatLeft() {
        showWithRail()
        rule.waitUntil(WAIT_MS) { viewModel.state.value.sources.total > 0 }
        searchFor()
        awaitText("Source A")
        onTheField()
        dpad.press(Dpad.Down) // The chips.
        val card = dpad.press(Dpad.Down)
        val second = dpad.press(Dpad.Right)
        assertTrue("Right moved along the rail from $card", second != card)
        assertEquals(card, dpad.press(Dpad.Left))

        val rail = dpad.press(Dpad.Left)
        assertTrue("Left reached the rail, got $rail", rail != card)
        assertEquals(card, dpad.press(Dpad.Right))
    }

    /** The panel holds focus until it closes: Left stays in it, and Done is reachable. */
    @Test
    fun thePanelTrapsFocusAndHasNoDeadEnd() {
        show()
        rule.waitUntil(WAIT_MS) { viewModel.state.value.sources.total > 0 }
        openPanel()
        assertEquals("Source A", dpad.press(Dpad.Left))
        assertEquals("Source A", dpad.press(Dpad.Right))
        assertEquals("Source B", dpad.press(Dpad.Down))
        pressDownUntil(DONE)
        assertEquals("Down at the foot stays", DONE, dpad.press(Dpad.Down))
        val back = generateSequence { dpad.press(Dpad.Up) }.take(PANEL_PRESSES).first { it == "Source A" }
        assertEquals("Source A", back)
    }

    // ---- The chips ----

    @Test
    fun eachTypeChipTogglesAndNarrowsTheSearch() {
        show()
        rule.waitUntil(WAIT_MS) { viewModel.state.value.typeFilters.available.isNotEmpty() }
        searchFor()
        awaitText("Source A") // Movies is lit and only Source A serves it.
        awaitGone("Source B")
        rule.onNodeWithText("Movies").assertIsSelected()
        rule.onNodeWithText("Anime").assertIsNotSelected()

        rule.onNodeWithText("Movies").requestFocus()
        dpad.center() // Movies off: nothing lit, so nothing narrows.
        rule.waitUntil(WAIT_MS) { SearchTypeFilter.Movies !in source.types }
        rule.onNodeWithText("Movies").assertIsNotSelected()
        awaitText("Source B")
        awaitText("Source A")

        assertEquals("Anime", dpad.press(Dpad.Right))
        dpad.center() // Anime on: only Source B.
        rule.waitUntil(WAIT_MS) { SearchTypeFilter.Anime in source.types }
        rule.onNodeWithText("Anime").assertIsSelected()
        awaitGone("Source A")
        awaitText("Source B")

        assertEquals("Livestreams", dpad.press(Dpad.Right))
        dpad.center()
        rule.waitUntil(WAIT_MS) { SearchTypeFilter.Live in source.types }
        rule.onNodeWithText("Livestreams").assertIsSelected()
    }

    // ---- 8c ----

    @Test
    fun noResultsActionRidesTheChipRowClearsBothFiltersAndSearches() {
        source.corpus = emptyMap()
        show()
        rule.waitUntil(WAIT_MS) { viewModel.state.value.sources.total > 0 }
        searchFor()
        awaitText(EVERYTHING)
        rule.onNodeWithText("Movies only. Your type filter is narrowing the search.", substring = true).assertExists()

        // On the chip row's line and at the end of the row, and focused without a press.
        val action = rule.onNodeWithText(EVERYTHING).getBoundsInRoot()
        val chip = rule.onNodeWithText("Movies").getBoundsInRoot()
        assertEquals(((action.top + action.bottom) / 2).value, ((chip.top + chip.bottom) / 2).value, PIXEL_DP)
        assertTrue("It does not float", action.top < 140.dp)
        assertEquals(EVERYTHING, rule.focusedLabel())

        val before = source.searched.size
        source.corpus = Corpus
        dpad.center()

        rule.waitUntil(WAIT_MS) { source.searched.size > before }
        assertEquals(setOf("Source A", "Source B", "Source C"), source.selected)
        assertEquals(emptySet<SearchTypeFilter>(), source.types)
        awaitText("Source C")
        rule.onNodeWithText("Movies").assertIsNotSelected()
    }

    // ---- The D-pad ----

    @Test
    fun downFromTheFieldGoesToTheFirstChipAndDownFromAChipToTheFirstCard() {
        show()
        rule.waitUntil(WAIT_MS) { viewModel.state.value.typeFilters.available.isNotEmpty() }
        searchFor()
        awaitText("Source A")
        onTheField()
        assertEquals("Movies", dpad.press(Dpad.Down))
        assertEquals(FIRST_CARD, dpad.press(Dpad.Down))
    }

    @Test
    fun downFromAFarChipAlsoGoesToTheFirstCard() {
        show()
        rule.waitUntil(WAIT_MS) { viewModel.state.value.typeFilters.available.isNotEmpty() }
        searchFor()
        awaitText("Source A")
        rule.onNodeWithText("Livestreams").requestFocus()
        assertEquals(FIRST_CARD, dpad.press(Dpad.Down))
    }

    @Test
    fun withNoChipsDownFromTheFieldGoesToTheFirstCardNotTheThird() {
        val bare = SearchViewModel(BareSource, NoHistory)
        show(bare)
        bare.onAction(SearchAction.RecentActivated(QUERY))
        awaitText("Source A")
        onTheField()
        assertEquals(FIRST_CARD, dpad.press(Dpad.Down))
    }

    @Test
    fun rightAtTheEndOfARailStaysOnTheRail() {
        val bare = SearchViewModel(BareSource, NoHistory)
        show(bare)
        bare.onAction(SearchAction.RecentActivated(QUERY))
        awaitText("Source A")
        onTheField()
        dpad.press(Dpad.Down)
        val last = BareSource.items.last { it.providerName == "Source A" }.title
        val reached = generateSequence { dpad.press(Dpad.Right) }.take(PANEL_PRESSES).first { it == last }
        assertEquals(last, reached)
        assertEquals("Right past the rail's last card moved focus", last, dpad.press(Dpad.Right))
    }

    @Test
    fun leftFromTheFieldLeavesThroughTheHost() {
        show()
        rule.waitUntil(WAIT_MS) { viewModel.state.value.sources.total > 0 }
        onTheField()
        dpad.press(Dpad.Left)
        rule.waitUntil(WAIT_MS) { leftEdges.isNotEmpty() }
    }

    /** With the query in the field, Left still leaves rather than moving a cursor it cannot see. */
    @Test
    fun leftFromAFieldHoldingTextAlsoLeaves() {
        show()
        rule.waitUntil(WAIT_MS) { viewModel.state.value.sources.total > 0 }
        searchFor()
        onTheField()
        dpad.press(Dpad.Left)
        rule.waitUntil(WAIT_MS) { leftEdges.isNotEmpty() }
    }

    @Test
    fun theScreenWithChipsHasNoUnreachableStopNoDeadEndAndNoTrap() {
        show()
        rule.waitUntil(WAIT_MS) { viewModel.state.value.typeFilters.available.isNotEmpty() }
        searchFor()
        awaitText("Source A")
        onTheField()
        val graph = dpad.walk()
        assertEquals(graph.toString(), emptyList<String>(), graph.unreachable(listOf(SOURCES, "Movies", "Anime", FIRST_CARD)))
        assertEquals(graph.toString(), emptyList<String>(), graph.deadEnds())
        assertEquals(graph.toString(), emptyList<String>(), graph.traps())
    }

    // ---- A rail's trailing card ----

    @Test
    fun theTrailingCardPagesInPlaceAndFocusGoesToTheFirstNewCard() {
        val paged = SearchViewModel(PagedSource, NoHistory)
        show(paged)
        paged.onAction(SearchAction.RecentActivated(QUERY))
        awaitText("Source A")
        onTheField()
        assertEquals(FIRST_CARD, dpad.press(Dpad.Down))
        assertEquals("Load more", dpad.press(Dpad.Right))
        assertEquals("Load more", dpad.press(Dpad.Right))
        dpad.center()
        rule.waitUntil(WAIT_MS) { rule.focusedLabel() == "Second page" }
        // That was the last page: the card has gone and the rail ends in a wall.
        assertEquals("Second page", dpad.press(Dpad.Right))
    }

    // ---- 4c ----

    @Test
    fun removingARowLeavesFocusOnARow() {
        val history = FilledHistory()
        val vm = SearchViewModel(source, history)
        show(vm)
        awaitText(history.first())
        onTheField()
        // The chips come between the field and the list.
        generateSequence { dpad.press(Dpad.Down) }.take(4).first { it == history.first() }
        dpad.press(Dpad.Right)
        dpad.center()
        rule.waitUntil(WAIT_MS) { history.entries.size == 7 }
        rule.waitUntil(WAIT_MS) { rule.focusedLabel() == history.first() }
    }

    @Test
    fun theHistoryHasNoUnreachableStopNoDeadEndAndNoTrap() {
        val history = FilledHistory()
        val vm = SearchViewModel(source, history)
        show(vm)
        awaitText(history.first())
        onTheField()
        val graph = dpad.walk()
        assertEquals(graph.toString(), emptyList<String>(), graph.unreachable(listOf(SOURCES, history.first(), "Clear all searches")))
        assertEquals(graph.toString(), emptyList<String>(), graph.deadEnds())
        assertEquals(graph.toString(), emptyList<String>(), graph.traps())
    }

    @Test
    fun screenshotHistoryFilled() {
        rule.setContent {
            val vm = remember { SearchViewModel(source, FilledHistory()) }
            val state by vm.state.collectAsState()
            CloudStreamTheme {
                Row(Modifier.fillMaxSize().background(CloudStreamTheme.colors.bg)) {
                    NavRail(Rail, selectedIndex = 1, onSelect = {})
                    SearchScreenTv(state = state, onAction = vm::onAction, modifier = Modifier.weight(1f))
                }
            }
        }
        awaitText("salt road")
        rule.onNode(Field).requestFocus()
        generateSequence { dpad.press(Dpad.Down) }.take(4).first { it == "salt road" }
        rule.waitForIdle()
        rule.assertScreenshot("search-history-filled")
    }

    // ---- Geometry ----

    @Test
    fun theSourcesButtonIsTheBoardsSize() {
        show()
        rule.waitUntil(WAIT_MS) { viewModel.state.value.sources.total > 0 }
        val bounds = rule.onNode(Sources).getBoundsInRoot()
        assertEquals(SearchScreenTvDefaults.SourcesButtonWidth.value, bounds.width.value, PIXEL_DP)
        assertEquals(48f, bounds.height.value, PIXEL_DP)
    }

    // ---- Screenshots ----

    private fun shotReady() {
        rule.waitUntil(WAIT_MS) { viewModel.state.value.sources.total > 0 }
        rule.waitForIdle()
    }

    /** A full rail scrolled to its end, where `SeeAllCard` waits. 4a's `See all`, paging in place. */
    @Test
    fun screenshotFullRailEndingInTheSeeAllTile() {
        val full = SearchViewModel(FullPagedSource, NoHistory)
        show(full)
        full.onAction(SearchAction.RecentActivated(QUERY))
        awaitText("Source A")
        onTheField()
        assertEquals(FullPagedSource.titles.first(), dpad.press(Dpad.Down))
        val reached = generateSequence { dpad.press(Dpad.Right) }.take(FullPagedSource.titles.size + 1).firstOrNull { it == "Load more" }
        assertEquals("Load more", reached)
        rule.waitForIdle()
        rule.assertScreenshot("search-rail-ends-in-see-all")
    }

    @Test
    fun screenshotSourcePanelOpen() {
        showWithRail()
        shotReady()
        openPanel()
        rule.mainClock.advanceTimeBy(PANEL_SETTLE_MS)
        rule.waitForIdle()
        rule.assertScreenshot("search-source-panel-open")
    }

    @Test
    fun screenshotTypeChipsFocusOnAChip() {
        showWithRail()
        shotReady()
        searchFor()
        awaitText("Source A")
        rule.onNodeWithText("Anime").requestFocus()
        rule.waitForIdle()
        rule.assertScreenshot("search-type-chips-focus-chip")
    }

    @Test
    fun screenshotSourcesButtonFocused() {
        showWithRail()
        shotReady()
        onTheField()
        assertEquals(SOURCES, dpad.press(Dpad.Right))
        rule.waitForIdle()
        rule.assertScreenshot("search-sources-button-focused")
    }

    @Test
    fun screenshotNoResultsFocusOnItsAction() {
        source.corpus = emptyMap()
        showWithRail()
        shotReady()
        searchFor()
        awaitText(EVERYTHING)
        rule.waitForIdle()
        assertEquals(EVERYTHING, rule.focusedLabel())
        rule.assertScreenshot("search-no-results-focus-action")
    }

    /** Real counts on a chip row that is also holding the failure group. */
    @Test
    fun screenshotPartialFailureNineOfFortyEight() {
        source.partial = SearchOutcome.Partial(
            items = BareSource.items,
            answered = 9,
            total = 12,
            detail = "TIMEOUT 10S",
        )
        source.count = SearchSources(selected = 9, total = 48, quickSearchable = false)
        showWithRail()
        viewModel.onAction(SearchAction.SourcesChanged) // The ViewModel read the count before this test set it.
        rule.waitUntil(WAIT_MS) { viewModel.state.value.sources.total == 48 }
        shotReady()
        searchFor()
        awaitText(RETRY_FAILED)
        rule.onNodeWithText(RETRY_FAILED).requestFocus()
        rule.waitForIdle()
        rule.assertScreenshot("search-partial-failure-nine-of-forty-eight")
    }

    private companion object {
        const val WAIT_MS = 10_000L

        /** A layout rounds to whole pixels, and this emulator has 1.33 of them to the dp. */
        const val PIXEL_DP = 1f
        const val PANEL_PRESSES = 8
        const val PANEL_SETTLE_MS = 500L
        const val QUERY = "salt"
        const val SOURCES = "Sources"
        const val DONE = "Done"
        const val EVERYTHING = "Search everything"
        const val RETRY_FAILED = "Retry failed"
        const val FIRST_CARD = "Salt Road"
        val Field = SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText)
        val Sources = hasText(SOURCES)
        val Rail = listOf(
            NavRailItem("Home", CloudStreamIcons.Home),
            NavRailItem("Search", CloudStreamIcons.Search),
            NavRailItem("Library", CloudStreamIcons.Library),
            NavRailItem("Downloads", CloudStreamIcons.Download),
            NavRailItem("Settings", CloudStreamIcons.Settings),
        )

        fun hit(title: String, provider: String) =
            SearchResultItem(id = SearchResultId("$provider/$title"), title = title, providerName = provider)

        val Serves = mapOf(
            "Source A" to setOf(SearchTypeFilter.Movies),
            "Source B" to setOf(SearchTypeFilter.Anime, SearchTypeFilter.Live),
            "Source C" to setOf(SearchTypeFilter.Anime),
        )
        val Corpus = mapOf(
            "Source A" to listOf(hit("Salt Road", "Source A"), hit("Salt Road II", "Source A")),
            "Source B" to listOf(hit("Salt Flat", "Source B"), hit("Salt Flat II", "Source B")),
            "Source C" to listOf(hit("Night Ferry", "Source C")),
        )
    }

    /** Picks, then chips, each dropped when it would leave nobody, as `selectSearchProviders` does. */
    private class FilterSource : SearchSource {
        @Volatile var selected = setOf("Source A", "Source B")
        @Volatile var types = setOf(SearchTypeFilter.Movies)
        @Volatile var corpus = Corpus
        @Volatile var partial: SearchOutcome? = null
        @Volatile var count: SearchSources? = null
        val searched = CopyOnWriteArrayList<String>()

        private fun asked(): List<String> {
            val picks = Serves.keys.filter { it in selected }
            return picks.filter { provider -> Serves.getValue(provider).any { it in types } }.ifEmpty { picks }
        }

        override suspend fun sources() = count ?: SearchSources(asked().size, Serves.size, quickSearchable = false)

        override suspend fun choices() = Serves.keys.map { provider ->
            SearchSourceChoice(
                id = provider,
                name = provider,
                detail = "EN · " + Serves.getValue(provider).joinToString(", ") { it.name.uppercase() },
                selected = provider in selected,
                health = if (provider == "Source C") SearchSourceHealth.Failed else SearchSourceHealth.Ok,
            )
        }

        override suspend fun selectSources(ids: Set<String>) {
            selected = ids
        }

        override suspend fun typeFilters() =
            SearchTypeFilters(listOf(SearchTypeFilter.Movies, SearchTypeFilter.Anime, SearchTypeFilter.Live), types)

        override suspend fun selectTypes(types: Set<SearchTypeFilter>) {
            this.types = types
        }

        override suspend fun clearFilters() {
            selected = Serves.keys
            types = emptySet()
        }

        override suspend fun search(query: String): SearchOutcome {
            searched += query
            return partial ?: SearchOutcome.Complete(asked().flatMap { corpus[it].orEmpty() })
        }

        override suspend fun quickSearch(query: String) = search(query)
    }

    /** A source with no filters of its own, as desktop's is: no chips are drawn. */
    private object BareSource : SearchSource {
        val items = listOf(
            hit("Salt Road", "Source A"),
            hit("Salt Road II", "Source A"),
            hit("Dusk", "Source A"),
            hit("Last Light", "Source A"),
            hit("Salt Flat", "Source B"),
            hit("Salt Flat II", "Source B"),
        )

        override suspend fun sources() = SearchSources(2, 2, quickSearchable = false)
        override suspend fun search(query: String): SearchOutcome = SearchOutcome.Complete(items)
        override suspend fun quickSearch(query: String) = search(query)
    }

    /** One rail with one more page, which is its last. */
    private object PagedSource : SearchSource {
        override suspend fun sources() = SearchSources(1, 1, quickSearchable = false)
        override suspend fun search(query: String): SearchOutcome =
            SearchOutcome.Complete(listOf(hit(FIRST_CARD, "Source A")), setOf("Source A"))
        override suspend fun quickSearch(query: String) = search(query)
        override suspend fun searchMore(query: String, providerName: String, page: Int): SearchOutcome =
            SearchOutcome.Complete(listOf(hit("Second page", "Source A")))
    }

    /** One rail longer than the screen, with another page behind its `See all`. */
    private object FullPagedSource : SearchSource {
        val titles = (1..10).map { "Result $it" }
        override suspend fun sources() = SearchSources(1, 1, quickSearchable = false)
        override suspend fun search(query: String): SearchOutcome =
            SearchOutcome.Complete(titles.map { hit(it, "Source A") }, setOf("Source A"))
        override suspend fun quickSearch(query: String) = search(query)
        override suspend fun searchMore(query: String, providerName: String, page: Int): SearchOutcome =
            SearchOutcome.Complete(listOf(hit("Second page", "Source A")))
    }

    private class FilledHistory : SearchHistoryStore {
        val entries = listOf(
            "salt road" to "2 minutes ago", "still water" to "today", "the long dark" to "yesterday",
            "fernwood" to "yesterday", "quiet harbor" to "2 days ago", "ashen field" to "3 days ago",
            "nine lives" to "last week", "kingfisher" to "last week",
        ).map { (query, whenLabel) -> SearchHistoryEntry(query, whenLabel) }.toMutableList()

        fun first() = entries.first().query

        override suspend fun recent() = entries.toList()
        override suspend fun remove(query: String) {
            entries.removeAll { it.query == query }
        }
        override suspend fun clear() = entries.clear()
    }

    private object NoHistory : SearchHistoryStore {
        override suspend fun recent() = emptyList<SearchHistoryEntry>()
        override suspend fun remove(query: String) = Unit
        override suspend fun clear() = Unit
    }
}
