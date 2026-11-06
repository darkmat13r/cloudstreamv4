package com.lagradost.cloudstream4.feature.home.tv

import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.lagradost.cloudstream4.compose.ObserveEffect
import com.lagradost.cloudstream4.core.devicetest.Dpad
import com.lagradost.cloudstream4.core.devicetest.DpadDriver
import com.lagradost.cloudstream4.core.devicetest.assertScreenshot
import com.lagradost.cloudstream4.core.devicetest.focusedLabel
import com.lagradost.cloudstream4.core.devicetest.solidPoster
import com.lagradost.cloudstream4.core.designsystem.icon.CloudStreamIcons
import com.lagradost.cloudstream4.core.designsystem.panel.sidePanelOutsideDim
import com.lagradost.cloudstream4.core.designsystem.rail.NavRail
import com.lagradost.cloudstream4.core.designsystem.rail.NavRailItem
import com.lagradost.cloudstream4.core.designsystem.rows.RailHeaderDefaults
import com.lagradost.cloudstream4.core.theme.CloudStreamDimens
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme
import com.lagradost.cloudstream4.core.theme.cloudStreamDarkColors
import com.lagradost.cloudstream4.feature.home.HomeAction
import com.lagradost.cloudstream4.feature.home.HomeBookmark
import com.lagradost.cloudstream4.feature.home.HomeBookmarkStore
import com.lagradost.cloudstream4.feature.home.HomeEffect
import com.lagradost.cloudstream4.feature.home.HomeFeatured
import com.lagradost.cloudstream4.feature.home.HomeHero
import com.lagradost.cloudstream4.feature.home.HomeHistory
import com.lagradost.cloudstream4.feature.home.HomeItem
import com.lagradost.cloudstream4.feature.home.HomePageReply
import com.lagradost.cloudstream4.feature.home.HomeResume
import com.lagradost.cloudstream4.feature.home.HomeRowPage
import com.lagradost.cloudstream4.feature.home.HomeSource
import com.lagradost.cloudstream4.feature.home.HomeSourceId
import com.lagradost.cloudstream4.feature.home.HomeSourceMemory
import com.lagradost.cloudstream4.feature.home.HomeSourceOption
import com.lagradost.cloudstream4.feature.home.HomeSources
import com.lagradost.cloudstream4.feature.home.HomeStatus
import com.lagradost.cloudstream4.feature.home.HomeViewModel
import com.lagradost.cloudstream4.feature.home.HomeWatchHistory
import com.lagradost.cloudstream4.feature.home.HomeWatchStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs

/**
 * The TV home page on a device, with a fake source and the real ViewModel: no `:app`, no network.
 *
 * `Television_720p` is about 961.5x540.8 dp rather than the artboard's 960x540, so this asserts each thing's own size and
 * its place from the top of the page, and never the screen's size.
 */
@RunWith(AndroidJUnit4::class)
class HomeScreenTvDeviceTest {

    @get:Rule
    val rule = createComposeRule()

    /**
     * A description wins over the words, because every rail's trailing card says the same words and only its description
     * names its row. Everything else has no description or one that says the same thing.
     */
    private val dpad = DpadDriver(
        readFocus = {
            rule.onAllNodes(isFocused()).fetchSemanticsNodes().firstOrNull()
                ?.config?.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull()
                ?: rule.focusedLabel()
        },
    )
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val source = FakeSource()
    private val memory = Kept()
    private val history = FakeHistory()
    private val shelf = FakeShelf()
    private val effects = CopyOnWriteArrayList<HomeEffect>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var onScreen by mutableStateOf(true)
    private var mayTakeFocus = true
    /** The view hosting the screen, which `HomeTvFragment.onResume` asks for focus as the page first opens. */
    private var hostView: android.view.View? = null

    private val viewModel by lazy { HomeViewModel(source, scope, memory, history, shelf) }

    @After
    fun stopTheViewModel() = scope.cancel()

    private fun showScreen(resume: Boolean = true) {
        rule.setContent {
            val state by viewModel.state.collectAsState()
            val view = androidx.compose.ui.platform.LocalView.current
            androidx.compose.runtime.SideEffect { hostView = view }
            ObserveEffect(viewModel.effect) { effects += it }
            // What `HomeTvFragment` does: on this API level Back can finish the activity past the panel's own key handler.
            val dispatcher = (LocalContext.current as ComponentActivity).onBackPressedDispatcher
            DisposableEffect(state.pickerOpen) {
                val closes = object : OnBackPressedCallback(state.pickerOpen) {
                    override fun handleOnBackPressed() = viewModel.onAction(HomeAction.PickerDismissed)
                }
                dispatcher.addCallback(closes)
                onDispose { closes.remove() }
            }
            CloudStreamTheme {
                Box(Modifier.fillMaxSize().background(CloudStreamTheme.colors.bg)) {
                    if (onScreen) {
                        HomeScreenTv(state = state, onAction = viewModel::onAction, mayTakeFocus = { mayTakeFocus })
                    }
                }
            }
        }
        if (resume) rule.runOnIdle { viewModel.onAction(HomeAction.Reappeared) }
    }

    private fun awaitText(text: String) = rule.waitUntil(WAIT_MS) {
        rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }

    private fun awaitFocus(label: String) {
        val deadline = System.currentTimeMillis() + WAIT_MS
        var now = dpad.focused()
        while (now != label && System.currentTimeMillis() < deadline) now = dpad.focused()
        assertEquals("what holds focus", label, now)
    }

    private fun pressUntil(direction: Dpad, label: String, presses: Int = MAX_PRESSES) {
        val seen = generateSequence { dpad.press(direction) }.take(presses).firstOrNull { it == label }
        assertEquals("pressing $direction", label, seen)
    }

    private fun titles(row: String, count: Int) = List(count) { "$row $it" }

    /** Rails named A, B, C and so on, each a section of its own with titles `A 0`, `A 1`. */
    private fun rails(count: Int, cards: Int = 3, hasNext: Boolean = false) {
        source.sections = count
        source.answer = { _, section, _ -> loaded(rowName(section), titles(rowName(section), cards), hasNext) }
    }

    // ---- Rows, and where focus lands ---------------------------------------------------------------------------

    @Test
    fun rowsLoadAtTheBoardsSizeAndFocusLandsOnTheFirstCardOnlyOnceResumed() {
        rails(2)
        showScreen(resume = false)

        awaitText("A")
        rule.onNodeWithText("B").assertExists()
        // A fragment's view is in this state for a while on a TV, and focus taken in it does not last.
        rule.onNode(isFocused()).assertDoesNotExist()

        rule.runOnIdle { viewModel.onAction(HomeAction.Reappeared) }
        awaitFocus("A 0")
        rule.onNode(isFocused()).assertWidthIsEqualTo(80.dp).assertHeightIsEqualTo(120.dp)
        // Landing does not scroll the chrome away, so the source control is on screen from the start.
        rule.onNodeWithContentDescription(SOURCE_ONE).assertIsDisplayed()
    }

    @Test
    fun rowsLandingAfterAPersonLeftForTheNavRailDoNotTakeFocus() {
        rails(2)
        mayTakeFocus = false
        showScreen()

        awaitText("A")
        rule.waitUntil(WAIT_MS) { !viewModel.state.value.landingOwed }
        rule.waitForIdle()
        rule.onNode(isFocused()).assertDoesNotExist()
    }

    @Test
    fun centreOpensTheFocusedTitle() {
        rails(1)
        showScreen()
        awaitFocus("A 0")

        dpad.press(Dpad.Right)
        dpad.center()
        rule.waitUntil(WAIT_MS) { effects.isNotEmpty() }
        assertEquals(HomeEffect.OpenTitle("id/A 1"), effects.single())
    }

    /** Pinned at the top of the band is the only place a rail's title is sure to be on screen with its card. */
    @Test
    fun aRailScrolledToHasItsTitleAtTheTopOfTheBand() {
        rails(8)
        showScreen()
        awaitFocus("A 0")

        // The second rail fits under the chrome, so nothing moves. The third does not.
        assertEquals("B 0", dpad.press(Dpad.Down))
        rule.onNodeWithContentDescription(SOURCE_ONE).assertIsDisplayed()
        assertEquals("C 0", dpad.press(Dpad.Down))
        rule.waitForIdle()
        assertTitleAtTheTopOfTheBand("C")

        // Going up the title would otherwise be left above the screen.
        assertEquals("B 0", dpad.press(Dpad.Up))
        rule.waitForIdle()
        assertTitleAtTheTopOfTheBand("B")
    }

    /** The text sits a little inside its 28dp header, so this is its header's place give or take that. */
    private fun assertTitleAtTheTopOfTheBand(title: String) {
        val top = rule.onNodeWithText(title).getUnclippedBoundsInRoot().top
        assertTrue("$title is at $top", top in 28.dp..32.dp)
    }

    // ---- What failed ---------------------------------------------------------------------------------------------

    @Test
    fun oneSectionFailingLeavesTheOthersAndItsRetryIsReachableAndAsksOnlyForIt() {
        rails(3)
        val answers = source.answer
        source.answer = { id, section, page ->
            if (section == 1) HomePageReply.Failed("HTTP 503") else answers(id, section, page)
        }
        showScreen()

        awaitFocus("A 0")
        rule.onNodeWithText("C").assertExists()
        rule.onAllNodesWithText("B").fetchSemanticsNodes().let { assertTrue("the failed row has no rail", it.isEmpty()) }
        awaitText("1 section failed to load")

        pressUntil(Dpad.Up, RETRY_FAILED)
        source.answer = answers
        source.calls.clear()
        dpad.center()

        awaitText("B")
        assertEquals("only what failed is asked again", listOf(1), source.calls.map { it.section })
        rule.waitUntil(WAIT_MS) { viewModel.state.value.status is HomeStatus.Showing }
        rule.onAllNodesWithText(RETRY_FAILED).fetchSemanticsNodes().let { assertTrue(it.isEmpty()) }
    }

    @Test
    fun everySectionFailingOffersARetryThatTakesFocusAndRecovers() {
        source.sections = 2
        source.answer = { _, _, _ -> HomePageReply.Failed("HTTP 503") }
        showScreen()

        awaitText("HTTP 503")
        awaitFocus(RETRY)

        rails(2)
        dpad.center()
        awaitText("A")
        // The button left with its state. Focus goes to a card rather than nowhere.
        awaitFocus("A 0")
    }

    @Test
    fun aFailureCanBeLeftForTheSourceControl() {
        source.sections = 1
        source.answer = { _, _, _ -> HomePageReply.Failed("HTTP 503") }
        showScreen()

        awaitFocus(RETRY)
        pressUntil(Dpad.Up, SOURCE_ONE, presses = 2)
    }

