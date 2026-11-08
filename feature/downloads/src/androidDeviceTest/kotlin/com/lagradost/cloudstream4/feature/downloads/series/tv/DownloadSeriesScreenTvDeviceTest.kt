package com.lagradost.cloudstream4.feature.downloads.series.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.Dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lagradost.cloudstream4.compose.ObserveEffect
import com.lagradost.cloudstream4.core.connectivity.Connectivity
import com.lagradost.cloudstream4.core.connectivity.ConnectivityFlow
import com.lagradost.cloudstream4.core.designsystem.icon.CloudStreamIcons
import com.lagradost.cloudstream4.core.designsystem.rail.NavRail
import com.lagradost.cloudstream4.core.designsystem.rail.NavRailItem
import com.lagradost.cloudstream4.core.devicetest.Dpad
import com.lagradost.cloudstream4.core.devicetest.DpadDriver
import com.lagradost.cloudstream4.core.devicetest.TouchModeReset
import com.lagradost.cloudstream4.core.devicetest.assertScreenshot
import com.lagradost.cloudstream4.core.devicetest.focusedLabel
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme
import com.lagradost.cloudstream4.feature.downloads.series.DownloadEpisode
import com.lagradost.cloudstream4.feature.downloads.series.DownloadEpisodeId
import com.lagradost.cloudstream4.feature.downloads.series.DownloadEpisodeState
import com.lagradost.cloudstream4.feature.downloads.series.DownloadSeriesContents
import com.lagradost.cloudstream4.feature.downloads.series.DownloadSeriesSource
import com.lagradost.cloudstream4.feature.downloads.series.DownloadSeriesViewModel
import com.lagradost.cloudstream4.feature.downloads.series.SeriesAction
import com.lagradost.cloudstream4.feature.downloads.series.SeriesEffect
import com.lagradost.cloudstream4.media.model.DownloadFailure
import com.lagradost.cloudstream4.media.model.QueuePosition
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * One series' downloads on a device, with a fake source and the real ViewModel. Every row's verbs share their labels, so
 * which row holds focus is read from where the focused stop is, against the rows' titles.
 */
@RunWith(AndroidJUnit4::class)
class DownloadSeriesScreenTvDeviceTest {

    /** `BUG-59`: starts and ends out of touch mode, whatever ran before it. */
    @get:Rule
    val touchMode = TouchModeReset()

    @get:Rule
    val rule = createComposeRule()

    private val dpad = DpadDriver(readFocus = { rule.focusedLabel() })
    private val source = FakeSource()
    private val network = MutableStateFlow(Connectivity.ONLINE)
    private val effects = CopyOnWriteArrayList<SeriesEffect>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val viewModel by lazy {
        DownloadSeriesViewModel(source, object : ConnectivityFlow { override val status = network }, scope)
    }

    @After
    fun stopTheViewModel() = scope.cancel()

