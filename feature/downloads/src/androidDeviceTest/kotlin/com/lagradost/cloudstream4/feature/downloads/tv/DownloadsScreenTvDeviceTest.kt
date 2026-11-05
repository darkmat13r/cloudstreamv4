package com.lagradost.cloudstream4.feature.downloads.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lagradost.cloudstream4.compose.ObserveEffect
import com.lagradost.cloudstream4.core.connectivity.Connectivity
import com.lagradost.cloudstream4.core.connectivity.ConnectivityFlow
import com.lagradost.cloudstream4.core.designsystem.icon.CloudStreamIcons
import com.lagradost.cloudstream4.core.designsystem.rail.NavRail
import com.lagradost.cloudstream4.core.designsystem.rail.NavRailItem
import com.lagradost.cloudstream4.core.devicetest.Dpad
import com.lagradost.cloudstream4.core.devicetest.DpadDriver
import com.lagradost.cloudstream4.core.devicetest.assertScreenshot
import com.lagradost.cloudstream4.core.devicetest.captureScreenshot
import com.lagradost.cloudstream4.core.devicetest.focusedLabel
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme
import com.lagradost.cloudstream4.core.theme.cloudStreamDarkColors
import com.lagradost.cloudstream4.feature.downloads.DownloadQueue
import com.lagradost.cloudstream4.feature.downloads.DownloadStorage
import com.lagradost.cloudstream4.feature.downloads.DownloadTitle
import com.lagradost.cloudstream4.feature.downloads.DownloadTitleId
import com.lagradost.cloudstream4.feature.downloads.DownloadTitleKind
import com.lagradost.cloudstream4.feature.downloads.DownloadTitleState
import com.lagradost.cloudstream4.feature.downloads.DownloadsAction
import com.lagradost.cloudstream4.feature.downloads.DownloadsContents
import com.lagradost.cloudstream4.feature.downloads.DownloadsEffect
import com.lagradost.cloudstream4.feature.downloads.DownloadsSource
import com.lagradost.cloudstream4.feature.downloads.DownloadsViewModel
import com.lagradost.cloudstream4.feature.downloads.StorageShortfall
import java.util.concurrent.CopyOnWriteArrayList
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The TV downloads screen on a device, with a fake source and the real ViewModel: no `:app`, no network.
 *
 * `Television_720p` is about 961.5x540.8 dp rather than the artboard's 960x540, so nothing here asserts the screen's size.
 */
@RunWith(AndroidJUnit4::class)
class DownloadsScreenTvDeviceTest {

    @get:Rule
    val rule = createComposeRule()

    private val dpad = DpadDriver(readFocus = { rule.focusedLabel() })
    private val source = FakeSource()
    private val network = MutableStateFlow(Connectivity.ONLINE)
    private val effects = CopyOnWriteArrayList<DownloadsEffect>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var onScreen by mutableStateOf(true)

    private val viewModel by lazy {
        DownloadsViewModel(source, object : ConnectivityFlow { override val status = network }, scope)
    }

    @After
    fun stopTheViewModel() = scope.cancel()

    private fun showScreen() {
        rule.setContent {
            val state by viewModel.state.collectAsState()
            ObserveEffect(viewModel.effect) { effects += it }
            CloudStreamTheme {
                Box(Modifier.fillMaxSize().background(CloudStreamTheme.colors.bg)) {
                    if (onScreen) DownloadsScreenTv(state, viewModel::onAction, ::bytes)
                }
            }
        }
    }

