package com.lagradost.cloudstream4.feature.library.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.layout.ContentScale
import com.lagradost.cloudstream4.core.designsystem.icon.CloudStreamIcons
import com.lagradost.cloudstream4.core.designsystem.rail.NavRail
import com.lagradost.cloudstream4.core.designsystem.rail.NavRailItem
import com.lagradost.cloudstream4.core.devicetest.TouchModeReset
import com.lagradost.cloudstream4.core.devicetest.assertScreenshot
import com.lagradost.cloudstream4.core.devicetest.solidPoster
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.lagradost.cloudstream4.compose.ObserveEffect
import com.lagradost.cloudstream4.core.devicetest.Dpad
import com.lagradost.cloudstream4.core.devicetest.DpadDriver
import com.lagradost.cloudstream4.core.devicetest.focusedLabel
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme
import com.lagradost.cloudstream4.feature.library.LibraryAction
import com.lagradost.cloudstream4.feature.library.LibraryBucket
import com.lagradost.cloudstream4.feature.library.LibraryEffect
import com.lagradost.cloudstream4.feature.library.LibraryStore
import com.lagradost.cloudstream4.feature.library.LibraryTitle
import com.lagradost.cloudstream4.feature.library.LibraryTitleId
import com.lagradost.cloudstream4.feature.library.LibraryViewModel
import com.lagradost.cloudstream4.feature.library.WatchPosition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The TV library on a device, with a fake store and the real ViewModel: no `:app`, no network.
 *
 * `Television_720p` is about 961.5x540.8 dp rather than the artboard's 960x540, so this asserts the card's
 * own size and never the screen's.
 */
@RunWith(AndroidJUnit4::class)
class LibraryScreenTvDeviceTest {

    /** `BUG-59`: starts and ends out of touch mode, whatever ran before it. */
    @get:Rule
    val touchMode = TouchModeReset()

    @get:Rule
    val rule = createComposeRule()

    private val dpad = DpadDriver(readFocus = { rule.focusedLabel() })
    private val store = FakeStore()
    private val effects = CopyOnWriteArrayList<LibraryEffect>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var onScreen by mutableStateOf(true)

    @After
    fun stopTheViewModel() = scope.cancel()

    private val viewModel by lazy { LibraryViewModel(store, scope, initialTab = LibraryTvTabs.first()) }

    private fun showScreen() {
        rule.setContent {
            val state by viewModel.state.collectAsState()
            ObserveEffect(viewModel.effect) { effects += it }
            CloudStreamTheme {
                Box(Modifier.fillMaxSize().background(CloudStreamTheme.colors.bg)) {
                    if (onScreen) LibraryScreenTv(state = state, onAction = viewModel::onAction)
                }
            }
        }
    }

    private fun awaitText(text: String) = rule.waitUntil(WAIT_MS) {
        rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }

    /** Starts on a tab rather than from nothing, so a test knows where its first press leaves from. */
    private fun focusTab(name: String) {
        awaitText(name)
        // By role as well: an empty list's headline is the same word as its tab.
        rule.onNode(hasText(name) and isSelectable()).requestFocus()
        rule.waitUntil(WAIT_MS) { rule.focusedLabel() == name }
    }

    private fun pressUntil(direction: Dpad, wanted: (String?) -> Boolean, presses: Int = MAX_PRESSES): String? =
        generateSequence { dpad.press(direction) }.take(presses).first(wanted)

    @Test
    fun theGridShowsCardsAtTheBoardsSizeAndTabsCarryTheirRealCounts() {
        store.titles = watching(3) + title("Finished", LibraryBucket.Completed)
        showScreen()

        focusTab(WATCHING)
        rule.onNodeWithText(WATCHING).assertIsSelected()
        rule.onNodeWithText("3").assertExists()
        rule.onNodeWithText("1").assertExists()
        rule.onNodeWithText("4").assertExists()
        assertEquals(LibraryTvTabs.size - 2, rule.onAllNodesWithText("0").fetchSemanticsNodes().size)

        pressUntil(Dpad.Down, { it?.startsWith("Title") == true })
        rule.onNode(isFocused()).assertWidthIsEqualTo(96.dp).assertHeightIsEqualTo(144.dp)
    }

