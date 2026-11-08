package com.lagradost.cloudstream4.home

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
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream4.DesktopApp
import com.lagradost.cloudstream4.EscapeHandler
import com.lagradost.cloudstream4.core.designsystem.desktop.forDesktop
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme
import com.lagradost.cloudstream4.core.theme.LocalCloudStreamTypography
import com.lagradost.cloudstream4.feature.home.HomeAction
import com.lagradost.cloudstream4.feature.home.HomeBookmark
import com.lagradost.cloudstream4.feature.home.HomeBookmarks
import com.lagradost.cloudstream4.feature.home.HomeFeatured
import com.lagradost.cloudstream4.feature.home.HomeHero
import com.lagradost.cloudstream4.feature.home.HomeHistory
import com.lagradost.cloudstream4.feature.home.HomeItem
import com.lagradost.cloudstream4.feature.home.HomeResume
import com.lagradost.cloudstream4.feature.home.HomeMore
import com.lagradost.cloudstream4.feature.home.HomePageReply
import com.lagradost.cloudstream4.feature.home.HomeRow
import com.lagradost.cloudstream4.feature.home.HomeRowKey
import com.lagradost.cloudstream4.feature.home.HomeRowPage
import com.lagradost.cloudstream4.feature.home.HomeSource
import com.lagradost.cloudstream4.feature.home.HomeSourceId
import com.lagradost.cloudstream4.feature.home.HomeSourceOption
import com.lagradost.cloudstream4.feature.home.HomeSources
import com.lagradost.cloudstream4.feature.home.HomeState
import com.lagradost.cloudstream4.feature.home.HomeStatus
import com.lagradost.cloudstream4.feature.home.HomeWatchStatus
import com.lagradost.cloudstream4.feature.home.desktop.HomeScreenDesktop
import com.lagradost.cloudstream4.feature.home.desktop.HomeScreenDesktopDefaults
import com.lagradost.cloudstream4.feature.library.LibraryBucket
import com.lagradost.cloudstream4.feature.library.LibraryTitle
import com.lagradost.cloudstream4.feature.library.LibraryTitleId
import com.lagradost.cloudstream4.feature.library.WatchPosition
import com.lagradost.cloudstream4.library.InMemoryLibraryStore
import java.io.File
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.EncodedImageFormat

/**
 * Real pointer and key events through the scene, against fake sources, plus a PNG of each state when
 * `cloudstream.home.renders` names a directory.
 */
