package com.lagradost.cloudstream4.feature.library.tv

import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.requestFocus
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
import com.lagradost.cloudstream4.core.devicetest.solidPoster
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme
import com.lagradost.cloudstream4.feature.library.LibraryAction
import com.lagradost.cloudstream4.feature.library.LibraryBucket
import com.lagradost.cloudstream4.feature.library.LibrarySort
import com.lagradost.cloudstream4.feature.library.LibrarySortCriterion
import com.lagradost.cloudstream4.feature.library.LibrarySource
import com.lagradost.cloudstream4.feature.library.LibrarySourceId
import com.lagradost.cloudstream4.feature.library.LibrarySources
import com.lagradost.cloudstream4.feature.library.LibraryStatus
import com.lagradost.cloudstream4.feature.library.LibraryStore
import com.lagradost.cloudstream4.feature.library.LibraryTitle
import com.lagradost.cloudstream4.feature.library.LibraryTitleId
import com.lagradost.cloudstream4.feature.library.LibraryViewModel
import com.lagradost.cloudstream4.feature.library.SyncedList
import com.lagradost.cloudstream4.feature.library.WatchPosition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * `KMP-49` on a device: 5a's provider button and 5e's panel, with a fake store, fake sources and the real ViewModel.
 * Every press is a remote's.
 */
@RunWith(AndroidJUnit4::class)
class LibrarySourcesTvDeviceTest {

    /** `BUG-59`: starts and ends out of touch mode, whatever ran before it. */
    @get:Rule
    val touchMode = TouchModeReset()

    @get:Rule
    val rule = createComposeRule()

    private val dpad = DpadDriver(readFocus = { rule.focusedLabel() })
    private val sources = FakeSources()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After
    fun stopTheViewModel() = scope.cancel()

    /** The device's network as the test sets it. Unknown, which is never offline, unless a test says otherwise. */
    private val network = object : ConnectivityFlow {
        override val status = MutableStateFlow(Connectivity.UNKNOWN)
    }

    private val viewModel by lazy {
        LibraryViewModel(
            FakeStore(), scope, initialTab = LibraryTvTabs.first(), sources = sources, connectivity = network,
        )
    }

