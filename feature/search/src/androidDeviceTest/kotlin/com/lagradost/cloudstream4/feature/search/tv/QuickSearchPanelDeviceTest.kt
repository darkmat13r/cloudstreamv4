package com.lagradost.cloudstream4.feature.search.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lagradost.cloudstream4.core.designsystem.icon.CloudStreamIcons
import com.lagradost.cloudstream4.core.designsystem.panel.SidePanelHost
import com.lagradost.cloudstream4.core.designsystem.rail.NavRail
import com.lagradost.cloudstream4.core.designsystem.rail.NavRailItem
import com.lagradost.cloudstream4.core.devicetest.Dpad
import com.lagradost.cloudstream4.core.devicetest.DpadDriver
import com.lagradost.cloudstream4.core.devicetest.assertScreenshot
import com.lagradost.cloudstream4.core.devicetest.focusedLabel
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme
import com.lagradost.cloudstream4.feature.search.QuickSearchAction
import com.lagradost.cloudstream4.feature.search.QuickSearchEffect
import com.lagradost.cloudstream4.feature.search.QuickSearchProgress
import com.lagradost.cloudstream4.feature.search.QuickSearchSource
import com.lagradost.cloudstream4.feature.search.QuickSearchViewModel
import com.lagradost.cloudstream4.feature.search.SearchResultId
import com.lagradost.cloudstream4.feature.search.SearchResultItem
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Artboards **8d** and **8e** of `design/TV Search Suggestions.dc.html` on the device, with the board's own data: twelve
 * sources asked, and the answers stepped in by the test the way they arrive.
 */
@RunWith(AndroidJUnit4::class)
class QuickSearchPanelDeviceTest {

    @get:Rule
    val rule = createComposeRule()

    private val dpad = DpadDriver(readFocus = { rule.focusedLabel() })
    private val source = SteppedSource()
    private val effects = CopyOnWriteArrayList<QuickSearchEffect>()

    private fun show(query: String = "dune"): QuickSearchViewModel {
        val viewModel = QuickSearchViewModel(query = query, source = source)
        rule.setContent {
            val state by viewModel.state.collectAsState()
            LaunchedEffect(viewModel) { viewModel.effect.collect { effects += it } }
            CloudStreamTheme {
                Row(Modifier.fillMaxSize().background(CloudStreamTheme.colors.bg)) {
                    NavRail(Rail, selectedIndex = 0, onSelect = {})
                    SidePanelHost(
                        expanded = true,
                        onDismissRequest = { viewModel.onAction(QuickSearchAction.Done) },
                        modifier = Modifier.weight(1f),
                        panel = { QuickSearchPanel(state = state, onAction = viewModel::onAction) },
                        content = { Box(Modifier.fillMaxSize()) },
                    )
                }
            }
        }
        rule.waitUntil(WAIT_MS) { source.started.get() > 0 }
        return viewModel
    }

    private fun answer(progress: QuickSearchProgress, viewModel: QuickSearchViewModel) {
        source.steps.trySend(progress)
        rule.waitUntil(WAIT_MS) { viewModel.state.value.progress == progress }
        rule.waitForIdle()
    }

    private fun awaitText(text: String) = rule.waitUntil(WAIT_MS) {
        rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }

    // ---- 8d ----

    @Test
    fun answeringCountsTheSourcesAndPutsFocusOnTheFirstRow() {
        val viewModel = show()
        rule.onNodeWithText("Quick search").assertExists()
        rule.onNodeWithText("RESULTS STREAM IN AS SOURCES ANSWER").assertExists()
        assertEquals("Before any answer the only stop is Done", "Done", dpad.focused())

        answer(Answering, viewModel)
        rule.onNodeWithText("3 OF 12 ANSWERED · 2 FAILED").assertExists()
        rule.onNodeWithText("VIDSRC · MOVIE · 4K").assertExists()
        rule.onNodeWithText("GOGO · TV SERIES").assertExists()
        assertEquals(FIRST, dpad.focused())
    }

    @Test
    fun downWalksTheRowsToDoneAndDoneCloses() {
        val viewModel = show()
        answer(Answering, viewModel)
        assertEquals(FIRST, dpad.focused())

        val walked = (1..Answering.items.size).map { dpad.press(Dpad.Down) }
        assertEquals(listOf(SECOND, THIRD, FOURTH, FIFTH, "Done"), walked)
        assertEquals("Left stays in the panel", "Done", dpad.press(Dpad.Left))

        dpad.center()
        rule.waitUntil(WAIT_MS) { effects.isNotEmpty() }
        assertEquals(listOf(QuickSearchEffect.Close), effects.toList())
    }

