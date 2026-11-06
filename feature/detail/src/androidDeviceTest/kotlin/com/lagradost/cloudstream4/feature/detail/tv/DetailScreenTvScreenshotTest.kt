package com.lagradost.cloudstream4.feature.detail.tv

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lagradost.cloudstream4.core.devicetest.Dpad
import com.lagradost.cloudstream4.core.devicetest.DpadDriver
import com.lagradost.cloudstream4.core.devicetest.assertScreenshot
import com.lagradost.cloudstream4.core.devicetest.focusedLabel
import com.lagradost.cloudstream4.core.devicetest.solidPoster
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme
import com.lagradost.cloudstream4.feature.detail.DetailAction
import com.lagradost.cloudstream4.feature.detail.DetailViewModel
import com.lagradost.cloudstream4.feature.detail.TitleDetail
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The reference images for this page's load-bearing states, per `.claude/rules/screenshot-testing.md`
 * and `INF-11`. `SearchScreenTvDeviceTest`'s `screenshot…` cases are the precedent and this follows
 * them: same `assertScreenshot`, same `screenshot` prefix so `tests_regex=.*screenshot.*` selects them,
 * references at 1280x720 in `src/androidDeviceTest/resources/screenshots`.
 *
 * ## Why these six
 *
 * The rule asks for states that are load-bearing rather than permutations, and names the two this page
 * must not get wrong: *"a failure that must stay legible"* and *"the empty and offline variants"*. The
 * six here are the states whose **geometry** a change can break silently, and each is one the behaviour
 * suite can already assert the *facts* of but not the *picture*:
 *
 * | Case | What the image is guarding |
 * |---|---|
 * | `detail-show-focus-primary` | 7a whole: the hero, the five-control action row inside its 833dp band, the focus ring on the primary, and the meta run |
 * | `detail-movie-focus-primary` | 7b, whose row is a **different** row — a film draws "start over" and a series does not |
 * | `detail-loading` | the one state with no content at all, where a wrong marker reads as a failure |
 * | `detail-error-focus-retry` | `DEC-17`'s hollow neutral failure marker and its reachable Retry — the legibility case the rule names |
 * | `detail-scrolled-recommendations` | the rails below the fold, which only exist once the page has scrolled |
 * | `detail-synopsis-panel-open` | the side panel over the page, the only state where two surfaces overlap |
 * | `detail-episodes-selected-row` | board 7i's episode rows: the pill on the row the primary would play, and the runtime line |
 *
 * Not drawn here and deliberately: the forty-episode list, one episode, no episodes and the long-text
 * case. Those are **assertions about layout** — a fixed box, an ellipsis, a plural — which
 * `DetailScreenTvDeviceTest` measures in dp and would be permutation filler as images.
 *
 * `detail-episodes-selected-row` is the one episode-list case, and it is here for the opposite reason:
 * a **selected** row is not layout. `SelectionMarker`'s muted pill and the row's `raised` fill are
 * painted, so `assertIsSelected` can pin the fact and only an image can show what a viewer sees. It is
 * also the only reference on this page that shows selection at all.
 *
 * ## Why this host is not the behaviour suite's
 *
 * Two deliberate differences. It fills [artwork], which `DetailScreenTvDeviceTest` leaves empty: an
 * image the host loads is most of what a viewer sees, and a reference with a hole where the hero goes
 * guards nothing about the composition over it. The fills are flat colours from [solidPoster], so they
 * are byte-identical on every run — a real decoded image would make the diff about JPEG noise. And it
 * wires no platform back and collects no effects, because no case here dismisses anything.
 */
@RunWith(AndroidJUnit4::class)
class DetailScreenTvScreenshotTest {

    @get:Rule
    val rule = createComposeRule()

    private val dpad = DpadDriver(readFocus = { rule.focusedLabel() })
    private val source = FakeDetailSource()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var viewModel: DetailViewModel

    @After
    fun stopTheHost() {
        scope.cancel()
    }