    private fun showScreen(withRail: Boolean = false) {
        rule.setContent {
            val state by viewModel.state.collectAsState()
            ObserveEffect(viewModel.effect) { effects += it }
            CloudStreamTheme {
                if (withRail) {
                    Row(Modifier.fillMaxSize().background(CloudStreamTheme.colors.bg)) {
                        NavRail(ShotRail, selectedIndex = DOWNLOADS_INDEX, onSelect = {})
                        DownloadSeriesScreenTv(state, viewModel::onAction, ::bytes, Modifier.weight(1f))
                    }
                } else {
                    Box(Modifier.fillMaxSize().background(CloudStreamTheme.colors.bg)) {
                        DownloadSeriesScreenTv(state, viewModel::onAction, ::bytes)
                    }
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

    private fun awaitFocus(text: String) {
        val deadline = System.currentTimeMillis() + WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            rule.waitForIdle()
            if (rule.focusedLabel() == text) return
            Thread.sleep(POLL_MS)
        }
        assertEquals(text, rule.focusedLabel())
    }

    /** The title of the row the focused stop sits beside: the one whose middle is nearest the stop's. */
    private fun focusedRow(): String? {
        rule.waitForIdle()
        val focused = rule.onAllNodes(isFocused()).fetchSemanticsNodes().singleOrNull() ?: return null
        val middle = focused.boundsInRoot.center.y
        return source.titles().minByOrNull { title ->
            val nodes = rule.onAllNodesWithText(title).fetchSemanticsNodes()
            nodes.minOfOrNull { abs(it.boundsInRoot.center.y - middle) } ?: Float.MAX_VALUE
        }
    }

    /** Onto [row] by remote, from the first row: a row below the fold is not composed until the list reaches it. */
    private fun focusRow(row: String) {
        val first = source.titles().first()
        awaitText(first)
        rule.onNodeWithText(first).requestFocus()
        awaitFocus(first)
        repeat(MAX_PRESSES) {
            if (rule.focusedLabel() == row) return
            dpad.press(Dpad.Down)
        }
        assertEquals(row, rule.focusedLabel())
    }

    /** Focus on [verb] beside [row], by remote: onto the row, then Right along it. */
    private fun focusVerb(row: String, verb: String) {
        focusRow(row)
        repeat(3) {
            if (rule.focusedLabel() == verb) return
            dpad.press(Dpad.Right)
        }
        assertEquals(verb, rule.focusedLabel())
        assertEquals(row, focusedRow())
    }

    // ---- Arrival and the page ---------------------------------------------------------------------------------------

    @Test
    fun arrivalLandsOnPlayNextThePagesOnePrimary() {
        source.contents(Board6d)
        showScreen()
        awaitFocus(PLAY_NEXT)
    }

    @Test
    fun theBandSaysWhatIsOnDiskAndWhatIsMovingOrStuck() {
        source.contents(Board6d)
        showScreen()
        awaitText("Downloads · Breaking Bad")
        awaitText("5 Episodes · 980 MB on disk")
        awaitText("1 active download · 1 download queued · 1 failed")
        awaitText("Retry failed (1)")
    }

    @Test
    fun everyRowSaysItsStateWithMiddleDotsAndOnlyAFailureSaysWhy() {
        source.contents(Board6d)
        showScreen()
        awaitText(PILOT)
        rule.onNodeWithText("Downloaded · 500 MB · 38% watched").assertExists()
        rule.onNodeWithText("128 MB / 500 MB · 4.2 MB/s · 1m 30s").assertExists()
        rule.onNodeWithText("Download Failed · Network lost · 96 MB / 500 MB").assertExists()
        rule.onNodeWithText("Queued · 2/3 · 500 MB").assertExists()
        focusRow(PAUSED)
        rule.onNodeWithText("Download Paused · 256 MB / 500 MB").assertExists()
    }

    // ---- The three verbs ----------------------------------------------------------------------------------------------

    /** 6d: the row, then play, its state's verb and delete. Right walks them in that order on every state. */
    @Test
    fun columnBIsTheStatesOwnVerbAndAFinishedRowHasNone() {
        source.contents(Board6d)
        showScreen()
        mapOf(RUNNING to PAUSE, PAUSED to RESUME, FAILED to RETRY).forEach { (row, verb) ->
            focusVerb(row, PLAY)
            assertEquals("$row's state verb", verb, dpad.press(Dpad.Right))
            assertEquals("$row's delete", DELETE, dpad.press(Dpad.Right))
            assertEquals(row, focusedRow())
        }
        // A queued episode has nothing to play yet, so its Play is not a stop: Right from the row is Cancel.
        focusRow(QUEUED)
        assertEquals("the queued row's state verb", CANCEL, dpad.press(Dpad.Right))
        assertEquals("the queued row's delete", DELETE, dpad.press(Dpad.Right))
        assertEquals(QUEUED, focusedRow())
        focusVerb(PILOT, PLAY)
        assertEquals("a finished row goes from play straight to delete", DELETE, dpad.press(Dpad.Right))
    }

    @Test
    fun eachStateVerbDoesWhatItSays() {
        source.contents(Board6d)
        showScreen()
        focusVerb(RUNNING, PAUSE)
        dpad.center()
        focusVerb(PAUSED, RESUME)
        dpad.center()
        focusVerb(QUEUED, CANCEL)
        dpad.center()
        rule.waitUntil(WAIT_MS) { source.calls.size == 3 }
        assertEquals(listOf("pause 2", "resume 5", "cancel 4"), source.calls.toList())
    }

    /** 6d's note: column B is the only way back into a dead download. */
    @Test
    fun aFailedRowsRetryIsReachableAndAsksTheSourceToResume() {
        source.contents(Board6d)
        showScreen()
        focusVerb(FAILED, RETRY)
        dpad.center()
        rule.waitUntil(WAIT_MS) { source.calls.isNotEmpty() }
        assertEquals(listOf("resume 3"), source.calls.toList())
    }

    @Test
    fun downFromAStateVerbStaysInItsColumn() {
        source.contents(Board6d)
        showScreen()
        focusVerb(RUNNING, PAUSE)
        assertEquals(RETRY, dpad.press(Dpad.Down))
        assertEquals(FAILED, focusedRow())
    }

    @Test
    fun playAndDeleteAskTheHostWithThatEpisode() {
        source.contents(Board6d)
        showScreen()
        focusVerb(PILOT, PLAY)
        dpad.center()
        focusVerb(PILOT, DELETE)
        dpad.center()
        rule.waitUntil(WAIT_MS) { effects.size == 2 }
        assertEquals(listOf(SeriesEffect.Play(DownloadEpisodeId(1)), SeriesEffect.ConfirmDelete(DownloadEpisodeId(1))), effects.toList())
    }

    @Test
    fun aRowThatCannotPlayYetHasNoWorkingPlay() {
        source.contents(Board6d)
        showScreen()
        focusVerb(QUEUED, CANCEL)
        dpad.press(Dpad.Left)
        assertTrue("Left from cancel does not stop on a play that cannot play", rule.focusedLabel() != PLAY || focusedRow() != QUEUED)
    }

    // ---- The page's buttons -------------------------------------------------------------------------------------------

    @Test
    fun playNextPlaysTheFirstEpisodeNotWatchedToTheEnd() {
        source.contents(Board6d)
        showScreen()
        awaitFocus(PLAY_NEXT)
        dpad.center()
        rule.waitUntil(WAIT_MS) { effects.isNotEmpty() }
        assertEquals(SeriesEffect.Play(DownloadEpisodeId(1)), effects.single())
    }

    @Test
    fun deleteAllAsksForEveryEpisodeAndRetryFailedRetriesEachFailure() {
        source.contents(Board6d)
        showScreen()
        awaitFocus(PLAY_NEXT)
        assertEquals(DELETE_ALL, dpad.press(Dpad.Right))
        dpad.center()
        assertEquals("Retry failed (1)", dpad.press(Dpad.Right))
        dpad.center()
        rule.waitUntil(WAIT_MS) { effects.isNotEmpty() && source.calls.isNotEmpty() }
        assertEquals(SeriesEffect.ConfirmDeleteAll((1..5).map { DownloadEpisodeId(it) }), effects.single())
        assertEquals(listOf("resume 3"), source.calls.toList())
    }

    /** It goes with the last failure, so it hands focus to `Play next` rather than out of the window. */
    @Test
    fun retryFailedGoingTakesFocusToPlayNext() {
        source.contents(Board6d)
        showScreen()
        awaitText("Retry failed (1)")
        rule.onNodeWithText("Retry failed (1)").requestFocus()
        awaitFocus("Retry failed (1)")
        source.contents(Board6d.copy(episodes = Board6d.episodes.map { if (it.id.value == 3) it.copy(state = DownloadEpisodeState.Running) else it }))
        awaitGone("Retry failed (1)")
        awaitFocus(PLAY_NEXT)
    }

    // ---- Coming back, and many episodes -------------------------------------------------------------------------------

    @Test
    fun focusComesBackToTheVerbThatOpenedThePlayer() {
        source.contents(Board6d)
        showScreen()
        focusVerb(PAUSED, PLAY)
        dpad.center()
        rule.waitUntil(WAIT_MS) { effects.isNotEmpty() }
        // What the host does on return.
        rule.runOnIdle { viewModel.onAction(SeriesAction.Reappeared) }
        awaitFocus(PLAY)
        assertEquals(PAUSED, focusedRow())
    }

    /** Down from the first row to the last: every row on screen whole, its top on the pitch, and the last reached. */
    @Test
    fun fortyEpisodesAreAllReachableAndScrollByWholeRows() {
        val many = DownloadSeriesContents(
            name = "Long Show",
            episodes = (1..40).map { episode(it, "Part $it", DownloadEpisodeState.Done) },
        )
        source.contents(many)
        showScreen()
        awaitText("S1 · E1 · Part 1")
        rule.onNodeWithText("S1 · E1 · Part 1").requestFocus()
        awaitFocus("S1 · E1 · Part 1")
        val firstTop = rule.onNodeWithText("S1 · E1 · Part 1").getBoundsInRoot().top
        var seen = "S1 · E1 · Part 1"
        repeat(45) { seen = dpad.press(Dpad.Down) ?: seen }
        assertEquals("S1 · E40 · Part 40", seen)
        val pitch = rule.onNodeWithText("S1 · E40 · Part 40").getBoundsInRoot().top - rule.onNodeWithText("S1 · E39 · Part 39").getBoundsInRoot().top
        val offset = rule.onNodeWithText("S1 · E40 · Part 40").getBoundsInRoot().top - firstTop
        assertEquals("the last row sits on the pitch", 0f, ((offset.value % pitch.value) + pitch.value) % pitch.value, 0.5f)
    }

    @Test
    fun aLongNameEndsInAnEllipsisAndKeepsItsVerbs() {
        source.contents(DownloadSeriesContents("Long", listOf(episode(1, LONG_NAME, DownloadEpisodeState.Done))))
        showScreen()
        awaitText("S1 · E1 · $LONG_NAME")
        focusVerb("S1 · E1 · $LONG_NAME", PLAY)
        assertEquals(DELETE, dpad.press(Dpad.Right))
    }

    // ---- Page states --------------------------------------------------------------------------------------------------

    @Test
    fun offlineRidesTheTopRowAndCostsNothing() {
        network.value = Connectivity.OFFLINE
        source.contents(Board6d)
        showScreen()
        awaitText(OFFLINE)
        awaitFocus(PLAY_NEXT)
    }

    @Test
    fun anEmptyPageOffersAWayBackAndAFailedReadARetry() {
        source.contents(DownloadSeriesContents("Gone", emptyList()))
        showScreen()
        awaitText("No downloads yet")
        rule.onNodeWithText("Go Back").requestFocus()
        dpad.center()
        rule.waitUntil(WAIT_MS) { effects.isNotEmpty() }
        assertEquals(SeriesEffect.Close, effects.single())
    }

    @Test
    fun aFailedReadSaysWhyAndItsRetryRecovers() {
        source.failure = IllegalStateException("list unreadable")
        showScreen()
        awaitText("list unreadable")
        source.failure = null
        source.contents(Board6d)
        rule.onNodeWithText(RETRY).requestFocus()
        dpad.center()
        awaitText(PILOT)
    }

    // ---- Screenshots ----------------------------------------------------------------------------------------------------

    @Test
    fun screenshotEpisodesFocusOnPlayNext() {
        source.contents(Board6d)
        showScreen(withRail = true)
        awaitFocus(PLAY_NEXT)
        rule.assertScreenshot("series-episodes-play-next")
    }

    @Test
    fun screenshotFailedRowFocusOnRetry() {
        source.contents(Board6d)
        showScreen(withRail = true)
        focusVerb(FAILED, RETRY)
        rule.assertScreenshot("series-failed-focus-retry")
    }

    @Test
    fun screenshotOfflineOverEpisodes() {
        network.value = Connectivity.OFFLINE
        source.contents(Board6d)
        showScreen(withRail = true)
        awaitText(OFFLINE)
        awaitFocus(PLAY_NEXT)
        rule.assertScreenshot("series-offline")
    }

    @Test
    fun screenshotScrolledRowsStayWhole() {
        source.contents(DownloadSeriesContents("Long Show", (1..20).map { episode(it, "Part $it", DownloadEpisodeState.Done) }))
        showScreen(withRail = true)
        awaitText("S1 · E1 · Part 1")
        rule.onNodeWithText("S1 · E1 · Part 1").requestFocus()
        repeat(8) { dpad.press(Dpad.Down) }
        awaitFocus("S1 · E9 · Part 9")
        rule.waitForIdle()
        rule.assertScreenshot("series-scrolled")
    }

    @Test
    fun screenshotLoading() {
        showScreen(withRail = true)
        rule.waitForIdle()
        rule.assertScreenshot("series-loading")
    }

    @Test
    fun screenshotEmpty() {
        source.contents(DownloadSeriesContents("Gone", emptyList()))
        showScreen(withRail = true)
        awaitText("No downloads yet")
        rule.waitForIdle()
        rule.assertScreenshot("series-empty")
    }

    @Test
    fun screenshotFailedRead() {
        source.failure = IllegalStateException("list unreadable")
        showScreen(withRail = true)
        awaitText("list unreadable")
        rule.onNodeWithText(RETRY).requestFocus()
        awaitFocus(RETRY)
        rule.assertScreenshot("series-failed-read")
    }

    private class FakeSource : DownloadSeriesSource {
        private val latest = MutableStateFlow<DownloadSeriesContents?>(null)
        val calls = CopyOnWriteArrayList<String>()

        @Volatile var failure: Exception? = null

        fun contents(value: DownloadSeriesContents) {
            latest.value = value
        }

        fun titles(): List<String> = latest.value?.episodes.orEmpty().map { "S${it.season} · E${it.episode} · ${it.name}" }

        override val contents: Flow<DownloadSeriesContents> = flow {
            failure?.let { throw it }
            latest.filterNotNull().collect { emit(it) }
        }

        override fun refresh() = Unit
        override fun pause(id: DownloadEpisodeId) { calls += "pause ${id.value}" }
        override fun resume(id: DownloadEpisodeId) { calls += "resume ${id.value}" }
        override fun cancel(id: DownloadEpisodeId) { calls += "cancel ${id.value}" }
    }

    private companion object {
        const val WAIT_MS = 10_000L
        const val POLL_MS = 50L
        const val MAX_PRESSES = 12
        const val DOWNLOADS_INDEX = 3
        const val GB = 1_000_000_000L
        const val MB = 1_000_000L

        const val PLAY_NEXT = "Play next"
        const val DELETE_ALL = "Delete all"
        const val PLAY = "Play"
        const val PAUSE = "Pause"
        const val RESUME = "Resume"
        const val RETRY = "Retry"
        const val CANCEL = "Cancel"
        const val DELETE = "Delete"
        const val OFFLINE = "Offline · no connection"

        const val PILOT = "S1 · E1 · Pilot"
        const val RUNNING = "S1 · E2 · Cat's in the Bag"
        const val FAILED = "S1 · E3 · And the Bag's in the River"
        const val QUEUED = "S1 · E4 · Cancer Man"
        const val PAUSED = "S1 · E5 · Gray Matter"
        const val LONG_NAME = "An Extraordinarily Long Episode Title That Will Never Fit On One Row However Wide The Television " +
            "Is, Which Goes On And On Well Past The Point Where Anyone Would Still Be Reading It"

        fun bytes(value: Long): String {
            if (value < GB) {
                val tenths = value * 10 / MB
                return if (tenths % 10 == 0L) "${tenths / 10} MB" else "${tenths / 10}.${tenths % 10} MB"
            }
            val tenths = value * 10 / GB
            return if (tenths % 10 == 0L) "${tenths / 10} GB" else "${tenths / 10}.${tenths % 10} GB"
        }

        fun episode(
            number: Int,
            name: String,
            state: DownloadEpisodeState,
            done: Long = 500 * MB,
            total: Long = 500 * MB,
        ) = DownloadEpisode(
            id = DownloadEpisodeId(number), season = 1, episode = number, name = name,
            state = state, bytesDone = done, bytesTotal = total,
        )

        val Board6d = DownloadSeriesContents(
            name = "Breaking Bad",
            episodes = listOf(
                episode(1, "Pilot", DownloadEpisodeState.Done).copy(watched = 0.38f),
                episode(2, "Cat's in the Bag", DownloadEpisodeState.Running, done = 128 * MB)
                    .copy(bytesPerSecond = 4_200_000L, secondsLeft = 90L),
                episode(3, "And the Bag's in the River", DownloadEpisodeState.Failed, done = 96 * MB)
                    .copy(failure = DownloadFailure.NetworkLost),
                episode(4, "Cancer Man", DownloadEpisodeState.Queued, done = 0L).copy(queue = QueuePosition(2, 3)),
                episode(5, "Gray Matter", DownloadEpisodeState.Paused, done = 256 * MB),
            ),
        )

        val ShotRail = listOf(
            NavRailItem("Home", CloudStreamIcons.Home),
            NavRailItem("Search", CloudStreamIcons.Search),
            NavRailItem("Library", CloudStreamIcons.Library),
            NavRailItem("Downloads", CloudStreamIcons.Download),
            NavRailItem("Settings", CloudStreamIcons.Settings),
        )
    }
}