    @Test
    fun okOnARowOpensThatResult() {
        val viewModel = show()
        answer(Answering, viewModel)
        assertEquals(FIRST, dpad.focused())
        assertEquals(SECOND, dpad.press(Dpad.Down))

        dpad.center()
        rule.waitUntil(WAIT_MS) { effects.isNotEmpty() }
        assertEquals(listOf(QuickSearchEffect.OpenResult(Answering.items[1].id)), effects.toList())
    }

    @Test
    fun aLaterAnswerKeepsTheFocusWhereItIs() {
        val viewModel = show()
        answer(Answering.copy(items = Answering.items.take(2), answered = 1, failed = 0), viewModel)
        assertEquals(SECOND, dpad.press(Dpad.Down))

        answer(Answering, viewModel)
        assertEquals(SECOND, dpad.focused())
    }

    /** `RowScroll`, shared with downloads: five rows fit and do not move the list; the sixth moves it by one whole row. */
    @Test
    fun aSixthRowMovesTheListByOneWholeRowAndBackRestoresIt() {
        val viewModel = show()
        answer(Long, viewModel)
        assertEquals(FIRST, dpad.focused())
        val firstTop = rowTop(FIRST)
        val fifthTop = rowTop(FIFTH)
        // Measured before anything moves: after, every row is a pitch higher and a difference of two reads nothing.
        val pitch = rowTop(SECOND) - firstTop

        assertEquals(listOf(SECOND, THIRD, FOURTH, FIFTH), (1..4).map { dpad.press(Dpad.Down) })
        assertEquals("A row that fits moved the list", firstTop, rowTop(FIRST), 0.5f)

        assertEquals(SIXTH, dpad.press(Dpad.Down))
        rule.waitForIdle()
        assertEquals("The sixth row is not where the fifth was", fifthTop, rowTop(SIXTH), 0.5f)
        assertEquals("Not one whole row", fifthTop - pitch, rowTop(FIFTH), 0.5f)

        assertEquals(listOf(FIFTH, FOURTH, THIRD, SECOND, FIRST), (1..5).map { dpad.press(Dpad.Up) })
        rule.waitForIdle()
        assertEquals("Back at the top, the first row is not where it began", firstTop, rowTop(FIRST), 0.5f)
    }

    private fun rowTop(title: String): Float = rule.onNodeWithText(title).fetchSemanticsNode().boundsInRoot.top

    // ---- 8e and the failures ----

    @Test
    fun nothingCarriesItAndOpenFullSearchTakesTheFocus() {
        val viewModel = show(query = "dunnne")
        answer(Empty, viewModel)
        rule.onNodeWithText("No matches").assertExists()
        rule.onNodeWithText("ALL SOURCES ANSWERED").assertExists()
        rule.onNodeWithText("12 OF 12 ANSWERED · 0 FAILED").assertExists()
        rule.onNodeWithText(
            "Every source answered and none of them carry “dunnne”. Try a shorter query, or open full search to change the source set.",
        ).assertExists()
        assertEquals(OPEN_FULL, dpad.focused())

        dpad.center()
        rule.waitUntil(WAIT_MS) { effects.isNotEmpty() }
        assertEquals(listOf(QuickSearchEffect.OpenFullSearch("dunnne")), effects.toList())
    }

    @Test
    fun everySourceFailingSaysSoAndTryAgainAsksThemAgain() {
        val viewModel = show()
        answer(Failed, viewModel)
        rule.onNodeWithText("Search failed").assertExists()
        rule.onNodeWithText("0 OF 12 ANSWERED · 12 FAILED").assertExists()
        rule.onAllNodesWithText("ALL SOURCES ANSWERED").assertCountEquals(0)
        assertEquals(TRY_AGAIN, dpad.focused())

        dpad.center()
        rule.waitUntil(WAIT_MS) { source.started.get() == 2 }
        rule.waitUntil(WAIT_MS) { viewModel.state.value.progress == null }
    }

    @Test
    fun offlineSaysOffline() {
        val viewModel = show()
        answer(Failed.copy(offline = true), viewModel)
        rule.onNodeWithText("You are offline").assertExists()
        assertEquals(TRY_AGAIN, dpad.focused())
    }

    // ---- Screenshots ----