    /** Beside the rail, as the app shows it, with a fixed clock and one-colour posters. */
    private fun showScreen() {
        rule.setContent {
            val state by viewModel.state.collectAsState()
            PanelBackHost(enabled = state.sourcesOpen || state.sortOpen) {
                viewModel.onAction(if (state.sortOpen) LibraryAction.SortDismissed else LibraryAction.SourcesDismissed)
            }
            CloudStreamTheme {
                Row(Modifier.fillMaxSize().background(CloudStreamTheme.colors.bg)) {
                    NavRail(ShotRail, selectedIndex = LIBRARY_INDEX, onSelect = {})
                    LibraryScreenTv(
                        state = state,
                        onAction = viewModel::onAction,
                        modifier = Modifier.weight(1f),
                        now = { NOW },
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

    private fun awaitText(text: String) = rule.waitUntil(WAIT_MS) {
        rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }

    private fun focusTab(name: String) {
        awaitText(name)
        rule.onNode(hasText(name) and isSelectable()).requestFocus()
        rule.waitUntil(WAIT_MS) { rule.focusedLabel() == name }
    }

    private fun pressUntil(direction: Dpad, wanted: (String?) -> Boolean): String? =
        generateSequence { dpad.press(direction) }.take(MAX_PRESSES).first(wanted)

    /**
     * Up from the first tab is the chrome row, the field or the button, whichever is nearest. From the field, Left is the
     * provider button: that is `BUG-34`'s route, which used to stop on the field.
     */
    private fun focusProviderButton(name: String) {
        focusTab(FIRST_LIST)
        val landed = pressUntil(Dpad.Up) { it == name || rule.onAllNodes(Field and isFocused()).fetchSemanticsNodes().isNotEmpty() }
        if (landed == name) {
            assertTrue("the field is to the button's right", rule.onAllNodes(Field).fetchSemanticsNodes().isNotEmpty())
            dpad.press(Dpad.Right)
            rule.waitUntil(WAIT_MS) { rule.onAllNodes(Field and isFocused()).fetchSemanticsNodes().isNotEmpty() }
        }
        assertEquals(name, dpad.press(Dpad.Left))
    }

    /** With no tabs, Down from the provider button is the failure page's action. */
    private fun focusRetryFromTheButton() {
        awaitText(FAILED)
        rule.onAllNodesWithText(ANILIST).onFirst().requestFocus()
        rule.waitUntil(WAIT_MS) { rule.focusedLabel() == ANILIST }
        assertEquals(RETRY, pressUntil(Dpad.Down) { it == RETRY })
    }

    /** An unfocused card shows no title, so what is shown is read from the state. */
    private fun awaitShowing(cards: Int) = rule.waitUntil(WAIT_MS) {
        (viewModel.state.value.status as? LibraryStatus.Showing)?.cards?.size == cards
    }

    /** The sort button is the chrome row's last stop: Right from the field. */
    private fun openSortPanel() {
        focusProviderButton(ANILIST)
        dpad.press(Dpad.Right)
        rule.waitUntil(WAIT_MS) { rule.onAllNodes(Field and isFocused()).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(SORT, dpad.press(Dpad.Right))
        dpad.center()
        awaitText(SORT_PANEL)
        awaitFocusInThePanel()
    }

    private fun shownTitles() = (viewModel.state.value.status as? LibraryStatus.Showing)?.cards.orEmpty().map { it.title }

    private fun openPanel(shown: String) {
        focusProviderButton(shown)
        dpad.center()
        awaitText(SOURCE_PANEL)
        awaitFocusInThePanel()
        assertEquals(shown, rule.focusedLabel())
    }

    /**
     * BACK as a remote sends it, as `DetailScreenTvDeviceTest` measured: `UiDevice.pressBack()` finishes the activity,
     * because `SidePanelHost` takes BACK as a key event and registers no back callback.
     */
    private fun pressRemoteBack() {
        val time = SystemClock.uptimeMillis()
        listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP).forEach { action ->
            val event = KeyEvent(
                time, time, action, KeyEvent.KEYCODE_BACK, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0,
                InputDevice.SOURCE_HDMI,
            )
            check(InstrumentationRegistry.getInstrumentation().uiAutomation.injectInputEvent(event, true)) {
                "BACK was not injected"
            }
        }
    }

    /**
     * The button and the panel's row for the shown source have the same words, so where focus is tells them apart: the
     * panel is the right-hand side of the screen.
     */
    private fun awaitFocusInThePanel() = rule.waitUntil(WAIT_MS) {
        val focused = rule.onAllNodes(isFocused()).fetchSemanticsNodes().firstOrNull() ?: return@waitUntil false
        focused.boundsInRoot.left > rule.onRoot().fetchSemanticsNode().size.width / 2
    }

    @Test
    fun theProviderButtonSaysWhatIsShownAndLeftFromTheFieldReachesIt() {
        showScreen()
        awaitText(ANILIST)
        // The time the host recorded, measured to the host's clock: four minutes.
        awaitText("tidewatch · Synced 4m ago")
        focusProviderButton(ANILIST)
    }

    @Test
    fun thePanelOpensOnTheShownSourceAndListsEverySourceWithItsState() {
        showScreen()
        openPanel(ANILIST)
        awaitText("One at a time · switching reloads")
        awaitText(LOCAL)
        awaitText(MAL)
        awaitText("Not connected")
        // The local list's count, though AniList is shown: the host counted it.
        awaitText("On device · 308")
    }

    @Test
    fun choosingASourceAppliesAtOnceClosesThePanelAndShowsItsLists() {
        showScreen()
        openPanel(ANILIST)
        assertEquals(LOCAL, dpad.press(Dpad.Down))
        dpad.center()
        rule.waitUntil(WAIT_MS) { rule.onAllNodesWithText(SOURCE_PANEL).fetchSemanticsNodes().isEmpty() }
        assertEquals(listOf(LibrarySourceId(LOCAL)), sources.chosen)
        // The store's tabs, Favourites among them, and the button names what is shown now.
        awaitText("Favorites")
        awaitText("On device · 1")
        rule.waitUntil(WAIT_MS) { rule.focusedLabel() == LOCAL }
    }

    /** Disabled with its reason, and still a stop so the reason can be read: pressing it chooses nothing. */
    @Test
    fun aSourceThatIsNotConnectedIsAStopThatChoosesNothing() {
        showScreen()
        openPanel(ANILIST)
        assertEquals(MAL, pressUntil(Dpad.Down) { it == MAL })
        dpad.center()
        rule.waitForIdle()
        assertTrue(rule.onAllNodesWithText(SOURCE_PANEL).fetchSemanticsNodes().isNotEmpty())
        assertTrue(sources.chosen.isEmpty())
        assertEquals(MAL, rule.focusedLabel())
    }

    @Test
    fun doneClosesThePanelAndFocusComesBackToTheButton() {
        showScreen()
        openPanel(ANILIST)
        assertEquals(DONE, pressUntil(Dpad.Down) { it == DONE })
        dpad.center()
        rule.waitUntil(WAIT_MS) { rule.onAllNodesWithText(SOURCE_PANEL).fetchSemanticsNodes().isEmpty() }
        rule.waitUntil(WAIT_MS) { rule.focusedLabel() == ANILIST }
        assertTrue(sources.chosen.isEmpty())
    }

    @Test
    fun backClosesThePanelAndFocusComesBackToTheButton() {
        showScreen()
        openPanel(ANILIST)
        assertEquals(LOCAL, dpad.press(Dpad.Down))
        pressRemoteBack()
        rule.waitUntil(WAIT_MS) { rule.onAllNodesWithText(SOURCE_PANEL).fetchSemanticsNodes().isEmpty() }
        rule.waitUntil(WAIT_MS) { rule.focusedLabel() == ANILIST }
        assertTrue(sources.chosen.isEmpty())
    }

    /** It opens on the shown source, not on the first row: Local is the second. */
    @Test
    fun reopenedAfterAChoiceThePanelLandsOnTheNewSource() {
        showScreen()
        openPanel(ANILIST)
        assertEquals(LOCAL, dpad.press(Dpad.Down))
        dpad.center()
        rule.waitUntil(WAIT_MS) { rule.onAllNodesWithText(SOURCE_PANEL).fetchSemanticsNodes().isEmpty() }
        rule.waitUntil(WAIT_MS) { rule.focusedLabel() == LOCAL }
        dpad.center()
        awaitText(SOURCE_PANEL)
        awaitFocusInThePanel()
        rule.waitUntil(WAIT_MS) { rule.focusedLabel() == LOCAL }
    }

    @Test
    fun aServicesTabsAreItsOwnListsAndItsCardsCarryItsFacts() {
        showScreen()
        focusTab(FIRST_LIST)
        assertEquals("Rewatching", dpad.press(Dpad.Right))
        assertEquals("Dropped", dpad.press(Dpad.Right))
        assertFalse("no Favourites on a service", rule.onAllNodesWithText("Favorites").fetchSemanticsNodes().isNotEmpty())
        assertEquals("Rewatching", dpad.press(Dpad.Left))
        assertEquals(FIRST_LIST, dpad.press(Dpad.Left))
        assertEquals(SyncedNames.first(), pressUntil(Dpad.Down) { it == SyncedNames.first() })
        awaitText("12 / 28 Episodes")
        awaitText("9.1")
    }

    /** A service's list with nothing in it is 5c's page, and its way out is the service's first list with titles. */
    @Test
    fun aServicesEmptyListOffersItsFirstListWithTitles() {
        showScreen()
        focusTab("Dropped")
        dpad.center()
        awaitText("This list is empty. Try switching to another one.")
        assertEquals(FIRST_LIST, pressUntil(Dpad.Down) { it == FIRST_LIST })
        dpad.center()
        awaitShowing(SyncedNames.size)
    }

    /** A service that cannot be read is the shared failure page, and Retry reads it again. */
    @Test
    fun aServiceThatCannotBeReadFailsAndRetryReadsItAgain() {
        sources.failing = true
        showScreen()
        focusRetryFromTheButton()
        // No tabs: the store's would be the local list's, under the service's name.
        listOf(FIRST_LIST, "Completed", "Favorites").forEach { tab ->
            assertTrue(tab, rule.onAllNodesWithText(tab).fetchSemanticsNodes().isEmpty())
        }
        sources.failing = false
        dpad.center()
        awaitShowing(SyncedNames.size)
        assertEquals(2, sources.reads.get())
    }

    // ---- KMP-51: a service that cannot be read (5b), and offline ------------------------------------------------------

    /** A good read, then a failed one: the good one stays up, marked, with Retry as the button's next stop. */
    private fun showUnreachable() {
        showScreen()
        awaitShowing(SyncedNames.size)
        sources.failing = true
        rule.runOnIdle { viewModel.onAction(LibraryAction.Retry) }
        awaitText("Unreachable · cached 4m ago")
    }

    @Test
    fun aServiceThatCannotBeReadKeepsItsHeldReadMarkedAndRetryIsTheButtonsNextStop() {
        showUnreachable()
        awaitShowing(SyncedNames.size)
        assertEquals(SyncedNames.size, rule.onAllNodesWithText(CACHED).fetchSemanticsNodes().size)
        rule.onAllNodesWithText(ANILIST).onFirst().requestFocus()
        rule.waitUntil(WAIT_MS) { rule.focusedLabel() == ANILIST }
        assertEquals(SYNC_RETRY, dpad.press(Dpad.Right))

        sources.failing = false
        dpad.center()
        rule.waitUntil(WAIT_MS) { rule.onAllNodesWithText(CACHED).fetchSemanticsNodes().isEmpty() }
        awaitText("tidewatch · Synced 4m ago")
        assertEquals(3, sources.reads.get())
    }

    /** Offline with nothing held: the service is not asked, and the page says what needs the connection. */
    @Test
    fun offlineWithNothingHeldTheServiceIsNotAskedAndThePageSaysWhatNeedsAConnection() {
        network.status.value = Connectivity.OFFLINE
        showScreen()
        awaitText("You are offline")
        awaitText("AniList needs a connection")
        assertEquals(0, sources.reads.get())
    }

    @Test
    fun screenshotServiceUnreachableFocusOnRetry() {
        showUnreachable()
        rule.onAllNodesWithText(ANILIST).onFirst().requestFocus()
        rule.waitUntil(WAIT_MS) { rule.focusedLabel() == ANILIST }
        assertEquals(SYNC_RETRY, dpad.press(Dpad.Right))
        rule.assertScreenshot("library-service-unreachable-focus-retry")
    }

    // ---- KMP-50: sort (5d) ------------------------------------------------------------------------------------------

    @Test
    fun theSortButtonNamesTheAppliedSortAndItsPanelOpensOnIt() {
        showScreen()
        awaitText(UPDATED_NEW)
        openSortPanel()
        assertEquals(UPDATED_NEW, rule.focusedLabel())
        awaitText(TITLE_A)
        awaitText("Not kept by AniList")
    }

    /** One choice, applied at once with the panel still open; the same row again is the other way round. */
    @Test
    fun aRowAppliesAtOnceAndPressingItAgainTurnsItTheOtherWayRound() {
        showScreen()
        openSortPanel()
        assertEquals(TITLE_A, dpad.press(Dpad.Down))
        dpad.center()
        rule.waitUntil(WAIT_MS) { shownTitles() == SyncedNames.sortedBy { it.lowercase() } }
        assertTrue("still open", rule.onAllNodesWithText(SORT_PANEL).fetchSemanticsNodes().isNotEmpty())
        dpad.center()
        rule.waitUntil(WAIT_MS) { rule.focusedLabel() == TITLE_Z }
        rule.waitUntil(WAIT_MS) { shownTitles() == SyncedNames.sortedBy { it.lowercase() }.reversed() }
        assertEquals(
            listOf(LibrarySort(LibrarySortCriterion.Title, false), LibrarySort(LibrarySortCriterion.Title, true)),
            sources.sorted,
        )
    }

    /** Disabled with its reason, and still a stop, as a source that is not connected is in 5e. */
    @Test
    fun aCriterionTheSourceKeepsNothingForIsAStopThatChoosesNothing() {
        showScreen()
        openSortPanel()
        assertEquals(RELEASE_NEW, pressUntil(Dpad.Down) { it == RELEASE_NEW })
        dpad.center()
        rule.waitForIdle()
        assertTrue(sources.sorted.isEmpty())
        assertEquals(RELEASE_NEW, rule.focusedLabel())
    }

    /**
     * BACK closes it with the choice applied, and focus comes back to Sort, the button that opened it. Until `BUG-36`'s
     * fix this pinned the defect: focus landed on the chrome row's first stop, the provider button, because
     * `SidePanelHost` could not find a nested opener without being handed it.
     */
    @Test
    fun backClosesTheSortPanelWithTheChoiceAppliedAndFocusBackOnSort() {
        showScreen()
        openSortPanel()
        assertEquals(TITLE_A, dpad.press(Dpad.Down))
        dpad.center()
        pressRemoteBack()
        rule.waitUntil(WAIT_MS) { rule.onAllNodesWithText(SORT_PANEL).fetchSemanticsNodes().isEmpty() }
        rule.waitUntil(WAIT_MS) { rule.focusedLabel() != null }
        rule.waitForIdle()
        assertEquals("BUG-36, fixed: the button that opened it", SORT, rule.focusedLabel())
        awaitText(TITLE_A)
    }

    @Test
    fun screenshotSortPanelOnTheAppliedSort() {
        showScreen()
        openSortPanel()
        assertEquals(UPDATED_NEW, rule.focusedLabel())
        rule.assertScreenshot("library-sort-panel")
    }

    // ---- Screenshots: 5a over a service, 5e open, and a service that failed --------------------------------------------------------------

    @Test
    fun screenshotServiceGridFocusOnACard() {
        showScreen()
        focusTab(FIRST_LIST)
        assertEquals(SyncedNames.first(), pressUntil(Dpad.Down) { it == SyncedNames.first() })
        awaitText("12 / 28 Episodes")
        rule.assertScreenshot("library-service-grid-focus-card")
    }

    @Test
    fun screenshotSourcePanelOnTheShownSource() {
        showScreen()
        openPanel(ANILIST)
        awaitText("Not connected")
        rule.assertScreenshot("library-source-panel")
    }

    @Test
    fun screenshotServiceFailedFocusOnRetry() {
        sources.failing = true
        showScreen()
        focusRetryFromTheButton()
        rule.assertScreenshot("library-service-failed-focus-retry")
    }

    private companion object {
        val Field = SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText)
        const val WAIT_MS = 10_000L
        const val MAX_PRESSES = 8
        const val ANILIST = "AniList"
        const val LOCAL = "Local"
        const val MAL = "MyAnimeList"
        const val DONE = "Done"
        const val FAILED = "Error"
        const val RETRY = "Try again"
        const val SORT = "Sort"
        const val CACHED = "Cached"
        const val SYNC_RETRY = "Retry sync"
        const val SORT_PANEL = "Sort by"
        const val UPDATED_NEW = "Updated (New to Old)"
        const val TITLE_A = "Alphabetical (A to Z)"
        const val TITLE_Z = "Alphabetical (Z to A)"
        const val RELEASE_NEW = "Release Date (New to Old)"
        const val SOURCE_PANEL = "Library source"
        const val FIRST_LIST = "Watching"
        const val NOW = 10_000_000L
        const val SYNCED_AT = NOW - 4 * 60_000L

        const val LIBRARY_INDEX = 2
        val ShotRail = listOf(
            NavRailItem("Home", CloudStreamIcons.Home),
            NavRailItem("Search", CloudStreamIcons.Search),
            NavRailItem("Library", CloudStreamIcons.Library),
            NavRailItem("Downloads", CloudStreamIcons.Download),
            NavRailItem("Settings", CloudStreamIcons.Settings),
        )
        val ShotPosters = listOf(0xFF39505C.toInt(), 0xFF5C4A39.toInt(), 0xFF3F5C39.toInt())
        val SyncedNames = listOf(
            "Fernwood: Beyond the Old Road", "Still Water", "Shoal Saga", "The Long Dark", "Kingfisher", "Nine Lives",
            "Paper Moons", "Grey Harbour", "Fathom", "Saltwater",
        )
        private val Counts = listOf(12 to 28, 9 to 10, 24 to 24, 3 to 8, 6 to 12, 11 to 13, 22 to 22, 7 to 26, 3 to 24, 1 to 8)
        private val Ratings = listOf("9.1", "8.8", "8.9", null, "8.0", "7.7", "9.4", null, "7.1", "6.9")
        val Watching = SyncedNames.mapIndexed { index, name ->
            LibraryTitle(
                id = LibraryTitleId("AniList/$name"),
                title = name,
                bucket = null,
                keptAt = 1000L - index,
                providerName = ANILIST,
                typeLabel = if (index % 2 == 0) "ANIME" else "TV SERIES",
                episodesWatched = Counts[index].first,
                episodesTotal = Counts[index].second,
                rating = Ratings[index],
            )
        }
        val Rewatching = listOf(Watching[2])
    }

    private class FakeStore : LibraryStore {
        override suspend fun titles() = listOf(
            LibraryTitle(LibraryTitleId("id/Tide"), "Tide", LibraryBucket.Watching, keptAt = 1, providerName = "Source A"),
        )
        override suspend fun positions() = emptyList<WatchPosition>()
        override suspend fun remember(title: LibraryTitle) = Unit
        override suspend fun setBucket(id: LibraryTitleId, bucket: LibraryBucket?) = Unit
        override suspend fun record(position: WatchPosition) = Unit
        override suspend fun clearPositions(id: LibraryTitleId) = Unit
    }

    private class FakeSources : LibrarySources {
        @Volatile private var current = LibrarySourceId(ANILIST)
        val chosen = CopyOnWriteArrayList<LibrarySourceId>()
        val reads = AtomicInteger()
        @Volatile var failing = false

        override suspend fun sources() = listOf(
            // AniList keeps no release date here, so 5d has a disabled row to show.
            LibrarySource(
                LibrarySourceId(ANILIST), ANILIST, account = "tidewatch", syncedAt = SYNCED_AT,
                sorts = setOf(LibrarySortCriterion.Updated, LibrarySortCriterion.Title, LibrarySortCriterion.Rating),
            ),
            LibrarySource(LibrarySourceId(LOCAL), LOCAL, local = true, titleCount = 308),
            LibrarySource(LibrarySourceId(MAL), MAL, connected = false),
        )

        override suspend fun current() = current

        val sorted = CopyOnWriteArrayList<LibrarySort>()
        override suspend fun sortBy(sort: LibrarySort) {
            sorted += sort
        }

        override suspend fun choose(id: LibrarySourceId) {
            chosen += id
            current = id
        }

        override suspend fun lists(id: LibrarySourceId): List<SyncedList> {
            reads.incrementAndGet()
            if (failing) throw IOException("unreachable")
            return lists
        }

        private val lists = listOf(
            SyncedList("watching", FIRST_LIST, Watching),
            SyncedList("rewatching", "Rewatching", Rewatching),
            SyncedList("dropped", "Dropped", emptyList()),
        )
    }
}

/**
 * Registers an [OnBackPressedCallback] on the host activity for as long as [enabled], as `LibraryTvFragment` does for
 * the real app, and as `DetailScreenTvDeviceTest`'s does: `SidePanelHost` leaves platform BACK to its host.
 */
@Composable
private fun PanelBackHost(enabled: Boolean, onBack: () -> Unit) {
    val activity = LocalContext.current as ComponentActivity
    val latest by rememberUpdatedState(onBack)
    val callback = remember {
        object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() = latest()
        }
    }
    DisposableEffect(activity, callback) {
        activity.onBackPressedDispatcher.addCallback(callback)
        onDispose { callback.remove() }
    }
    LaunchedEffect(enabled) { callback.isEnabled = enabled }
}