    @Test
    fun centreOpensTheFocusedTitle() {
        store.titles = watching(3)
        showScreen()

        focusTab(WATCHING)
        val card = pressUntil(Dpad.Down, { it?.startsWith("Title") == true })
        dpad.center()
        rule.waitUntil(WAIT_MS) { effects.isNotEmpty() }
        assertEquals(LibraryEffect.OpenTitle(LibraryTitleId("id/$card")), effects.single())
    }

    /** An empty list while the library has titles: the tabs stay, and the action goes to a list that has some. */
    @Test
    fun anEmptyPageKeepsItsTabsAndItsActionIsReachableAndGoesToAList() {
        store.titles = watching(2)
        showScreen()

        focusTab(ON_HOLD)
        dpad.center()
        awaitText("This list is empty. Try switching to another one.")
        // The tab keeps focus: choosing a list must not throw focus off the row.
        assertEquals(ON_HOLD, rule.focusedLabel())
        assertEquals("the tab, and the button", 2, rule.onAllNodesWithText(WATCHING).fetchSemanticsNodes().size)
        assertEquals("the readout, and the tab", 2, rule.onAllNodesWithText("2").fetchSemanticsNodes().size)

        assertEquals(WATCHING, pressUntil(Dpad.Down, { it == WATCHING }))
        // The label is the button's: the tab above has the same word, and the button is the one focused now.
        dpad.center()
        rule.waitUntil(WAIT_MS) { rule.onAllNodesWithText(WATCHING).fetchSemanticsNodes().size == 1 }
        rule.waitUntil(WAIT_MS) { rule.focusedLabel() == WATCHING }
        rule.onNodeWithText(WATCHING).assertIsSelected()
    }

    /** Nothing kept anywhere: the one thing to offer is the screen that finds something. */
    @Test
    fun anEmptyLibraryOffersSearchAndTheActionIsReachable() {
        showScreen()

        focusTab(WATCHING)
        awaitText(SEARCH)
        assertEquals(SEARCH, pressUntil(Dpad.Down, { it == SEARCH }))
        dpad.center()
        rule.waitUntil(WAIT_MS) { effects.isNotEmpty() }
        assertEquals(LibraryEffect.OpenSearch, effects.single())
    }

    @Test
    fun aFailedReadSaysSoAndRetryIsReachableAndRecovers() {
        store.failure = IllegalStateException("store unreadable")
        showScreen()

        focusTab(WATCHING)
        awaitText("store unreadable")
        assertEquals(RETRY, pressUntil(Dpad.Down, { it == RETRY }))

        store.failure = null
        store.titles = watching(2)
        dpad.center()
        awaitText("2")
        // The button left with its state, so focus went to the tab rather than nowhere.
        rule.waitUntil(WAIT_MS) { rule.focusedLabel() == WATCHING }
    }

    @Test
    fun aLongTitleIsEllipsizedInsideItsCard() {
        store.titles = listOf(title(LONG_TITLE, LibraryBucket.Watching))
        showScreen()

        focusTab(WATCHING)
        assertEquals(LONG_TITLE, pressUntil(Dpad.Down, { it == LONG_TITLE }))
        val layouts = mutableListOf<TextLayoutResult>()
        val node = rule.onNodeWithText(LONG_TITLE, useUnmergedTree = true).fetchSemanticsNode()
        node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
        val layout = layouts.single()
        assertEquals(2, layout.lineCount)
        assertTrue(layout.isLineEllipsized(1))
        rule.onNode(isFocused()).assertWidthIsEqualTo(96.dp).assertHeightIsEqualTo(144.dp)
    }

    /** Three rows, so the third has to scroll in; a walk finds every tab, every card, and a way back. */
    @Test
    fun everyTabAndCardIsReachableWithNoDeadEndsOrTraps() {
        store.titles = watching(16)
        showScreen()

        focusTab(WATCHING)
        val graph = dpad.walk()
        val expected = TAB_NAMES + (0 until 16).map { "Title $it" }
        assertEquals(graph.toString(), emptyList<String>(), graph.unreachable(expected))
        assertEquals(graph.toString(), emptyList<String>(), graph.deadEnds())
        assertEquals(graph.toString(), emptyList<String>(), graph.traps())
        // The field is a stop too, and crossing it opens nothing (`BUG-30`).
        assertTrue(graph.toString(), graph.reached.any { it !in expected })
        assertEquals(graph.toString(), emptySet<String>(), graph.keyboardOpenedOn)
    }

