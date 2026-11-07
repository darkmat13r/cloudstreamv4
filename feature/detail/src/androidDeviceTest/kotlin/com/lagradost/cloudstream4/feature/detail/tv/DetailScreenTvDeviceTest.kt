package com.lagradost.cloudstream4.feature.detail.tv

import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lagradost.cloudstream4.compose.ObserveEffect
import com.lagradost.cloudstream4.core.devicetest.Dpad
import com.lagradost.cloudstream4.core.devicetest.DpadDriver
import com.lagradost.cloudstream4.core.devicetest.focusedLabel
import com.lagradost.cloudstream4.core.theme.CloudStreamDimens
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme
import com.lagradost.cloudstream4.feature.detail.DetailAction
import com.lagradost.cloudstream4.feature.detail.DetailEffect
import com.lagradost.cloudstream4.feature.detail.DetailViewModel
import com.lagradost.cloudstream4.feature.detail.DetailWatchStatus
import com.lagradost.cloudstream4.feature.detail.TitleDetail
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The TV detail page on a device, with [FakeDetailSource] and the real [DetailViewModel]: no `:app`,
 * no provider, no network. `SearchScreenTvDeviceTest` is the shape — a fake at the module's seam, the
 * screen in a small host under `createComposeRule`, Compose semantics for what is drawn and
 * [DpadDriver] for what a remote can do with it.
 *
 * Two things are read from semantics rather than from the accessibility tree, both for the reasons
 * `INF-10` measured: focus comes from `rule.focusedLabel()`, which is exact under the rule's clock
 * instead of trailing the screen by up to 2 s, and every "it is not there" assertion runs frames
 * throughout rather than sampling once.
 *
 * ## There is no nav rail in *this host*, and that is not what the page does
 *
 * The real page **does** show the rail: the owner ruled it does, and `MainActivity.updateNavBar` puts it
 * there for `navigation_results_tv` through `railOnDetail` (`KMP-38`). The rail is the shell's, and this
 * host composes `DetailScreenTv` on its own, so there is no rail cell here to find — which is also why
 * this module's references draw content at 24dp rather than at the board's 76dp origin.
 *
 * Nothing below expects a rail cell and no test presses LEFT hoping to find one. LEFT into the rail and
 * RIGHT back out are `:app`'s `TvDetailNavigationTest`, which runs the real shell.
 *
 * ## The strings are English literals
 *
 * The screen resolves every word from `:core:strings` in the composition's locale. The emulator runs
 * `en-US`, so the constants below are the `values/strings.xml` forms. A test run under another locale
 * fails on the text assertions rather than on behaviour.
 */
@RunWith(AndroidJUnit4::class)
class DetailScreenTvDeviceTest {

    @get:Rule
    val rule = createComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val density = instrumentation.targetContext.resources.displayMetrics.density

    private val dpad = DpadDriver(readFocus = { rule.focusedLabel() })
    private val source = FakeDetailSource()
    private val effects = CopyOnWriteArrayList<DetailEffect>()
    private val backs = CopyOnWriteArrayList<String>()