    /** Opened, nothing answered yet: the header and `Done`, and no number that could change. */
    @Test
    fun screenshotBeforeAnyAnswer() {
        show()
        awaitText("Done")
        rule.waitForIdle()
        rule.assertScreenshot("quick-search-asking")
    }

    /** 8d as drawn: five rows, three of twelve answered and two failed, the focus on the first row. */
    @Test
    fun screenshotAnswering() {
        val viewModel = show()
        answer(Answering, viewModel)
        assertEquals(FIRST, dpad.focused())
        rule.assertScreenshot("quick-search-answering")
    }

    /** Past the fifth row: the list has moved by one whole row, and nothing is cut at the header or the rule. */
    @Test
    fun screenshotScrolledByOneRow() {
        val viewModel = show()
        answer(Long, viewModel)
        assertEquals(FIRST, dpad.focused())
        repeat(5) { dpad.press(Dpad.Down) }
        assertEquals(SIXTH, dpad.focused())
        rule.waitForIdle()
        rule.assertScreenshot("quick-search-scrolled")
    }

    /** 8e: everyone answered, nobody carries it, and the action that can change that has the focus. */
    @Test
    fun screenshotEmpty() {
        val viewModel = show(query = "dunnne")
        answer(Empty, viewModel)
        assertEquals(OPEN_FULL, dpad.focused())
        rule.assertScreenshot("quick-search-empty")
    }

    @Test
    fun screenshotEverySourceFailed() {
        val viewModel = show()
        answer(Failed, viewModel)
        assertEquals(TRY_AGAIN, dpad.focused())
        rule.assertScreenshot("quick-search-failed")
    }

    @Test
    fun screenshotOffline() {
        val viewModel = show()
        answer(Failed.copy(offline = true), viewModel)
        assertEquals(TRY_AGAIN, dpad.focused())
        rule.assertScreenshot("quick-search-offline")
    }

    /** Answers arrive when the test sends them. Each search gets the same channel; a retry is counted. */
    private class SteppedSource : QuickSearchSource {
        val steps = Channel<QuickSearchProgress>(Channel.UNLIMITED)
        val started = AtomicInteger()
        override fun search(query: String): Flow<QuickSearchProgress> {
            started.incrementAndGet()
            return steps.receiveAsFlow()
        }
    }

    private companion object {
        const val WAIT_MS = 10_000L
        const val OPEN_FULL = "Open full search"
        const val TRY_AGAIN = "Try again"

        // Distinct titles, because the driver names a stop by its text. The board's two `Dune` rows differ by year,
        // which the item does not carry, so the fixture says which is which.
        const val FIRST = "Dune: Part Two"
        const val SECOND = "Dune: Part Two (4K)"
        const val THIRD = "Dune"
        const val FOURTH = "Dune: Prophecy"
        const val FIFTH = "Dune (1984)"
        const val SIXTH = "Duna"

        val Rail = listOf(
            NavRailItem("Home", CloudStreamIcons.Home),
            NavRailItem("Search", CloudStreamIcons.Search),
            NavRailItem("Library", CloudStreamIcons.Library),
            NavRailItem("Downloads", CloudStreamIcons.Download),
            NavRailItem("Settings", CloudStreamIcons.Settings),
        )

        fun hit(title: String, provider: String, type: String?, quality: String? = null) = SearchResultItem(
            id = SearchResultId("$provider/$title"),
            title = title,
            providerName = provider,
            typeLabel = type,
            qualityLabel = quality,
        )

        /** 8d's `qsRaw`, without the year. */
        val Answering = QuickSearchProgress(
            items = listOf(
                hit(FIRST, "Vidsrc", "Movie"),
                hit(SECOND, "Vidsrc", "Movie", "4K"),
                hit(THIRD, "FilmXY", "Movie"),
                hit(FOURTH, "Gogo", "TV Series"),
                hit(FIFTH, "FilmXY", "Movie"),
            ),
            answered = 3,
            failed = 2,
            total = 12,
        )
        /** More rows than the panel holds whole. */
        val Long = Answering.copy(
            items = Answering.items + listOf(
                hit(SIXTH, "Gogo", "Movie"),
                hit("Dune Messiah", "Vidsrc", "TV Series"),
                hit("Dune: Water", "FilmXY", "Movie"),
            ),
        )
        val Empty = QuickSearchProgress(items = emptyList(), answered = 12, failed = 0, total = 12)
        val Failed = QuickSearchProgress(items = emptyList(), answered = 0, failed = 12, total = 12)
    }
}