    /** Leaves composition and comes back, which is what the fragment's view does around a title's page. */
    @Test
    fun focusReturnsToTheOpenedCardOnItsRowWhenTheScreenComesBack() {
        store.titles = watching(16)
        showScreen()

        focusTab(WATCHING)
        // Down the grid until the row that had to scroll in.
        val card = pressUntil(Dpad.Down, { it == "Title 14" || it == "Title 15" }, presses = 4)
            ?: error("The third row was not reached")
        dpad.center()
        rule.waitUntil(WAIT_MS) { effects.isNotEmpty() }

        onScreen = false
        rule.waitForIdle()
        rule.onNode(isFocused()).assertDoesNotExist()

        onScreen = true
        rule.waitForIdle()
        // Composed but not resumed yet: a fragment's view is in this state for a while, and takes nothing.
        rule.onNode(isFocused()).assertDoesNotExist()
        viewModel.onAction(LibraryAction.Reappeared)
        rule.waitUntil(WAIT_MS) { viewModel.state.value.returnTo == null }
        rule.waitForIdle()
        // After the return point is cleared and everything has recomposed: focus has to stay, not just arrive.
        assertEquals(card, rule.focusedLabel())
    }

    /** Up from the tabs to the field, OK for the keyboard, typed through the keyboard, as a person would. */
    @Test
    fun theFilterNarrowsTheGridAndClearingItBringsTheCardsBack() {
        store.titles = listOf(
            title("Salt Road", LibraryBucket.Watching),
            title("Tide", LibraryBucket.Watching),
        )
        showScreen()

        focusTab(WATCHING)
        generateSequence { dpad.press(Dpad.Up) }.take(MAX_PRESSES)
            .first { rule.onAllNodes(Field and isFocused()).fetchSemanticsNodes().isNotEmpty() }
        assertFalse("Landing on the field must not open the keyboard", dpad.keyboardShown())
        dpad.center()
        rule.waitUntil(WAIT_MS) { dpad.keyboardShown() }
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).executeShellCommand("input text zzz")
        awaitText(CLEAR)
        // The counts are the lists' real sizes, not what the filter left.
        assertEquals("the readout, and the tab", 2, rule.onAllNodesWithText("2").fetchSemanticsNodes().size)
        rule.onNode(hasSetTextAction()).performImeAction()
        rule.waitUntil(WAIT_MS) { !dpad.keyboardShown() }

