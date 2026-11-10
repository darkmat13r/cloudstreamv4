package com.lagradost.cloudstream4.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream4.assertPageBar
import com.lagradost.cloudstream4.DesktopApp
import com.lagradost.cloudstream4.EscapeHandler
import com.lagradost.cloudstream4.core.designsystem.desktop.PosterSize
import com.lagradost.cloudstream4.core.designsystem.desktop.desktopTypography
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme
import com.lagradost.cloudstream4.core.theme.LocalCloudStreamTypography
import com.lagradost.cloudstream4.feature.home.HomePageReply
import com.lagradost.cloudstream4.feature.home.HomeSource
import com.lagradost.cloudstream4.feature.home.HomeSourceId
import com.lagradost.cloudstream4.feature.home.HomeSources
import com.lagradost.cloudstream4.feature.library.LibraryAction
import com.lagradost.cloudstream4.feature.library.LibraryBucket
import com.lagradost.cloudstream4.feature.library.LibraryCard
import com.lagradost.cloudstream4.feature.library.LibraryResume
import com.lagradost.cloudstream4.feature.library.LibrarySort
import com.lagradost.cloudstream4.feature.library.LibrarySortCriterion
import com.lagradost.cloudstream4.feature.library.LibrarySource
import com.lagradost.cloudstream4.feature.library.LibrarySourceId
import com.lagradost.cloudstream4.feature.library.LibraryStale
import com.lagradost.cloudstream4.feature.library.LibraryState
import com.lagradost.cloudstream4.feature.library.LibraryStatus
import com.lagradost.cloudstream4.feature.library.LibraryTab
import com.lagradost.cloudstream4.feature.library.LibraryTitle
import com.lagradost.cloudstream4.feature.library.LibraryTitleId
import com.lagradost.cloudstream4.feature.library.WatchPosition
import com.lagradost.cloudstream4.feature.library.desktop.LibraryScreenDesktop
import com.lagradost.cloudstream4.feature.library.desktop.LibraryScreenDesktopDefaults
import com.lagradost.cloudstream4.detail.DesktopDetailViewModels
import com.lagradost.cloudstream4.home.DesktopShortcuts
import com.lagradost.cloudstream4.home.TopLevelDestination
import java.io.File
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.EncodedImageFormat

/**
 * `DS-22`'s Library boards, L1 to L7, through real pointer and key events in the scene, plus a PNG of each state when
 * `cloudstream.library.renders` names a directory. Every coordinate below was read off those renders.
 */
