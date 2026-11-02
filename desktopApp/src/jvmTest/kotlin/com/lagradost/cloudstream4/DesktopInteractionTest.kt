package com.lagradost.cloudstream4

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import com.lagradost.cloudstream4.core.designsystem.state.UiState
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme
import com.lagradost.cloudstream4.detail.rememberDesktopDetailCopy
import com.lagradost.cloudstream4.feature.detail.DetailAction
import com.lagradost.cloudstream4.feature.detail.DetailState
import com.lagradost.cloudstream4.feature.detail.StreamLink
import com.lagradost.cloudstream4.feature.detail.TitleDetail
import com.lagradost.cloudstream4.feature.detail.desktop.DetailScreenDesktop
import com.lagradost.cloudstream4.feature.search.SearchAction
import com.lagradost.cloudstream4.feature.search.SearchContent
import com.lagradost.cloudstream4.feature.search.SearchResultGroup
import com.lagradost.cloudstream4.feature.search.SearchResultId
import com.lagradost.cloudstream4.feature.search.SearchResultItem
import com.lagradost.cloudstream4.feature.search.SearchState
import com.lagradost.cloudstream4.feature.search.desktop.SearchScreenDesktop
import com.lagradost.cloudstream4.player.PlayerStatus
import com.lagradost.cloudstream4.player.SeekTrack
import com.lagradost.cloudstream4.search.rememberDesktopSearchCopy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What the desktop screens do under a real pointer and keyboard, sent through the scene's own input.
 *
 * The screenshot suite cannot guard these screens (`DEC-10`), and none of this shows in a still anyway.
 */
@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class DesktopInteractionTest {

    private fun scene(content: @Composable () -> Unit) =
        ImageComposeScene(WIDTH, HEIGHT, Density(1f)) { CloudStreamTheme { content() } }

    private fun ImageComposeScene.settle() = repeat(SETTLE_FRAMES) { render(it * FRAME_NANOS) }

    private fun ImageComposeScene.click(at: Offset) {
        sendPointerEvent(PointerEventType.Move, at)
        sendPointerEvent(PointerEventType.Press, at)
        sendPointerEvent(PointerEventType.Release, at)
        settle()
    }

    @Test
    fun `an empty field has nothing to press`() {
        val actions = mutableListOf<SearchAction>()
        val scene = scene {
            val (copy, prompts) = rememberDesktopSearchCopy()
            SearchScreenDesktop(SearchState(content = prompts.idle()), copy, { actions += it })
        }
        scene.settle()
        scene.click(Offset(WIDTH / 2f, HEIGHT / 2f))
        assertTrue(actions.isEmpty(), "the idle words are not a control: $actions")
        scene.close()
    }

    /**
     * Escape reaches the field's handler only while focus is inside the field, so this is also the test
     * that a search finding nothing does not move focus to its button mid-typing.
     */
    @Test
    fun `focus stays in the field when nothing is found, and escape empties it`() {
        val actions = mutableListOf<SearchAction>()
        var query by mutableStateOf("nasa")
        val scene = scene {
            val (copy, prompts) = rememberDesktopSearchCopy()
            val nothing = prompts.noResults("nasa", 1) {}
            SearchScreenDesktop(SearchState(query = query, content = nothing), copy, { actions += it })
        }
        scene.settle()
        assertTrue(scene.sendKeyEvent(escape()), "handled while there is text")
        assertEquals(listOf<SearchAction>(SearchAction.Cleared), actions)

        query = ""
        scene.settle()
        actions.clear()
        assertFalse(scene.sendKeyEvent(escape()), "passed on, so the window can go back")
        assertTrue(actions.isEmpty())
        scene.close()
    }

    @Test
    fun `a click on a card opens it`() {
        val actions = mutableListOf<SearchAction>()
        val item = SearchResultItem(SearchResultId("one"), "Title", providerName = "YouTube")
        val state = SearchState(
            query = "q",
            content = UiState.Content(SearchContent.Results(listOf(SearchResultGroup("YouTube", listOf(item))))),
        )
        val scene = scene {
            val (copy, _) = rememberDesktopSearchCopy()
            SearchScreenDesktop(state, copy, { actions += it })
        }
        scene.settle()
        scene.click(Offset(CARD_X, CARD_Y))
        assertEquals(listOf<SearchAction>(SearchAction.ResultActivated(item.id)), actions)
        scene.close()
    }

    @Test
    fun `clicking outside the source panel dismisses it, and a click in the panel does not`() {
        val actions = mutableListOf<DetailAction>()
        val link = StreamLink("Source", "1080p", "u", null, null, null)
        val detail = TitleDetail("T", "P", null, null, null, null, "d", emptyList())
        val scene = scene {
            val (copy, _) = rememberDesktopDetailCopy()
            DetailScreenDesktop(
                DetailState(UiState.Content(detail), UiState.Content(listOf(link))),
                copy,
                { actions += it },
                {},
                {},
            )
        }
        scene.settle()
        scene.click(Offset(WIDTH - 100f, FIRST_PANEL_ROW_Y))
        assertEquals(listOf<DetailAction>(DetailAction.LinkActivated(link)), actions)

        actions.clear()
        scene.click(Offset(100f, HEIGHT - 20f))
        assertEquals(listOf<DetailAction>(DetailAction.LinksDismissed), actions)
        scene.close()
    }

    /** A seek is a network request, so dragging must not send one per pixel. */
    @Test
    fun `scrubbing seeks once, on release, to where the pointer ended`() {
        val seeks = mutableListOf<Float>()
        val status = PlayerStatus(playing = true, buffering = false, positionMillis = 0, lengthMillis = 100_000)
        val scene = scene {
            SeekTrack(status, { seeks += it }, remember { Modifier.fillMaxWidth() })
        }
        scene.settle()
        val y = 16f
        scene.sendPointerEvent(PointerEventType.Move, Offset(200f, y))
        scene.sendPointerEvent(PointerEventType.Press, Offset(200f, y))
        scene.settle()
        scene.sendPointerEvent(PointerEventType.Move, Offset(500f, y))
        scene.settle()
        assertTrue(seeks.isEmpty(), "no seek while held")
        // Well above the 32dp track: a scrub keeps tracking wherever the pointer wanders while held.
        scene.sendPointerEvent(PointerEventType.Move, Offset(600f, HEIGHT - 10f))
        scene.sendPointerEvent(PointerEventType.Release, Offset(600f, HEIGHT - 10f))
        scene.settle()
        assertEquals(1, seeks.size)
        assertTrue(seeks.single() in 0.4f..0.8f, "${seeks.single()}")
        scene.close()
    }

    private fun escape() = KeyEvent(Key.Escape, KeyEventType.KeyDown)

    private companion object {
        const val WIDTH = 1000
        const val HEIGHT = 600
        const val SETTLE_FRAMES = 30
        const val FRAME_NANOS = 50_000_000L
        /** Inside the first card: below the toolbar and section header. */
        const val CARD_X = 60f
        const val CARD_Y = 200f
        /** Inside the first row: below the panel's title row. */
        const val FIRST_PANEL_ROW_Y = 88f
    }
}