        assertEquals(CLEAR, pressUntil(Dpad.Down, { it == CLEAR }))
        dpad.center()
        rule.waitUntil(WAIT_MS) { rule.onAllNodesWithText(CLEAR).fetchSemanticsNodes().isEmpty() }
        rule.onNode(Field).assert(hasText(""))
    }

    // ---- Screenshots (`INF-11`): 5a with a card and with a tab focused, 5c with its action, and the filter field ------

    /** The screen beside the rail, as the app shows it, with one-colour posters so nothing loads. */
    private fun showForScreenshot() {
        rule.setContent {
            val state by viewModel.state.collectAsState()
            CloudStreamTheme {
                Row(Modifier.fillMaxSize().background(CloudStreamTheme.colors.bg)) {
                    NavRail(ShotRail, selectedIndex = LIBRARY_INDEX, onSelect = {})
                    LibraryScreenTv(
                        state = state,
                        onAction = viewModel::onAction,
                        modifier = Modifier.weight(1f),
                        artwork = { card ->
                            Image(
                                bitmap = solidPoster(ShotPosters[card.title.length % ShotPosters.size]),
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.FillBounds,
                            )
                        },
                    )
                }
            }
        }
    }

    private fun showShotLibrary() {
        store.titles = ShotTitles
        store.positions = ShotPositions
        showForScreenshot()
        focusTab(WATCHING)
    }

    @Test
    fun screenshotGridFocusOnACard() {
        showShotLibrary()
        assertEquals(ShotTitles.first().title, pressUntil(Dpad.Down, { it == ShotTitles.first().title }))
        rule.assertScreenshot("library-grid-focus-card")
    }

    @Test
    fun screenshotGridFocusOnATab() {
        showShotLibrary()
        assertEquals("Completed", dpad.press(Dpad.Right))
        rule.assertScreenshot("library-grid-focus-tab")
    }

    @Test
    fun screenshotEmptyPageFocusOnItsAction() {
        showShotLibrary()
        focusTab(ON_HOLD)
        dpad.center()
        awaitText("This list is empty. Try switching to another one.")
        assertEquals(WATCHING, pressUntil(Dpad.Down, { it == WATCHING }))
        rule.assertScreenshot("library-empty-focus-action")
    }

    @Test
    fun screenshotFilterFieldFocused() {
        showShotLibrary()
        generateSequence { dpad.press(Dpad.Up) }.take(MAX_PRESSES)
            .first { rule.onAllNodes(Field and isFocused()).fetchSemanticsNodes().isNotEmpty() }
        assertFalse(dpad.keyboardShown())
        rule.assertScreenshot("library-filter-focus-field")
    }

    private companion object {
        val Field = SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText)
        const val WAIT_MS = 10_000L
        const val MAX_PRESSES = 6
        const val WATCHING = "Watching"
        const val ON_HOLD = "On-Hold"
        const val SEARCH = "Search"
        const val RETRY = "Try again"
        const val CLEAR = "Clear"
        val TAB_NAMES = listOf(WATCHING, "Completed", ON_HOLD, "Dropped", "Plan to Watch", "Favorites")
        const val LONG_TITLE = "The Extraordinarily Long Title Of A Film Whose Name Will Never Fit On Any Poster " +
            "Card In This Grid However Wide The Television Is"

        const val LIBRARY_INDEX = 2
        val ShotRail = listOf(
            NavRailItem("Home", CloudStreamIcons.Home),
            NavRailItem("Search", CloudStreamIcons.Search),
            NavRailItem("Library", CloudStreamIcons.Library),
            NavRailItem("Downloads", CloudStreamIcons.Download),
            NavRailItem("Settings", CloudStreamIcons.Settings),
        )
        val ShotPosters = listOf(0xFF39505C.toInt(), 0xFF5C4A39.toInt(), 0xFF3F5C39.toInt())
        private val ShotNames = listOf(
            "Fernwood: Beyond the Old Road", "Still Water", "Salt Road II", "The Long Dark", "Shoal Saga",
            "Quiet Harbor", "Ashen Field", "Kingfisher", "Nine Lives", "The Final Cut", "Northern Line", "Paper Moons",
            "Saltwater", "Dust and Iron", "Low Tide", "Grey Harbour", "The Warden", "Halcyon", "Summer Gull", "Redwood",
        )
        private val ShotTypes = listOf("ANIME", "TV SERIES", "MOVIE")
        val ShotTitles = ShotNames.mapIndexed { index, name ->
            title(name, LibraryBucket.Watching, keptAt = 1000L - index).copy(typeLabel = ShotTypes[index % ShotTypes.size])
        } + title("Arrival", LibraryBucket.Completed) + title("Tide", LibraryBucket.Favourites)
        /** Every third title part-watched, so the progress rule shows. */
        val ShotPositions = ShotTitles.filterIndexed { index, _ -> index % 3 == 0 }.mapIndexed { index, kept ->
            WatchPosition(kept.id, null, positionMillis = 600_000L + index * 300_000L, durationMillis = 3_000_000L, watchedAt = 1L)
        }

        fun title(name: String, bucket: LibraryBucket, keptAt: Long = 0L) = LibraryTitle(
            id = LibraryTitleId("id/$name"),
            title = name,
            bucket = bucket,
            keptAt = keptAt,
            providerName = "Source A",
        )

        /** Newest first, so `Title 0` is the first card. */
        fun watching(count: Int) = List(count) { title("Title $it", LibraryBucket.Watching, keptAt = 1000L - it) }
    }

    private class FakeStore : LibraryStore {
        @Volatile var titles: List<LibraryTitle> = emptyList()
        @Volatile var failure: Exception? = null

        override suspend fun titles(): List<LibraryTitle> {
            failure?.let { throw it }
            return titles
        }

        @Volatile var positions: List<WatchPosition> = emptyList()

        override suspend fun positions() = positions
        override suspend fun remember(title: LibraryTitle) = Unit
        override suspend fun setBucket(id: LibraryTitleId, bucket: LibraryBucket?) = Unit
        override suspend fun record(position: WatchPosition) = Unit
        override suspend fun clearPositions(id: LibraryTitleId) = Unit
    }
}