    @Test
    fun beingOfflineSaysSoAndItsRetryIsReachable() {
        source.sections = 1
        source.answer = { _, _, _ -> HomePageReply.Offline }
        showScreen()

        awaitText("You are offline")
        awaitFocus(RETRY)

        rails(1)
        dpad.center()
        awaitFocus("A 0")
    }

    @Test
    fun nothingInstalledOffersExtensionsAndTheActionIsReachable() {
        source.options = emptyList()
        source.installed = 0
        showScreen()

        awaitText("Install the extension first")
        awaitFocus(EXTENSIONS)
        dpad.center()
        rule.waitUntil(WAIT_MS) { effects.isNotEmpty() }
        assertEquals(HomeEffect.OpenExtensions, effects.single())
    }

    @Test
    fun sourcesThatOnlySearchOfferSearchAndTheActionIsReachable() {
        source.options = emptyList()
        source.installed = 2
        showScreen()

        awaitText("None of your extensions has a home page, but you can still search them.")
        awaitFocus("Search")
        dpad.center()
        rule.waitUntil(WAIT_MS) { effects.isNotEmpty() }
        assertEquals(HomeEffect.OpenSearch, effects.single())
    }

    // ---- Plugins arriving in stages ------------------------------------------------------------------------------

    /** The page is drawn before plugins load, and saying nothing is installed at that moment would be false. */
    @Test
    fun whilePluginsLoadTheRememberedSourceIsWaitedForAndNothingElseIsFetched() {
        memory.id = TWO.id
        rails(2)
        source.options = listOf(ONE)
        source.stillLoading = true
        showScreen()

        rule.waitUntil(WAIT_MS) { viewModel.state.value.sources.isNotEmpty() }
        rule.waitForIdle()
        assertTrue("nothing is fetched for a source that may be replaced", source.calls.isEmpty())
        rule.onAllNodesWithText("Install the extension first").fetchSemanticsNodes().let { assertTrue(it.isEmpty()) }

        source.options = listOf(ONE, TWO)
        source.stillLoading = false
        rule.runOnIdle { viewModel.onAction(HomeAction.SourcesChanged) }

        awaitFocus("A 0")
        assertEquals(setOf(TWO.id), source.calls.map { it.source }.toSet())
        rule.onNodeWithContentDescription(SOURCE_TWO).assertExists()
    }

    /** A plugin stage finishing, or coming back from a title's page, must not rebuild what is being looked at. */
    @Test
    fun sourcesChangingLeavesRowsAndFocusAlone() {
        rails(2)
        showScreen()
        awaitFocus("A 0")
        dpad.press(Dpad.Right)
        source.calls.clear()

        rule.runOnIdle { viewModel.onAction(HomeAction.SourcesChanged) }
        rule.waitForIdle()

        assertEquals("A 1", dpad.focused())
        assertTrue("nothing was asked again", source.calls.isEmpty())
    }

    // ---- Long words, and short rows ------------------------------------------------------------------------------

    @Test
    fun aLongTitleIsEllipsizedInsideItsCardAndALongRowNameStaysOnOneLine() {
        source.sections = 1
        source.answer = { _, _, _ -> loaded(LONG_ROW, listOf(LONG_TITLE, "Short")) }
        showScreen()

        awaitFocus(LONG_TITLE)
        val layouts = mutableListOf<TextLayoutResult>()
        val title = rule.onNodeWithText(LONG_TITLE, useUnmergedTree = true).fetchSemanticsNode()
        title.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
        assertEquals(2, layouts.single().lineCount)
        assertTrue(layouts.single().isLineEllipsized(1))
        rule.onNode(isFocused()).assertWidthIsEqualTo(80.dp).assertHeightIsEqualTo(120.dp)

        layouts.clear()
        val row = rule.onNodeWithText(LONG_ROW, useUnmergedTree = true).fetchSemanticsNode()
        row.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
        assertEquals(1, layouts.single().lineCount)
        assertTrue(layouts.single().isLineEllipsized(0))
    }

    /** One title and nothing more to load: no trailing card, and RIGHT stays rather than jumping to another rail. */
    @Test
    fun aRowWithASingleItemHasAWallAtItsEnd() {
        source.sections = 2
        source.answer = { _, section, _ ->
            if (section == 0) loaded("Single", listOf("Only")) else loaded("Next", titles("Next", 4))
        }
        showScreen()

        awaitFocus("Only")
        assertEquals("Only", dpad.press(Dpad.Right))
        rule.onAllNodesWithText(LOAD_MORE).fetchSemanticsNodes().let { assertTrue(it.isEmpty()) }
        assertEquals("Next 0", dpad.press(Dpad.Down))
        assertEquals("Only", dpad.press(Dpad.Up))
    }

    @Test
    fun aSingleItemWithMoreToLoadStillOffersIt() {
        source.sections = 1
        source.answer = { _, _, page ->
            if (page == 1) loaded("Single", listOf("Only"), hasNext = true) else loaded("Single", listOf("Second"))
        }
        showScreen()

        awaitFocus("Only")
        assertEquals("$LOAD_MORE · Single", dpad.press(Dpad.Right))
        dpad.center()
        awaitFocus("Second")
    }

    // ---- Paging --------------------------------------------------------------------------------------------------

    @Test
    fun theTrailingCardFetchesTheNextPageAndFocusMovesToItsFirstTitle() {
        source.sections = 1
        source.answer = { _, _, page ->
            loaded("A", List(2) { "Page $page · $it" }, hasNext = page < 2)
        }
        showScreen()

        awaitFocus("Page 1 · 0")
        pressUntil(Dpad.Right, "$LOAD_MORE · A")
        source.calls.clear()
        dpad.center()

        awaitFocus("Page 2 · 0")
        assertEquals(listOf(Call(ONE.id, 0, 2)), source.calls)
        // That was the last page, so the card it would have asked with has gone.
        rule.onAllNodesWithContentDescription("$LOAD_MORE · A").fetchSemanticsNodes().let { assertTrue(it.isEmpty()) }
        assertEquals("Page 2 · 1", dpad.press(Dpad.Right))
        assertEquals("the end of the rail is a wall", "Page 2 · 1", dpad.press(Dpad.Right))
    }

    @Test
    fun aPageThatFailsSaysSoInTheRowsTitleAndItsCardRetries() {
        source.sections = 1
        source.answer = { _, _, page ->
            if (page == 1) loaded("A", titles("A", 2), hasNext = true) else HomePageReply.Failed("HTTP 500")
        }
        showScreen()

        awaitFocus("A 0")
        pressUntil(Dpad.Right, "$LOAD_MORE · A")
        dpad.center()

        awaitFocus("$RETRY · A")
        awaitText("Source error · HTTP 500")

        source.answer = { _, _, page ->
            if (page == 1) loaded("A", titles("A", 2), hasNext = true) else loaded("A", listOf("Recovered"))
        }
        dpad.center()
        awaitFocus("Recovered")
    }

    /** A source can keep claiming there is more and repeat itself. The card goes, and focus has somewhere to be. */
    @Test
    fun aPageThatAddsNothingEndsTheRowAndFocusMovesToItsLastTitle() {
        source.sections = 1
        source.answer = { _, _, _ -> loaded("A", titles("A", 2), hasNext = true) }
        showScreen()

        awaitFocus("A 0")
        pressUntil(Dpad.Right, "$LOAD_MORE · A")
        dpad.center()

        awaitFocus("A 1")
        rule.onAllNodesWithContentDescription("$LOAD_MORE · A").fetchSemanticsNodes().let { assertTrue(it.isEmpty()) }
    }

    // ---- Every stop ----------------------------------------------------------------------------------------------

    /** Long rails have to scroll to reach their trailing cards, and the fourth rail has to scroll in. */
    @Test
    fun everyRailCardAndTrailingCardIsReachableWithNoDeadEndsOrTraps() {
        source.options = listOf(ONE, TWO)
        source.sections = 4
        source.answer = { _, section, _ ->
            when (section) {
                0 -> loaded("A", titles("A", 12), hasNext = true)
                1 -> loaded("B", titles("B", 1))
                else -> loaded(rowName(section), titles(rowName(section), 3), hasNext = true)
            }
        }
        showScreen()
        awaitFocus("A 0")

        val graph = dpad.walk()
        val expected = titles("A", 12) + titles("B", 1) + titles("C", 3) + titles("D", 3) +
            listOf("$LOAD_MORE · A", "$LOAD_MORE · C", "$LOAD_MORE · D", SOURCE_ONE, ACCOUNTS)
        assertEquals(graph.toString(), emptyList<String>(), graph.unreachable(expected))
        assertEquals(graph.toString(), emptyList<String>(), graph.deadEnds())
        assertEquals(graph.toString(), emptyList<String>(), graph.traps())
        assertEquals(graph.toString(), emptySet<String>(), graph.keyboardOpenedOn)
    }

    // ---- Coming back ---------------------------------------------------------------------------------------------

    /** Leaves composition and comes back, which is what the fragment's view does around a title's page. */
    @Test
    fun focusReturnsToTheOpenedCardOnItsScrolledRowWhenTheScreenComesBack() {
        rails(5, cards = 12)
        showScreen()
        awaitFocus("A 0")

        pressUntil(Dpad.Down, "D 0", presses = 3)
        pressUntil(Dpad.Right, "D 10", presses = 10)
        dpad.center()
        rule.waitUntil(WAIT_MS) { effects.isNotEmpty() }
        assertEquals(HomeEffect.OpenTitle("id/D 10"), effects.single())
        source.calls.clear()

        rule.runOnIdle { viewModel.onAction(HomeAction.Disappeared) }
        onScreen = false
        rule.waitForIdle()
        rule.onNode(isFocused()).assertDoesNotExist()

        onScreen = true
        rule.waitForIdle()
        // Composed but not resumed yet. A fragment's view is in this state for a while, and takes nothing.
        rule.onNode(isFocused()).assertDoesNotExist()

        rule.runOnIdle { viewModel.onAction(HomeAction.Reappeared) }
        rule.waitUntil(WAIT_MS) { viewModel.state.value.returnTo == null }
        rule.waitForIdle()
        // After the return point is cleared and everything has recomposed: focus has to stay, not just arrive.
        assertEquals("D 10", dpad.focused())
        rule.onNode(isFocused()).assertIsDisplayed()
        rule.onNodeWithText("D").assertIsDisplayed()
        assertTrue("nothing was fetched again", source.calls.isEmpty())
    }

    @Test
    fun aTitleThatHasGoneFallsBackToTheFirstCard() {
        rails(2)
        showScreen()
        awaitFocus("A 0")
        pressUntil(Dpad.Down, "B 0", presses = 1)
        dpad.center()
        rule.waitUntil(WAIT_MS) { effects.isNotEmpty() }

        rule.runOnIdle { viewModel.onAction(HomeAction.Disappeared) }
        onScreen = false
        rule.waitForIdle()
        // The same source, whose next page of rows does not have that card any more.
        source.answer = { _, section, _ -> loaded(rowName(section), titles("New ${rowName(section)}", 2)) }
        rule.runOnIdle { viewModel.onAction(HomeAction.Retry) }
        rule.waitUntil(WAIT_MS) { viewModel.state.value.status is HomeStatus.Showing }
        onScreen = true
        rule.runOnIdle { viewModel.onAction(HomeAction.Reappeared) }

        awaitFocus("New A 0")
    }