    /**
     * The host's scope, as `DetailTvFragment` gives one: the ViewModel takes it because desktop has no
     * `Dispatchers.Main` for `viewModelScope`. Cancelled after each test, which also releases the
     * never-answering `details` of the loading case.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var viewModel: DetailViewModel

    @After
    fun stopTheHost() {
        scope.cancel()
    }

    /**
     * Shows the page for [detail], which may be null when the fake is set to fail or to never answer.
     *
     * The ViewModel is built **here** and not as a field: its `init` loads at once, so the fake has to
     * be configured before it exists.
     */
    private fun show(detail: TitleDetail?) {
        source.detail = detail
        viewModel = DetailViewModel(id = DetailFixtures.Id, source = source, scope = scope)
        rule.setContent {
            val state by viewModel.state.collectAsState()
            ObserveEffect(viewModel.effect) { effects += it }
            // Mirrors `DetailTvFragment.wirePanelBack`. `SidePanelHost` consumes BACK only as a Compose
            // key event on the focused panel and assigns platform back to its host, and this host has no
            // fragment — so without this, an injected BACK reaches the activity and finishes it, which is
            // how the picker test failed twice. What this exercises is the production dismiss path
            // (`PanelDismissed` closing either panel) and not the fragment's own six lines of wiring,
            // which nothing automated reaches: the `:app` test cannot open a panel with no provider
            // installed. See `KMP-38`.
            PanelBackHost(enabled = state.panelOpen) { viewModel.onAction(DetailAction.PanelDismissed) }
            CloudStreamTheme {
                Box(Modifier.fillMaxSize().background(CloudStreamTheme.colors.bg)) {
                    DetailScreenTv(
                        state = state,
                        onAction = viewModel::onAction,
                        onBack = { backs += BACK },
                        artwork = {},
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }

    /** An action the host would send: on the main thread, as a Fragment's callback is. */
    private fun act(action: DetailAction) = rule.runOnUiThread { viewModel.onAction(action) }

    private fun awaitFocus(label: String) = rule.waitUntil(WAIT_MS) { rule.focusedLabel() == label }

    /**
     * Waits for focus to land on one of [labels] and returns which, naming what it actually found when
     * none of them arrives. Used where the component documents *that* focus returns but this dismiss
     * path does not pin *which* control it returns to.
     */
    private fun awaitOneOf(vararg labels: String): String {
        val wanted = labels.toSet()
        var seen: String? = null
        try {
            rule.waitUntil(WAIT_MS) {
                seen = rule.focusedLabel()
                seen in wanted
            }
        } catch (timeout: ComposeTimeoutException) {
            throw AssertionError("Focus never reached any of $wanted; it was on $seen", timeout)
        }
        return checkNotNull(seen)
    }

    private fun awaitText(text: String) = rule.waitUntil(WAIT_MS) {
        rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }

    /** "Not there", not "not there yet": it runs a frame first, so a pending recomposition has landed. */
    private fun assertNoText(text: String, substring: Boolean = false) {
        rule.waitForIdle()
        assertEquals(
            "\"$text\" is on screen",
            0,
            rule.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().size,
        )
    }

    /**
     * Asserts focus stays where it is for [STILL_MS], running frames throughout.
     *
     * A single read cannot say this: the thing being ruled out is a focus request that arrives a frame
     * or two later, which is exactly what `DetailReturn.ready` exists to delay.
     */
    private fun assertStaysFocusedOn(label: String?, moment: String) {
        val deadline = System.currentTimeMillis() + STILL_MS
        while (System.currentTimeMillis() < deadline) {
            rule.waitForIdle()
            assertEquals("focus moved $moment", label, rule.focusedLabel())
            Thread.sleep(POLL_MS)
        }
    }

    /** Presses [direction] until [label] holds focus, as a person finds a control on a TV screen. */
    /**
     * Back to the watch-status cell from wherever a panel dismiss left focus.
     *
     * It is in the action row now, not the chrome, so a landing in the chrome comes DOWN into the row
     * first and then walks right. From inside the row the direction depends on which side of the cell
     * focus landed: Resume and Trailer are left of it, everything else right.
     */
    private fun returnToWatchStatus(from: String) {
        if (from == BACK || from == FAVOURITE) {
            dpad.press(Dpad.Down)
        }
        val towards = if (rule.focusedLabel() in setOf(RESUME, TRAILER)) Dpad.Right else Dpad.Left
        assertEquals(WATCH_STATUS, pressUntil(towards, WATCH_STATUS))
    }

    private fun pressUntil(direction: Dpad, label: String, presses: Int = MAX_PRESSES): String {
        if (rule.focusedLabel() == label) return label
        return generateSequence { dpad.press(direction) }.take(presses).firstOrNull { it == label }
            ?: error("$direction never reached $label in $presses presses, stopped on ${rule.focusedLabel()}")
    }

    /**
     * DOWN until focus is anywhere in the cast rail.
     *
     * **Which** item it lands on is geometry, not a choice this test may make: §2.5's DOWN target is
     * "the cast item whose x overlaps", and `INF-10` measured Android's focus search picking the
     * candidate nearest the *centre* of where it came from rather than the first one. Resume is 220dp
     * wide over a 108dp cast pitch, so its centre sits over the second item.
     */
    private fun enterTheCastRow(): String {
        val names = DetailFixtures.Cast.map { it.name }.toSet()
        return generateSequence { dpad.press(Dpad.Down) }.take(MAX_PRESSES).firstOrNull { it in names }
            ?: error("DOWN never reached the cast rail, stopped on ${rule.focusedLabel()}")
    }

    /**
     * Asserts every control in the row still measured at least its own floor and ends inside the
     * content band, which runs from [DetailScreenTvDefaults.ContentStart] to the screen's width less
     * [DetailScreenTvDefaults.ContentEnd].
     *
     * Two floors, because the row holds two kinds of control since `DEC-17`: a [texts] button is held
     * to [CloudStreamDimens.TextButtonMinWidth] (120dp) and an icon-only [cells] entry to
     * [CloudStreamDimens.IconTargetCell] (48dp). An icon cell is **not** let off the check — it is held
     * to the smaller number that is correct for it.
     *
     * A cell is found by content description rather than text: its label is drawn on focus, not laid
     * out, so there is no `Text` node to match.
     */
    private fun assertActionRowFits(texts: List<String>, cells: List<String> = emptyList()) {
        val width = rule.onRoot().fetchSemanticsNode().size.width
        val bandEnd = width - dp(DetailScreenTvDefaults.ContentEnd)
        fun check(label: String, node: SemanticsNode, floor: Dp) {
            assertTrue(
                "$label measured ${node.size.width}px, under the $floor minimum",
                node.size.width >= dp(floor),
            )
            assertTrue(
                "$label ends at ${node.boundsInRoot.right}, past the band's $bandEnd of $width",
                node.boundsInRoot.right <= bandEnd,
            )
        }
        texts.forEach { label ->
            check(label, rule.onNodeWithText(label).fetchSemanticsNode(), CloudStreamDimens.TextButtonMinWidth)
        }
        cells.forEach { label ->
            check(
                label,
                rule.onNodeWithContentDescription(label).fetchSemanticsNode(),
                CloudStreamDimens.IconTargetCell,
            )
        }
    }

    /**
     * BACK as a remote sends it: through the input dispatcher with [InputDevice.SOURCE_HDMI], which is
     * what every other key in this suite already uses.
     *
     * `UiDevice.pressBack()` is **not** the same key here, and it finished the host activity instead of
     * closing the panel. `SidePanelHost` takes BACK in `onPreviewKeyEvent` and its KDoc is explicit
     * that it registers no `OnBackPressedDispatcher` callback, so the panel only answers a key that
     * reaches Compose's pipeline as a real remote's — the source `INF-10` measured. [DpadDriver] has no
     * public BACK, so this sends exactly the event it does.
     */
    private fun pressRemoteBack() {
        val time = SystemClock.uptimeMillis()
        listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP).forEach { action ->
            val event = KeyEvent(
                time,
                time,
                action,
                KeyEvent.KEYCODE_BACK,
                0,
                0,
                KeyCharacterMap.VIRTUAL_KEYBOARD,
                0,
                0,
                InputDevice.SOURCE_HDMI,
            )
            check(instrumentation.uiAutomation.injectInputEvent(event, true)) { "BACK was not injected" }
        }
    }

    private fun dp(value: Dp): Int = (value.value * density).roundToInt()

    private fun assertNear(what: String, expected: Int, actual: Int, tolerance: Int = dp(1.dp)) =
        assertTrue("$what: expected $expected px, got $actual px", abs(expected - actual) <= tolerance)

    /**
     * Mean brightness, `0f..1f`, of one pixel row [PROGRESS_PROBE] up from a node's bottom edge.
     *
     * The progress bar inside the primary is a `Box` with no semantics — it is geometry, not a control
     * — so the only honest way to assert it is drawn or not drawn is to look at the pixels. The button's
     * fill is `ink` (white) and the bar is `bg` (black) over a 24%-black track, so the two readings are
     * far apart: no bar reads ~1.0, a bar at 38% reads ~0.47 or less.
     *
     * Inset from both ends because `shapes.medium` is an 8dp radius and the corners are not the fill.
     */
    private fun bottomEdgeBrightness(node: SemanticsNodeInteraction): Float {
        val pixels = node.captureToImage().toPixelMap()
        val row = pixels.height - 1 - dp(PROGRESS_PROBE)
        val inset = dp(PROGRESS_INSET)
        var sum = 0f
        var count = 0
        for (x in inset until pixels.width - inset) {
            val colour = pixels[x, row]
            sum += (colour.red + colour.green + colour.blue) / CHANNELS
            count++
        }
        check(count > 0) { "The button is too narrow to sample: ${pixels.width}px" }
        return sum / count
    }

    // ---- The content states ------------------------------------------------------------------------

    /** A show: every band the board draws, and the episode list a remote walks down into. */
    @Test
    fun aShowDrawsItsTitleMetaPlotCastRecommendationsAndEpisodes() {
        show(DetailFixtures.Show)
        awaitFocus(RESUME)

        rule.onNodeWithText(DetailFixtures.Show.title).assertExists()
        rule.onNodeWithText(SHOW_META).assertExists()
        rule.onNodeWithText(DetailFixtures.Show.plot.orEmpty()).assertExists()
        rule.onNodeWithText(DetailFixtures.Cast.first().name).assertExists()
        rule.onNodeWithText(RECOMMENDED).assertExists()
        rule.onNodeWithText("$EPISODES_PLURAL · 3").assertExists()

        // The list is below the fold. A lazy column composes past its bounds for a focus search, so
        // DOWN reaches it: cast, then a recommendation, then the first row.
        assertEquals(DetailFixtures.episodeName(0), pressUntil(Dpad.Down, DetailFixtures.episodeName(0)))
        rule.onNodeWithText(DetailFixtures.episodeName(2)).assertExists()
    }

    /** A film: `playData` set, no episodes, and the row names the movie rather than an episode. */
    @Test
    fun aMovieHasNoEpisodesAndItsRowNamesTheMovie() {
        show(DetailFixtures.Movie)
        awaitFocus(RESUME)

        rule.onNodeWithText(DetailFixtures.Movie.title).assertExists()
        // By content description, not text: "start over" is an icon-only cell under rule 4's ruled
        // exception, and its label is drawn only on focus rather than laid out as a `Text` node.
        rule.onNodeWithContentDescription(PLAY_MOVIE).assertExists()
        assertNoText(PLAY_EPISODE)
        assertNoText(DetailFixtures.episodeName(0))
        // No season pair: a film names no season, so neither button is drawn.
        assertNoText(EPISODES_PLURAL, substring = true)
        assertNoText(SEASON, substring = true)
    }

    /**
     * Loading: the page has no content yet and focus rests on the back cell, which is where §2.8 puts
     * it and the one thing a remote can do.
     *
     * The skeleton itself carries no semantics — `SkeletonRail` is boxes and a pulse — so what is
     * asserted is that the loaded page is **not** drawn and that the back cell is the only focus stop.
     */
    @Test
    fun loadingHasNoContentAndRestsFocusOnTheBackCell() {
        source.answers = false
        show(null)

        awaitFocus(BACK)
        assertNoText(DetailFixtures.Show.title)
        assertNoText(RESUME)
        assertEquals(
            "the back cell is the only stop while loading",
            1,
            rule.onAllNodes(hasClickAction()).fetchSemanticsNodes().size,
        )
        Dpad.entries.forEach { direction ->
            assertEquals("$direction from the back cell", BACK, dpad.press(direction))
        }

        // And it is live, not decoration: the board keeps it pressable through all three states.
        dpad.center()
        rule.waitUntil(WAIT_MS) { backs.isNotEmpty() }
    }

    /**
     * The failure, and the thing this case exists to rule out: a failure state that is a dead end.
     *
     * `StateView` puts focus on its own action, so landing on Retry proves nothing on its own. The
     * remote therefore leaves it for the back cell and comes back by key, and only then presses it.
     */
    @Test
    fun theFailureStateDrawsAndARemoteCanReachItsRetryAndRecover() {
        source.failure = IOException("HTTP 503")
        show(null)

        awaitText(FAILED_HEADLINE)
        rule.onNodeWithText(FAILED_BODY).assertExists()
        assertEquals("the page asked once", 1, source.reads.get())

        awaitFocus(RETRY)
        assertEquals("UP leaves the failure for the back cell", BACK, dpad.press(Dpad.Up))
        assertEquals("and DOWN comes back to Retry", RETRY, dpad.press(Dpad.Down))

        source.failure = null
        source.detail = DetailFixtures.Show
        dpad.center()

        awaitText(DetailFixtures.Show.title)
        assertEquals("the press asked the source again", 2, source.reads.get())
        awaitFocus(RESUME)
    }

    /** A series whose provider returned no episodes: the caption says so, and there is still a way on. */
    @Test
    fun aSeriesWithNoEpisodesSaysSoAndStillHasSomewhereToGo() {
        show(DetailFixtures.NoEpisodes)

        // Nothing is playable, so there is no primary and the back cell takes the entry focus instead.
        awaitFocus(BACK)
        // "No Episodes found" itself contains the word, so only the controls are ruled out by name.
        rule.onNodeWithText(NO_EPISODES).assertExists()
        assertNoText(RESUME)
        assertNoText(PLAY_EPISODE)
        assertNoText("$SEASON 1")
        assertNoText(DetailFixtures.episodeName(0))

        val below = dpad.press(Dpad.Down)
        assertTrue("DOWN from the back cell reached $below", below == TRAILER || below == SYNOPSIS)
    }

    /** One episode: the plural resolves to its singular, and the list is one row. */
    @Test
    fun oneEpisodeIsASingularAndASingleRow() {
        show(DetailFixtures.OneEpisode)
        awaitFocus(PLAY_EPISODE)

        rule.onNodeWithText("$EPISODE_SINGULAR · 1").assertExists()
        assertEquals(DetailFixtures.episodeName(0), pressUntil(Dpad.Down, DetailFixtures.episodeName(0)))
        assertEquals(
            "one row",
            1,
            rule.onAllNodesWithText(DetailFixtures.episodeName(0)).fetchSemanticsNodes().size,
        )
        assertNoText(DetailFixtures.episodeName(1))
    }

    /** Forty episodes: the list scrolls under the D-pad and the last row is still reachable. */
    @Test
    fun fortyEpisodesScrollAndTheLastRowIsReachable() {
        show(DetailFixtures.FortyEpisodes)
        awaitFocus(PLAY_EPISODE)

        rule.onNodeWithText("$EPISODES_PLURAL · 40").assertExists()
        val last = DetailFixtures.episodeName(LAST_OF_FORTY)
        assertEquals(last, pressUntil(Dpad.Down, last, presses = FORTY_PRESSES))
        rule.onNodeWithText(last).assertExists()
    }

    /**
     * Long text ellipsises; it does not wrap, and it does not move the action row.
     *
     * No full-screen geometry is asserted: `Television_720p` is 1280x720 at 213 dpi, about
     * 961.5x540.8 dp, not the artboard's 960x540. What is asserted is each fixed box and the one gap
     * that the caption slot exists to keep constant.
     */
    @Test
    fun longTitleAndPlotEllipsiseWithoutGrowingTheirBoxes() {
        show(DetailFixtures.LongText)
        awaitFocus(RESUME)

        val title = rule.onNodeWithText(DetailFixtures.LongTitle).fetchSemanticsNode()
        val plot = rule.onNodeWithText(DetailFixtures.LongPlot).fetchSemanticsNode()
        val primary = rule.onNodeWithText(RESUME).fetchSemanticsNode()

        // Each is clamped **at** its maximum, which is what says the text was longer than the box: a
        // box that had wrapped or measured its content would not land exactly here.
        assertNear("the title's width", dp(DetailScreenTvDefaults.TitleMaxWidth), title.size.width)
        assertNear("the plot's width", dp(DetailScreenTvDefaults.PlotMaxWidth), plot.size.width)
        assertNear("the plot's height", dp(DetailScreenTvDefaults.PlotHeight), plot.size.height)

        // To the primary's own top, which is not the row's: `DEC-17` made the row a 48dp
        // `IconTargetCell` box with the 32dp text buttons centred, so the cell puts
        // `(IconTargetCell - TextButtonHeight) / 2` above the primary. Named here rather than folded
        // into `CaptionToActions`, so that if the row ever stops centring, this fails instead of
        // quietly agreeing with whatever it does.
        val cellPadding = (CloudStreamDimens.IconTargetCell - CloudStreamDimens.TextButtonHeight) / 2
        val gap = DetailScreenTvDefaults.PlotToCaption + DetailScreenTvDefaults.CaptionHeight +
            DetailScreenTvDefaults.CaptionToActions + cellPadding
        assertNear(
            "plot to action row",
            dp(gap),
            (primary.boundsInRoot.top - plot.boundsInRoot.bottom).roundToInt(),
        )
        assertNear("the primary's height", dp(CloudStreamDimens.TextButtonHeight), primary.size.height)
        assertTrue(
            "the action row left the screen, bottom at ${primary.boundsInRoot.bottom}",
            primary.boundsInRoot.bottom <= rule.onRoot().fetchSemanticsNode().size.height,
        )
    }

    /**
     * Board 7i's second line, as far as the data reaches: the code, then the runtime.
     *
     * The code is still on the row because the season chips that would carry the season are a later
     * task; see `episodeSubtitle`. The air date 7i also draws is not here — `DetailEpisode.airDateMs`
     * says why it is carried and not drawn.
     */
    @Test
    fun anEpisodeRowNamesItsRuntimeAfterItsCode() {
        show(DetailFixtures.RuntimeEpisodes)
        awaitFocus(RESUME)
        // DOWN to the row first: the episode list is the last section of a `LazyColumn`, so at entry
        // focus none of it is composed and no query can see it.
        val second = DetailFixtures.episodeName(1)
        assertEquals(second, pressUntil(Dpad.Down, second))

        rule.onNodeWithText("${SEASON_SHORT}1:${EPISODE_SHORT}2 $SEPARATOR ${DetailFixtures.EPISODE_MINUTES} min")
            .assertExists()
    }

    /**
     * A provider that sent no runtime: the row keeps its code and gains nothing.
     *
     * Its own test rather than a second assertion above, because `show` goes through
     * `ComposeTestRule.setContent`, which may be called once per test.
     */
    @Test
    fun anEpisodeRowWithoutARuntimeShowsOnlyItsCode() {
        show(DetailFixtures.Show)
        awaitFocus(RESUME)
        val second = DetailFixtures.episodeName(1)
        assertEquals(second, pressUntil(Dpad.Down, second))

        rule.onNodeWithText("${SEASON_SHORT}1:${EPISODE_SHORT}2").assertExists()
        assertNoText("${DetailFixtures.EPISODE_MINUTES} min")
    }

    /**
     * 7i's pill marks the row the primary would act on, and marks exactly one.
     *
     * `ListRow` puts this in the semantics tree as `selectable(selected = …)`, so the fact is
     * assertable here; the picture is a screenshot case, because the marker and the selected row's
     * `raised` fill are drawn rather than laid out.
     */
    @Test
    fun exactlyOneEpisodeRowIsMarkedAndItIsThePrimarysTarget() {
        show(DetailFixtures.RuntimeEpisodes)
        awaitFocus(RESUME)
        // Walking to the middle row composes all three, which is what lets the two negatives below
        // be asserted at all.
        val second = DetailFixtures.episodeName(1)
        assertEquals(second, pressUntil(Dpad.Down, second))

        // Resume is "e1", the middle episode, so this is neither "always row 1" nor "always the last".
        rule.onNodeWithText(DetailFixtures.episodeName(1)).assertIsSelected()
        rule.onNodeWithText(DetailFixtures.episodeName(0)).assertIsNotSelected()
        rule.onNodeWithText(DetailFixtures.episodeName(2)).assertIsNotSelected()
    }

    /**
     * What DOWN does at the last episode row: **nothing**, and focus stays on it.
     *
     * Measured rather than argued, because board 7i says "DOWN past the last row loads the next page
     * of episodes" and nothing in the contract can do that: `DetailSource.details` returns every
     * episode in one answer and `DetailSeason.episodeCount` is counted from that same list, so there
     * is no page to load. The episode list is also the column's last section, so there is nothing
     * below it to take focus either. This test is what pins both halves of that.
     *
     * `pressUntil` stops at its first match, so reaching the last row would never press *past* it;
     * these presses are deliberately made after arriving.
     */
    @Test
    fun downAtTheLastEpisodeRowStaysOnIt() {
        show(DetailFixtures.RuntimeEpisodes)
        awaitFocus(RESUME)

        val last = DetailFixtures.episodeName(LAST_OF_THREE)
        assertEquals(last, pressUntil(Dpad.Down, last))
        repeat(PRESSES_PAST_THE_END) {
            assertEquals("DOWN past the last row moved focus", last, dpad.press(Dpad.Down))
        }
    }

    /**
     * A stored position of zero is not a resume.
     *
     * `DetailResume.fraction` returns null for a zero or negative position, which is the guard
     * `ResultEpisode.getWatchProgress()` is missing. Three things follow and all three are asserted:
     * the primary says Play, the caption slot is empty, and the button has no progress bar.
     */
    @Test
    fun aStoredPositionOfZeroIsNotAResume() {
        show(DetailFixtures.ResumeAtZero)
        awaitFocus(PLAY_EPISODE)

        assertNoText(RESUME)
        assertNoText(REMAINING, substring = true)
        val brightness = bottomEdgeBrightness(rule.onNodeWithText(PLAY_EPISODE))
        assertTrue(
            "the primary's bottom edge reads $brightness, so something is drawn over its fill",
            brightness > NO_BAR_BRIGHTNESS,
        )
    }

    /** The same measurement, the other way round: a real position draws the bar and the time left. */
    @Test
    fun aStoredPositionDrawsTheProgressBarAndTheTimeLeft() {
        show(DetailFixtures.Show)
        awaitFocus(RESUME)

        rule.onNodeWithText(REMAINING, substring = true).assertExists()
        val brightness = bottomEdgeBrightness(rule.onNodeWithText(RESUME))
        assertTrue(
            "the primary's bottom edge reads $brightness, so no bar is drawn over its fill",
            brightness < BAR_BRIGHTNESS,
        )
    }

    // ---- The D-pad ---------------------------------------------------------------------------------

    /**
     * Every stop reachable, no dead ends, no traps, no keyboard — the three clauses `FocusGraph`
     * answers, on the fixture that has one of everything.
     */
    @Test
    fun everyStopIsReachableWithNoDeadEndsOrTraps() {
        show(DetailFixtures.WalkShow)
        awaitFocus(RESUME)

        // No "start over": the fixture is a series, and `KMP-38` makes that control a playable title's.
        //
        // The chrome row is Back and Favourite only: `DEC-17` moved the watch-status cell down into the
        // action row, where boards 7f and 7g draw it, after Trailer. Trailer and the watch-status cell
        // are icon-only now and carry their label as a content description, which is what
        // `focusedLabel()` falls back to, so the walk still names them exactly as before.
        val expected = listOf(
            BACK, FAVOURITE,
            RESUME, TRAILER, WATCH_STATUS, "$SEASON 1", "$EPISODES_PLURAL · 2", SYNOPSIS,
        ) +
            DetailFixtures.Cast.map { it.name } +
            DetailFixtures.Recommendations.map { it.title } +
            DetailFixtures.WalkShow.episodes.map { it.name.orEmpty() }

        // Not vacuous, two ways: an expectation that had been emptied or that named one stop twice
        // would make `unreachable` trivially empty, and both fail here before the walk starts.
        assertEquals("the fixture's stops", WALK_STOPS, expected.size)
        assertEquals("and every one distinct", expected.size, expected.toSet().size)

        val graph = dpad.walk()
        assertEquals(graph.toString(), emptyList<String>(), graph.unreachable(expected))
        assertEquals(graph.toString(), emptyList<String>(), graph.deadEnds())
        assertEquals(graph.toString(), emptyList<String>(), graph.traps())
        assertEquals(graph.toString(), emptySet<String>(), graph.keyboardOpenedOn)
    }

    /**
     * A show's action row at its ceiling: **six** controls — Resume, Trailer, the watch-status cell,
     * Season N, Episodes · N, Synopsis.
     *
     * `DEC-17` is what let it reach six without overflowing. Trailer and the watch-status cell are
     * [CloudStreamDimens.IconTargetCell] (48dp) rather than
     * [CloudStreamDimens.TextButtonMinWidth] (120dp) ghosts, so the row is
     * [DetailScreenTvDefaults.ResumeWidth] 220 + 2 x (12 + 48) + 3 x (12 + 120) = **736dp** against the
     * band's 833 (961.5 less [DetailScreenTvDefaults.ContentStart] and
     * [DetailScreenTvDefaults.ContentEnd]). The old five-text-button row was 748dp and a sixth text
     * button would have been 880, which is what used to overflow this band.
     *
     * There is no "start over" here and that is the point. `KMP-38`: a series' only candidate for it is
     * **episode 1**, which beside a Resume of S3:E4 is a different action rather than the same one from
     * the beginning, so the control is 7g's alone.
     *
     * The measurement is kept because it is what caught that: a `Row` that overflows measures its last
     * children down towards nothing, so a control that has silently stopped fitting is still in the
     * semantics tree and still "exists" — only its width says otherwise. Each control is held to its
     * own floor, so an icon cell is not let off the check — it is held to 48dp instead of 120.
     */
    @Test
    fun theShowsActionRowFitsTheBandAtItsSixControlCeiling() {
        show(DetailFixtures.Show)
        awaitFocus(RESUME)

        assertNoText(PLAY_EPISODE)
        assertActionRowFits(
            texts = listOf(RESUME, "$SEASON 1", "$EPISODES_PLURAL · 3", SYNOPSIS),
            cells = listOf(TRAILER, WATCH_STATUS),
        )
    }

    /**
     * A film's row, which is the board that **does** draw "start over": five controls, 532dp while
     * resuming — which is exactly what the loading skeleton's bar is derived from.
     *
     * Asserted from both sides deliberately. The show case above says the control is absent for a
     * series; without this one, a regression that dropped it everywhere would pass both.
     */
    @Test
    fun theMoviesActionRowDrawsStartOverAndFitsTheBand() {
        show(DetailFixtures.Movie)
        awaitFocus(RESUME)

        rule.onNodeWithContentDescription(PLAY_MOVIE).assertExists()
        assertActionRowFits(
            texts = listOf(RESUME, SYNOPSIS),
            cells = listOf(PLAY_MOVIE, TRAILER, WATCH_STATUS),
        )
    }

    /**
     * `Episodes · N` scrolls to the list, which is what it does in place of the episode panel board 6a
     * draws.
     *
     * It also asserts the page still has a focus owner afterwards. The scroll takes the head block —
     * and with it the button that was pressed — off screen, and a TV screen with nothing focused
     * answers the D-pad with nothing.
     */
    @Test
    fun theEpisodesButtonScrollsToTheListAndKeepsAFocusOwner() {
        show(DetailFixtures.Show)
        awaitFocus(RESUME)

        pressUntil(Dpad.Right, "$EPISODES_PLURAL · 3")
        dpad.center()

        awaitText(DetailFixtures.episodeName(0))
        assertNotNull(
            "the scroll took the pressed button off screen and left nothing focused",
            dpad.focused(),
        )
    }

    // ---- Coming back -------------------------------------------------------------------------------

    /**
     * Focus is restored through `DetailReturn`, and **only** once `DetailAction.Reappeared` says the
     * screen is back.
     *
     * A host composes its view well before it resumes, and focus taken in that gap is gone by the time
     * the screen above has finished leaving — `KMP-34` measured that on this emulator. So the negative
     * half of this test is the load-bearing half: between the action and `Reappeared`, focus must not
     * move at all.
     */
    @Test
    fun focusComesBackToThePrimaryOnlyOnceTheScreenSaysItIsBack() {
        show(DetailFixtures.Show)
        awaitFocus(RESUME)

        val cast = enterTheCastRow()

        act(DetailAction.TrailerRequested)
        rule.waitUntil(WAIT_MS) { effects.any { it is DetailEffect.PlayTrailer } }
        val recorded = viewModel.state.value.returnTo
        assertNotNull("the page recorded where to come back to", recorded)
        assertFalse("and has not been told it is back", recorded?.ready == true)
        assertStaysFocusedOn(cast, "before Reappeared arrived")

        act(DetailAction.Reappeared)
        awaitFocus(RESUME)
        rule.waitUntil(WAIT_MS) { viewModel.state.value.returnTo == null }
    }

    /** The other target: an episode was played, so the page comes back to the episode list. */
    @Test
    fun focusComesBackToTheEpisodeListAfterPlayingAnEpisode() {
        show(DetailFixtures.Show)
        awaitFocus(RESUME)

        val second = DetailFixtures.episodeName(1)
        assertEquals(second, pressUntil(Dpad.Down, second))

        // Not the primary's data, which is what tells `playbackTarget` this came from a row.
        act(DetailAction.PlaybackRequested(DetailFixtures.Show.episodes[1].data, fromStart = false))
        rule.waitUntil(WAIT_MS) { effects.any { it is DetailEffect.OpenPlayer } }
        assertStaysFocusedOn(second, "before Reappeared arrived")

        act(DetailAction.Reappeared)
        awaitFocus(DetailFixtures.episodeName(0))
        rule.waitUntil(WAIT_MS) { viewModel.state.value.returnTo == null }
    }

    // ---- The two presses this page has ------------------------------------------------------------

    /**
     * The watch-status picker: it opens, it is a trap in the sense `SidePanelHost` means — LEFT at its
     * leading edge does nothing, BACK closes it and hands focus back to the cell that opened it — and
     * pressing a row **is** the commit (`DEC-17`).
     */
    @Test
    fun theWatchStatusPickerOpensIsTrappedAndCommitsOnARowPress() {
        show(DetailFixtures.Show)
        awaitFocus(RESUME)

        // The opener is in the action row now, two stops right of Resume, rather than in the chrome.
        assertEquals(WATCH_STATUS, pressUntil(Dpad.Right, WATCH_STATUS))

        dpad.center()
        awaitFocus(WATCHING)
        assertEquals("LEFT at the panel's edge stays in the panel", WATCHING, dpad.press(Dpad.Left))

        pressRemoteBack()
        // BACK closes the panel and commits nothing: that is `DEC-17`'s rule and what this pins.
        //
        // Focus has to come back to the page's chrome row, and this names which control it reached.
        // Both dismiss paths are measured and both are asserted at the end of the test rather than
        // here, so that one run reports both instead of stopping at the first.
        val landedOnBack = awaitOneOf(WATCH_STATUS, BACK, FAVOURITE, RESUME, TRAILER)
        assertEquals("BACK committed nothing", emptyList<DetailWatchStatus>(), source.watchStatuses.toList())
        assertEquals("the panel is closed", false, viewModel.state.value.panelOpen)

        // Back to the opener for the commit half, from wherever the restore put focus. The opener now
        // sits in the action row, so a landing in the chrome has to come down into the row first.
        if (landedOnBack != WATCH_STATUS) {
            returnToWatchStatus(landedOnBack)
        }

        dpad.center()
        awaitFocus(WATCHING)
        assertEquals(COMPLETED, dpad.press(Dpad.Down))
        dpad.center()

        rule.waitUntil(WAIT_MS) { source.watchStatuses.isNotEmpty() }
        assertEquals(listOf(DetailWatchStatus.Completed), source.watchStatuses.toList())
        // The commit is also a dismiss, so the same restore has to happen — and the panel the viewer
        // sees sliding out must still be the picker, which is what `PanelLatch` is for.
        val landedOnCommit = awaitOneOf(WATCH_STATUS, BACK, FAVOURITE, RESUME, TRAILER)
        assertEquals("the panel is closed after the commit", false, viewModel.state.value.panelOpen)

        // `BUG-36`, fixed: `DetailPanelsTv` hands the cell that opened the picker to `SidePanelHost` as `opener`. This
        // pinned the defect until then: both dismiss paths landed on `Go Back`, the chrome row's first cell, because the
        // host's `focusRestorer` records only its direct child and the cell is an `IconCell` in a `Row` in the
        // `LazyColumn`'s keyed head item.
        assertEquals("BACK gives focus back to the cell that opened the panel", WATCH_STATUS, landedOnBack)
        assertEquals("and so does the commit", WATCH_STATUS, landedOnCommit)
    }

    /**
     * A cast item's only press swaps its role line for the voice actor, and a member with no voice
     * actor does nothing — while staying a focus stop, so the rail still scrolls through them.
     */
    @Test
    fun aCastPressSwapsTheRoleForTheVoiceActorAndDoesNothingWithoutOne() {
        show(DetailFixtures.Show)
        awaitFocus(RESUME)

        val voiced = DetailFixtures.Cast[VOICED]
        enterTheCastRow()
        assertEquals(voiced.name, pressUntil(Dpad.Right, voiced.name))
        rule.onNodeWithText(MAIN).assertExists()

        dpad.center()
        awaitText(voiced.voiceActorName.orEmpty())
        assertNoText(MAIN)
        assertEquals(setOf(VOICED), viewModel.state.value.swappedRoles)

        val silent = DetailFixtures.Cast.first()
        assertEquals(silent.name, pressUntil(Dpad.Left, silent.name))
        dpad.center()
        assertStaysFocusedOn(silent.name, "pressing a member with no voice actor")
        assertEquals("nothing else swapped", setOf(VOICED), viewModel.state.value.swappedRoles)
        rule.onNodeWithText(silent.role.orEmpty()).assertExists()
    }

    private companion object {
        const val WAIT_MS = 10_000L
        const val STILL_MS = 3_000L
        const val POLL_MS = 200L
        const val MAX_PRESSES = 10
        const val FORTY_PRESSES = 60
        const val LAST_OF_FORTY = 39
        const val LAST_OF_THREE = 2

        /** Enough to show DOWN is saturated at the last row, not merely slow to move. */
        const val PRESSES_PAST_THE_END = 3

        // `season_short`, `episode_short` and the boards' meta separator, as the screen resolves them.
        const val SEASON_SHORT = "S"
        const val EPISODE_SHORT = "E"
        const val SEPARATOR = "\u00b7"
        const val WALK_STOPS = 16
        const val VOICED = 2
        const val CHANNELS = 3f

        /** 2dp up from the bottom edge is inside the bar's 4dp and clear of the label. */
        val PROGRESS_PROBE: Dp = 2.dp

        /** `shapes.medium` is an 8dp radius; 16dp in from each end is the fill and nothing else. */
        val PROGRESS_INSET: Dp = 16.dp

        /** White fill, nothing over it. */
        const val NO_BAR_BRIGHTNESS = 0.9f

        /** A 38% bar of black over a 24%-black track reads about 0.47. */
        const val BAR_BRIGHTNESS = 0.6f

        // `values/strings.xml`, resolved by the screen in the composition's locale.
        const val BACK = "Go Back"
        const val WATCH_STATUS = "Set watch status"
        const val FAVOURITE = "Favorite"
        const val RESUME = "Resume"
        const val PLAY_EPISODE = "Play Episode"
        const val PLAY_MOVIE = "Play Movie"
        const val TRAILER = "Trailer"
        const val SEASON = "Season"
        const val SYNOPSIS = "Synopsis"
        const val EPISODES_PLURAL = "Episodes"
        const val EPISODE_SINGULAR = "Episode"
        const val RECOMMENDED = "More like this"
        const val NO_EPISODES = "No Episodes found"
        const val RETRY = "Try again"
        const val FAILED_HEADLINE = "Couldn't open this title"
        const val FAILED_BODY = "Its source may be unavailable."
        const val REMAINING = "remaining"
        const val WATCHING = "Watching"
        const val COMPLETED = "Completed"
        const val MAIN = "Main"

        /** `metaRun`: score, tags, year, certification, then the runtime or the status. */
        const val SHOW_META = "8.4/10.0 · Drama · Thriller · 2019 · TV-MA · Ongoing"
    }
}

/**
 * Registers an [OnBackPressedCallback] on the host activity for as long as [enabled], the same way
 * `DetailTvFragment` does for the real app.
 */
@Composable
private fun PanelBackHost(enabled: Boolean, onBack: () -> Unit) {
    val activity = LocalContext.current as ComponentActivity
    val latest by rememberUpdatedState(onBack)
    val callback = remember { object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = latest()
    } }
    DisposableEffect(activity, callback) {
        activity.onBackPressedDispatcher.addCallback(callback)
        onDispose { callback.remove() }
    }
    LaunchedEffect(enabled) { callback.isEnabled = enabled }
}


