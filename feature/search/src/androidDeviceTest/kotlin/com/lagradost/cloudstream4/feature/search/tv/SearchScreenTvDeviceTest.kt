package com.lagradost.cloudstream4.feature.search.tv

import android.view.View
import android.view.WindowInsets
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.lagradost.cloudstream4.compose.ObserveEffect
import com.lagradost.cloudstream4.core.devicetest.Dpad
import com.lagradost.cloudstream4.core.devicetest.DpadDriver
import com.lagradost.cloudstream4.core.devicetest.focusedLabel
import com.lagradost.cloudstream4.core.designsystem.target.TextField
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme
import com.lagradost.cloudstream4.feature.search.SearchAction
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
    private val viewModel = SearchViewModel(source = source, history = NoHistory)
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private lateinit var view: View

    private fun showScreen() {
        rule.setContent {
            view = LocalView.current
            val state by viewModel.state.collectAsState()
            ObserveEffect(viewModel.effect) { effects += it }
            CloudStreamTheme {
                Box(Modifier.fillMaxSize().background(CloudStreamTheme.colors.bg)) {
                    SearchScreenTv(state = state, onAction = viewModel::onAction)
                }
            }
        }
    }

    /**
     * Moves up to the field, the screen's top stop. An empty screen can give its own action the first
     * focus, so landing anywhere and pressing OK would press that instead.
     */
    private fun landOnTheField() {
        dpad.enter()
        repeat(FIELD_UP_PRESSES) {
            if (rule.onAllNodes(Field and isFocused()).fetchSemanticsNodes().isNotEmpty()) return
            dpad.press(Dpad.Up)
        }
        rule.onNode(Field).assertIsFocused()
    }

    /** Lands on the field, opens the keyboard with OK, types through it and presses its action key. */
    private fun search(query: String) {
        landOnTheField()
        dpad.center()
        assertKeyboardOpens()
        type(query)
        rule.waitUntil(WAIT_MS) { viewModel.state.value.query == query }
        rule.onNode(hasSetTextAction()).performImeAction()
        // The action key closes it. A Back here would race that, and leave the screen if it lost.
        rule.waitUntil(WAIT_MS) { !dpad.keyboardShown() }
    }

    /** Key events through the input dispatcher, so they reach the field by way of the open keyboard. */
    private fun type(text: String) {
        device.executeShellCommand("input text $text")
    }

    /** The two places the system reports it: the IME service's own state, and the window's insets. */
    private fun imeVisibleInInsets(): Boolean {
        var visible = false
        rule.runOnUiThread {
            visible = view.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true
        }
        return visible
    }

    /** `waitUntil`, because the rule's clock only runs frames while the rule is being asked something. */
    private fun assertKeyboardOpens() {
        rule.waitUntil(WAIT_MS) { dpad.keyboardShown() && imeVisibleInInsets() }
    }

    /** Runs frames throughout, and long enough for a cold keyboard on two cores: "none", not "not yet". */
    private fun assertNoKeyboard(on: String) {
        val deadline = System.currentTimeMillis() + NO_KEYBOARD_WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            rule.waitForIdle()
            assertFalse("The IME service reports a keyboard on $on", dpad.keyboardShown())
            assertFalse("The window's insets report a keyboard on $on", imeVisibleInInsets())
            Thread.sleep(POLL_MS)
        }
    }

    private fun showResults() {
        source.outcome = SearchOutcome.Complete(Results)
        showScreen()
        viewModel.onAction(SearchAction.RecentActivated(QUERY))
        awaitText("Filmxy")
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
        // The owner's ruling, `BUG-30`: landing on the field only highlights it.
        assertEquals(graph.toString(), emptySet<String>(), graph.keyboardOpenedOn)
    }

    /** Crossing the field in both directions, and leaving it sideways, never brings up the keyboard. */
    @Test
    fun theDpadPassesThroughTheFieldWithNoKeyboard() {
        showResults()

        assertEquals(QUERY, dpad.enter())
        assertNoKeyboard("the first landing")

        val card = dpad.press(Dpad.Down)
        assertTrue("Down reached $card", card in Titles)
        assertNoKeyboard("the way down")

        assertEquals(QUERY, dpad.press(Dpad.Up))
        assertNoKeyboard("the way back up")

        assertEquals("Sources", dpad.press(Dpad.Right))
        assertEquals(QUERY, dpad.press(Dpad.Left))
        assertNoKeyboard("the way back from Sources")
    }

    /** OK opens it; Back closes it, keeps focus on the field and gives the D-pad back. */
    @Test
    fun okOpensTheKeyboardAndBackClosesItLeavingFocusOnTheField() {
        showResults()
        assertEquals(QUERY, dpad.enter())

        dpad.center()
        assertKeyboardOpens()

        dpad.closeKeyboard()
        assertNoKeyboard("the field after Back")
        assertEquals(QUERY, dpad.focused())
        assertTrue("Back left the screen", rule.onAllNodesWithText("Filmxy").fetchSemanticsNodes().isNotEmpty())

        // OK again, because a remote may have nowhere else to go, and the D-pad is free after each Back.
        dpad.center()
        assertKeyboardOpens()
        dpad.closeKeyboard()
        assertEquals("Sources", dpad.press(Dpad.Right))
        assertNoKeyboard("Sources")
    }

    /** Some remotes send Enter for OK. It has to open the keyboard, every time, and not search. */
    @Test
    fun enterOpensTheKeyboardAndDoesNotSearch() {
        showResults()
        assertEquals(QUERY, dpad.enter())
        val searchesBefore = source.searched.size

        repeat(2) {
            dpad.enterKey()
            assertKeyboardOpens()
            dpad.closeKeyboard()
            assertEquals(QUERY, dpad.focused())
        }
        assertEquals(searchesBefore, source.searched.size)
    }

    /** Typing through the open keyboard reaches the query; its action key searches and closes it. */
    @Test
    fun typingThroughTheKeyboardUpdatesTheQuery() {
        source.outcome = SearchOutcome.Complete(Results)
        showScreen()

        landOnTheField()
        dpad.center()
        assertKeyboardOpens()
        type(QUERY)

        rule.waitUntil(WAIT_MS) { viewModel.state.value.query == QUERY }
        rule.onNodeWithText(QUERY).assertExists()
        assertEquals(QUERY, dpad.focused())

        rule.onNode(hasSetTextAction()).performImeAction()
        awaitText("Filmxy")
        assertEquals(listOf(QUERY), source.searched)
        rule.waitUntil(WAIT_MS) { !dpad.keyboardShown() }
        assertNoKeyboard("the field after searching")
    }

    /** A screen reader's activation is not a key press, and a read-only field would ignore it. */
    @Test
    fun aScreenReadersActivationOpensTheKeyboard() {
        showScreen()
        landOnTheField()
        assertNoKeyboard("the field")

        rule.onNode(Field).performSemanticsAction(SemanticsActions.OnClick)
        assertKeyboardOpens()
        dpad.closeKeyboard()
    }

    /** `isLayout(TV)` is a preference, so this screen can be on a touchscreen, where a tap opens it. */
    @Test
    fun aTapOpensTheKeyboard() {
        showScreen()
        landOnTheField()
        assertNoKeyboard("the field")

        val bounds = rule.onNode(Field).fetchSemanticsNode().boundsInWindow
        device.click(bounds.center.x.toInt(), bounds.center.y.toInt())
        assertKeyboardOpens()
        dpad.closeKeyboard()
    }

    /** The option is search's. A field that does not ask for it keeps what every field does. */
    @Test
    fun aTextFieldWithoutTheOptionOpensTheKeyboardOnLanding() {
        rule.setContent {
            view = LocalView.current
            CloudStreamTheme {
                TextField(value = "", onValueChange = {}, placeholder = "", modifier = Modifier.fillMaxWidth())
            }
        }
        landOnTheField()
        assertKeyboardOpens()
        dpad.closeKeyboard()
    }

    private companion object {
        const val WAIT_MS = 10_000L
        const val NO_KEYBOARD_WAIT_MS = 3_000L
        const val POLL_MS = 200L
        const val FIELD_UP_PRESSES = 4
        const val RETRY = "Try again"
        const val QUERY = "blade"
        val Field = SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText)

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