    // ---- Choosing a source ---------------------------------------------------------------------------------------

    @Test
    fun theSourceControlOpensAPanelOnTheChosenRowAndAChoiceAppliesAtOnceAndIsRemembered() {
        rails(2)
        source.options = listOf(ONE, TWO)
        memory.id = TWO.id
        showScreen()
        awaitFocus("A 0")

        pressUntil(Dpad.Up, SOURCE_TWO)
        dpad.center()
        awaitFocus(TWO.name)

        source.answer = { _, section, _ -> loaded("Other ${rowName(section)}", titles("Other", 2)) }
        source.calls.clear()
        pressUntil(Dpad.Up, ONE.name, presses = 1)
        dpad.center()

        awaitText("Other A")
        assertEquals(ONE.id, memory.id)
        assertTrue(source.calls.all { it.source == ONE.id })
        // The panel has closed by itself, and focus is back on the control so another choice is one press away.
        awaitFocus(SOURCE_ONE)
        assertFalse(viewModel.state.value.pickerOpen)
    }

    @Test
    fun backClosesThePanelAndChangesNothing() {
        rails(1)
        source.options = listOf(ONE, TWO)
        showScreen()
        awaitFocus("A 0")

        pressUntil(Dpad.Up, SOURCE_ONE)
        dpad.center()
        awaitFocus(ONE.name)
        source.calls.clear()

        device.pressBack()

        rule.waitUntil(WAIT_MS) { !viewModel.state.value.pickerOpen }
        awaitFocus(SOURCE_ONE)
        assertTrue(source.calls.isEmpty())
        assertEquals(null, memory.id)
    }

    @Test
    fun theFocusInThePanelStaysInThePanel() {
        rails(1)
        source.options = listOf(ONE, TWO)
        showScreen()
        awaitFocus("A 0")
        pressUntil(Dpad.Up, SOURCE_ONE)
        dpad.center()
        awaitFocus(ONE.name)

        assertEquals(ONE.name, dpad.press(Dpad.Left))
        // None sits above the sources (3h), and is the panel's top.
        assertEquals(NONE, dpad.press(Dpad.Up))
        assertEquals(NONE, dpad.press(Dpad.Up))
        assertEquals(ONE.name, dpad.press(Dpad.Down))
        assertEquals(TWO.name, dpad.press(Dpad.Down))
        assertEquals(TWO.name, dpad.press(Dpad.Down))
        device.pressBack()
        rule.waitUntil(WAIT_MS) { !viewModel.state.value.pickerOpen }
    }

    @Test
    fun theAccountCellAsksForAccounts() {
        rails(1)
        showScreen()
        awaitFocus("A 0")

        pressUntil(Dpad.Up, SOURCE_ONE)
        // 3j's order: the source, Reload, Random, Search, then the account.
        assertEquals(RELOAD, dpad.press(Dpad.Right))
        assertEquals(RANDOM, dpad.press(Dpad.Right))
        assertEquals(SEARCH, dpad.press(Dpad.Right))
        assertEquals(ACCOUNTS, dpad.press(Dpad.Right))
        dpad.center()
        rule.waitUntil(WAIT_MS) { effects.isNotEmpty() }
        assertEquals(HomeEffect.OpenAccounts, effects.single())
    }

    // ---- The hero and Continue watching (3c) -------------------------------------------------------------------------

    private fun featuring(vararg titles: HomeFeatured) {
        source.featured = { item -> titles.firstOrNull { it.id == item.id } }
    }

    private fun resuming(count: Int, progress: Float = HALF, title: (Int) -> String = { "$RESUME $it" }) {
        history.history = HomeHistory(
            resume = List(count) {
                HomeResume("cw/$it", title(it), progress = progress, season = 1, episode = it + 1, sourceName = ONE.name)
            },
        )
    }

    /** The colour at a fraction across, and a distance up from the foot, of what [node] draws. */
    private fun pixel(node: androidx.compose.ui.test.SemanticsNodeInteraction, across: Float, fromFoot: Float): Int {
        val image = node.captureToImage().asAndroidBitmap()
        val density = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
        val y = (image.height - fromFoot * density).toInt().coerceIn(0, image.height - 1)
        return image.getPixel((image.width * across).toInt(), y)
    }

    private fun assertColour(what: String, expected: androidx.compose.ui.graphics.Color, actual: Int) {
        val want = expected.toArgb()
        val close = listOf(16, 8, 0).all { shift -> abs((want shr shift and 0xFF) - (actual shr shift and 0xFF)) <= COLOUR_SLACK }
        assertTrue("$what: wanted ${Integer.toHexString(want)}, was ${Integer.toHexString(actual)}", close)
    }

    @Test
    fun theHeroTakesFocusAtRestWithItsFactsWhereTheBoardPutsThem() {
        rails(2)
        featuring(FEATURED)
        resuming(2)
        showScreen()

        awaitFocus(HERO)
        rule.onNodeWithText(FEATURED.title).assertIsDisplayed()
        rule.onNodeWithText("2026 · 48 min · Sci-Fi · Thriller").assertIsDisplayed()
        rule.onNodeWithText("16+").assertIsDisplayed()
        rule.onNodeWithText("8.7").assertIsDisplayed()
        rule.onNodeWithText(FEATURED.synopsis!!).assertIsDisplayed()
        // The eyebrow is the row the title was found on.
        assertEquals(2, rule.onAllNodesWithText("A").fetchSemanticsNodes().size)
        // 3c halved: the button at y252 and 32dp tall, Continue watching's cards at y360.
        val button = rule.onNode(isFocused()).getUnclippedBoundsInRoot()
        assertEquals(252f, button.top.value, GEOMETRY_SLACK)
        assertEquals(32f, (button.bottom - button.top).value, GEOMETRY_SLACK)
        val card = rule.onNodeWithContentDescription("$RESUME 0").getUnclippedBoundsInRoot()
        assertEquals(360f, card.top.value, GEOMETRY_SLACK)
        // 192x152, give or take the pixel each band rounds to at this AVD's 1.33125 density: 152dp measures 150.99.
        assertEquals(192f, (card.right - card.left).value, GEOMETRY_SLACK)
        assertEquals(152f, (card.bottom - card.top).value, ROUNDING_SLACK)

        dpad.center()
        rule.waitUntil(WAIT_MS) { effects.isNotEmpty() }
        assertEquals(HomeEffect.OpenTitle(FEATURED.id), effects.single())
    }

    @Test
    fun aHeroWithNoFactsLeavesItsBandsEmptyAndItsButtonWhereItWas() {
        rails(1)
        featuring(HomeFeatured("id/A 0", "Bare"))
        showScreen()

        awaitFocus("$MORE_INFO · Bare")
        assertEquals(252f, rule.onNode(isFocused()).getUnclippedBoundsInRoot().top.value, GEOMETRY_SLACK)
        rule.onAllNodesWithText("8.7").fetchSemanticsNodes().let { assertTrue("no score was invented", it.isEmpty()) }
    }

    @Test
    fun noTitleAnsweringIsNoHeroAndFocusLandsOnTheFirstCard() {
        rails(1, cards = 4)
        featuring()
        showScreen()

        awaitFocus("A 0")
        assertEquals(HomeHero.None, viewModel.state.value.hero)
        rule.onAllNodesWithText(MORE_INFO).fetchSemanticsNodes().let { assertTrue(it.isEmpty()) }
        rule.onAllNodesWithText(CONTINUE).fetchSemanticsNodes().let { assertTrue("no entries, no rail", it.isEmpty()) }
    }

    @Test
    fun oneEntryIsARailOfOneThatTakesFocusWithNoHeroAndOpensItsTitle() {
        rails(1)
        resuming(1)
        showScreen()

        awaitFocus("$RESUME 0")
        rule.onNodeWithText(CONTINUE).assertIsDisplayed()
        rule.onNodeWithText("S1 E1 · ${ONE.name}").assertIsDisplayed()
        // A rail of one has a wall at its end, as every rail does.
        assertEquals("$RESUME 0", dpad.press(Dpad.Right))
        // Down lands on the poster under the card's middle, which is the second: the card is 192dp wide over 80dp posters.
        assertEquals("A 1", dpad.press(Dpad.Down))
        assertEquals("$RESUME 0", dpad.press(Dpad.Up))

        dpad.center()
        rule.waitUntil(WAIT_MS) { effects.isNotEmpty() }
        assertEquals(HomeEffect.OpenTitle("cw/0"), effects.single())
    }

    @Test
    fun manyEntriesScrollToTheLastWhichIsAWall() {
        rails(1)
        resuming(12)
        showScreen()

        awaitFocus("$RESUME 0")
        pressUntil(Dpad.Right, "$RESUME 11", presses = 11)
        rule.onNode(isFocused()).assertIsDisplayed()
        assertEquals("$RESUME 11", dpad.press(Dpad.Right))
    }

    /** The progress rule, in `live` to the fraction watched and `line` past it, and only the track at zero. */
    @Test
    fun aContinueCardDrawsItsProgressAndAPositionOfZeroDrawsOnlyTheTrack() {
        rails(1)
        history.history = HomeHistory(
            resume = listOf(HomeResume("cw/half", "Half", progress = HALF), HomeResume("cw/zero", "Zero", progress = 0f)),
        )
        showScreen()
        awaitFocus("Half")
        val colors = cloudStreamDarkColors()
        // The rule is 4dp tall and its foot is 40dp above the card's: 4 under it, then the 32dp text stack.
        val half = rule.onNodeWithContentDescription("Half")
        assertColour("half, before the middle", colors.live, pixel(half, across = 0.2f, fromFoot = RULE_FROM_FOOT))
        assertColour("half, past the middle", colors.line, pixel(half, across = 0.8f, fromFoot = RULE_FROM_FOOT))
        val zero = rule.onNodeWithContentDescription("Zero")
        assertColour("zero, at the start", colors.line, pixel(zero, across = 0.02f, fromFoot = RULE_FROM_FOOT))
        assertColour("zero, at the end", colors.line, pixel(zero, across = 0.98f, fromFoot = RULE_FROM_FOOT))
    }