    /**
     * Shows the page, with the ViewModel built here because its `init` loads at once — the fake has to
     * be configured before it exists. [DetailScreenTvDeviceTest.show]'s reasoning, kept local.
     */
    private fun show(detail: TitleDetail?) {
        source.detail = detail
        viewModel = DetailViewModel(id = DetailFixtures.Id, source = source, scope = scope)
        rule.setContent {
            val state by viewModel.state.collectAsState()
            CloudStreamTheme {
                // `muted` and `line`, not `surface` and `raised`: the first recording used those and
                // both vanished into the background, so the hero and every headshot were invisible and
                // the reference guarded nothing about them. These stand in for a photograph, so what
                // matters is that they are visible and flat — and they are still tokens, so a palette
                // change still shows up in the diff.
                val hero = CloudStreamTheme.colors.muted.toArgb()
                val headshot = CloudStreamTheme.colors.line.toArgb()
                Box(Modifier.fillMaxSize().background(CloudStreamTheme.colors.bg)) {
                    DetailScreenTv(
                        state = state,
                        onAction = viewModel::onAction,
                        onBack = {},
                        artwork = { url ->
                            // By url, so a headshot and the hero are distinguishable in the reference
                            // rather than one flat field. Theme colours, so a token change shows up.
                            Image(
                                bitmap = solidPoster(if (url == DetailFixtures.HeadshotUrl) headshot else hero),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }

    private fun awaitText(text: String) = rule.waitUntil(WAIT_MS) {
        rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }

    private fun awaitFocus(label: String) = rule.waitUntil(WAIT_MS) { rule.focusedLabel() == label }

    /**
     * DOWN until a focused stop's label contains one of [wanted], so the ring is where a remote would
     * have put it. Returns the trail, so a failure says what it walked past instead of only where it
     * stopped.
     *
     * `contains` and not equality: the first run asserted the recommendation's exact title and walked
     * straight past the rail into the episodes, because a card's focus label is not only its title.
     */
    private fun pressUntilOneOf(wanted: List<String>): Pair<String?, List<String?>> {
        val trail = mutableListOf(rule.focusedLabel())
        repeat(MAX_PRESSES) {
            val focused = trail.last()
            if (wanted.any { focused?.contains(it) == true }) return focused to trail
            trail += dpad.press(Dpad.Down)
        }
        return trail.last() to trail
    }

    /**
     * 7a, the whole page, with the ring on the control the page opens on.
     *
     * Entry focus is asserted rather than requested: `DetailScreenTv` places it itself, and a reference
     * recorded after a `requestFocus` would still look right if that stopped working.
     */
    @Test
    fun screenshotShowWithThePrimaryFocused() {
        show(DetailFixtures.Show)
        awaitText(DetailFixtures.Show.title)
        awaitFocus(RESUME)
        rule.assertScreenshot("detail-show-focus-primary")
    }

    /** 7b: a film's action row is not a series', because only a playable title draws "start over". */
    @Test
    fun screenshotMovieWithThePrimaryFocused() {
        show(DetailFixtures.Movie)
        awaitText(DetailFixtures.Movie.title)
        awaitFocus(RESUME)
        rule.assertScreenshot("detail-movie-focus-primary")
    }

    /** The state with no content: the fake never answers, which is what the page sees while loading. */
    @Test
    fun screenshotLoading() {
        // The skeleton pulses forever, so an auto-advancing clock gives a different frame every run
        // and the reference could never be matched twice. Stopped, then advanced a fixed amount, the
        // pulse is at one known phase. This is the only case that needs it: the other five are still.
        rule.mainClock.autoAdvance = false
        source.answers = false
        show(null)
        rule.mainClock.advanceTimeBy(PULSE_PHASE_MS)
        rule.assertScreenshot("detail-loading")
    }

    /**
     * `DEC-17`'s failure: a hollow neutral marker, the two lines, and Retry with the ring on it.
     *
     * The one the rule asks for by name — *"a failure that must stay legible"* — and the one where a
     * regression is least likely to be noticed by hand, because nothing crashes.
     */
    @Test
    fun screenshotErrorWithRetryFocused() {
        source.failure = IOException("HTTP 503")
        show(null)
        awaitText(FAILED_HEADLINE)
        awaitFocus(RETRY)
        rule.assertScreenshot("detail-error-focus-retry")
    }

    /**
     * The page scrolled to the rails below the fold, which is where "More like this" lives.
     *
     * Scrolled by pressing DOWN to a recommendation rather than by a scroll call: the lazy column moves
     * because focus moved, so this is the geometry a viewer actually arrives at.
     */
    @Test
    fun screenshotScrolledToRecommendations() {
        show(DetailFixtures.Show)
        awaitText(DetailFixtures.Show.title)
        awaitFocus(RESUME)
        val titles = DetailFixtures.Recommendations.map { it.title }
        val (landed, trail) = pressUntilOneOf(titles)
        assertTrue(
            "no recommendation took focus. DOWN went: ${trail.joinToString(" -> ")}",
            titles.any { landed?.contains(it) == true },
        )
        rule.waitForIdle()
        rule.assertScreenshot("detail-scrolled-recommendations")
    }

    /** The only state where two surfaces overlap: the synopsis panel over the page it came from. */
    @Test
    fun screenshotSynopsisPanelOpen() {
        show(DetailFixtures.Show)
        awaitText(DetailFixtures.Show.title)
        awaitFocus(RESUME)
        rule.runOnUiThread { viewModel.onAction(DetailAction.SynopsisToggled) }
        rule.waitUntil(WAIT_MS) { viewModel.state.value.synopsisOpen }
        // Two "Synopsis" on screen: the action row's control and the panel's own header. One means the
        // panel is not composed yet — and a capture taken then would record a frame of the 240 ms
        // slide, which is a reference that cannot be matched twice.
        rule.waitUntil(WAIT_MS) { rule.onAllNodesWithText(SYNOPSIS).fetchSemanticsNodes().size == PANEL_AND_BUTTON }
        // Its one stop. The panel's only focusable is the row that closes it, so this is also what
        // decides where the ring is in the image.
        awaitFocus(GO_BACK)
        rule.waitForIdle()
        assertTrue("the panel is still open", viewModel.state.value.synopsisOpen)
        rule.assertScreenshot("detail-synopsis-panel-open")
    }

    /**
     * Board 7i's rows: the pill on the row the primary would play, and the runtime after each code.
     *
     * Focus is walked to the **last** row, which is what puts the whole list in frame. Stopping at the
     * first row instead left the selected one clipped to ten pixels at the bottom edge — the reference
     * was recorded that way once and guarded nothing, which is what looking at it caught.
     *
     * So the ring is on episode 3 and the pill is on episode 2, and the frame carries both halves of
     * rule 4 at once: **colour says focus, shape says state**, on two different rows of one list. The
     * fixture resumes the middle episode precisely so those two can never be the same row.
     */
    @Test
    fun screenshotEpisodeListWithTheSelectedRowMarked() {
        show(DetailFixtures.RuntimeEpisodes)
        awaitText(DetailFixtures.RuntimeEpisodes.title)
        awaitFocus(RESUME)
        val last = DetailFixtures.episodeName(DetailFixtures.RuntimeEpisodes.episodes.lastIndex)
        val (landed, trail) = pressUntilOneOf(listOf(last))
        assertTrue(
            "the last episode row never took focus. DOWN went: ${trail.joinToString(" -> ")}",
            landed?.contains(last) == true,
        )
        rule.waitForIdle()
        rule.assertScreenshot("detail-episodes-selected-row")
    }

    private companion object {
        const val WAIT_MS = 10_000L
        const val MAX_PRESSES = 12

        // `values/strings.xml`, resolved by the screen in the composition's locale.
        const val RESUME = "Resume"
        const val RETRY = "Try again"
        const val FAILED_HEADLINE = "Couldn't open this title"
        const val SYNOPSIS = "Synopsis"
        const val GO_BACK = "Go Back"

        /** The action row's control and the open panel's header, which is how many "Synopsis" there are. */
        const val PANEL_AND_BUTTON = 2

        /** Any fixed point in the skeleton's pulse. Only the fact that it is fixed matters. */
        const val PULSE_PHASE_MS = 500L
    }
}
