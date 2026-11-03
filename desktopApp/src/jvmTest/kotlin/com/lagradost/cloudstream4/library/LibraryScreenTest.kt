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
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream4.DesktopApp
import com.lagradost.cloudstream4.EscapeHandler
import com.lagradost.cloudstream4.core.designsystem.desktop.forDesktop
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
import com.lagradost.cloudstream4.feature.library.LibraryState
import com.lagradost.cloudstream4.feature.library.LibraryStatus
import com.lagradost.cloudstream4.feature.library.LibraryTab
import com.lagradost.cloudstream4.feature.library.LibraryTitle
import com.lagradost.cloudstream4.feature.library.LibraryTitleId
import com.lagradost.cloudstream4.feature.library.WatchPosition
import com.lagradost.cloudstream4.feature.library.desktop.LibraryScreenDesktop
import com.lagradost.cloudstream4.feature.library.desktop.LibraryScreenDesktopDefaults
import java.io.File
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.EncodedImageFormat

/**
 * Real pointer events through the scene, plus a PNG of each state when `cloudstream.home.renders`
 * names a directory — the same property the home suite uses, because it is the same window.
 */
@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class LibraryScreenTest {

    private val dune = LibraryTitleId("Prov\u0000https://example.com/dune")
    private val arrival = LibraryTitleId("Prov\u0000https://example.com/arrival")

    private fun card(
        id: LibraryTitleId,
        title: String,
        bucket: LibraryBucket? = null,
        progress: Float? = null,
        resume: LibraryResume? = null,
    ) = LibraryCard(id, title, posterUrl = null, providerName = "Prov", bucket, progress, resume)

    private fun state(status: LibraryStatus, tab: LibraryTab = LibraryTab.ContinueWatching) =
        LibraryState(tab = tab, status = status)

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

    private fun screen(state: LibraryState, onAction: (LibraryAction) -> Unit = {}) =
        scene {
            LibraryScreenDesktop(state, onAction, artwork = { Box(Modifier.fillMaxSize().background(ART)) })
        }

    private fun ImageComposeScene.save(name: String) {
        val directory = System.getProperty("cloudstream.home.renders") ?: return
        settle()
        val png = checkNotNull(render(SETTLE_FRAMES * FRAME_NANOS).encodeToData(EncodedImageFormat.PNG)).bytes
        File(directory, "$name.png").writeBytes(png)
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
    fun `a click on a card on continue watching resumes it`() {
        val actions = mutableListOf<LibraryAction>()
        val cards = listOf(card(dune, "Dune", resume = LibraryResume("e1", 60_000L), progress = 0.1f))
        val scene = screen(state(LibraryStatus.Showing(cards)), { actions += it })
        scene.settle()
        scene.click(Offset(CARD_X, CARD_Y))
        assertEquals(listOf<LibraryAction>(LibraryAction.ResumeActivated(dune)), actions)
        scene.close()
    }

    @Test
    fun `a click on a card in a list opens the title instead`() {
        val actions = mutableListOf<LibraryAction>()
        val cards = listOf(card(dune, "Dune", bucket = LibraryBucket.Watching))
        val tab = LibraryTab.Bucket(LibraryBucket.Watching)
        val scene = screen(state(LibraryStatus.Showing(cards), tab), { actions += it })
        scene.settle()
        scene.click(Offset(CARD_X, CARD_Y))
        assertEquals(listOf<LibraryAction>(LibraryAction.TitleActivated(dune)), actions)
        scene.close()
    }

    @Test
    fun `a card's button opens the list picker for that card and not another`() {
        val actions = mutableListOf<LibraryAction>()
        val cards = listOf(card(dune, "Dune"), card(arrival, "Arrival"))
        val scene = screen(state(LibraryStatus.Showing(cards)), { actions += it })
        scene.settle()
        scene.click(Offset(secondCardButtonX(), CARD_BUTTON_Y))
        assertEquals(listOf<LibraryAction>(LibraryAction.PickerOpened(arrival)), actions)
        scene.close()
    }

    @Test
    fun `the picker offers every list and the way out of all of them`() {
        val actions = mutableListOf<LibraryAction>()
        val cards = listOf(card(dune, "Dune"))
        val open = state(LibraryStatus.Showing(cards)).copy(picking = dune)
        val scene = screen(open, { actions += it })
        scene.settle()
        scene.save("library-picker")
        // Each row of the panel in turn: the six lists, then Remove.
        val chosen = mutableListOf<LibraryAction>()
        LibraryBucket.entries.forEachIndexed { index, bucket ->
            actions.clear()
            scene.click(Offset(PANEL_X, FIRST_PANEL_ROW_Y + index * PANEL_ROW_STEP))
            chosen += actions.single()
            assertEquals(LibraryAction.BucketChosen(dune, bucket), actions.single())
        }
        assertEquals(LibraryBucket.entries.size, chosen.size)
        actions.clear()
        scene.click(Offset(PANEL_X, FIRST_PANEL_ROW_Y + LibraryBucket.entries.size * PANEL_ROW_STEP))
        assertEquals(listOf<LibraryAction>(LibraryAction.RemoveRequested(dune)), actions)
        scene.close()
    }

    @Test
    fun `a click outside the picker closes it`() {
        val actions = mutableListOf<LibraryAction>()
        val open = state(LibraryStatus.Showing(listOf(card(dune, "Dune")))).copy(picking = dune)
        val scene = screen(open, { actions += it })
        scene.settle()
        scene.click(Offset(CARD_X, CARD_Y))
        assertEquals(listOf<LibraryAction>(LibraryAction.PickerDismissed), actions)
        scene.close()
    }

    @Test
    fun `progress draws over the art, and no progress draws nothing`() {
        // Found by scanning rather than by a constant: a token change should move the bar, not fail here.
        val without = screen(state(LibraryStatus.Showing(listOf(card(dune, "Dune")))))
        without.settle()
        val bottom = without.lastArtRow()
        assertEquals(ART.toArgb(), without.pixel(CARD_X.toInt(), bottom))
        without.close()

        val withBar = screen(state(LibraryStatus.Showing(listOf(card(dune, "Dune", progress = 1f)))))
        withBar.settle()
        assertTrue(withBar.pixel(CARD_X.toInt(), bottom) != ART.toArgb())
        // And it is only the last few rows: a bar that covered the poster would be a layout bug.
        assertEquals(ART.toArgb(), withBar.pixel(CARD_X.toInt(), bottom - PROGRESS_CLEARANCE))
        withBar.close()
    }

    /** The lowest row of the first card that is still artwork. */
    private fun ImageComposeScene.lastArtRow(): Int {
        val bitmap = Bitmap.makeFromImage(render(SETTLE_FRAMES * FRAME_NANOS))
        val column = CARD_X.toInt()
        val found = (HEIGHT - 1 downTo 0).firstOrNull { bitmap.getColor(column, it) == ART.toArgb() }
        return checkNotNull(found) { "no artwork was drawn at x=$column" }
    }

    @Test
    fun `the empty state keeps the list names reachable`() {
        val actions = mutableListOf<LibraryAction>()
        val scene = screen(state(LibraryStatus.Empty, LibraryTab.Bucket(LibraryBucket.Dropped)), { actions += it })
        scene.settle()
        scene.save("library-empty")
        scene.click(Offset(FIRST_TAB_X, TAB_Y))
        assertEquals(
            listOf<LibraryAction>(LibraryAction.TabSelected(LibraryTab.ContinueWatching)),
            actions,
        )
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
    fun `the renders are written`() {
        val cards = listOf(
            card(dune, "Dune", resume = LibraryResume("e1", 60_000L), progress = 0.4f),
            card(arrival, "Arrival", bucket = LibraryBucket.Completed, progress = 1f),
            card(LibraryTitleId("Prov\u0000c"), "A title with a name long enough to need two lines of label"),
        )
        screen(state(LibraryStatus.Showing(cards))).use { it.save("library-continue") }
        screen(state(LibraryStatus.Showing(cards), LibraryTab.Bucket(LibraryBucket.Watching)))
            .use { it.save("library-list") }
        screen(state(LibraryStatus.Loading)).use { it.save("library-loading") }
        scene(MIN_WIDTH, MIN_HEIGHT) {
            LibraryScreenDesktop(
                state(LibraryStatus.Showing(cards)),
                {},
                artwork = { Box(Modifier.fillMaxSize().background(ART)) },
            )
        }.use { it.save("library-minimum-window") }
    }

    @Test
    fun `a Greek list name does not push the names off the row`() {
        val previous = Locale.getDefault()
        Locale.setDefault(Locale.forLanguageTag("el"))
        try {
            val actions = mutableListOf<LibraryAction>()
            val scene = scene(MIN_WIDTH, MIN_HEIGHT) {
                LibraryScreenDesktop(state(LibraryStatus.Empty), { actions += it }, artwork = {})
            }
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

    @Test
    fun `the window has a library tab, and it shows what the store holds`() {
        var at = ""
        val store = InMemoryLibraryStore(
            listOf(
                LibraryTitle(dune, "Dune", LibraryBucket.Watching, keptAt = 1L, providerName = "Prov"),
            ),
            listOf(WatchPosition(dune, "e1", 60_000L, 600_000L, 1L)),
        )
        val escape = EscapeHandler()
        val scene = scene {
            DesktopApp(escape, EmptyHome, library = store, onScreen = { at = it })
        }
        scene.settle()
        assertEquals("Home", at)

        scene.click(Offset(LIBRARY_TAB_X, WINDOW_TAB_Y))
        repeat(WAIT_ROUNDS) {
            Thread.sleep(WAIT_MILLIS)
            scene.settle()
        }
        assertEquals("Library", at)
        scene.save("library-in-window")
        // The one resumable position is on screen, so its card's button answers.
        scene.click(Offset(WINDOW_CARD_BUTTON_X, WINDOW_CARD_BUTTON_Y))
        scene.settle()
        scene.save("library-in-window-picker")

        // And the picker is what Escape now closes.
        assertTrue(escape.handle())
        scene.settle()
        scene.close()
    }

    private object EmptyHome : HomeSource {
        override suspend fun sources() = HomeSources(emptyList(), installed = 0)

        override suspend fun page(source: HomeSourceId, section: Int, page: Int) = HomePageReply.Offline
    }

    private fun ImageComposeScene.pixel(x: Int, y: Int): Int =
        Bitmap.makeFromImage(render(SETTLE_FRAMES * FRAME_NANOS)).getColor(x, y)

    private inline fun ImageComposeScene.use(block: (ImageComposeScene) -> Unit) {
        try {
            block(this)
        } finally {
            close()
        }
    }

    private fun secondCardButtonX(): Float {
        val width = WIDTH.dp - DESKTOP_WINDOW_MARGIN * 2
        val columns = LibraryScreenDesktopDefaults.columns(width)
        val cardWidth = LibraryScreenDesktopDefaults.cardWidth(width, columns)
        // Into the second card, at its trailing end where the button sits.
        return DESKTOP_WINDOW_MARGIN.value + cardWidth.value + GROUP_GAP.value + cardWidth.value - BUTTON_INSET
    }

    private companion object {
        const val WIDTH = 1280
        const val HEIGHT = 800
        const val MIN_WIDTH = 800
        const val MIN_HEIGHT = 520
        const val SETTLE_FRAMES = 30
        const val FRAME_NANOS = 50_000_000L

        /** Something no theme colour is, so a pixel read can tell artwork from anything drawn over it. */
        val ART = Color(0xFF00FF80)

        val DESKTOP_WINDOW_MARGIN = 24.dp
        val GROUP_GAP = 16.dp

        /** Margin 24, then the strip's own 36-high buttons: the row's middle. */
        const val TAB_Y = 42f
        const val FIRST_TAB_X = 60f
        const val SECOND_TAB_X = 210f

        const val CARD_X = 60f
        const val CARD_Y = 200f

        /** Under the art and the two label lines, in the row that holds the button. */
        const val CARD_BUTTON_Y = 496f
        const val BUTTON_INSET = 30f

        /** Inside the panel, which is 360 wide and against the trailing edge. */
        const val PANEL_X = 1100f

        /** In the window, the screen sits under a tab bar, so everything is lower. */
        const val WINDOW_TAB_Y = 34f
        const val LIBRARY_TAB_X = 215f
        const val WINDOW_CARD_BUTTON_X = 230f
        const val WINDOW_CARD_BUTTON_Y = 548f
        const val WAIT_ROUNDS = 6
        const val WAIT_MILLIS = 100L
        const val FIRST_PANEL_ROW_Y = 94f
        const val PANEL_ROW_STEP = 48f

        /** Taller than the rule, so a pixel this far above the art's foot is never the bar. */
        const val PROGRESS_CLEARANCE = 8
    }
}