    @Test
    fun aRailCardWithAStoredPositionDrawsItsProgressAndOneWithoutDoesNot() {
        rails(1, cards = 2)
        history.history = HomeHistory(progress = mapOf("id/A 1" to HALF))
        showScreen()
        awaitFocus("A 0")
        val colors = cloudStreamDarkColors()
        val without = pixel(rule.onNode(isFocused()), across = 0.2f, fromFoot = LIFTED_RULE_FROM_FOOT)
        assertFalse("no position, no rule", abs((without shr 16 and 0xFF) - (colors.live.toArgb() shr 16 and 0xFF)) <= COLOUR_SLACK)

        assertEquals("A 1", dpad.press(Dpad.Right))
        val card = rule.onNode(isFocused())
        assertColour("watched half", colors.live, pixel(card, across = 0.2f, fromFoot = LIFTED_RULE_FROM_FOOT))
        assertColour("the rest", colors.line, pixel(card, across = 0.8f, fromFoot = LIFTED_RULE_FROM_FOOT))
    }

    @Test
    fun longTitlesStayInsideTheCardAndTheHeroOnOneLine() {
        rails(1)
        featuring(HomeFeatured("id/A 0", LONG_TITLE))
        resuming(2) { if (it == 0) LONG_TITLE else "$RESUME $it" }
        showScreen()
        awaitFocus("$MORE_INFO · $LONG_TITLE")

        val texts = rule.onAllNodesWithText(LONG_TITLE, useUnmergedTree = true).fetchSemanticsNodes()
        assertEquals("the hero's title and the card's", 2, texts.size)
        texts.forEach { node ->
            val width = with(rule.density) { node.size.width.toDp() }
            assertTrue("$width is inside 500dp", width.value <= 500f + GEOMETRY_SLACK)
            val layout = mutableListOf<TextLayoutResult>()
            node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layout)
            assertEquals("one line", 1, layout.single().lineCount)
            assertTrue("ellipsized", layout.single().isLineEllipsized(0))
        }
        rule.onNodeWithContentDescription(LONG_TITLE).assertWidthIsEqualTo(192.dp)
        // The next card is where it would be beside a short title: 192 + 12.
        val first = rule.onNodeWithContentDescription(LONG_TITLE).getUnclippedBoundsInRoot()
        val second = rule.onNodeWithContentDescription("$RESUME 1").getUnclippedBoundsInRoot()
        assertEquals(204f, (second.left - first.left).value, GEOMETRY_SLACK)
    }

    /** The owner's ruling for 3g and 3m: what is on this device stays above a failure. */
    @Test
    fun offlineKeepsContinueWatchingAboveTheStateAndBothAreReachable() {
        source.answer = { _, _, _ -> HomePageReply.Offline }
        featuring(FEATURED)
        resuming(3)
        showScreen()

        awaitFocus(RETRY)
        rule.onNodeWithText(CONTINUE).assertIsDisplayed()
        rule.onNodeWithText(OFFLINE).assertIsDisplayed()
        rule.onAllNodesWithText(MORE_INFO).fetchSemanticsNodes().let { assertTrue("no hero offline", it.isEmpty()) }
        val rail = rule.onNodeWithContentDescription("$RESUME 0").getUnclippedBoundsInRoot()
        val retry = rule.onNode(isFocused()).getUnclippedBoundsInRoot()
        assertTrue("the rail is above the state", rail.bottom <= retry.top)
        // It fits at 720p without its body, so nothing moved: the chrome is where it is at rest, clear of overscan.
        rule.waitForIdle()
        assertEquals(CHROME_CONTROL_TOP, rule.onNodeWithContentDescription(SOURCE_ONE).getUnclippedBoundsInRoot().top.value, GEOMETRY_SLACK)
        rule.onNode(isFocused()).assertIsDisplayed()
        assertTrue("the button is inside the band", retry.bottom.value <= SCREEN_HEIGHT_DP - 28f + GEOMETRY_SLACK)
        rule.onNodeWithContentDescription("$RESUME 0").assertIsDisplayed()
        rule.onAllNodesWithText(NO_CONNECTION, substring = true).fetchSemanticsNodes()
            .let { assertTrue("the body is left out under the rail", it.isEmpty()) }

        // Up reaches the card above the button's middle, and Down comes back.
        assertEquals("$RESUME 2", dpad.press(Dpad.Up))
        assertEquals(RETRY, dpad.press(Dpad.Down))
        val graph = dpad.walk()
        val expected = List(3) { "$RESUME $it" } + listOf(RETRY, SOURCE_ONE, ACCOUNTS)
        assertEquals(graph.toString(), emptyList<String>(), graph.unreachable(expected))
        assertEquals(graph.toString(), emptyList<String>(), graph.deadEnds())
        assertEquals(graph.toString(), emptyList<String>(), graph.traps())
    }

    /**
     * Offline under Continue watching and the bookmarks is taller than what 720p leaves below both rails, so the page moves.
     * It moves until the chrome is wholly off the screen: less would leave it cut by a TV's overscan. (A failure was this
     * case's state until 3m made a failure under the local rails a 48dp band, which fits.)
     */
    @Test
    fun aStateTooTallUnderContinueWatchingMovesTheChromeWhollyOffTheScreen() {
        source.answer = { _, _, _ -> HomePageReply.Offline }
        resuming(3)
        bookmarking(3)
        showScreen()

        awaitFocus(RETRY)
        rule.waitForIdle()
        val chrome = rule.onAllNodesWithContentDescription(SOURCE_ONE).fetchSemanticsNodes()
        val retry = rule.onNode(isFocused()).getUnclippedBoundsInRoot()
        assertTrue(
            "the chrome is off the screen, was ${chrome.map { it.boundsInRoot }}, with Retry at $retry",
            chrome.all { it.boundsInRoot.bottom <= 0f },
        )
        assertTrue("the button is inside the band", retry.bottom.value <= SCREEN_HEIGHT_DP - 28f + GEOMETRY_SLACK)
        rule.onNode(isFocused()).assertIsDisplayed()
    }

    /** 3m: under Continue watching a failure is the 48dp band, whose detail is one ellipsized line, and nothing moves. */
    @Test
    fun aFailureWithALongDetailUnderContinueWatchingIsOneBandAndNothingMoves() {
        source.answer = { _, _, _ -> HomePageReply.Failed(LONG_DETAIL) }
        resuming(3)
        showScreen()

        awaitFocus(RETRY)
        rule.waitForIdle()
        assertEquals(CHROME_CONTROL_TOP, rule.onNodeWithContentDescription(SOURCE_ONE).getUnclippedBoundsInRoot().top.value, GEOMETRY_SLACK)
        rule.onNodeWithContentDescription("$RESUME 0").assertIsDisplayed()
        val detail = rule.onNodeWithText(LONG_DETAIL).getUnclippedBoundsInRoot()
        val retry = rule.onNode(isFocused()).getUnclippedBoundsInRoot()
        assertTrue("the detail is one line beside Try again, was $detail against $retry", detail.bottom - detail.top < retry.bottom - retry.top)
        assertTrue("the detail ends before Try again", detail.right <= retry.left)
        assertTheRingIsAboveTheBottomBand()
    }

    /** What the page reserves for Continue watching above a state, against the card as it really measures. */
    @Test
    fun continueWatchingsReservedHeightIsTheRealRails() {
        rails(1)
        resuming(1)
        showScreen()
        awaitFocus("$RESUME 0")
        val card = rule.onNodeWithContentDescription("$RESUME 0").getUnclippedBoundsInRoot()
        val measured = RailHeaderDefaults.Height.value + CloudStreamDimens.LiftedRowClearance.value + (card.bottom - card.top).value
        assertEquals(measured, ContinueRailHeight.value, ROUNDING_SLACK)
        // And the rail's header sits where that height says, above the card.
        val header = rule.onNodeWithText(CONTINUE).getUnclippedBoundsInRoot()
        assertTrue("the header is in the 28dp above the clearance", header.top.value >= card.top.value - 44f - GEOMETRY_SLACK)
    }

    @Test
    fun theDpadGoesFromTheHeroIntoTheRailsAndBackWithNoDeadEndsOrTraps() {
        rails(3, cards = 4)
        featuring(FEATURED)
        resuming(3)
        showScreen()
        awaitFocus(HERO)

        assertEquals("$RESUME 0", dpad.press(Dpad.Down))
        // The poster under the card's middle, as on `oneEntryIsARailOfOne…`.
        assertEquals("A 1", dpad.press(Dpad.Down))
        assertEquals("B 1", dpad.press(Dpad.Down))
        assertEquals("A 1", dpad.press(Dpad.Up))
        assertEquals("$RESUME 0", dpad.press(Dpad.Up))
        assertEquals(HERO, dpad.press(Dpad.Up))
        rule.waitForIdle()
        // Back at rest: the chrome and the hero's title are on screen again.
        rule.onNodeWithContentDescription(SOURCE_ONE).assertIsDisplayed()
        rule.onNodeWithText(FEATURED.title).assertIsDisplayed()
        upToTheSourceControl()

        val graph = dpad.walk()
        val expected = listOf(HERO, SOURCE_ONE, ACCOUNTS) + List(3) { "$RESUME $it" } +
            titles("A", 4) + titles("B", 4) + titles("C", 4)
        assertEquals(graph.toString(), emptyList<String>(), graph.unreachable(expected))
        assertEquals(graph.toString(), emptyList<String>(), graph.deadEnds())
        assertEquals(graph.toString(), emptyList<String>(), graph.traps())
        assertEquals(graph.toString(), emptySet<String>(), graph.keyboardOpenedOn)
    }

    /** Opens, leaves composition as the fragment's view does, and comes back. */
    private fun openAndComeBack(expected: HomeEffect) {
        dpad.center()
        rule.waitUntil(WAIT_MS) { effects.isNotEmpty() }
        assertEquals(expected, effects.single())
        rule.runOnIdle { viewModel.onAction(HomeAction.Disappeared) }
        onScreen = false
        rule.waitForIdle()
        onScreen = true
        rule.waitForIdle()
        rule.onNode(isFocused()).assertDoesNotExist()
        rule.runOnIdle { viewModel.onAction(HomeAction.Reappeared) }
        rule.waitUntil(WAIT_MS) { viewModel.state.value.returnTo == null }
        rule.waitForIdle()
    }

    @Test
    fun focusComesBackToTheContinueCardThatWasOpened() {
        rails(2)
        featuring(FEATURED)
        resuming(8)
        showScreen()
        awaitFocus(HERO)
        pressUntil(Dpad.Down, "$RESUME 0", presses = 1)
        pressUntil(Dpad.Right, "$RESUME 6", presses = 6)

        openAndComeBack(HomeEffect.OpenTitle("cw/6"))
        // After the return point is cleared and everything has recomposed: focus has to stay, not just arrive.
        assertEquals("$RESUME 6", dpad.focused())
        rule.onNode(isFocused()).assertIsDisplayed()
    }

    @Test
    fun focusComesBackToTheHero() {
        rails(2)
        featuring(FEATURED)
        resuming(2)
        showScreen()
        awaitFocus(HERO)

        openAndComeBack(HomeEffect.OpenTitle(FEATURED.id))
        assertEquals(HERO, dpad.focused())
    }

    @Test
    fun aContinueEntryThatHasGoneFallsBackToTheHero() {
        rails(1)
        featuring(FEATURED)
        resuming(3)
        showScreen()
        awaitFocus(HERO)
        pressUntil(Dpad.Down, "$RESUME 0", presses = 1)
        pressUntil(Dpad.Right, "$RESUME 2", presses = 2)
        // Finished while its page was open, so the player took it off the list.
        resuming(2)

        openAndComeBack(HomeEffect.OpenTitle("cw/2"))
        assertEquals(HERO, dpad.focused())
    }

    // ---- Bookmarks (`TV Home Extras` 3e to 3g) ---------------------------------------------------------------------

    /** [count] bookmarks named `Saved 0`, `Saved 1`, newest first, in [status], with [others] in Completed. */
    private fun bookmarking(count: Int, status: HomeWatchStatus = HomeWatchStatus.Watching, others: Int = 0, kept: HomeWatchStatus? = status) {
        shelf.all = List(count) { HomeBookmark(HomeItem("bm/$it", "$SAVED $it"), status, updatedAt = (count - it).toLong()) } +
            List(others) { HomeBookmark(HomeItem("done/$it", "$DONE $it"), HomeWatchStatus.Completed, updatedAt = 0L) }
        shelf.kept = kept
    }

    @Test
    fun theBookmarksRailSitsBetweenContinueWatchingAndTheRailsAndTheDpadWalksChipsThenCards() {
        rails(2, cards = 4)
        featuring(FEATURED)
        resuming(2)
        bookmarking(3, others = 1)
        showScreen()
        awaitFocus(HERO)

        // Each Down reaches the next group, at whichever stop lies under the last one's middle, as Compose's search does.
        assertEquals("$RESUME 0", dpad.press(Dpad.Down))
        assertEquals(WATCHING, dpad.press(Dpad.Down))
        val card = dpad.press(Dpad.Down)
        assertTrue("Down from the chips is a bookmark, was $card", card?.startsWith(SAVED) == true)
        val rail = dpad.press(Dpad.Down)
        assertTrue("Down from a bookmark is the next rail, was $rail", rail?.startsWith("A ") == true)
        assertEquals(card, dpad.press(Dpad.Up))
        assertTrue("Up from a bookmark is a chip", dpad.press(Dpad.Up) in setOf(WATCHING, COMPLETED))
        // A chip only for a status that holds a title, in the old order: no Plan to Watch, On-Hold or Dropped here.
        rule.onNodeWithText(COMPLETED).assertIsDisplayed()
        rule.onAllNodesWithText("Dropped").fetchSemanticsNodes().let { assertTrue("no empty status gets a chip", it.isEmpty()) }
        // The title is on the chips' row, not a header of its own.
        val title = rule.onNodeWithText(BOOKMARKS).getUnclippedBoundsInRoot()
        val chip = rule.onNode(isFocused()).getUnclippedBoundsInRoot()
        assertEquals((chip.top + chip.bottom).value / 2, (title.top + title.bottom).value / 2, GEOMETRY_SLACK)
        rule.onNode(isFocused()).assertHeightIsEqualTo(CloudStreamDimens.ChipFocusCell)

        val graph = dpad.walk()
        val expected = listOf(HERO, SOURCE_ONE, ACCOUNTS, WATCHING, COMPLETED) + List(2) { "$RESUME $it" } +
            titles(SAVED, 3) + titles("A", 4) + titles("B", 4)
        assertEquals(graph.toString(), emptyList<String>(), graph.unreachable(expected))
        assertEquals(graph.toString(), emptyList<String>(), graph.deadEnds())
        assertEquals(graph.toString(), emptyList<String>(), graph.traps())
    }

    @Test
    fun theLastChipAndTheLastBookmarkAreWalls() {
        rails(1)
        bookmarking(2, others = 1)
        showScreen()
        awaitFocus("$SAVED 0")
        assertEquals("$SAVED 1", dpad.press(Dpad.Right))
        assertEquals("$SAVED 1", dpad.press(Dpad.Right))
        pressUntil(Dpad.Up, WATCHING, presses = 2)
        assertEquals(COMPLETED, dpad.press(Dpad.Right))
        assertEquals(COMPLETED, dpad.press(Dpad.Right))
    }

    @Test
    fun aChipAppliesAtOnceFocusStaysOnItAndPressingTheChosenOneShowsNoStatusChosen() {
        rails(1, cards = 4)
        bookmarking(2, others = 2)
        showScreen()
        awaitFocus("$SAVED 0")
        pressUntil(Dpad.Up, WATCHING, presses = 1)
        assertEquals(COMPLETED, dpad.press(Dpad.Right))
        // Walking onto a chip chose nothing.
        rule.waitForIdle()
        assertEquals(HomeWatchStatus.Watching, viewModel.state.value.bookmarks.applied)

        dpad.center()
        rule.waitUntil(WAIT_MS) { viewModel.state.value.bookmarks.applied == HomeWatchStatus.Completed }
        rule.waitForIdle()
        assertEquals(COMPLETED, dpad.focused())
        assertEquals(titles(DONE, 2).map { "done/${it.removePrefix("$DONE ")}" }, viewModel.state.value.bookmarks.shown.map { it.id })
        assertEquals(HomeWatchStatus.Completed, shelf.kept)
        // The cards under it are the Completed ones.
        val card = dpad.press(Dpad.Down)
        assertTrue("Down reaches a Completed card, was $card", card?.startsWith(DONE) == true)
        // Up lands on the chip above the card's middle, which need not be the one pressed.
        assertTrue("Up comes back to a chip", dpad.press(Dpad.Up) in setOf(WATCHING, COMPLETED))
        pressUntil(Dpad.Right, COMPLETED, presses = 1)

        dpad.center()
        awaitText(NO_STATUS)
        assertEquals(COMPLETED, dpad.focused())
        assertEquals(null, shelf.kept)
        rule.onNodeWithText(NO_STATUS_BODY).assertIsDisplayed()
        // The band keeps the cards' height, so the rails below do not move, and it is not a stop: Down goes past it.
        val band = rule.onNodeWithText(NO_STATUS).getUnclippedBoundsInRoot()
        val below = dpad.press(Dpad.Down)
        assertTrue("Down goes past the band to the next rail, was $below", below?.startsWith("A ") == true)
        assertTrue("the next rail is below the band", rule.onNode(isFocused()).getUnclippedBoundsInRoot().top >= band.bottom)
        assertTrue("Up comes back to a chip", dpad.press(Dpad.Up) in setOf(WATCHING, COMPLETED))
    }

    /**
     * `BUG-41`. The bookmarks are one local read, so at launch they can be on screen before the source's rows and the hero.
     * The page then asks for focus as `HomeTvFragment.onResume` does, nothing pressed, and the window gives it to the first
     * stop it finds, a chip. That is not a person choosing the bookmarks, so when the hero lands it still takes focus.
     */
    @Test
    fun focusTheWindowGaveTheBookmarksBeforeTheHeroLandedStillGoesToTheHero() {
        rails(1)
        featuring(FEATURED)
        bookmarking(2)
        val pages = kotlinx.coroutines.CompletableDeferred<Unit>()
        source.gate = pages
        showScreen()
        awaitText(WATCHING)
        rule.runOnIdle { hostView!!.requestFocus() }
        rule.waitForIdle()
        // The precondition the launch race produces: focus sits in the bookmarks rail, nothing pressed.
        val given = dpad.focused()
        assertTrue("the window gave focus to the bookmarks rail, was $given", given == WATCHING || given?.startsWith(SAVED) == true)

        pages.complete(Unit)
        awaitFocus(HERO)
    }

    @Test
    fun noBookmarksIsNoRail() {
        rails(1)
        showScreen()
        awaitFocus("A 0")
        rule.onAllNodesWithText(BOOKMARKS).fetchSemanticsNodes().let { assertTrue("no bookmarks, no rail", it.isEmpty()) }
    }

    @Test
    fun withNoHeroOrContinueWatchingFocusLandsOnTheFirstBookmark() {
        rails(1)
        bookmarking(2)
        showScreen()
        awaitFocus("$SAVED 0")
        dpad.center()
        rule.waitUntil(WAIT_MS) { effects.isNotEmpty() }
        assertEquals(HomeEffect.OpenTitle("bm/0"), effects.single())
    }

    @Test
    fun focusComesBackToTheBookmarkThatWasOpened() {
        rails(2)
        featuring(FEATURED)
        bookmarking(9)
        showScreen()
        awaitFocus(HERO)
        // Down to the chips, then to whichever card is under the chip, then along to the eighth.
        assertEquals(WATCHING, dpad.press(Dpad.Down))
        dpad.press(Dpad.Down)
        rule.waitUntil(WAIT_MS) { dpad.focused()?.startsWith(SAVED) == true }
        if (dpad.focused() != "$SAVED 0") pressUntil(Dpad.Left, "$SAVED 0", presses = 3)
        pressUntil(Dpad.Right, "$SAVED 7", presses = 7)

        openAndComeBack(HomeEffect.OpenTitle("bm/7"))
        assertEquals("$SAVED 7", dpad.focused())
        rule.onNode(isFocused()).assertIsDisplayed()
    }

    /** 3g: offline no longer replaces the page. The bookmarks stay, dimmed, and the chrome says how many are kept. */
    @Test
    fun offlineKeepsTheBookmarksAboveTheStateDimmedAndSaysWhatNeedsTheConnection() {
        source.answer = { _, _, _ -> HomePageReply.Offline }
        bookmarking(3)
        showScreen()

        awaitFocus(RETRY)
        rule.waitForIdle()
        // It fits under the bookmarks, so nothing moved: the chrome is where it rests, with the notice in it.
        assertEquals(CHROME_CONTROL_TOP, rule.onNodeWithContentDescription(SOURCE_ONE).getUnclippedBoundsInRoot().top.value, GEOMETRY_SLACK)
        rule.onNodeWithText(OFFLINE).assertIsDisplayed()
        rule.onNodeWithText("3 BOOKMARKS").assertIsDisplayed()
        rule.onNodeWithText("${ONE.name} needs a connection").assertIsDisplayed()
        rule.onNodeWithText("Your bookmarks are saved on this device.").assertIsDisplayed()
        val chips = rule.onNodeWithText(WATCHING).getUnclippedBoundsInRoot()
        val retry = rule.onNode(isFocused()).getUnclippedBoundsInRoot()
        assertTrue("the rail is above the state", chips.bottom + BookmarksRailHeight - CloudStreamDimens.ChipFocusCell <= retry.top)
        assertTrue("the button is inside the band", retry.bottom.value <= SCREEN_HEIGHT_DP - 28f + GEOMETRY_SLACK)

        // Up reaches a card, then the chips, which work offline; Down comes back.
        pressUntil(Dpad.Up, WATCHING, presses = 2)
        pressUntil(Dpad.Down, RETRY, presses = 2)
        val graph = dpad.walk()
        val expected = titles(SAVED, 3) + listOf(WATCHING, RETRY, SOURCE_ONE, ACCOUNTS)
        assertEquals(graph.toString(), emptyList<String>(), graph.unreachable(expected))
        assertEquals(graph.toString(), emptyList<String>(), graph.deadEnds())
        assertEquals(graph.toString(), emptyList<String>(), graph.traps())
    }

    /** A card's middle, under the rail's title: cards start where the title does, a chip cell and the clearance below. */
    private fun firstBookmarkPixel(): Int {
        val title = rule.onNodeWithText(BOOKMARKS).getUnclippedBoundsInRoot()
        val chip = rule.onNodeWithText(WATCHING).getUnclippedBoundsInRoot()
        val cardTop = (chip.top + chip.bottom).value / 2 + CloudStreamDimens.ChipFocusCell.value / 2 +
            CloudStreamDimens.LiftedRowClearance.value
        val density = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
        val image = rule.onRoot().captureToImage().asAndroidBitmap()
        return image.getPixel(((title.left.value + 40f) * density).toInt(), ((cardTop + 60f) * density).toInt())
    }

    /** A dimmed card is 40% of the same card at rest: read from its pixels against the black ground. */
    @Test
    fun offlineBookmarksAreDrawnAtFortyPercent() {
        source.answer = { _, _, _ -> HomePageReply.Offline }
        bookmarking(1)
        showForScreenshot()
        awaitFocus(RETRY)
        rule.waitForIdle()
        val dim = firstBookmarkPixel()
        source.answer = { _, _, _ -> loaded("A", listOf("A 0")) }
        rule.runOnIdle { viewModel.onAction(HomeAction.Retry) }
        awaitText("A")
        rule.waitForIdle()
        // Retry was focused, and the page landed elsewhere: put focus on the chip, away from the card being read.
        rule.onNodeWithText(WATCHING).performSemanticsAction(SemanticsActions.RequestFocus)
        rule.waitForIdle()
        val full = firstBookmarkPixel()
        val ratio = android.graphics.Color.green(dim).toFloat() / android.graphics.Color.green(full)
        assertEquals("dimmed over full, ${Integer.toHexString(dim)} over ${Integer.toHexString(full)}", 0.4f, ratio, 0.05f)
    }

    /** 3m's rule, which the first load meets too: a failure keeps the bookmarks above it, and Try again takes focus. */
    @Test
    fun aFailureKeepsTheBookmarksAboveIt() {
        source.answer = { _, _, _ -> HomePageReply.Failed("HTTP 503") }
        resuming(2)
        bookmarking(2)
        showScreen()
        awaitFocus(RETRY)
        rule.waitForIdle()
        rule.onNode(isFocused()).assertIsDisplayed()
        rule.onNodeWithText("HTTP 503").assertIsDisplayed()
        val retry = rule.onNode(isFocused()).getUnclippedBoundsInRoot()
        val card = dpad.press(Dpad.Up)
        assertTrue("Up from Try again is the bookmarks rail, was $card", card?.startsWith(SAVED) == true || card == WATCHING)
        rule.onNode(isFocused()).assertIsDisplayed()
        assertTrue("the bookmarks are above the state", rule.onNode(isFocused()).getUnclippedBoundsInRoot().bottom <= retry.top)
    }

    /** What the page reserves for the bookmarks above a state, against the rail as it really measures. */
    @Test
    fun theBookmarksReservedHeightIsTheRealRail() {
        rails(1)
        bookmarking(1)
        showScreen()
        awaitFocus("$SAVED 0")
        val card = rule.onNode(isFocused()).getUnclippedBoundsInRoot()
        val chip = rule.onNodeWithText(WATCHING).getUnclippedBoundsInRoot()
        val chipCell = (chip.top + chip.bottom).value / 2 - CloudStreamDimens.ChipFocusCell.value / 2
        assertEquals(BookmarksRailHeight.value, card.bottom.value - chipCell, ROUNDING_SLACK)
    }

    // ---- The chrome, and None (`TV Home Extras` 3h to 3j, 3m) ------------------------------------------------------

    /** Up from the hero lands on whichever chrome cell is nearest above it; the source control is the row's first. */
    private fun upToTheSourceControl() {
        if (dpad.press(Dpad.Up) != SOURCE_ONE) pressUntil(Dpad.Left, SOURCE_ONE, presses = 4)
    }

    @Test
    fun theChromesCellsNameThemselvesAndAreThereOnlyBesideRows() {
        rails(1)
        showScreen()
        awaitFocus("A 0")
        pressUntil(Dpad.Up, SOURCE_ONE)
        // Icon-only, named by its description; the label drawn under it on focus is in the screenshot (3j).
        assertEquals(RELOAD, dpad.press(Dpad.Right))
        rule.onNode(isFocused()).assertWidthIsEqualTo(CloudStreamDimens.IconTargetCell)
        assertEquals(RANDOM, dpad.press(Dpad.Right))
        assertEquals(SEARCH, dpad.press(Dpad.Right))
    }

    @Test
    fun offlineHasNoReloadOrRandomOrSearch() {
        source.answer = { _, _, _ -> HomePageReply.Offline }
        showScreen()
        awaitFocus(RETRY)
        listOf(RELOAD, RANDOM, SEARCH).forEach { cell ->
            rule.onAllNodesWithContentDescription(cell).fetchSemanticsNodes().let { assertTrue("no $cell offline", it.isEmpty()) }
        }
    }

    @Test
    fun searchFromTheChromeIsOfThisSource() {
        rails(1)
        showScreen()
        awaitFocus("A 0")
        pressUntil(Dpad.Up, SOURCE_ONE)
        pressUntil(Dpad.Right, SEARCH, presses = 3)
        dpad.center()
        rule.waitUntil(WAIT_MS) { effects.isNotEmpty() }
        assertEquals(HomeEffect.OpenSourceSearch(ONE.id), effects.single())
    }

    @Test
    fun randomOpensALoadedTitleAndFocusComesBackToItsCell() {
        rails(2)
        showScreen()
        awaitFocus("A 0")
        pressUntil(Dpad.Up, SOURCE_ONE)
        pressUntil(Dpad.Right, RANDOM, presses = 2)
        dpad.center()
        rule.waitUntil(WAIT_MS) { effects.isNotEmpty() }
        val opened = effects.single() as HomeEffect.OpenTitle
        assertTrue("a loaded title, was ${opened.id}", opened.id in (titles("A", 3) + titles("B", 3)).map { "id/$it" })
        rule.runOnIdle { viewModel.onAction(HomeAction.Disappeared) }
        onScreen = false
        rule.waitForIdle()
        onScreen = true
        rule.waitForIdle()
        rule.runOnIdle { viewModel.onAction(HomeAction.Reappeared) }
        rule.waitUntil(WAIT_MS) { viewModel.state.value.returnTo == null }
        rule.waitForIdle()
        assertEquals(RANDOM, dpad.focused())
    }

    /** 3j: a reload in flight keeps the rows and focus, a second press asks nothing, and the answer swaps in place. */
    @Test
    fun aReloadKeepsTheRowsAndFocusWhileItRunsAndSwapsThemInPlace() {
        rails(1)
        showScreen()
        awaitFocus("A 0")
        pressUntil(Dpad.Up, SOURCE_ONE)
        pressUntil(Dpad.Right, RELOAD, presses = 1)
        val before = source.calls.size
        source.hold = true
        dpad.center()
        rule.waitUntil(WAIT_MS) { viewModel.state.value.reloading }
        dpad.center()
        rule.waitForIdle()
        assertEquals("a second press asks nothing", before + 1, source.calls.size)
        assertEquals(listOf("A"), viewModel.state.value.status.let { (it as? HomeStatus.Showing)?.rows?.map { row -> row.title } })
        rule.onNodeWithText("A").assertIsDisplayed()
        assertEquals(RELOAD, dpad.focused())
    }

    /** 3m: a failed reload shows the error where the rails were, with what the device keeps above it, and Try again. */
    @Test
    fun aReloadThatFailsKeepsWhatTheDeviceKeepsAboveTheErrorAndTryAgainTakesFocus() {
        rails(1)
        resuming(2)
        bookmarking(2)
        featuring(FEATURED)
        showScreen()
        awaitFocus(HERO)
        upToTheSourceControl()
        pressUntil(Dpad.Right, RELOAD, presses = 1)
        source.answer = { _, _, _ -> HomePageReply.Failed("HTTP 503") }
        dpad.center()
        awaitFocus(RETRY)
        rule.waitForIdle()
        rule.onNode(isFocused()).assertIsDisplayed()
        rule.onNodeWithText("HTTP 503").assertIsDisplayed()
        // 3m's band, not the full-page state: what failed is its one line, and there is no page headline above it.
        rule.onNodeWithText("Could not load ${ONE.name}").assertIsDisplayed()
        rule.onAllNodesWithText(SOURCE_ERROR).fetchSemanticsNodes().let { assertTrue("no full-page headline", it.isEmpty()) }
        assertTheRingIsAboveTheBottomBand()
        rule.onAllNodesWithContentDescription(RELOAD).fetchSemanticsNodes().let { assertTrue("no Reload with no rows", it.isEmpty()) }
        // Up reaches the bookmarks above, then the chips, then Continue watching.
        val above = generateSequence { dpad.press(Dpad.Up) }.take(4).toList()
        assertTrue("Up walks the kept rails, was $above", above.any { it?.startsWith(SAVED) == true })
        assertTrue("and Continue watching, was $above", above.any { it?.startsWith(RESUME) == true })
    }

    /** 3h: None sits first, and choosing it applies at once and closes. */
    @Test
    fun noneIsFirstInThePanelAndChoosingItShowsOnlyWhatTheDeviceKeeps() {
        rails(1)
        resuming(1)
        bookmarking(1)
        showScreen()
        awaitFocus("$RESUME 0")
        pressUntil(Dpad.Up, SOURCE_ONE)
        dpad.center()
        awaitFocus(ONE.name)
        assertEquals(NONE, dpad.press(Dpad.Up))
        rule.onNodeWithText(NONE_SUBTITLE).assertIsDisplayed()
        dpad.center()
        rule.waitUntil(WAIT_MS) { !viewModel.state.value.pickerOpen }
        assertEquals(HomeSourceId.None, memory.id)

        // 3i: the control names None, focus is back on it, and no source rails, Reload, Random or Search.
        awaitFocus(SOURCE_NONE)
        rule.onAllNodesWithText("A").fetchSemanticsNodes().let { assertTrue("no source rails", it.isEmpty()) }
        listOf(RELOAD, RANDOM, SEARCH).forEach { cell ->
            rule.onAllNodesWithContentDescription(cell).fetchSemanticsNodes().let { assertTrue("no $cell on None", it.isEmpty()) }
        }
        rule.onNodeWithContentDescription("$RESUME 0").assertExists()
        rule.onNodeWithText(BOOKMARKS).assertIsDisplayed()
        val graph = dpad.walk()
        val expected = listOf(SOURCE_NONE, ACCOUNTS, "$RESUME 0", WATCHING, "$SAVED 0")
        assertEquals(graph.toString(), emptyList<String>(), graph.unreachable(expected))
        assertEquals(graph.toString(), emptyList<String>(), graph.deadEnds())
        assertEquals(graph.toString(), emptyList<String>(), graph.traps())
    }

    @Test
    fun noneRememberedLandsOnTheFirstCardKeptOnTheDevice() {
        rails(1)
        bookmarking(2)
        memory.id = HomeSourceId.None
        showScreen()
        awaitFocus("$SAVED 0")
        assertTrue("nothing asked of a source", source.calls.isEmpty())
    }

    /** 3i with neither rail kept: the built state, whose one button opens the panel. */
    @Test
    fun noneWithNothingKeptIsAStateWhoseButtonOpensThePanel() {
        rails(1)
        memory.id = HomeSourceId.None
        showScreen()
        awaitFocus(SOURCES)
        rule.onNodeWithText(NO_SOURCE).assertIsDisplayed()
        dpad.center()
        rule.waitUntil(WAIT_MS) { viewModel.state.value.pickerOpen }
        awaitFocus(NONE)
    }

    // ---- Screenshots (`INF-11`), compared with the references in src/androidDeviceTest/resources/screenshots ---------

    /** As the app shows it: the rail beside the screen, which puts the page at the board's x, and solid posters. */
    private fun showForScreenshot() {
        rule.setContent {
            val state by viewModel.state.collectAsState()
            CloudStreamTheme {
                Row(Modifier.fillMaxSize().background(CloudStreamTheme.colors.bg)) {
                    // Dimmed while a side panel is open, as the TV shell's rail is (`TvRail`, 4d).
                    NavRail(
                        ShotRail,
                        selectedIndex = HOME_INDEX,
                        onSelect = {},
                        modifier = Modifier.sidePanelOutsideDim(CloudStreamTheme.colors.bg),
                    )
                    HomeScreenTv(
                        state = state,
                        onAction = viewModel::onAction,
                        modifier = Modifier.weight(1f),
                        artwork = { item ->
                            Image(
                                bitmap = solidPoster(ShotPosters[(item.title.hashCode() and Int.MAX_VALUE) % ShotPosters.size]),
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.FillBounds,
                            )
                        },
                    )
                }
            }
        }
        rule.runOnIdle { viewModel.onAction(HomeAction.Reappeared) }
    }

    /** Three rails as 3d draws them, the first with more to load, and a badge where the provider gave a quality. */
    private fun shotRails(failSection: Int? = null) {
        source.sections = 3
        source.answer = { _, section, _ ->
            when (section) {
                failSection -> HomePageReply.Failed("HTTP 503")
                0 -> shotRow("Popular movies", ShotTitles.take(8), hasNext = true)
                1 -> shotRow("TV shows", ShotTitles.drop(8).take(9))
                else -> shotRow("Latest", ShotTitles.drop(17))
            }
        }
    }

    @Test
    fun screenshotRailsFocusOnACard() {
        shotRails()
        showForScreenshot()
        awaitFocus(ShotTitles[0])
        assertEquals(ShotTitles[1], dpad.press(Dpad.Right))
        rule.assertScreenshot("home-rails-focus-card")
    }

    @Test
    fun screenshotRailsFocusOnLoadMore() {
        shotRails()
        showForScreenshot()
        awaitFocus(ShotTitles[0])
        pressUntil(Dpad.Right, "$LOAD_MORE · Popular movies", presses = 9)
        rule.assertScreenshot("home-rails-focus-load-more")
    }

    @Test
    fun screenshotPartialFailureFocusOnRetry() {
        shotRails(failSection = 1)
        showForScreenshot()
        awaitFocus(ShotTitles[0])
        pressUntil(Dpad.Up, RETRY_FAILED)
        rule.assertScreenshot("home-partial-failure-focus-retry")
    }

    @Test
    fun screenshotNothingInstalledFocusOnItsAction() {
        source.options = emptyList()
        source.installed = 0
        showForScreenshot()
        awaitFocus(EXTENSIONS)
        rule.assertScreenshot("home-nothing-installed-focus-action")
    }

    @Test
    fun screenshotSourcePanelFocusOnTheCurrentSource() {
        shotRails()
        source.options = listOf(ONE, TWO, THREE)
        memory.id = TWO.id
        showForScreenshot()
        awaitFocus(ShotTitles[0])
        pressUntil(Dpad.Up, SOURCE_TWO)
        dpad.center()
        awaitFocus(TWO.name)
        rule.assertScreenshot("home-source-panel-focus-current")
    }

    /** No page ever answers. Under a test the skeleton's pulse is held still, so this is one frame of it. */
    @Test
    fun screenshotLoading() {
        source.sections = 3
        source.answer = { _, _, _ -> HomePageReply.Loaded(emptyList(), hasNext = false) }
        source.hold = true
        showForScreenshot()
        rule.waitUntil(WAIT_MS) { viewModel.state.value.selected != null }
        rule.waitForIdle()
        assertTrue(viewModel.state.value.status is HomeStatus.Loading)
        rule.assertScreenshot("home-loading")
    }

    /** 3c at rest: Continue watching as the board fills it, part-watched, and three rails below. */
    private fun shot3c() {
        shotRails()
        source.featured = { item ->
            HomeFeatured(
                id = item.id,
                title = item.title,
                backdropUrl = "solid",
                year = 2026,
                durationMinutes = 48,
                genres = listOf("Sci-Fi", "Thriller", "Drama"),
                score = "8.7",
                contentRating = "16+",
                synopsis = "A salvage crew wakes from cryo to find the relay station empty, the logs erased, and " +
                    "something still breathing on deck four.",
            ).takeIf { item.title == ShotTitles[0] }
        }
        history.history = HomeHistory(
            resume = ShotContinue.mapIndexed { index, (title, progress) ->
                HomeResume("cw/$title", title, progress = progress, season = index % 3 + 1, episode = index + 4, sourceName = ONE.name)
            },
        )
    }

    /** 3d's rails with this device's progress on two posters, the focused one part-watched. */
    @Test
    fun screenshotRailsWithProgressFocusOnACard() {
        shotRails()
        history.history = HomeHistory(progress = mapOf("id/${ShotTitles[1]}" to 0.62f, "id/${ShotTitles[3]}" to 0.3f))
        showForScreenshot()
        awaitFocus(ShotTitles[0])
        assertEquals(ShotTitles[1], dpad.press(Dpad.Right))
        rule.assertScreenshot("home-rails-progress-focus-card")
    }

    @Test
    fun screenshotHeroFocusOnItsButton() {
        shot3c()
        showForScreenshot()
        awaitFocus("$MORE_INFO · ${ShotTitles[0]}")
        rule.assertScreenshot("home-hero-focus-button")
    }

    @Test
    fun screenshotContinueWatchingFocusOnACard() {
        shot3c()
        showForScreenshot()
        awaitFocus("$MORE_INFO · ${ShotTitles[0]}")
        assertEquals(ShotContinue[0].first, dpad.press(Dpad.Down))
        assertEquals(ShotContinue[1].first, dpad.press(Dpad.Right))
        rule.assertScreenshot("home-continue-focus-card")
    }

    @Test
    fun screenshotOfflineWithContinueWatchingFocusOnRetry() {
        shot3c()
        source.answer = { _, _, _ -> HomePageReply.Offline }
        showForScreenshot()
        awaitFocus(RETRY)
        rule.assertScreenshot("home-offline-continue-focus-retry")
    }

    /** 3e: the bookmarks rail with a card focused, as the board draws it, Continue watching above and a rail below. */
    @Test
    fun screenshotBookmarksFocusOnACard() {
        shotRails()
        shotBookmarks()
        showForScreenshot()
        // With no hero and no Continue watching, the first bookmark is the first thing on the page.
        awaitFocus(ShotMarks[0])
        assertEquals(ShotMarks[1], dpad.press(Dpad.Right))
        rule.assertScreenshot("home-bookmarks-focus-card")
    }

    /** 3f: no status chosen, focus on the chip just pressed, the source's rails still loading beneath. */
    @Test
    fun screenshotBookmarksNoStatusFocusOnTheChip() {
        source.sections = 2
        source.answer = { _, _, _ -> HomePageReply.Loaded(emptyList(), hasNext = false) }
        source.hold = true
        shotBookmarks(kept = null)
        showForScreenshot()
        rule.waitUntil(WAIT_MS) { viewModel.state.value.bookmarks.all.isNotEmpty() }
        rule.waitForIdle()
        // Nothing lands while the rails load, and a press with nothing focused goes wherever the window starts.
        rule.onNodeWithText(WATCHING).performSemanticsAction(SemanticsActions.RequestFocus)
        awaitFocus(WATCHING)
        rule.assertScreenshot("home-bookmarks-no-status-focus-chip")
    }

    /** 3g: offline, the bookmarks dimmed above the prompt, the chrome saying so, Try again focused. */
    @Test
    fun screenshotOfflineWithBookmarksFocusOnRetry() {
        source.answer = { _, _, _ -> HomePageReply.Offline }
        shotBookmarks()
        showForScreenshot()
        awaitFocus(RETRY)
        rule.assertScreenshot("home-offline-bookmarks-focus-retry")
    }

    /**
     * 3g at its tightest at 720p: offline with Continue watching and the bookmarks both above the prompt. The prompt keeps
     * the source's headline and drops its body, and Try again is focused, on screen and out of the bottom focus band.
     */
    @Test
    fun screenshotOfflineWithContinueWatchingAndBookmarksFocusOnRetry() {
        shot3c()
        shotBookmarks()
        source.answer = { _, _, _ -> HomePageReply.Offline }
        showForScreenshot()
        awaitFocus(RETRY)
        rule.waitForIdle()
        rule.onNodeWithText("${ONE.name} needs a connection").assertIsDisplayed()
        rule.onNodeWithText("Your bookmarks are saved on this device.").assertDoesNotExist()
        rule.onNode(isFocused()).assertIsDisplayed()
        val retry = rule.onNode(isFocused()).getUnclippedBoundsInRoot()
        assertTrue("the button is inside the band, bottom ${retry.bottom}", retry.bottom.value <= SCREEN_HEIGHT_DP - 28f + GEOMETRY_SLACK)
        rule.assertScreenshot("home-offline-continue-bookmarks-focus-retry")
    }

    /** 3e's nine bookmarks, two with a quality badge, and one Completed so that two chips show. */
    private fun shotBookmarks(kept: HomeWatchStatus? = HomeWatchStatus.Watching) {
        shelf.all = ShotMarks.mapIndexed { index, title ->
            HomeBookmark(HomeItem("bm/$title", title, badge = ShotBadges[title]), HomeWatchStatus.Watching, updatedAt = 100L - index)
        } + listOf(HomeBookmark(HomeItem("bm/done", "Signal Hill"), HomeWatchStatus.Completed))
        shelf.kept = kept
    }

    /** 3j at rest with Random focused: the source, Reload, Random named under its cell, Search and the account. */
    @Test
    fun screenshotChromeFocusOnRandom() {
        shot3c()
        showForScreenshot()
        awaitFocus("$MORE_INFO · ${ShotTitles[0]}")
        upToTheSourceControl()
        pressUntil(Dpad.Right, RANDOM, presses = 2)
        rule.assertScreenshot("home-chrome-focus-random")
    }

    /** 3h: the panel with None first and focused, the current source marked. */
    @Test
    fun screenshotSourcePanelFocusOnNone() {
        shotRails()
        source.options = listOf(ONE, TWO, THREE)
        showForScreenshot()
        awaitFocus(ShotTitles[0])
        pressUntil(Dpad.Up, SOURCE_ONE)
        dpad.center()
        awaitFocus(ONE.name)
        assertEquals(NONE, dpad.press(Dpad.Up))
        rule.assertScreenshot("home-source-panel-focus-none")
    }

    /** 3i: None, with Continue watching and the bookmarks, focus on the source control. */
    @Test
    fun screenshotNoneFocusOnTheSourceControl() {
        shot3c()
        shotBookmarks()
        memory.id = HomeSourceId.None
        showForScreenshot()
        awaitFocus(ShotContinue[0].first)
        pressUntil(Dpad.Up, SOURCE_NONE, presses = 1)
        rule.assertScreenshot("home-none-focus-source")
    }

    /** 3m: a reload that failed, the kept rails above the error, Try again focused. */
    @Test
    fun screenshotReloadFailedFocusOnTryAgain() {
        shot3c()
        shotBookmarks()
        showForScreenshot()
        awaitFocus("$MORE_INFO · ${ShotTitles[0]}")
        upToTheSourceControl()
        pressUntil(Dpad.Right, RELOAD, presses = 1)
        source.answer = { _, _, _ -> HomePageReply.Failed("HTTP 503") }
        dpad.center()
        awaitFocus(RETRY)
        rule.waitForIdle()
        assertTheRingIsAboveTheBottomBand()
        rule.assertScreenshot("home-reload-failed-focus-retry")
    }

    /**
     * The focused control's ring, which sits `FocusRingBleed` outside it, stays out of the `FocusBandInset` the screen keeps
     * free at its foot: a ring in that band is in a TV's overscan.
     */
    private fun assertTheRingIsAboveTheBottomBand() {
        val ringBottom = rule.onNode(isFocused()).getUnclippedBoundsInRoot().bottom + CloudStreamDimens.FocusRingBleed
        val limit = SCREEN_HEIGHT_DP - CloudStreamDimens.FocusBandInset.value
        assertTrue("the ring's foot is at ${ringBottom.value}dp, past the band at ${limit}dp", ringBottom.value <= limit + GEOMETRY_SLACK)
    }

    private data class Call(val source: HomeSourceId, val section: Int, val page: Int)

    private class Kept(var id: HomeSourceId? = null) : HomeSourceMemory {
        override fun last() = id

        override fun remember(id: HomeSourceId) {
            this.id = id
        }
    }

    private class FakeSource : HomeSource {
        @Volatile var options = listOf(ONE)
        @Volatile var installed = 1
        @Volatile var sections = 1
        @Volatile var stillLoading = false
        /** Every page waits forever. */
        @Volatile var hold = false
        /** Every page waits until this completes. */
        @Volatile var gate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
        @Volatile var answer: (HomeSourceId, Int, Int) -> HomePageReply = { _, _, _ -> loaded("A", listOf("A 0")) }
        /** The hero's facts by title id. None by default, which is a host that cannot ask: no hero. */
        @Volatile var featured: (HomeItem) -> HomeFeatured? = { null }
        val calls = CopyOnWriteArrayList<Call>()

        override suspend fun featured(item: HomeItem): HomeFeatured? = featured.invoke(item)

        override suspend fun sources() = HomeSources(options.map { it.copy(sections = sections) }, installed, stillLoading)

        override suspend fun page(source: HomeSourceId, section: Int, page: Int): HomePageReply {
            calls += Call(source, section, page)
            if (hold) awaitCancellation()
            gate?.await()
            return answer(source, section, page)
        }
    }

    private class FakeHistory : HomeWatchHistory {
        @Volatile var history = HomeHistory()

        override suspend fun read(): HomeHistory = history
    }

    private class FakeShelf : HomeBookmarkStore {
        @Volatile var all = emptyList<HomeBookmark>()
        @Volatile var kept: HomeWatchStatus? = null

        override suspend fun read() = all

        override fun chosen() = kept

        override fun choose(status: HomeWatchStatus?) {
            kept = status
        }
    }

    private companion object {
        const val WAIT_MS = 10_000L
        const val MAX_PRESSES = 8
        const val RETRY = "Try again"
        const val SOURCE_ERROR = "Source error"
        const val RETRY_FAILED = "Retry failed"
        const val LOAD_MORE = "Load more"
        const val EXTENSIONS = "Extensions"
        const val ACCOUNTS = "Switch account"
        const val LONG_TITLE = "The Extraordinarily Long Title Of A Film Whose Name Will Never Fit On Any Poster " +
            "Card In This Rail However Wide The Television Is"
        const val LONG_ROW = "A Row Whose Provider Gave It A Name So Long That It Could Not Possibly Fit On One Line " +
            "Of Any Television Even One Of Some Considerable Width And Then Some More Words After That For Luck"
        val ONE = HomeSourceOption(HomeSourceId("one"), "Source One")
        val TWO = HomeSourceOption(HomeSourceId("two"), "Source Two")
        val THREE = HomeSourceOption(HomeSourceId("three"), "Source Three", language = "en")
        const val HOME_INDEX = 0
        val ShotRail = listOf(
            NavRailItem("Home", CloudStreamIcons.Home),
            NavRailItem("Search", CloudStreamIcons.Search),
            NavRailItem("Library", CloudStreamIcons.Library),
            NavRailItem("Downloads", CloudStreamIcons.Download),
            NavRailItem("Settings", CloudStreamIcons.Settings),
        )
        val ShotPosters = listOf(0xFF39505C.toInt(), 0xFF5C4A39.toInt(), 0xFF3F5C39.toInt(), 0xFF4A395C.toInt())
        val ShotTitles = listOf(
            "Vantage", "Salt Road", "Drift", "Low Tide", "Quiet Harbor", "Meridian", "Cold Open", "Nightshift",
            "The Long Dark", "Ashen Field", "Hollow Crown", "Nine Lives", "Kingfisher", "Paper Cities", "The Wake",
            "Greyline", "Signal Hill", "Tidewater", "Northbound", "Copper Sky", "Afterglow", "Lantern", "Glass Bay",
            "Redwing", "Stillwater",
        )
        val ShotBadges = mapOf("Salt Road" to "4K", "Ashen Field" to "DUB")
        val ShotContinue = listOf(
            "The Long Dark" to 0.62f, "Hollow Crown" to 0.81f, "Ashen Field" to 0.44f, "Quiet Harbor" to 0.22f,
            "Nine Lives" to 0.92f,
        )
        const val MORE_INFO = "More info"
        const val BOOKMARKS = "Bookmarks"
        const val RELOAD = "Reload Provider"
        const val RANDOM = "Random"
        const val SEARCH = "Search"
        const val NONE = "None"
        const val NONE_SUBTITLE = "NO RAILS · YOUR ROWS STAY"
        const val SOURCE_NONE = "Source · None"
        const val SOURCES = "Sources"
        const val NO_SOURCE = "No source chosen"
        const val WATCHING = "Watching"
        const val COMPLETED = "Completed"
        const val SAVED = "Saved"
        const val DONE = "Done"
        const val NO_STATUS = "No status chosen"
        const val NO_STATUS_BODY = "Choose one above to see those bookmarks."
        val ShotMarks = listOf(
            "The Long Dark", "Salt Road", "Hollow Crown", "Ashen Field", "Nine Lives", "Kingfisher", "Paper Cities", "The Wake",
            "Greyline",
        )
        const val CONTINUE = "Continue Watching"
        const val OFFLINE = "You are offline"
        const val RESUME = "Resume"
        const val HALF = 0.5f
        /** Up from a continue card's foot to inside its rule: the 32dp text stack, 4dp, then 2dp into the 4dp rule. */
        const val RULE_FROM_FOOT = 38f
        /**
         * Up from a focused poster's foot to inside its rule. The node's bounds are where the card sits at rest, and it is
         * drawn 4dp higher (`FocusLift`), so its 4dp rule is 4 to 8dp above the foot of what is captured.
         */
        const val LIFTED_RULE_FROM_FOOT = 6f
        const val GEOMETRY_SLACK = 1f
        /** Five bands each rounded to a whole pixel at 1.33125 px per dp. */
        const val ROUNDING_SLACK = 1.5f
        /** The source control at rest: `ChromeRowTop` 28, and its 32dp height centred in the 48dp chrome row. */
        const val CHROME_CONTROL_TOP = 36f
        const val NO_CONNECTION = "No internet connection"
        const val LONG_DETAIL = "HTTP 503 · upstream connect error or disconnect/reset before headers · reset reason: " +
            "connection termination · retried 3 times against mirror two of three"
        /** `Television_720p` is 540.8dp tall; the band's foot is `FocusBandInset` above that. */
        const val SCREEN_HEIGHT_DP = 540.8f
        const val COLOUR_SLACK = 8
        val FEATURED = HomeFeatured(
            id = "id/A 0",
            title = "The Long Dark",
            year = 2026,
            durationMinutes = 48,
            genres = listOf("Sci-Fi", "Thriller"),
            score = "8.7",
            contentRating = "16+",
            synopsis = "A salvage crew wakes from cryo to find the relay station empty.",
        )
        val HERO = "$MORE_INFO · ${FEATURED.title}"

        fun shotRow(row: String, titles: List<String>, hasNext: Boolean = false) = HomePageReply.Loaded(
            rows = listOf(
                HomeRowPage(row, landscape = false, items = titles.map { HomeItem("id/$it", it, badge = ShotBadges[it]) }),
            ),
            hasNext = hasNext,
        )
        val SOURCE_ONE = "Source · ${ONE.name}"
        val SOURCE_TWO = "Source · ${TWO.name}"

        fun rowName(section: Int) = ('A' + section).toString()

        fun loaded(row: String, titles: List<String>, hasNext: Boolean = false) = HomePageReply.Loaded(
            rows = listOf(HomeRowPage(row, landscape = false, items = titles.map { HomeItem("id/$it", it) })),
            hasNext = hasNext,
        )
    }
}