@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class HomeScreenTest {

    private val youtube = HomeSourceOption(HomeSourceId("YouTube"), "YouTube", "en", sections = 3)
    private val options = listOf(youtube, HomeSourceOption(HomeSourceId("Archive"), "Internet Archive", "en"))

    private fun cards(prefix: String, count: Int) = List(count) { HomeItem("$prefix$it", "$prefix title $it") }

    private fun row(name: String, count: Int = 12, section: Int = 0, landscape: Boolean = false) =
        HomeRow(HomeRowKey(section, name), landscape, cards(name, count), hasMore = true)

    private fun state(status: HomeStatus, pickerOpen: Boolean = false) =
        HomeState(options, youtube.id, pickerOpen, status)

    private fun scene(width: Int = WIDTH, height: Int = HEIGHT, content: @Composable () -> Unit) =
        ImageComposeScene(width, height, Density(1f)) {
            CloudStreamTheme {
                CompositionLocalProvider(LocalCloudStreamTypography provides CloudStreamTheme.typography.forDesktop()) {
                    Box(Modifier.fillMaxSize().background(CloudStreamTheme.colors.bg)) { content() }
                }
            }
        }

    private fun ImageComposeScene.settle() = repeat(SETTLE_FRAMES) { render(it * FRAME_NANOS) }

    private fun ImageComposeScene.click(at: Offset) {
        sendPointerEvent(PointerEventType.Move, at)
        sendPointerEvent(PointerEventType.Press, at)
        sendPointerEvent(PointerEventType.Release, at)
        settle()
    }

    private fun screen(state: HomeState, onAction: (HomeAction) -> Unit = {}) =
        scene { HomeScreenDesktop(state, onAction, artwork = { Box(Modifier.fillMaxSize().background(Color.DarkGray)) }) }

    private fun ImageComposeScene.save(name: String) {
        val directory = System.getProperty("cloudstream.home.renders") ?: return
        settle()
        val png = checkNotNull(render(SETTLE_FRAMES * FRAME_NANOS).encodeToData(EncodedImageFormat.PNG)).bytes
        File(directory, "$name.png").writeBytes(png)
    }

    @Test
    fun `a click on a card opens that title`() {
        val actions = mutableListOf<HomeAction>()
        val scene = screen(state(HomeStatus.Showing(listOf(row("Popular")))), { actions += it })
        scene.settle()
        scene.click(Offset(CARD_X, CARD_Y))
        assertEquals(listOf<HomeAction>(HomeAction.TitleActivated("Popular0")), actions)
        scene.close()
    }

    @Test
    fun `the row's button asks for more, with the columns that fit`() {
        val actions = mutableListOf<HomeAction>()
        val scene = screen(state(HomeStatus.Showing(listOf(row("Popular")))), { actions += it })
        scene.settle()
        scene.click(Offset(WIDTH - 60f, ROW_HEADER_Y))
        val columns = HomeScreenDesktopDefaults.columns((WIDTH - 48).dp, landscape = false)
        assertEquals(listOf<HomeAction>(HomeAction.RowMoreRequested(HomeRowKey(0, "Popular"), columns)), actions)
        scene.close()
    }

    @Test
    fun `a row with everything showing and no next page has no button`() {
        val actions = mutableListOf<HomeAction>()
        val few = row("Popular", count = 2).copy(hasMore = false)
        val scene = screen(state(HomeStatus.Showing(listOf(few))), { actions += it })
        scene.settle()
        scene.click(Offset(WIDTH - 60f, ROW_HEADER_Y))
        assertTrue(actions.isEmpty(), "$actions")
        scene.close()
    }

    @Test
    fun `a row loading its next page cannot be pressed again`() {
        val actions = mutableListOf<HomeAction>()
        val busy = row("Popular").copy(more = HomeMore.Loading)
        val scene = screen(state(HomeStatus.Showing(listOf(busy))), { actions += it })
        scene.settle()
        scene.click(Offset(WIDTH - 40f, ROW_HEADER_Y))
        assertTrue(actions.isEmpty(), "$actions")
        scene.close()
    }

    @Test
    fun `the cards stay put when a row starts loading its next page`() {
        fun firstCardTop(more: HomeMore): Int {
            val scene = screen(state(HomeStatus.Showing(listOf(row("Popular").copy(more = more)))))
            scene.settle()
            val bitmap = Bitmap.makeFromImage(scene.render(SETTLE_FRAMES * FRAME_NANOS))
            scene.close()
            val grey = Color.DarkGray.toArgb()
            return (0 until HEIGHT).first { bitmap.getColor(CARD_X.toInt() + PAST_ROW_TITLE, it) == grey }
        }
        assertEquals(firstCardTop(HomeMore.Idle), firstCardTop(HomeMore.Loading))
    }

    /** Greek is the longest `load_more` of any locale, and 800 x 520 is the smallest window. */
    @Test
    fun `a long Load more squeezes the row title, not the cards, at the minimum window`() {
        val width = 800
        fun measure(language: String): Triple<Int, Int, List<HomeAction>> {
            val previous = Locale.getDefault()
            Locale.setDefault(Locale.forLanguageTag(language))
            try {
                val actions = mutableListOf<HomeAction>()
                val long = row("A row whose title is far too long to sit beside its button in the smallest window there is, so it ellipsises")
                val scene = scene(width, 520) {
                    HomeScreenDesktop(
                        state(HomeStatus.Showing(listOf(long))),
                        { actions += it },
                        artwork = { Box(Modifier.fillMaxSize().background(Color.DarkGray)) },
                    )
                }
                scene.settle()
                scene.save("14-minimum-window-$language")
                val bitmap = Bitmap.makeFromImage(scene.render(SETTLE_FRAMES * FRAME_NANOS))
                val grey = Color.DarkGray.toArgb()
                val cardTop = (0 until 520).first { bitmap.getColor(CARD_X.toInt() + PAST_ROW_TITLE, it) == grey }
                // Above the label's line box and inside the button, whose fill runs unbroken to its left edge.
                val fill = bitmap.getColor(width - BUTTON_INSET, BUTTON_FILL_Y)
                val buttonLeft = (width - BUTTON_INSET downTo 0).first { bitmap.getColor(it, BUTTON_FILL_Y) != fill } + 1
                scene.click(Offset(buttonLeft + 8f, ROW_HEADER_Y))
                scene.close()
                return Triple(cardTop, buttonLeft, actions)
            } finally {
                Locale.setDefault(previous)
            }
        }
        val (englishTop, englishLeft, englishActions) = measure("en")
        val (greekTop, greekLeft, greekActions) = measure("el")
        assertEquals(englishTop, greekTop)
        assertTrue(greekLeft < englishLeft, "the Greek label did not resolve: $greekLeft, $englishLeft")
        // The title still has room to be read, and the button's whole width is inside the window and pressable.
        assertTrue(greekLeft > width / 2, "$greekLeft")
        assertEquals(1, englishActions.size, "$englishActions")
        assertEquals(1, greekActions.size, "$greekActions")
    }

    @Test
    fun `only as many cards as lines allow are drawn, and more lines draw more`() {
        val columns = HomeScreenDesktopDefaults.columns((WIDTH - 48).dp, landscape = false)
        val actions = mutableListOf<HomeAction>()
        val one = screen(state(HomeStatus.Showing(listOf(row("Popular")))), { actions += it })
        one.settle()
        // Where a second line would be, there is nothing to click on one line.
        one.click(Offset(CARD_X, SECOND_LINE_Y))
        assertTrue(actions.isEmpty(), "$actions")
        one.close()

        val two = screen(state(HomeStatus.Showing(listOf(row("Popular").copy(lines = 2)))), { actions += it })
        two.settle()
        two.click(Offset(CARD_X, SECOND_LINE_Y))
        assertEquals(listOf<HomeAction>(HomeAction.TitleActivated("Popular$columns")), actions)
        two.close()
    }

    @Test
    fun `the picker opens from the toolbar and an outside click closes it, and an inside click chooses`() {
        val actions = mutableListOf<HomeAction>()
        val closed = screen(state(HomeStatus.Loading), { actions += it })
        closed.settle()
        closed.click(Offset(PICKER_X, TOOLBAR_Y))
        assertEquals(listOf<HomeAction>(HomeAction.PickerOpened), actions)
        closed.close()

        actions.clear()
        val open = screen(state(HomeStatus.Loading, pickerOpen = true), { actions += it })
        open.settle()
        open.click(Offset(WIDTH - 100f, PANEL_NONE_Y + PANEL_ROW_STEP * 2))
        assertEquals(listOf<HomeAction>(HomeAction.SourceSelected(options[1].id)), actions)

        // H3: None is the first row, above every source.
        actions.clear()
        open.click(Offset(WIDTH - 100f, PANEL_NONE_Y))
        assertEquals(listOf<HomeAction>(HomeAction.SourceSelected(HomeSourceId.None)), actions)

        actions.clear()
        open.click(Offset(100f, HEIGHT - 20f))
        assertEquals(listOf<HomeAction>(HomeAction.PickerDismissed), actions)
        open.close()
    }

    @Test
    fun `a failure's button retries, and the picker stays reachable through it`() {
        val actions = mutableListOf<HomeAction>()
        val scene = screen(state(HomeStatus.Failed("HTTP 503")), { actions += it })
        scene.settle()
        scene.click(Offset(PICKER_X, TOOLBAR_Y))
        assertEquals(listOf<HomeAction>(HomeAction.PickerOpened), actions)
        scene.close()
    }

    @Test
    fun `renders`() {
        val rows = listOf(
            row("Popular"),
            row("Trending in this source with a name far too long to fit on the one line that a row header gets at all and then some more words", section = 1),
            row("Clips", count = 4, section = 2, landscape = true).copy(hasMore = false),
        )
        val states = mapOf(
            "01-content" to state(HomeStatus.Showing(rows)),
            "02-partial" to state(HomeStatus.Partial(rows, listOf(1, 2), 6, "HTTP 503 · OFFLINE")),
            "03-partial-retrying" to state(HomeStatus.Partial(rows, listOf(1, 2), 6, "HTTP 503", retrying = true)),
            "04-loading" to state(HomeStatus.Loading),
            "05-empty" to state(HomeStatus.Empty),
            "06-failed" to state(HomeStatus.Failed("IOException: YouTube did not answer within 60000ms")),
            "07-offline" to state(HomeStatus.Offline),
            "08-no-sources" to HomeState(status = HomeStatus.NoSources(0)),
            "09-search-only" to HomeState(status = HomeStatus.NoSources(2)),
            "10-picker" to state(HomeStatus.Showing(rows), pickerOpen = true),
            "11-row-loading" to state(
                HomeStatus.Showing(listOf(rows[0].copy(lines = 3, more = HomeMore.Loading), rows[1])),
            ),
            "12-row-failed" to state(
                HomeStatus.Showing(listOf(rows[0].copy(more = HomeMore.Failed("IOException: HTTP 503")), rows[1])),
            ),
        )
        states.forEach { (name, value) ->
            val scene = screen(value)
            scene.save(name)
            scene.close()
        }
        val small = scene(800, 520) {
            HomeScreenDesktop(state(HomeStatus.Showing(rows)), {}, artwork = { Box(Modifier.fillMaxSize().background(Color.DarkGray)) })
        }
        small.save("13-minimum-window")
        small.close()

        // KMP-55: what the device keeps above the source's rows, in each state that matters, full size and minimum.
        val kept = withKept(state(HomeStatus.Showing(rows)))
        mapOf(
            "15-kept-content" to kept,
            "16-kept-offline" to kept.copy(status = HomeStatus.Offline),
            "17-kept-unsourced" to kept.copy(selected = HomeSourceId.None, status = HomeStatus.Unsourced, hero = HomeHero.None),
            "18-kept-picker" to kept.copy(pickerOpen = true),
            "19-kept-no-status" to kept.copy(bookmarks = kept.bookmarks.copy(chosen = null)),
        ).forEach { (name, value) ->
            val scene = screen(value)
            scene.save(name)
            scene.close()
        }
        mapOf(
            "20-kept-minimum" to kept,
            "21-kept-minimum-offline" to kept.copy(status = HomeStatus.Offline),
        ).forEach { (name, value) ->
            val scene = scene(800, 520) {
                HomeScreenDesktop(value, {}, artwork = { Box(Modifier.fillMaxSize().background(Color.DarkGray)) })
            }
            scene.save(name)
            scene.close()
        }
    }

    /** A hero, two to resume and bookmarks in three statuses, Watching chosen. */
    private fun withKept(base: HomeState) = base.copy(
        hero = HomeHero.Shown(
            HomeFeatured(
                id = "hero",
                title = "The Last Signal",
                year = 2019,
                genres = listOf("Drama", "Thriller"),
                score = "8.4",
                contentRating = "TV-MA",
                synopsis = "Six months after the broadcast went dark, a salvage crew picks up a repeating signal from a station decommissioned forty years ago.",
            ),
        ),
        history = HomeHistory(
            resume = listOf(
                HomeResume("r1", "Severance", progress = 0.62f, season = 2, episode = 4, sourceName = "YouTube"),
                HomeResume("r2", "Arcane", progress = 0.35f, sourceName = "Internet Archive"),
            ),
        ),
        bookmarks = HomeBookmarks(
            all = listOf(
                HomeBookmark(HomeItem("b1", "Deep Signal"), HomeWatchStatus.Watching, 3),
                HomeBookmark(HomeItem("b2", "Quiet Harbor"), HomeWatchStatus.Watching, 2),
                HomeBookmark(HomeItem("b3", "Nine Lives"), HomeWatchStatus.PlanToWatch, 1),
                HomeBookmark(HomeItem("b4", "Salt Road"), HomeWatchStatus.Completed, 1),
            ),
            chosen = HomeWatchStatus.Watching,
        ),
    )

    private class FakeHome : HomeSource {
        override suspend fun sources() = HomeSources(listOf(HomeSourceOption(HomeSourceId("Fake"), "Fake")), 1)

        override suspend fun page(source: HomeSourceId, section: Int, page: Int) = HomePageReply.Loaded(
            listOf(HomeRowPage("Popular", false, List(12) { HomeItem(com.lagradost.cloudstream4.search.DesktopSearchSource.encodeId("Fake", "u$it"), "Title $it") })),
            hasNext = false,
        )
    }

    /** The device's library, as the window's own store reads it: two to resume and bookmarks in two statuses. */
    private fun keptLibrary() = InMemoryLibraryStore(
        titles = listOf("Severance", "Arcane", "Deep Signal", "Nine Lives").mapIndexed { index, name ->
            LibraryTitle(
                LibraryTitleId("k$index"),
                name,
                bucket = if (index < 2) null else if (index == 2) LibraryBucket.Watching else LibraryBucket.PlanToWatch,
                keptAt = index.toLong(),
                providerName = "Fake",
            )
        },
        positions = listOf(
            WatchPosition(LibraryTitleId("k0"), null, positionMillis = 30 * 60_000L, durationMillis = 50 * 60_000L, watchedAt = 2),
            WatchPosition(LibraryTitleId("k1"), null, positionMillis = 10 * 60_000L, durationMillis = 40 * 60_000L, watchedAt = 1),
        ),
    )

    /** A source that is there and offline: what H4's pill is for. */
    private class OfflineHome : HomeSource {
        override suspend fun sources() = HomeSources(listOf(HomeSourceOption(HomeSourceId("YouTube"), "YouTube")), 1)

        override suspend fun page(source: HomeSourceId, section: Int, page: Int) = HomePageReply.Offline
    }

    /**
     * H6 as the whole window, toolbar and all, at the 800×520 minimum (`KMP-55`): offline, where the pill sits beside the
     * source, and with rows, where Reload and Random do. Renders only; the window's behaviour has its own tests.
     */
    @Test
    fun `whole window renders at the minimum`() {
        if (System.getProperty("cloudstream.home.renders") == null) return
        listOf("22-window-minimum-offline" to OfflineHome(), "23-window-minimum-content" to FakeHome()).forEach { (name, home) ->
            val scene = scene(800, 520) { DesktopApp(EscapeHandler(), home, library = keptLibrary()) }
            repeat(WAIT_ROUNDS) {
                scene.settle()
                Thread.sleep(WAIT_MILLIS)
            }
            scene.save(name)
            scene.close()
        }
    }

    /** The whole window: where it opens, opening a title, and Back and Escape from there. */
    /**
     * `KMP-77` (`DS-21` E1): a title's page keeps the sidebar, with the tab it came from chosen, and a row there or its
     * key leaves the title for that tab, with nothing left under it.
     */
    @Test
    fun `a title's page keeps the sidebar, and a row or its key leaves the title for that tab`() {
        val escape = EscapeHandler()
        val shortcuts = DesktopShortcuts()
        var at = ""
        val scene = scene { DesktopApp(escape, FakeHome(), library = InMemoryLibraryStore(), onScreen = { at = it }, shortcuts = shortcuts) }
        fun waitForCards() = repeat(WAIT_ROUNDS) {
            scene.settle()
            Thread.sleep(WAIT_MILLIS)
        }
        fun openTitle() {
            val card = Offset(CARD_X + SIDEBAR_WIDTH, CARD_Y)
            scene.sendPointerEvent(PointerEventType.Move, card)
            scene.sendPointerEvent(PointerEventType.Press, card)
            scene.sendPointerEvent(PointerEventType.Release, card)
            waitForCards()
            assertTrue(at.startsWith("Detail"), at)
        }
        waitForCards()
        openTitle()
        scene.save("24-window-title-with-sidebar")
        // Home, where the title was opened from, is the chosen row: its fill is not the unchosen Search row's.
        val bitmap = Bitmap.makeFromImage(scene.render(SETTLE_FRAMES * FRAME_NANOS))
        assertTrue(
            bitmap.getColor(ROW_FILL_X, HOME_ITEM_Y.toInt() - ROW_FILL_ABOVE) != bitmap.getColor(ROW_FILL_X, SEARCH_ITEM_Y.toInt() - ROW_FILL_ABOVE),
            "Home is chosen under the title",
        )

        scene.click(Offset(SIDEBAR_ITEM_X, SEARCH_ITEM_Y))
        assertEquals("Search", at, "the sidebar's row leaves the title")
        assertFalse(escape.handle(), "and nothing is left under Search")

        scene.click(Offset(SIDEBAR_ITEM_X, HOME_ITEM_Y))
        waitForCards()
        openTitle()
        shortcuts.go(TopLevelDestination.Library)
        scene.settle()
        assertEquals("Library", at, "command 3 leaves a title too")
        assertFalse(escape.handle(), "and nothing is left under Library")
        scene.close()
    }

    @Test
    fun `home and search are the top level, and a title returns to where it was opened from`() {
        val escape = EscapeHandler()
        var at = ""
        val scene = scene { DesktopApp(escape, FakeHome(), library = InMemoryLibraryStore(), onScreen = { at = it }) }
        fun waitForCards() {
            repeat(WAIT_ROUNDS) {
                scene.settle()
                Thread.sleep(WAIT_MILLIS)
            }
        }
        waitForCards()
        assertEquals("Home", at, "the window opens on home")
        scene.save("14-window")
        assertFalse(escape.handle(), "nothing under home, so Escape is the window's")

        // A double click: must take one Back to undo.
        val card = Offset(CARD_X + SIDEBAR_WIDTH, CARD_Y)
        scene.sendPointerEvent(PointerEventType.Move, card)
        scene.sendPointerEvent(PointerEventType.Press, card)
        scene.sendPointerEvent(PointerEventType.Release, card)
        scene.sendPointerEvent(PointerEventType.Press, card)
        scene.sendPointerEvent(PointerEventType.Release, card)
        waitForCards()
        assertTrue(at.startsWith("Detail"), at)

        assertTrue(escape.handle(), "Escape goes back from a title")
        scene.settle()
        assertEquals("Home", at, "one Back, after a double click")
        assertFalse(escape.handle(), "the title's handler went with it")

        scene.click(Offset(PICKER_X + SIDEBAR_WIDTH, TOOLBAR_Y))
        assertTrue(escape.handle(), "Escape closes the picker")
        scene.settle()
        Thread.sleep(WAIT_MILLIS)
        scene.settle()
        assertEquals("Home", at, "and stays on home")
        assertFalse(escape.handle(), "the picker is closed, so Escape is the window's again")

        scene.click(Offset(SIDEBAR_ITEM_X, SEARCH_ITEM_Y))
        assertEquals("Search", at)
        assertFalse(escape.handle(), "search is top level too, and Escape belongs to its field")
        val typed = scene.sendKeyEvent(KeyEvent(Key.Escape, KeyEventType.KeyDown))
        assertFalse(typed, "an empty field passes it on")

        scene.click(Offset(SIDEBAR_ITEM_X, HOME_ITEM_Y))
        assertEquals("Home", at)
        scene.close()
    }

    private companion object {
        const val WIDTH = 1280
        const val HEIGHT = 800
        const val SETTLE_FRAMES = 30
        const val FRAME_NANOS = 50_000_000L
        const val WAIT_ROUNDS = 6
        const val WAIT_MILLIS = 100L
        /** Margin 16, button 36, margin 16; then a 36 header and 16 before the first card. */
        const val TOOLBAR_Y = 34f
        const val PICKER_X = 100f
        const val ROW_HEADER_Y = 86f
        /** Inside a row button's horizontal padding, and above its label's line box. */
        const val BUTTON_INSET = 28
        const val BUTTON_FILL_Y = 72
        const val CARD_X = 60f
        /** Far enough along the first card to clear the row title above it. */
        const val PAST_ROW_TITLE = 80
        const val CARD_Y = 200f
        const val SECOND_LINE_Y = 480f
        /** Measured from `18-kept-picker.png`: the panel's title, its line, then None, then each source. */
        const val PANEL_NONE_Y = 120f
        const val PANEL_ROW_STEP = 49f
        /** In the window the page sits beside the 220 sidebar (`KMP-53`), so everything is further right. */
        const val SIDEBAR_WIDTH = 220f
        /** Sidebar rows: 24 down, then 32 rows 4 apart. */
        const val SIDEBAR_ITEM_X = 100f
        const val HOME_ITEM_Y = 40f
        const val SEARCH_ITEM_Y = 76f
        /** Inside a sidebar row's fill, left of its glyph, and above its label's line: from `24-window-title-with-sidebar`. */
        const val ROW_FILL_X = 16
        const val ROW_FILL_ABOVE = 12
    }
}