@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class LibraryScreenTest {

    private val tide = LibraryTitleId("Prov\u0000https://example.com/tide")
    private val mooring = LibraryTitleId("Prov\u0000https://example.com/mooring")

    private val local = LibrarySource(
        LibrarySourceId("Local"),
        "Local",
        local = true,
        titleCount = 2,
        sorts = setOf(LibrarySortCriterion.Updated, LibrarySortCriterion.Title),
    )
    private val aniList = LibrarySource(
        LibrarySourceId("AniList"),
        "AniList",
        account = "tidewatch",
        syncedAt = NOW - 2 * HOUR,
    )
    private val mal = LibrarySource(LibrarySourceId("MyAnimeList"), "MyAnimeList", connected = false)

    private fun card(
        id: LibraryTitleId,
        title: String,
        bucket: LibraryBucket? = null,
        progress: Float? = null,
        resume: LibraryResume? = null,
    ) = LibraryCard(id, title, posterUrl = null, providerName = "Prov", bucket, progress, resume)

    private fun state(status: LibraryStatus, tab: LibraryTab = LibraryTab.ContinueWatching) =
        LibraryState(tab = tab, status = status)

    /** As the window has it since `KMP-57`: the local list as the one source, and the lists counted. */
    private fun LibraryState.sourced(shown: LibrarySource = local) = copy(
        sources = listOf(local, aniList, mal),
        source = shown.id,
        counts = mapOf(LibraryBucket.Watching to 2, LibraryBucket.Completed to 1),
        totalTitles = 3,
    )

    private fun scene(width: Int = WIDTH, height: Int = HEIGHT, content: @Composable () -> Unit) =
        ImageComposeScene(width, height, Density(1f)) {
            CloudStreamTheme {
                CompositionLocalProvider(LocalCloudStreamTypography provides desktopTypography()) {
                    Box(Modifier.fillMaxSize().background(CloudStreamTheme.colors.bg)) { content() }
                }
            }
        }

    private fun ImageComposeScene.settle() = repeat(SETTLE_FRAMES) { render(it * FRAME_NANOS) }

    private fun ImageComposeScene.click(at: Offset) {
        sendPointerEvent(PointerEventType.Move, at)
        sendPointerEvent(PointerEventType.Press, at, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
        sendPointerEvent(PointerEventType.Release, at, button = PointerButton.Primary)
        settle()
    }

    /** A Mac's secondary click: what opens a thing's menu. */
    private fun ImageComposeScene.secondaryClick(at: Offset) {
        sendPointerEvent(PointerEventType.Move, at)
        sendPointerEvent(PointerEventType.Press, at, buttons = PointerButtons(isSecondaryPressed = true), button = PointerButton.Secondary)
        sendPointerEvent(PointerEventType.Release, at, button = PointerButton.Secondary)
        settle()
    }

    private fun ImageComposeScene.hover(at: Offset) {
        sendPointerEvent(PointerEventType.Move, at)
        settle()
    }

    private fun ImageComposeScene.press(key: Key, shift: Boolean = false) {
        sendKeyEvent(KeyEvent(key, KeyEventType.KeyDown, isShiftPressed = shift))
        sendKeyEvent(KeyEvent(key, KeyEventType.KeyUp, isShiftPressed = shift))
        settle()
    }

    private fun screen(
        state: LibraryState,
        onAction: (LibraryAction) -> Unit = {},
        width: Int = WIDTH,
        height: Int = HEIGHT,
    ) = scene(width, height) {
        LibraryScreenDesktop(state, onAction, now = { NOW }, artwork = { Box(Modifier.fillMaxSize().background(ART)) })
    }

    private fun ImageComposeScene.save(name: String) {
        val directory = System.getProperty("cloudstream.library.renders") ?: return
        settle()
        val png = checkNotNull(render(SETTLE_FRAMES * FRAME_NANOS).encodeToData(EncodedImageFormat.PNG)).bytes
        File(directory, "$name.png").writeBytes(png)
    }

    // ---- The lists (L1, L3, L7) ----

    @Test
    fun `Library starts with the page bar, 52 tall with a hairline under it (KMP-98, L1)`() {
        // The source, the filter and Sort, in the one band every Mac page has.
        val scene = screen(state(LibraryStatus.Empty).sourced())
        scene.settle()
        scene.assertPageBar(WIDTH, atNanos = SETTLE_FRAMES * FRAME_NANOS)
        scene.close()
    }

    @Test
    fun `a click on the second list name selects it`() {
        val actions = mutableListOf<LibraryAction>()
        val scene = screen(state(LibraryStatus.Empty), { actions += it })
        scene.settle()
        scene.click(Offset(SECOND_TAB_X, TAB_Y))
        assertEquals(
            listOf<LibraryAction>(LibraryAction.TabSelected(LibraryTab.Bucket(LibraryBucket.Watching))),
            actions,
        )
        scene.close()
    }

    @Test
    fun `the empty state keeps the list names reachable`() {
        val actions = mutableListOf<LibraryAction>()
        val scene = screen(state(LibraryStatus.Empty, LibraryTab.Bucket(LibraryBucket.Dropped)), { actions += it })
        scene.settle()
        scene.click(Offset(FIRST_TAB_X, TAB_Y))
        assertEquals(
            listOf<LibraryAction>(LibraryAction.TabSelected(LibraryTab.ContinueWatching)),
            actions,
        )
        scene.close()
    }

    @Test
    fun `an empty list offers the list that has something (L3)`() {
        val actions = mutableListOf<LibraryAction>()
        val empty = state(LibraryStatus.Empty, LibraryTab.Bucket(LibraryBucket.OnHold)).sourced()
        val scene = screen(empty, { actions += it })
        scene.settle()
        scene.save("library-l3-empty-list")
        scene.click(Offset(CENTRE_X, EMPTY_ACTION_Y))
        // Watching is the first list with titles in it, so it is the one offered.
        assertEquals(
            listOf<LibraryAction>(LibraryAction.TabSelected(LibraryTab.Bucket(LibraryBucket.Watching))),
            actions,
        )
        scene.close()
    }

    @Test
    fun `with nothing kept anywhere, the empty list offers search instead`() {
        val actions = mutableListOf<LibraryAction>()
        val scene = screen(state(LibraryStatus.Empty, LibraryTab.Bucket(LibraryBucket.OnHold)), { actions += it })
        scene.settle()
        scene.save("library-empty")
        scene.click(Offset(CENTRE_X, EMPTY_ACTION_Y))
        assertEquals(listOf<LibraryAction>(LibraryAction.SearchRequested), actions)
        scene.close()
    }

    @Test
    fun `a failure keeps them reachable too`() {
        val actions = mutableListOf<LibraryAction>()
        val failed = LibraryStatus.Failed("permission denied")
        val scene = screen(state(failed), { actions += it })
        scene.settle()
        scene.save("library-failed")
        scene.click(Offset(SECOND_TAB_X, TAB_Y))
        assertTrue(actions.isNotEmpty())
        scene.close()
    }

    @Test
    fun `a Greek list name does not push the names off the row`() {
        val previous = Locale.getDefault()
        Locale.setDefault(Locale.forLanguageTag("el"))
        try {
            val actions = mutableListOf<LibraryAction>()
            val scene = screen(state(LibraryStatus.Empty), { actions += it }, MIN_SCREEN_WIDTH, MIN_HEIGHT)
            scene.settle()
            scene.save("library-minimum-window-el")
            // The strip scrolls, so the first name is still where it was and still answers.
            scene.click(Offset(FIRST_TAB_X, TAB_Y))
            assertEquals(
                listOf<LibraryAction>(LibraryAction.TabSelected(LibraryTab.ContinueWatching)),
                actions,
            )
            scene.close()
        } finally {
            Locale.setDefault(previous)
        }
    }

    // ---- Cards (L1) ----

    @Test
    fun `a click on a card on continue watching resumes it`() {
        val actions = mutableListOf<LibraryAction>()
        val cards = listOf(card(tide, "Tide", resume = LibraryResume("e1", 60_000L), progress = 0.1f))
        val scene = screen(state(LibraryStatus.Showing(cards)), { actions += it })
        scene.settle()
        scene.click(Offset(CARD_X, CARD_Y))
        assertEquals(listOf<LibraryAction>(LibraryAction.ResumeActivated(tide)), actions)
        scene.close()
    }

    @Test
    fun `a click on a card in a list opens the title instead`() {
        val actions = mutableListOf<LibraryAction>()
        val cards = listOf(card(tide, "Tide", bucket = LibraryBucket.Watching))
        val tab = LibraryTab.Bucket(LibraryBucket.Watching)
        val scene = screen(state(LibraryStatus.Showing(cards), tab), { actions += it })
        scene.settle()
        scene.click(Offset(CARD_X, CARD_Y))
        assertEquals(listOf<LibraryAction>(LibraryAction.TitleActivated(tide)), actions)
        scene.close()
    }

    @Test
    fun `hovering a card shows Remove on its poster, and Remove removes that card without opening it`() {
        val actions = mutableListOf<LibraryAction>()
        val cards = listOf(card(tide, "Tide", LibraryBucket.Watching), card(mooring, "Mooring", LibraryBucket.Watching))
        val tab = LibraryTab.Bucket(LibraryBucket.Watching)
        val scene = screen(state(LibraryStatus.Showing(cards), tab).sourced(), { actions += it })
        scene.settle()
        val remove = Offset(removeX(card = 1), REMOVE_Y)
        // Not drawn until the pointer is on the card: the art is all there is at Remove's place.
        assertEquals(ART.toArgb(), scene.pixel(remove.x.toInt(), remove.y.toInt()))
        scene.hover(Offset(cardCentreX(1), CARD_Y))
        assertNotEquals(ART.toArgb(), scene.pixel(remove.x.toInt(), remove.y.toInt()))
        scene.save("library-l1-hovered-remove")
        actions.clear()
        scene.click(remove)
        assertEquals(listOf<LibraryAction>(LibraryAction.RemoveRequested(mooring)), actions)
        scene.close()
    }

    @Test
    fun `a secondary click on a card opens the list picker for that card and not another`() {
        val actions = mutableListOf<LibraryAction>()
        val cards = listOf(card(tide, "Tide"), card(mooring, "Mooring"))
        val scene = screen(state(LibraryStatus.Showing(cards)), { actions += it })
        scene.settle()
        scene.secondaryClick(Offset(cardCentreX(1), CARD_Y))
        // And only that: the press that opens the menu does not also resume or open the title.
        assertEquals(listOf<LibraryAction>(LibraryAction.PickerOpened(mooring)), actions)
        scene.close()
    }

    @Test
    fun `the context-menu keys open the picker for the card Tab reached`() {
        val actions = mutableListOf<LibraryAction>()
        val cards = listOf(card(tide, "Tide"), card(mooring, "Mooring"))
        val scene = screen(state(LibraryStatus.Showing(cards)), { actions += it })
        scene.settle()
        // The filter, Sort, the seven lists, then the first card.
        repeat(TABS_TO_FIRST_CARD) { scene.press(Key.Tab) }
        scene.save("library-keyboard-card")
        scene.press(Key.F10, shift = true)
        assertEquals(listOf<LibraryAction>(LibraryAction.PickerOpened(tide)), actions)
        actions.clear()
        scene.press(Key.Menu)
        assertEquals(listOf<LibraryAction>(LibraryAction.PickerOpened(tide)), actions)
        scene.close()
    }

    @Test
    fun `progress draws over the art, and no progress draws nothing`() {
        // Found by scanning rather than by a constant: a token change should move the bar, not fail here.
        val without = screen(state(LibraryStatus.Showing(listOf(card(tide, "Tide")))))
        without.settle()
        val bottom = without.lastArtRow()
        assertEquals(ART.toArgb(), without.pixel(CARD_X.toInt(), bottom))
        without.close()

        val withBar = screen(state(LibraryStatus.Showing(listOf(card(tide, "Tide", progress = 1f)))))
        withBar.settle()
        assertTrue(withBar.pixel(CARD_X.toInt(), bottom) != ART.toArgb())
        // And it is only the last few rows: a bar that covered the poster would be a layout bug.
        assertEquals(ART.toArgb(), withBar.pixel(CARD_X.toInt(), bottom - PROGRESS_CLEARANCE))
        withBar.close()
    }

    // ---- The bar (L1, L7) ----

    @Test
    fun `the bar opens the source and Sort panels`() {
        val actions = mutableListOf<LibraryAction>()
        val scene = screen(state(LibraryStatus.Showing(listOf(card(tide, "Tide")))).sourced(), { actions += it })
        scene.settle()
        scene.click(Offset(SOURCE_BUTTON_X, BAR_Y))
        scene.click(Offset(SORT_BUTTON_X, BAR_Y))
        assertEquals(listOf(LibraryAction.SourcesOpened, LibraryAction.SortOpened), actions)
        scene.close()
    }

    @Test
    fun `Escape in the filter empties it`() {
        val actions = mutableListOf<LibraryAction>()
        val filtered = state(LibraryStatus.Showing(listOf(card(tide, "Tide")))).copy(filter = "tide")
        val scene = screen(filtered, { actions += it })
        scene.settle()
        scene.click(Offset(FILTER_X, BAR_Y))
        scene.press(Key.Escape)
        assertEquals(listOf<LibraryAction>(LibraryAction.FilterChanged("")), actions)
        scene.close()
    }

    @Test
    fun `in the minimum window the filter gives way, unless it holds words (L7)`() {
        val actions = mutableListOf<LibraryAction>()
        val cards = listOf(card(tide, "Tide"))
        val bare = screen(state(LibraryStatus.Showing(cards)).sourced(), { actions += it }, MIN_SCREEN_WIDTH, MIN_HEIGHT)
        bare.settle()
        bare.save("library-l7-minimum-window")
        // Where the field would end, beside Sort, there is only the bar.
        bare.click(Offset(MIN_FILTER_X, BAR_Y))
        bare.press(Key.Escape)
        assertTrue(actions.none { it is LibraryAction.FilterChanged })
        bare.close()

        val kept = screen(
            state(LibraryStatus.Showing(cards)).sourced().copy(filter = "du"),
            { actions += it },
            MIN_SCREEN_WIDTH,
            MIN_HEIGHT,
        )
        kept.settle()
        kept.save("library-l7-minimum-window-filtered")
        kept.click(Offset(MIN_FILTER_X, BAR_Y))
        kept.press(Key.Escape)
        assertEquals(LibraryAction.FilterChanged(""), actions.last())
        kept.close()
    }

    // ---- A sync that failed (L2) ----

    @Test
    fun `a failed sync keeps the cards, dims them, and offers the read again (L2)`() {
        val actions = mutableListOf<LibraryAction>()
        val cards = listOf(card(tide, "Tide", LibraryBucket.Watching))
        // A service's own lists, as the ViewModel gives them: its tabs are those, not the store's.
        val watching = LibraryTab.Synced("CURRENT", "Watching")
        val held = state(LibraryStatus.Showing(cards), watching)
            .sourced(aniList)
            .copy(
                stale = LibraryStale.Unreachable,
                syncedTabs = listOf(watching, LibraryTab.Synced("COMPLETED", "Completed")),
                syncedCounts = mapOf("CURRENT" to 1, "COMPLETED" to 0),
            )
        val scene = screen(held, { actions += it })
        scene.settle()
        scene.save("library-l2-sync-failed")
        // Dimmed: the art is still drawn where the card is, and it is no longer the art's own colour.
        assertNotEquals(ART.toArgb(), scene.pixel(CARD_X.toInt(), HELD_CARD_Y))
        scene.click(Offset(RETRY_SYNC_X, BANNER_Y))
        assertEquals(listOf<LibraryAction>(LibraryAction.Retry), actions)
        scene.close()
    }

    // ---- Sort (L4) ----

    @Test
    fun `Sort lists every criterion, and one the source does not keep is greyed and takes no press (L4)`() {
        val actions = mutableListOf<LibraryAction>()
        val open = state(LibraryStatus.Showing(listOf(card(tide, "Tide")))).sourced().copy(sortOpen = true)
        val scene = screen(open, { actions += it })
        scene.settle()
        scene.save("library-l4-sort")
        // The applied sort is the selected row; a greyed one is not.
        assertTrue(scene.isSelected("Updated (New to Old)"))
        assertTrue(!scene.isSelected("Rating (High to Low)"))
        scene.click(Offset(PANEL_X, SORT_TITLE_ROW_Y))
        scene.click(Offset(PANEL_X, SORT_RATING_ROW_Y))
        // Title answers; Your rating stays in the list with its reason and does nothing.
        assertEquals(listOf<LibraryAction>(LibraryAction.SortChosen(LibrarySortCriterion.Title)), actions)
        actions.clear()
        scene.click(Offset(PANEL_BUTTON_X, PANEL_BUTTON_Y))
        assertEquals(listOf<LibraryAction>(LibraryAction.SortDismissed), actions)
        scene.close()
    }

    // ---- The library's source (L5) ----

    @Test
    fun `the source panel chooses a connected source and refuses one that is not (L5)`() {
        val actions = mutableListOf<LibraryAction>()
        val open = state(LibraryStatus.Showing(listOf(card(tide, "Tide")))).sourced().copy(sourcesOpen = true)
        val scene = screen(open, { actions += it })
        scene.settle()
        scene.save("library-l5-source")
        scene.click(Offset(PANEL_X, MAL_ROW_Y))
        scene.click(Offset(PANEL_X, ANILIST_ROW_Y))
        assertEquals(listOf<LibraryAction>(LibraryAction.SourceChosen(aniList.id)), actions)
        actions.clear()
        scene.click(Offset(CARD_X, CARD_Y))
        assertEquals(listOf<LibraryAction>(LibraryAction.SourcesDismissed), actions)
        scene.close()
    }

    // ---- Moving a card (L6) ----

    @Test
    fun `the picker offers every list and the way out of all of them (L6)`() {
        val actions = mutableListOf<LibraryAction>()
        val cards = listOf(card(tide, "Tide", bucket = LibraryBucket.Completed))
        val open = state(LibraryStatus.Showing(cards)).copy(picking = tide)
        val scene = screen(open, { actions += it })
        scene.settle()
        scene.save("library-picker")
        // The card's own list is the selected row, and only that one.
        assertTrue(scene.isSelected("Completed"))
        assertTrue(!scene.isSelected("Watching"))
        // Each row of the panel in turn: the six lists, then Remove.
        LibraryBucket.entries.forEachIndexed { index, bucket ->
            actions.clear()
            scene.click(Offset(PANEL_X, FIRST_PANEL_ROW_Y + index * PANEL_ROW_STEP))
            assertEquals(LibraryAction.BucketChosen(tide, bucket), actions.single())
        }
        actions.clear()
        scene.click(Offset(PANEL_X, PICKER_REMOVE_Y))
        assertEquals(listOf<LibraryAction>(LibraryAction.RemoveRequested(tide)), actions)
        scene.close()
    }

    @Test
    fun `a click outside the picker closes it`() {
        val actions = mutableListOf<LibraryAction>()
        val open = state(LibraryStatus.Showing(listOf(card(tide, "Tide")))).copy(picking = tide)
        val scene = screen(open, { actions += it })
        scene.settle()
        scene.click(Offset(CARD_X, CARD_Y))
        assertEquals(listOf<LibraryAction>(LibraryAction.PickerDismissed), actions)
        scene.close()
    }

    @Test
    fun `the renders are written`() {
        val cards = listOf(
            card(tide, "Tide", resume = LibraryResume("e1", 60_000L), progress = 0.4f),
            card(mooring, "Mooring", bucket = LibraryBucket.Completed, progress = 1f),
            card(LibraryTitleId("Prov\u0000c"), "A title with a name long enough to need two lines of label"),
        )
        screen(state(LibraryStatus.Showing(cards))).use { it.save("library-continue") }
        screen(state(LibraryStatus.Showing(cards), LibraryTab.Bucket(LibraryBucket.Watching)).sourced())
            .use { it.save("library-l1-list") }
        screen(state(LibraryStatus.Showing(cards)).sourced().copy(picking = mooring))
            .use { it.save("library-l6-picker-counted") }
        screen(state(LibraryStatus.Loading)).use { it.save("library-loading") }
        screen(state(LibraryStatus.Showing(cards)), width = MIN_SCREEN_WIDTH, height = MIN_HEIGHT)
            .use { it.save("library-minimum-window") }
    }

    // ---- In the window ----

    /** `KMP-77` over `KMP-75`: ⌘4 works on a title's page too, and leaves it for Downloads, letting the title go. */
    @Test
    fun `command 4 from a title opened from the library lands on Downloads and lets the title go`() {
        var at = ""
        val store = InMemoryLibraryStore(
            listOf(LibraryTitle(tide, "Quiet Harbor", LibraryBucket.Watching, keptAt = 1L, providerName = "Source One")),
            // Resumable, so its card is on Continue watching, where the window opens the library, at the card's place.
            listOf(WatchPosition(tide, "e1", 60_000L, 600_000L, 1L)),
        )
        val shortcuts = DesktopShortcuts()
        val escape = EscapeHandler()
        val scene = scene { DesktopApp(escape, EmptyHome, library = store, onScreen = { at = it }, shortcuts = shortcuts) }
        scene.settle()
        scene.click(Offset(SIDEBAR_ITEM_X, LIBRARY_ITEM_Y))
        scene.wait()
        assertEquals("Library", at)
        scene.click(Offset(WINDOW_CARD_X, WINDOW_CARD_Y))
        scene.wait()
        assertTrue(at.startsWith("Detail"), at)
        val id = checkNotNull(Regex("""Detail\(id=(.*), resume=""").find(at)).groupValues[1]
        assertTrue(DesktopDetailViewModels.isLive(id), "the open title keeps its view model")
        shortcuts.go(TopLevelDestination.Downloads)
        scene.settle()
        assertEquals("Downloads", at, "command 4 leaves the title for Downloads")
        assertFalse(escape.handle(), "and nothing is left under Downloads")
        assertFalse(DesktopDetailViewModels.isLive(id), "and the title's view model went with it")
        scene.close()
    }

    @Test
    fun `the window has a library tab, and it shows what the store holds`() {
        var at = ""
        val store = InMemoryLibraryStore(
            listOf(
                LibraryTitle(tide, "Tide", LibraryBucket.Watching, keptAt = 1L, providerName = "Prov"),
            ),
            listOf(WatchPosition(tide, "e1", 60_000L, 600_000L, 1L)),
        )
        val escape = EscapeHandler()
        val scene = scene { DesktopApp(escape, EmptyHome, library = store, onScreen = { at = it }) }
        scene.settle()
        assertEquals("Home", at)

        scene.click(Offset(SIDEBAR_ITEM_X, LIBRARY_ITEM_Y))
        scene.wait()
        assertEquals("Library", at)
        scene.save("library-in-window")
        // The one resumable position is on screen, so its card answers a secondary click with the picker.
        scene.secondaryClick(Offset(WINDOW_CARD_X, WINDOW_CARD_Y))
        scene.save("library-in-window-picker")

        // And the picker is what Escape now closes.
        assertTrue(escape.handle())
        scene.settle()
        assertTrue(!escape.handle())
        scene.close()
    }

    @Test
    fun `in the window, Sort reorders the list, and Escape closes the panel`() {
        val store = InMemoryLibraryStore(
            listOf(
                LibraryTitle(mooring, "Mooring", LibraryBucket.Watching, keptAt = 1L, providerName = "Prov"),
                LibraryTitle(tide, "Tide", LibraryBucket.Watching, keptAt = 2L, providerName = "Prov"),
            ),
            emptyList(),
        )
        val actions = mutableListOf<String>()
        val escape = EscapeHandler()
        val scene = scene {
            DesktopApp(
                escape,
                EmptyHome,
                library = store,
                onScreen = { actions += it },
                librarySort = RecordingSortMemory,
            )
        }
        scene.settle()
        scene.click(Offset(SIDEBAR_ITEM_X, LIBRARY_ITEM_Y))
        scene.wait()
        scene.click(Offset(WINDOW_SECOND_TAB_X, WINDOW_TAB_Y))
        scene.wait()
        // Last updated first: Tide was changed last.
        val first = scene.pixelRow(WINDOW_TITLE_Y)
        scene.click(Offset(WINDOW_SORT_X, WINDOW_BAR_Y))
        scene.wait()
        scene.save("library-in-window-sort")
        scene.click(Offset(WINDOW_PANEL_X, WINDOW_SORT_TITLE_ROW_Y))
        scene.wait()
        assertEquals(LibrarySort(LibrarySortCriterion.Title, descending = false), RecordingSortMemory.kept)
        assertTrue(escape.handle())
        scene.wait()
        scene.save("library-in-window-sorted")
        // A to Z: Mooring first now, so the first card's words are not what they were.
        assertNotEquals(first, scene.pixelRow(WINDOW_TITLE_Y))
        assertTrue(!escape.handle())
        scene.close()
    }

    private object RecordingSortMemory : LibrarySortMemory {
        @Volatile
        var kept: LibrarySort? = null

        override fun last(): LibrarySort? = null

        override fun remember(sort: LibrarySort) {
            kept = sort
        }
    }

    private object EmptyHome : HomeSource {
        override suspend fun sources() = HomeSources(emptyList(), installed = 0)

        override suspend fun page(source: HomeSourceId, section: Int, page: Int) = HomePageReply.Offline
    }

    private fun ImageComposeScene.wait() = repeat(WAIT_ROUNDS) {
        Thread.sleep(WAIT_MILLIS)
        settle()
    }

    /** The lowest row of the first card that is still artwork. */
    private fun ImageComposeScene.lastArtRow(): Int {
        val bitmap = Bitmap.makeFromImage(render(SETTLE_FRAMES * FRAME_NANOS))
        val column = CARD_X.toInt()
        val found = (HEIGHT - 1 downTo 0).firstOrNull { bitmap.getColor(column, it) == ART.toArgb() }
        return checkNotNull(found) { "no artwork was drawn at x=$column" }
    }

    /**
     * `KMP-90e`: Poster size sets the smallest card, 160, 200 or 240 here, a step wider than Search's. Medium is the
     * grid as it was.
     */
    @Test
    fun `Poster size changes how many cards fit across, and Medium is the grid as it was`() {
        val cards = List(8) { card(LibraryTitleId("t$it"), "Shelf title $it") }
        val shown = state(LibraryStatus.Showing(cards))
        val expected = mapOf(
            760 to listOf(PosterSize.Small to 4, PosterSize.Medium to 3, PosterSize.Large to 2),
            1280 to listOf(PosterSize.Small to 7, PosterSize.Medium to 5, PosterSize.Large to 4),
        )
        expected.forEach { (width, sizes) ->
            sizes.forEach { (size, columns) ->
                scene(width) {
                    LibraryScreenDesktop(shown, {}, now = { NOW }, artwork = { Box(Modifier.fillMaxSize().background(ART)) }, posterSize = size)
                }.use { scene ->
                    assertEquals(columns, scene.firstLine("Shelf title"), "$size at $width")
                    if (width == WIDTH) scene.save("poster-size-${size.name.lowercase()}")
                }
            }
        }
        screen(shown).use { scene -> assertEquals(5, scene.firstLine("Shelf title"), "no posterSize is Medium") }
    }

    /** How many cards the first line of [prefix]'s titles holds, read from what a screen reader is told. */
    private fun ImageComposeScene.firstLine(prefix: String): Int {
        settle()
        fun SemanticsNode.every(): List<SemanticsNode> = listOf(this) + children.flatMap { it.every() }
        val tops = semanticsOwners.flatMap { it.rootSemanticsNode.every() }
            .filter { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text.startsWith(prefix) } }
            .map { it.boundsInRoot }
        val first = tops.minOf { it.top }
        return tops.filter { it.top == first }.map { it.left }.distinct().size
    }

    private fun ImageComposeScene.pixel(x: Int, y: Int): Int =
        Bitmap.makeFromImage(render(SETTLE_FRAMES * FRAME_NANOS)).getColor(x, y)

    /** One row of pixels across the first card's words, to tell one title from another without reading text. */
    private fun ImageComposeScene.pixelRow(y: Int): List<Int> {
        val bitmap = Bitmap.makeFromImage(render(SETTLE_FRAMES * FRAME_NANOS))
        return (WINDOW_CARD_LEFT until WINDOW_CARD_LEFT + WORDS_SPAN).map { bitmap.getColor(it, y) }
    }

    /**
     * Whether the choice whose words include [text] is said to be selected, read from the scene's merged semantics
     * tree: what a screen reader is told, not what is drawn. Fails unless exactly one choice carries those words, so a
     * row that says nothing about selection fails rather than reading as unselected.
     */
    private fun ImageComposeScene.isSelected(text: String): Boolean {
        settle()
        val found = semanticsOwners.flatMap { owner -> owner.rootSemanticsNode.all() }
            // Only rows that say whether they are selected: a list's button behind the panel has the same words.
            .filter { node -> SemanticsProperties.Selected in node.config }
            .filter { node ->
                node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == text }
            }
        val node = found.singleOrNull() ?: error("expected one choice reading \"$text\", found ${found.size}")
        return node.config[SemanticsProperties.Selected]
    }

    private fun SemanticsNode.all(): List<SemanticsNode> = listOf(this) + children.flatMap { it.all() }

    private inline fun ImageComposeScene.use(block: (ImageComposeScene) -> Unit) {
        try {
            block(this)
        } finally {
            close()
        }
    }

    private fun cardWidth(): Float {
        val width = WIDTH.dp - MARGIN * 2
        return LibraryScreenDesktopDefaults.cardWidth(width, LibraryScreenDesktopDefaults.columns(width)).value
    }

    private fun cardCentreX(card: Int): Float = MARGIN.value + card * (cardWidth() + GAP.value) + cardWidth() / 2

    /** Inside Remove, which sits a related gap in from the poster's top-right. */
    private fun removeX(card: Int): Float = MARGIN.value + card * (cardWidth() + GAP.value) + cardWidth() - REMOVE_INSET

    private companion object {
        const val WIDTH = 1280
        const val HEIGHT = 800
        /** The minimum window, 800, less the 220 sidebar. */
        const val MIN_SCREEN_WIDTH = 580
        const val MIN_HEIGHT = 520
        const val SETTLE_FRAMES = 30
        const val FRAME_NANOS = 50_000_000L
        const val NOW = 1_800_000_000_000L
        const val HOUR = 3_600_000L

        /** Something no theme colour is, so a pixel read can tell artwork from anything drawn over it. */
        val ART = Color(0xFF00FF80)

        val MARGIN = 24.dp
        val GAP = 16.dp

        /** The bar: 8 above a 36-high control. */
        const val BAR_Y = 26f
        const val SOURCE_BUTTON_X = 120f
        const val SORT_BUTTON_X = 1216f
        const val FILTER_X = 900f
        /** In a 580 screen, just ahead of Sort's leading edge. */
        const val MIN_FILTER_X = 280f

        /** The rule at 52, a margin, then the 36-high list buttons: their middle. */
        const val TAB_Y = 95f
        const val FIRST_TAB_X = 60f
        const val SECOND_TAB_X = 250f

        /** A margin under the lists. */
        const val CARD_X = 60f
        const val CARD_Y = 260f
        /** Remove: a related gap in from the poster's corner, 36 high. */
        const val REMOVE_Y = 163f
        const val REMOVE_INSET = 24f

        /** L2's banner sits between the lists and the cards, which it pushes down by its height and a gap. */
        const val BANNER_Y = 156f
        const val RETRY_SYNC_X = 1180f
        const val HELD_CARD_Y = 320

        /** L3's prompt, in the middle of what is under the lists. */
        const val CENTRE_X = 640f
        const val EMPTY_ACTION_Y = 549f

        /** Inside the panel, which is 360 wide and against the trailing edge. */
        const val PANEL_X = 1100f
        const val PANEL_BUTTON_X = 1220f
        const val PANEL_BUTTON_Y = 30f
        const val FIRST_PANEL_ROW_Y = 104f
        const val PANEL_ROW_STEP = 48f
        /** The source panel's rows each carry a line under the name: the middle of each name, off the render. */
        const val ANILIST_ROW_Y = 152f
        const val MAL_ROW_Y = 201f
        /** Six bare rows, then a rule with a related gap either side, then Remove. */
        const val PICKER_REMOVE_Y = 409f
        /** Last updated and Title are bare; Release date and Your rating carry their reason. */
        const val SORT_TITLE_ROW_Y = 152f
        const val SORT_RATING_ROW_Y = 248f

        /** In the window, the screen sits beside the 220 sidebar (`KMP-53`): its third row is Library. */
        const val SIDEBAR_ITEM_X = 100f
        const val LIBRARY_ITEM_Y = 140f
        const val WINDOW_CARD_X = 340f
        const val WINDOW_CARD_Y = 260f
        const val WINDOW_BAR_Y = 26f
        const val WINDOW_SORT_X = 1216f
        const val WINDOW_TAB_Y = 95f
        const val WINDOW_SECOND_TAB_X = 470f
        const val WINDOW_PANEL_X = 1100f
        const val WINDOW_SORT_TITLE_ROW_Y = 152f
        const val WINDOW_CARD_LEFT = 244
        const val WORDS_SPAN = 120
        const val WINDOW_TITLE_Y = 516
        const val WAIT_ROUNDS = 6
        const val WAIT_MILLIS = 100L

        const val TABS_TO_FIRST_CARD = 10

        /** Taller than the rule, so a pixel this far above the art's foot is never the bar. */
        const val PROGRESS_CLEARANCE = 8
    }
}