    /** As the app shows it: the rail beside the screen, which is what puts it on the board's x. */
    private fun showForScreenshot() {
        rule.setContent {
            val state by viewModel.state.collectAsState()
            CloudStreamTheme {
                Row(Modifier.fillMaxSize().background(CloudStreamTheme.colors.bg)) {
                    NavRail(ShotRail, selectedIndex = DOWNLOADS_INDEX, onSelect = {})
                    DownloadsScreenTv(state, viewModel::onAction, ::bytes, Modifier.weight(1f))
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

    private fun focus(text: String) {
        awaitText(text)
        rule.onNodeWithText(text).requestFocus()
        rule.waitUntil(WAIT_MS) { rule.focusedLabel() == text }
    }

    /** `waitUntil` that says where focus actually is when it gives up. */
    private fun awaitFocus(text: String) {
        val deadline = System.currentTimeMillis() + WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            rule.waitForIdle()
            if (rule.focusedLabel() == text) return
            Thread.sleep(POLL_MS)
        }
        assertEquals(text, rule.focusedLabel())
    }

    private fun pressUntil(direction: Dpad, wanted: String, presses: Int = MAX_PRESSES): String? =
        generateSequence { dpad.press(direction) }.take(presses).firstOrNull { it == wanted }

    private fun assertWalkIsClean(expected: List<String>) {
        val graph = dpad.walk()
        assertEquals(graph.toString(), emptyList<String>(), graph.unreachable(expected))
        assertEquals(graph.toString(), emptyList<String>(), graph.deadEnds())
        assertEquals(graph.toString(), emptyList<String>(), graph.traps())
    }

    // ---- 6c --------------------------------------------------------------------------------------------------------

    @Test
    fun titlesShowTheirSizesProgressAndTheMeter() {
        source.contents(Board6c)
        showScreen()

        awaitText("Arcane")
        rule.onNodeWithText("18 Episodes | 11.2 GB").assertExists()
        rule.onNodeWithText("Movie • 4.2 GB").assertExists()
        rule.onNodeWithText("4 Episodes | 1.9 GB / 3.1 GB").assertExists()
        rule.onNodeWithText("61%").assertExists()
        rule.onNodeWithText("1/4").assertExists()
        rule.onNodeWithText("384.0 GB of 512.0 GB used · 128.0 GB in downloads").assertExists()
    }

    @Test
    fun okOnARowOpensItsTitle() {
        source.contents(Board6c)
        showScreen()

        focus(ARCANE)
        dpad.center()
        rule.waitUntil(WAIT_MS) { effects.isNotEmpty() }
        assertEquals(DownloadsEffect.OpenTitle(DownloadTitleId(ARCANE_ID)), effects.single())
    }

    @Test
    fun theChipsGoWhereTheirNamesSay() {
        source.contents(Board6c)
        showScreen()

        focus(QUEUE)
        dpad.center()
        rule.waitUntil(WAIT_MS) { effects.size == 1 }
        assertEquals(PLAY_URL, dpad.press(Dpad.Right))
        dpad.center()
        rule.waitUntil(WAIT_MS) { effects.size == 2 }
        assertEquals(listOf(DownloadsEffect.OpenQueue, DownloadsEffect.OpenPlayFromUrl), effects.toList())
        assertEquals(SELECT, dpad.press(Dpad.Right))
    }

    /** A failed row's way back in, by remote: Right from the row, OK, and focus does not fall off with the button. */
    @Test
    fun aFailedTransferShowsRetryAndItIsReachableAndFocusSurvivesIt() {
        source.contents(Board6c)
        showScreen()

        focus(BLUE_EYE)
        rule.onNodeWithText(FAILED).assertExists()
        assertEquals(RETRY, dpad.press(Dpad.Right))
        dpad.center()
        rule.waitUntil(WAIT_MS) { source.resumed.isNotEmpty() }
        assertEquals(listOf(DownloadTitleId(BLUE_EYE_ID)), source.resumed.toList())

        source.contents(Board6c.copy(titles = Board6c.titles.map {
            if (it.id.value == BLUE_EYE_ID) it.copy(state = DownloadTitleState.Running) else it
        }))
        awaitGone(RETRY)
        awaitFocus(BLUE_EYE)
    }

    @Test
    fun aRowWithNoVerbDoesNotSendRightToAnotherRowsVerb() {
        source.contents(Board6c)
        showScreen()

        focus(ARCANE)
        assertEquals(ARCANE, dpad.press(Dpad.Right))
    }

    @Test
    fun aPausedRowOffersResume() {
        val paused = title(9, "Paused one", DownloadTitleState.Paused, progress = 0.5f)
        source.contents(DownloadsContents(titles = listOf(paused)))
        showScreen()

        focus("Paused one")
        assertEquals(RESUME, dpad.press(Dpad.Right))
        dpad.center()
        rule.waitUntil(WAIT_MS) { source.resumed.isNotEmpty() }
    }

    /** More than fit: every one is reached by Down and comes on screen, and Up gets back to the chips. */
    @Test
    fun fortyRowsAreAllReachableAndScrollIntoView() {
        val titles = List(40) { title(it + 1, "Row ${it + 1}") }
        source.contents(DownloadsContents(titles = titles))
        showScreen()

        focus("Row 1")
        for (n in 2..40) {
            assertEquals("Row $n", dpad.press(Dpad.Down))
            rule.onNodeWithText("Row $n").assertExists()
        }
        assertEquals("the end of the list is an edge", "Row 40", dpad.press(Dpad.Down))
        for (n in 39 downTo 1) assertEquals("Row $n", dpad.press(Dpad.Up))
        assertTrue("Up from the top row reaches a chip", dpad.press(Dpad.Up) in listOf(QUEUE, PLAY_URL, SELECT))
    }

    @Test
    fun aLongNameIsEllipsizedOnOneLine() {
        source.contents(DownloadsContents(titles = listOf(title(1, LONG_NAME))))
        showScreen()

        awaitText(LONG_NAME)
        val layouts = mutableListOf<TextLayoutResult>()
        rule.onNodeWithText(LONG_NAME, useUnmergedTree = true).fetchSemanticsNode()
            .config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
        assertEquals(1, layouts.single().lineCount)
        assertTrue(layouts.single().isLineEllipsized(0))
    }

    @Test
    fun everyStopOnTheListIsReachableWithNoDeadEndsOrTraps() {
        source.contents(Board6c)
        showScreen()

        focus(DUNE)
        assertWalkIsClean(listOf(QUEUE, PLAY_URL, SELECT, DUNE, ARCANE, BLUE_EYE, RETRY, EXPANSE))
    }

    // ---- 6e --------------------------------------------------------------------------------------------------------

    @Test
    fun emptySaysSoAndItsActionIsReachableAndGoesToTheLibrary() {
        source.contents(DownloadsContents(queue = DownloadQueue(0, 3)))
        showScreen()

        awaitText(NO_DOWNLOADS)
        assertFalse("nothing takes focus on arrival", rule.onAllNodes(isFocused()).fetchSemanticsNodes().isNotEmpty())
        rule.onNodeWithText(PLAY_URL).assertExists()
        rule.onNodeWithText("$QUEUE (0/3)").assertExists()

        focus(PLAY_URL)
        assertEquals(BROWSE, dpad.press(Dpad.Down))
        dpad.center()
        rule.waitUntil(WAIT_MS) { effects.isNotEmpty() }
        assertEquals(DownloadsEffect.OpenLibrary, effects.single())
    }

    @Test
    fun everyStopWhenEmptyIsReachableWithNoDeadEndsOrTraps() {
        source.contents(DownloadsContents(storage = Board6c.storage))
        showScreen()

        awaitText(NO_DOWNLOADS)
        assertWalkIsClean(listOf(PLAY_URL, QUEUE, BROWSE))
    }

    @Test
    fun offlineRidesTheTopRowOnBothLayoutsOnlyWhenTheDeviceSaysSo() {
        source.contents(Board6c)
        showScreen()

        awaitText(ARCANE)
        rule.onNodeWithText(OFFLINE).assertDoesNotExist()
        network.value = Connectivity.OFFLINE
        awaitText(OFFLINE)
        // The list stays, because it is what still works.
        rule.onNodeWithText(ARCANE).assertExists()

        source.contents(DownloadsContents())
        awaitText(NO_DOWNLOADS)
        rule.onNodeWithText(OFFLINE).assertExists()

        network.value = Connectivity.UNKNOWN
        awaitGone(OFFLINE)
    }

    @Test
    fun aFullDiskStatesTheShortfallAndFreeUpSpaceIsReachableAndChooses() {
        source.contents(Board6c.copy(storageFull = StorageShortfall(1_200_000_000), queue = DownloadQueue(0, 3)))
        showScreen()

        awaitText(NOT_ENOUGH)
        rule.onNodeWithText("Needs 1.2 GB more · 3 downloads queued").assertExists()
        // The chips made way for it, and two of them moved up rather than going.
        rule.onNodeWithText(SELECT).assertDoesNotExist()
        focus("$QUEUE (0/3)")
        assertEquals(FREE_UP, dpad.press(Dpad.Down))
        dpad.center()
        awaitText(SELECT_ALL)
        // The button went with the banner, and focus is on what to choose instead of nowhere.
        awaitFocus(DUNE)
    }

    @Test
    fun aFullDiskWithAnUnknownShortfallStillSaysSoWithoutANumber() {
        source.contents(DownloadsContents(storageFull = StorageShortfall(null)))
        showScreen()

        awaitText(NOT_ENOUGH)
        rule.onAllNodesWithText("Needs", substring = true).fetchSemanticsNodes().let { assertTrue(it.isEmpty()) }
    }

    @Test
    fun everyStopWithABannerIsReachableWithNoDeadEndsOrTraps() {
        source.contents(Board6c.copy(storageFull = StorageShortfall(1_200_000_000)))
        showScreen()

        focus(DUNE)
        assertWalkIsClean(listOf(PLAY_URL, "$QUEUE (1/4)", FREE_UP, DUNE, ARCANE, BLUE_EYE, RETRY, EXPANSE))
    }

    /** `DEC-17`: pixels, because a token can be swapped without anything else noticing. */
    @Test
    fun aFailureSpendsNoLive() {
        source.contents(Board6c.copy(storageFull = StorageShortfall(1_200_000_000)))
        showForScreenshot()
        awaitText(NOT_ENOUGH)
        rule.waitForIdle()

        val shot = rule.captureScreenshot()
        val live = cloudStreamDarkColors().live.toArgb()
        val pixels = IntArray(shot.width * shot.height)
        shot.getPixels(pixels, 0, shot.width, 0, 0, shot.width, shot.height)
        assertEquals(0, pixels.count { it == live })
    }

    @Test
    fun aFailedReadSaysSoAndRetryIsReachableAndRecovers() {
        source.failure = IllegalStateException("list unreadable")
        showScreen()

        awaitText("list unreadable")
        focus(PLAY_URL)
        assertEquals(RETRY, dpad.press(Dpad.Down))

        source.failure = null
        source.contents(Board6c)
        dpad.center()
        awaitText(ARCANE)
        // The button left with its state, so focus went somewhere rather than nowhere.
        awaitFocus(PLAY_URL)
    }

    // ---- Choosing, which has no board ------------------------------------------------------------------------------

    @Test
    fun selectChoosesRowsAndDeleteAsksTheHostForThose() {
        source.contents(Board6c)
        showScreen()

        focus(SELECT)
        dpad.center()
        awaitText(SELECT_ALL)
        awaitFocus(DUNE)
        dpad.center()
        awaitText("Delete (1 | 4.2 GB)")
        rule.onNodeWithText(DUNE).assertIsSelected()
        assertEquals("OK while choosing opens nothing", emptyList<DownloadsEffect>(), effects.toList())

        // The nearest chip, whichever it is, and every one is a press away along the row.
        val chip = dpad.press(Dpad.Up)
        assertTrue("$chip", chip in listOf(SELECT_ALL, CANCEL, "Delete (1 | 4.2 GB)"))
        assertEquals("Delete (1 | 4.2 GB)", if (chip == "Delete (1 | 4.2 GB)") chip else pressUntil(Dpad.Right, "Delete (1 | 4.2 GB)"))
        dpad.center()
        rule.waitUntil(WAIT_MS) { effects.isNotEmpty() }
        assertEquals(DownloadsEffect.ConfirmDelete(setOf(DownloadTitleId(DUNE_ID))), effects.single())
    }

    @Test
    fun cancellingFromItsChipPutsFocusBackOnSelect() {
        source.contents(Board6c)
        showScreen()

        focus(SELECT)
        dpad.center()
        awaitText(CANCEL)
        focus(CANCEL)
        dpad.center()
        awaitText(SELECT)
        awaitFocus(SELECT)
    }

    @Test
    fun whileChoosingNoRowOffersAVerb() {
        source.contents(Board6c)
        showScreen()

        awaitText(RETRY)
        viewModel.onAction(DownloadsAction.SelectionStarted)
        awaitGone(RETRY)
        focus(BLUE_EYE)
        assertEquals(BLUE_EYE, dpad.press(Dpad.Right))
    }

    /** A delete arriving from elsewhere, or confirmed in the dialog, while a row holds focus. */
    @Test
    fun aRowThatGoesWhileFocusedHandsFocusOn() {
        source.contents(Board6c)
        showScreen()

        focus(ARCANE)
        source.contents(Board6c.copy(titles = Board6c.titles.filterNot { it.id.value == ARCANE_ID }))
        awaitGone(ARCANE)
        awaitFocus(DUNE)
    }

    @Test
    fun theLastRowGoingLeavesFocusOnTheEmptyScreensTopRow() {
        source.contents(DownloadsContents(titles = listOf(title(1, "Only one"))))
        showScreen()

        focus("Only one")
        source.contents(DownloadsContents())
        awaitText(NO_DOWNLOADS)
        awaitFocus(PLAY_URL)
    }

    // ---- Coming back -----------------------------------------------------------------------------------------------

    /** Leaves composition and comes back, which is what the fragment's view does around another page. */
    private fun leaveAndComeBack() {
        onScreen = false
        rule.waitForIdle()
        rule.onNode(isFocused()).assertDoesNotExist()
        onScreen = true
        rule.waitForIdle()
        // Composed but not resumed yet: a fragment's view is in this state for a while, and takes nothing.
        rule.onNode(isFocused()).assertDoesNotExist()
        viewModel.onAction(DownloadsAction.Reappeared)
        rule.waitUntil(WAIT_MS) { viewModel.state.value.returnTo == null }
        rule.waitForIdle()
    }

    @Test
    fun focusReturnsToTheOpenedRowEvenOneThatHadToScrollIn() {
        val titles = List(40) { title(it + 1, "Row ${it + 1}") }
        source.contents(DownloadsContents(titles = titles))
        showScreen()

        focus("Row 1")
        repeat(11) { dpad.press(Dpad.Down) }
        assertEquals("Row 12", rule.focusedLabel())
        dpad.center()
        rule.waitUntil(WAIT_MS) { effects.isNotEmpty() }

        leaveAndComeBack()
        // After the return point is cleared and everything has recomposed: focus has to stay, not just arrive.
        assertEquals("Row 12", rule.focusedLabel())
        assertEquals("Row 13", dpad.press(Dpad.Down))
    }

    @Test
    fun focusReturnsToTheQueueChipFromTheQueue() {
        source.contents(Board6c)
        showScreen()

        focus(QUEUE)
        dpad.center()
        rule.waitUntil(WAIT_MS) { effects.isNotEmpty() }

        leaveAndComeBack()
        assertEquals(QUEUE, rule.focusedLabel())
    }

    @Test
    fun aRowDeletedWhileAwayGivesFocusToSelectInstead() {
        source.contents(Board6c)
        showScreen()

        focus(ARCANE)
        dpad.center()
        rule.waitUntil(WAIT_MS) { effects.isNotEmpty() }
        source.contents(Board6c.copy(titles = Board6c.titles.filterNot { it.id.value == ARCANE_ID }))

        leaveAndComeBack()
        // Its requester is not attached, so the fallback is the entry.
        assertEquals(SELECT, rule.focusedLabel())
    }

    // ---- Screenshots -----------------------------------------------------------------------------------------------

    private fun holdFocusOn(text: String) {
        focus(text)
        rule.waitForIdle()
    }

    @Test
    fun screenshotTitlesFocusOnTheRunningRow() {
        source.contents(Board6c)
        showForScreenshot()
        holdFocusOn(EXPANSE)
        rule.assertScreenshot("downloads-titles-focus-running-row")
    }

    @Test
    fun screenshotTitlesFocusOnRetry() {
        source.contents(Board6c)
        showForScreenshot()
        holdFocusOn(RETRY)
        rule.assertScreenshot("downloads-titles-failed-focus-retry")
    }

    @Test
    fun screenshotOfflineOverTitles() {
        network.value = Connectivity.OFFLINE
        source.contents(Board6c)
        showForScreenshot()
        holdFocusOn(ARCANE)
        rule.assertScreenshot("downloads-offline-over-titles")
    }

    @Test
    fun screenshotStorageFullOverTitles() {
        source.contents(Board6c.copy(storageFull = StorageShortfall(1_200_000_000), queue = DownloadQueue(0, 3)))
        showForScreenshot()
        holdFocusOn(FREE_UP)
        rule.assertScreenshot("downloads-storage-full-over-titles")
    }

    @Test
    fun screenshotEmpty() {
        source.contents(DownloadsContents(storage = Board6c.storage))
        showForScreenshot()
        holdFocusOn(BROWSE)
        rule.assertScreenshot("downloads-empty")
    }

    /** 6e exactly: nothing downloaded, no network, and not enough room, all at once. */
    @Test
    fun screenshotEmptyOfflineAndStorageFull() {
        network.value = Connectivity.OFFLINE
        source.contents(DownloadsContents(storageFull = StorageShortfall(1_200_000_000), queue = DownloadQueue(0, 3)))
        showForScreenshot()
        holdFocusOn(BROWSE)
        rule.assertScreenshot("downloads-empty-offline-storage-full")
    }

    @Test
    fun screenshotChoosing() {
        source.contents(Board6c)
        showForScreenshot()
        holdFocusOn(SELECT)
        dpad.center()
        awaitText(SELECT_ALL)
        awaitFocus(DUNE)
        dpad.center()
        awaitText("Delete (1 | 4.2 GB)")
        rule.waitForIdle()
        rule.assertScreenshot("downloads-choosing")
    }

    private class FakeSource : DownloadsSource {
        private val latest = MutableStateFlow<DownloadsContents?>(null)
        val resumed = CopyOnWriteArrayList<DownloadTitleId>()
        @Volatile var failure: Exception? = null

        fun contents(value: DownloadsContents) {
            latest.value = value
        }

        override val contents: Flow<DownloadsContents> = flow {
            failure?.let { throw it }
            latest.filterNotNull().collect { emit(it) }
        }

        override fun refresh() = Unit

        override fun resume(id: DownloadTitleId) {
            resumed += id
        }
    }

    private companion object {
        const val WAIT_MS = 10_000L
        const val MAX_PRESSES = 8
        const val POLL_MS = 100L
        const val DOWNLOADS_INDEX = 3

        const val QUEUE = "Download queue"
        const val PLAY_URL = "Play from URL"
        const val SELECT = "Select"
        const val SELECT_ALL = "Select All"
        const val CANCEL = "Cancel"
        const val RETRY = "Retry"
        const val RESUME = "Resume Download"
        const val FAILED = "Download Failed"
        const val NO_DOWNLOADS = "No downloads yet"
        const val BROWSE = "Browse library"
        const val OFFLINE = "Offline · no connection"
        const val NOT_ENOUGH = "Not enough storage"
        const val FREE_UP = "Free up space"

        const val DUNE_ID = 1
        const val DUNE = "Dune: Part Two"
        const val ARCANE_ID = 3
        const val ARCANE = "Arcane"
        const val BLUE_EYE_ID = 4
        const val BLUE_EYE = "Blue Eye Samurai"
        const val EXPANSE = "The Expanse"
        const val LONG_NAME = "The Extraordinarily Long Title Of A Series Whose Name Will Never Fit On A Row However " +
            "Wide The Television Is, Which Goes On And On Past The Point Where Anyone Would Still Be Reading It, And " +
            "Then Carries On A Good Deal Further Than That Just To Be Certain That It Runs Off The End"

        const val GB = 1_000_000_000L

        fun bytes(value: Long): String {
            val tenths = value * 10 / GB
            return "${tenths / 10}.${tenths % 10} GB"
        }

        fun title(
            id: Int,
            name: String,
            state: DownloadTitleState = DownloadTitleState.Done,
            kind: DownloadTitleKind = DownloadTitleKind.Film,
            episodes: Int = 1,
            done: Long = GB,
            total: Long = GB,
            progress: Float? = null,
        ) = DownloadTitle(DownloadTitleId(id), name, kind, episodes, done, total, state, progress)

        fun gb(tenths: Int): Long = tenths * GB / 10

        /** The board's own rows, in the order the view model sorts them. */
        val Board6c = DownloadsContents(
            titles = listOf(
                title(DUNE_ID, DUNE, done = gb(42), total = gb(42)),
                title(2, "Everything Everywhere All at Once", done = gb(68), total = gb(68)),
                title(ARCANE_ID, ARCANE, kind = DownloadTitleKind.Series, episodes = 18, done = gb(112), total = gb(112)),
                title(
                    BLUE_EYE_ID,
                    BLUE_EYE,
                    DownloadTitleState.Failed,
                    DownloadTitleKind.Series,
                    episodes = 7,
                    done = gb(54),
                    total = gb(64),
                    progress = 54f / 64f,
                ),
                title(5, "Severance", kind = DownloadTitleKind.Series, episodes = 9, done = gb(76), total = gb(76)),
                title(
                    6,
                    EXPANSE,
                    DownloadTitleState.Running,
                    DownloadTitleKind.Series,
                    episodes = 4,
                    done = gb(19),
                    total = gb(31),
                    progress = 0.61f,
                ),
                title(7, "Yellowjackets", kind = DownloadTitleKind.Series, episodes = 10, done = gb(81), total = gb(81)),
            ),
            storage = DownloadStorage(usedBytes = 384 * GB, totalBytes = 512 * GB, downloadBytes = 128 * GB),
            queue = DownloadQueue(running = 1, total = 4),
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
