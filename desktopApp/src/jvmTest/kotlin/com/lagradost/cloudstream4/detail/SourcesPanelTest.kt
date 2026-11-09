package com.lagradost.cloudstream4.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
import com.lagradost.cloudstream4.core.designsystem.desktop.desktopTypography
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme
import com.lagradost.cloudstream4.core.theme.LocalCloudStreamTypography
import com.lagradost.cloudstream4.feature.detail.DetailAction
import com.lagradost.cloudstream4.feature.detail.DetailLoad
import com.lagradost.cloudstream4.feature.detail.DetailState
import com.lagradost.cloudstream4.feature.detail.StreamLink
import com.lagradost.cloudstream4.feature.detail.TitleDetail
import com.lagradost.cloudstream4.feature.detail.desktop.DetailScreenDesktop
import java.io.File
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image

/**
 * The source list while links are still arriving: real pointer and key events through the scene, plus a PNG of
 * each state when `cloudstream.home.renders` names a directory.
 */
@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class SourcesPanelTest {

    private val detail = TitleDetail("Inception", "CineTmdb", "Movie", 2010, "A thief who steals secrets.", null, "d", emptyList())

    private fun links(count: Int) = List(count) { index ->
        StreamLink("Source ${index + 1}", "${QUALITIES[index % QUALITIES.size]}p", "https://a.invalid/$index", null, null, null)
    }

    private fun arriving(count: Int) = DetailState(DetailLoad.Loaded(detail), DetailLoad.Loaded(links(count)), linksArriving = true)

    private fun finished(count: Int) = DetailState(DetailLoad.Loaded(detail), DetailLoad.Loaded(links(count)))

    private fun scene(content: @Composable () -> Unit) = ImageComposeScene(WIDTH, HEIGHT, Density(1f)) {
        CloudStreamTheme {
            CompositionLocalProvider(LocalCloudStreamTypography provides desktopTypography()) {
                Box(Modifier.fillMaxSize().background(CloudStreamTheme.colors.bg)) { content() }
            }
        }
    }

    private fun panel(state: () -> DetailState, onAction: (DetailAction) -> Unit = {}) = scene {
        DetailScreenDesktop(state(), onAction, {}, { Box(Modifier.fillMaxSize().background(Color.DarkGray)) })
    }

    private fun ImageComposeScene.settle() = repeat(SETTLE_FRAMES) { render(it * FRAME_NANOS) }

    private fun ImageComposeScene.frame(): Image = render(SETTLE_FRAMES * FRAME_NANOS)

    private fun ImageComposeScene.click(at: Offset) {
        sendPointerEvent(PointerEventType.Move, at)
        sendPointerEvent(PointerEventType.Press, at)
        sendPointerEvent(PointerEventType.Release, at)
        settle()
    }

    private fun ImageComposeScene.press(key: Key) {
        val type = java.awt.event.KeyEvent.KEY_PRESSED
        val code = if (key == Key.Tab) java.awt.event.KeyEvent.VK_TAB else java.awt.event.KeyEvent.VK_ENTER
        val char = if (key == Key.Tab) '\t' else '\n'
        val down = java.awt.event.KeyEvent(java.awt.Container(), type, 0L, 0, code, char)
        sendKeyEvent(KeyEvent(key, KeyEventType.KeyDown, nativeEvent = down))
        val up = java.awt.event.KeyEvent(java.awt.Container(), java.awt.event.KeyEvent.KEY_RELEASED, 0L, 0, code, char)
        sendKeyEvent(KeyEvent(key, KeyEventType.KeyUp, nativeEvent = up))
        settle()
    }

    private fun ImageComposeScene.save(name: String) {
        val directory = System.getProperty("cloudstream.home.renders") ?: return
        settle()
        File(directory, "$name.png").writeBytes(checkNotNull(frame().encodeToData(EncodedImageFormat.PNG)).bytes)
    }

    /** ARGB of every pixel in a band of the panel, so two frames can be compared for a row having moved. */
    private fun Image.band(top: Int, bottom: Int): List<Int> {
        val bitmap = Bitmap.makeFromImage(this)
        return (top until bottom).flatMap { y -> (WIDTH - PANEL_WIDTH until WIDTH).map { x -> bitmap.getColor(x, y) } }
    }

    @Test
    fun `the row under the pointer is the same row after the list grows`() {
        val actions = mutableListOf<DetailAction>()
        var state by mutableStateOf(arriving(3))
        val scene = panel({ state }, { actions += it })
        scene.settle()
        val onSecond = Offset(WIDTH - PANEL_WIDTH / 2f, FIRST_ROW_Y + ROW_HEIGHT)
        scene.sendPointerEvent(PointerEventType.Move, onSecond)
        scene.settle()
        val threeRows = HEADER_BOTTOM + 3 * ROW_HEIGHT
        val before = scene.frame().band(HEADER_BOTTOM, threeRows)

        state = arriving(30)
        scene.settle()
        assertEquals(before, scene.frame().band(HEADER_BOTTOM, threeRows), "a row moved, or the hover left it")

        scene.click(onSecond)
        assertEquals(listOf<DetailAction>(DetailAction.LinkActivated(links(3)[1])), actions)
        scene.close()
    }

    @Test
    fun `a row scrolled to stays put while more arrive below it`() {
        var state by mutableStateOf(arriving(30))
        val scene = panel({ state })
        scene.settle()
        scene.sendPointerEvent(PointerEventType.Move, Offset(WIDTH - PANEL_WIDTH / 2f, FIRST_ROW_Y))
        scene.sendPointerEvent(PointerEventType.Scroll, Offset(WIDTH - PANEL_WIDTH / 2f, FIRST_ROW_Y), scrollDelta = Offset(0f, SCROLL_NOTCHES))
        scene.settle()
        val before = scene.frame().band(HEADER_BOTTOM, HEIGHT)
        assertFalse(before == panel({ arriving(30) }).let { fresh -> fresh.settle(); fresh.frame().band(HEADER_BOTTOM, HEIGHT).also { fresh.close() } }, "did not scroll")

        state = arriving(60)
        scene.settle()
        assertEquals(before, scene.frame().band(HEADER_BOTTOM, HEIGHT))
        scene.close()
    }

    @Test
    fun `focus stays on its row while the list grows, and finishing does not move it either`() {
        val actions = mutableListOf<DetailAction>()
        var state by mutableStateOf(arriving(3))
        val scene = panel({ state }, { actions += it })
        scene.settle()
        val target = DetailAction.LinkActivated(links(3)[1])
        var tabs = 0
        while (target !in actions) {
            assertTrue(++tabs < MAX_TABS, "Tab never reached the second row: $actions")
            scene.press(Key.Tab)
            scene.press(Key.Enter)
        }
        actions.clear()

        state = arriving(30)
        scene.settle()
        scene.press(Key.Enter)
        assertEquals(listOf<DetailAction>(target), actions, "focus left the row when the list grew")

        actions.clear()
        state = finished(30)
        scene.settle()
        scene.press(Key.Enter)
        assertEquals(listOf<DetailAction>(target), actions, "focus left the row when the list finished")
        scene.close()
    }

    @Test
    fun `finishing changes the words in the header and moves no row`() {
        var state by mutableStateOf(arriving(5))
        val scene = panel({ state })
        scene.settle()
        val whileArriving = scene.frame()
        val header = whileArriving.band(0, HEADER_BOTTOM)
        val rows = whileArriving.band(HEADER_BOTTOM, HEIGHT)

        state = finished(5)
        scene.settle()
        val done = scene.frame()
        assertFalse(header == done.band(0, HEADER_BOTTOM), "nothing said more was on the way")
        assertEquals(rows, done.band(HEADER_BOTTOM, HEIGHT), "a row moved when the list finished")
        scene.close()
    }

    @Test
    fun `the states, drawn`() {
        val page = DetailLoad.Loaded(detail)
        val states = listOf(
            "1-waiting" to DetailState(page, DetailLoad.Loading, linksArriving = true),
            "2-first-link" to arriving(1),
            "3-growing" to arriving(7),
            "4-overflowing" to arriving(40),
            "5-finished" to finished(40),
            "6-nothing-found" to DetailState(page, DetailLoad.Empty),
            "7-failed" to DetailState(page, DetailLoad.Failed("CineStream did not answer within 90000ms")),
        )
        for ((name, state) in states) {
            val scene = panel({ state })
            scene.save(name)
            scene.close()
        }
    }

    /** The longest title of any locale beside the longest word for loading, which is Albanian. */
    @Test
    fun `the longest words give way, and Cancel keeps its place`() {
        val previous = Locale.getDefault()
        try {
            for (language in listOf("fil", "sq", "ar")) {
                Locale.setDefault(Locale.forLanguageTag(language))
                val scene = panel({ arriving(12) })
                scene.save("8-$language")
                scene.close()
            }
        } finally {
            Locale.setDefault(previous)
        }
    }

    private companion object {
        const val WIDTH = 1280
        const val HEIGHT = 800
        const val SETTLE_FRAMES = 30
        const val FRAME_NANOS = 50_000_000L
        const val PANEL_WIDTH = 360
        const val HEADER_BOTTOM = 80
        const val FIRST_ROW_Y = 104f
        const val ROW_HEIGHT = 49
        const val MAX_TABS = 12
        const val SCROLL_NOTCHES = 8f
        val QUALITIES = listOf(2160, 1080, 720, 480)
    }
}
