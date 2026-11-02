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
import com.lagradost.cloudstream4.feature.home.HomeItem
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
import com.lagradost.cloudstream4.feature.home.desktop.HomeScreenDesktop
import com.lagradost.cloudstream4.feature.home.desktop.HomeScreenDesktopDefaults
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
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
        open.click(Offset(WIDTH - 100f, FIRST_PANEL_ROW_Y + DesktopRowStep))
        assertEquals(listOf<HomeAction>(HomeAction.SourceSelected(options[1].id)), actions)

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
    }

    private class FakeHome : HomeSource {
        override suspend fun sources() = HomeSources(listOf(HomeSourceOption(HomeSourceId("Fake"), "Fake")), 1)

        override suspend fun page(source: HomeSourceId, section: Int, page: Int) = HomePageReply.Loaded(
            listOf(HomeRowPage("Popular", false, List(12) { HomeItem(com.lagradost.cloudstream4.search.DesktopSearchSource.encodeId("Fake", "u$it"), "Title $it") })),
            hasNext = false,
        )
    }

    /** The whole window: where it opens, opening a title, and Back and Escape from there. */
    @Test
    fun `home and search are the top level, and a title returns to where it was opened from`() {
        val escape = EscapeHandler()
        var at = ""
        val scene = scene { DesktopApp(escape, FakeHome(), onScreen = { at = it }) }
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
        val card = Offset(CARD_X, CARD_Y + TAB_BAR_HEIGHT)
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

        scene.click(Offset(PICKER_X, TOOLBAR_Y + TAB_BAR_HEIGHT))
        assertTrue(escape.handle(), "Escape closes the picker")
        scene.settle()
        Thread.sleep(WAIT_MILLIS)
        scene.settle()
        assertEquals("Home", at, "and stays on home")
        assertFalse(escape.handle(), "the picker is closed, so Escape is the window's again")

        scene.click(Offset(SEARCH_TAB_X, TAB_Y))
        assertEquals("Search", at)
        assertFalse(escape.handle(), "search is top level too, and Escape belongs to its field")
        val typed = scene.sendKeyEvent(KeyEvent(Key.Escape, KeyEventType.KeyDown))
        assertFalse(typed, "an empty field passes it on")

        scene.click(Offset(HOME_TAB_X, TAB_Y))
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
        const val CARD_X = 60f
        const val CARD_Y = 200f
        const val SECOND_LINE_Y = 480f
        const val FIRST_PANEL_ROW_Y = 88f
        const val DesktopRowStep = 64f
        const val TAB_BAR_HEIGHT = 52f
        const val TAB_Y = 34f
        const val HOME_TAB_X = 50f
        const val SEARCH_TAB_X = 130f
    }
}
